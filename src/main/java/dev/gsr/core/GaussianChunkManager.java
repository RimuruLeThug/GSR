package dev.gsr.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manager of the "Gaussian Chunks".
 *
 * Pipeline (threads):
 * <pre>
 *  [Main thread]                             [Workers]                         [Render thread]
 *  block modified / chunk loaded
 *    -> capture queue (distance priority)
 *  tick(): capture(section) = snapshot ──► SectionSummary.build()
 *                                          + if in L0: VoxelGrid → splats ──► "ready" queue
 *          summary changed ◄────────────── ("summarized" queue)
 *    -> dirty L1..L3 pages (debounce)
 *  tick(): coarse page due ──────────────► VoxelGrid.fromSummaries → splats ─► "ready" queue
 *                                                                              drainUploads():
 *                                                                              version up to date? inside
 *                                                                              the window? -> GpuSink
 * </pre>
 * Under Minecraft, tick and rendering are on the same client thread; all public methods
 * except the constructor must be called there.
 */
public final class GaussianChunkManager implements AutoCloseable {
    private static final int DIST_BUCKETS = 64;

    private final GsrConfig cfg;
    private final GaussianClipmap clipmap;
    private final SectionSnapshotSource source;
    private final ExecutorService workers;

    // Shared with the workers
    private final ConcurrentHashMap<Long, SectionSummary> summaries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PageKey, Long> latestVersion = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PageData> ready = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> summarized = new ConcurrentLinkedQueue<>();
    private final AtomicLong versions = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    /** CPU time spent in worker jobs (section summaries + splat generation), and job count. */
    private final AtomicLong workerNanos = new AtomicLong(), workerJobs = new AtomicLong();

    // Main thread only
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Long>[] captureBuckets = new ArrayDeque[DIST_BUCKETS];
    private final Set<Long> captureQueued = new HashSet<>();
    private final Map<PageKey, Long> coarseDue = new HashMap<>();
    private final List<Integer> pendingInvalidations = new ArrayList<>();
    private int psx, psy, psz;
    private long tickCount;
    private boolean windowMovedSinceEviction;
    /** Splats per GPU slot as uploaded (-1 = invalid slot). CPU mirror of the meta, for stats. */
    private final int[] slotSplats;
    private long totalUploads;
    /** Capture attempts since creation: sections copied, empty (air), not loaded (null). */
    private long capturesCopied, capturesEmpty, capturesMissing;
    /** Client-thread time spent in the capture loop since creation (ns). */
    private long captureNanos;
    private double frameCaptureMsMax, frameCaptureFirstMs;
    private long frameCaptureCalls;
    private final java.util.function.LongSupplier clock;
    /** Client-thread cost of tick(): moving average and maximum since the last stats() call (ms). */
    private double tickMsAvg, tickMsMax;

    public GaussianChunkManager(GsrConfig cfg, SectionSnapshotSource source) {
        this(cfg, source, System::nanoTime);
    }

    /** @param clock nanosecond clock of the capture time budget (injectable for tests). */
    GaussianChunkManager(GsrConfig cfg, SectionSnapshotSource source, java.util.function.LongSupplier clock) {
        this.clock = clock;
        this.cfg = cfg.validate();
        this.clipmap = new GaussianClipmap(cfg);
        this.source = source;
        for (int i = 0; i < DIST_BUCKETS; i++) captureBuckets[i] = new ArrayDeque<>();
        this.slotSplats = new int[cfg.totalSlots()];
        java.util.Arrays.fill(slotSplats, -1);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "GSR-Splat-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        };
        this.workers = Executors.newFixedThreadPool(cfg.workerThreads, tf);
    }

    public GaussianClipmap clipmap() { return clipmap; }
    public GsrConfig config() { return cfg; }
    /** Work in progress or pending (useful for tests and the debug HUD). */
    public boolean isIdle() {
        return inFlight.get() == 0 && ready.isEmpty() && summarized.isEmpty() && captureQueued.isEmpty() && coarseDue.isEmpty();
    }

    // ------------------------------------------------------------------ World events

    /** A block changed: rebuild its section, and the neighboring one if the block touches a boundary. */
    public void onBlockChanged(int bx, int by, int bz) {
        int sx = bx >> 4, sy = by >> 4, sz = bz >> 4;
        enqueueCapture(sx, sy, sz);
        int lx = bx & 15, ly = by & 15, lz = bz & 15;
        if (lx == 0) enqueueCapture(sx - 1, sy, sz); else if (lx == 15) enqueueCapture(sx + 1, sy, sz);
        if (ly == 0) enqueueCapture(sx, sy - 1, sz); else if (ly == 15) enqueueCapture(sx, sy + 1, sz);
        if (lz == 0) enqueueCapture(sx, sy, sz - 1); else if (lz == 15) enqueueCapture(sx, sy, sz + 1);
    }

    /** The light of a section changed (the summary only triggers a coarse rebuild if it differs). */
    public void onSectionDirty(int sx, int sy, int sz) { enqueueCapture(sx, sy, sz); }

    /** Chunk loaded: its sections + those of the 4 neighboring columns (their border faces change). */
    public void onChunkLoaded(int cx, int cz, int minSy, int maxSy) {
        for (int sy = minSy; sy <= maxSy; sy++) {
            enqueueCapture(cx, sy, cz);
            enqueueCapture(cx - 1, sy, cz); enqueueCapture(cx + 1, sy, cz);
            enqueueCapture(cx, sy, cz - 1); enqueueCapture(cx, sy, cz + 1);
        }
    }

    public void onChunkUnloaded(int cx, int cz, int minSy, int maxSy) {
        for (int sy = minSy; sy <= maxSy; sy++) summaries.remove(GsrLayout.sectionKey(cx, sy, cz));
        // Already-built pages stay valid until they leave the window: no visual hole.
    }

    private void enqueueCapture(int sx, int sy, int sz) {
        if (!clipmap.sectionInCoverage(sx, sy, sz)) return;
        long key = GsrLayout.sectionKey(sx, sy, sz);
        if (!captureQueued.add(key)) return;
        int d = Math.max(Math.abs(sx - psx), Math.max(Math.abs(sy - psy), Math.abs(sz - psz)));
        captureBuckets[Math.min(DIST_BUCKETS - 1, d)].add(key);
    }

    // ------------------------------------------------------------------ Tick (main thread)

    public void tick(int playerBx, int playerBy, int playerBz, long nowMs) {
        long t0 = System.nanoTime();
        tickWork(playerBx, playerBy, playerBz, nowMs);
        double ms = (System.nanoTime() - t0) / 1e6;
        tickMsAvg = tickMsAvg * 0.95 + ms * 0.05;
        tickMsMax = Math.max(tickMsMax, ms);
    }

    private void tickWork(int playerBx, int playerBy, int playerBz, long nowMs) {
        tickCount++;
        psx = playerBx >> 4; psy = playerBy >> 4; psz = playerBz >> 4;

        // 1. Toroidal recentering: only the incoming pages need to be (re)built.
        for (PageKey k : clipmap.update(playerBx, playerBy, playerBz)) {
            if (tickCount > 1) windowMovedSinceEviction = true;   // the initial placement leaves nothing outside coverage
            pendingInvalidations.add(clipmap.slotIndex(k));
            latestVersion.remove(k);
            if (k.level() == 0) {
                enqueueCapture(k.x(), k.y(), k.z());
            } else {
                coarseDue.putIfAbsent(k, nowMs); // existing summaries are often enough
                // Sections already loaded but never summarized (they were outside coverage):
                // no load event will report them, so we request them explicitly.
                int span = 1 << k.level();
                for (int dz = 0; dz < span; dz++)
                    for (int dy = 0; dy < span; dy++)
                        for (int dx = 0; dx < span; dx++) {
                            int sx = k.x() * span + dx, sy = k.y() * span + dy, sz = k.z() * span + dz;
                            if (!summaries.containsKey(GsrLayout.sectionKey(sx, sy, sz))) enqueueCapture(sx, sy, sz);
                        }
            }
        }

        // 2. Modified summaries -> dirty coarse pages (with debounce increasing per level).
        for (Long key; (key = summarized.poll()) != null; ) {
            int sx = GsrLayout.sectionKeyX(key), sy = GsrLayout.sectionKeyY(key), sz = GsrLayout.sectionKeyZ(key);
            for (int L = 1; L < clipmap.levels(); L++) {
                PageKey p = GaussianClipmap.pageOfSection(L, sx, sy, sz);
                if (clipmap.contains(p)) coarseDue.putIfAbsent(p, nowMs + cfg.debounceForLevel(L));
            }
        }

        // 3. Captures, from nearest to farthest. An unloaded section (null) only costs
        //    a chunk lookup: it does not consume the copy budget, otherwise L3 coverage
        //    (+/-512 blocks, well beyond the render distance) fills the queue with empty sections that
        //    never drains (seen in game: ~100,000 entries). Separate cap to bound the tick.
        //    Bounded by client-thread time (captureBudgetMs, checked before each attempt, at least one
        //    attempt per tick), the counts are only upper bounds. A fixed 48 copies per tick made the
        //    first build last ~16 s at render distance 12, mostly waiting: air sections and unloaded
        //    sections cost microseconds each.
        captureStep(cfg.captureBudgetMs);

        // 4. Coarse pages whose debounce has elapsed.
        coarseStep(nowMs);

        // 5. Periodic eviction of summaries outside coverage.
        //    Only if the window moved since the last eviction: summaries are only created for sections in
        //    coverage. Measured in game: the first eviction pass over ~12,000 summaries took 1.1 ms (tick 200),
        //    the largest tick after the clipmap build at a standstill.
        if (tickCount % 200 == 0 && windowMovedSinceEviction) {
            windowMovedSinceEviction = false;
            summaries.keySet().removeIf(k -> !clipmap.sectionInCoverage(
                    GsrLayout.sectionKeyX(k), GsrLayout.sectionKeyY(k), GsrLayout.sectionKeyZ(k)));
        }
    }

    private void coarseStep(long nowMs) {
        if (coarseDue.isEmpty()) return;
        int coarseBudget = cfg.coarseJobsPerTick;
        for (Iterator<Map.Entry<PageKey, Long>> it = coarseDue.entrySet().iterator(); it.hasNext() && coarseBudget > 0; ) {
            Map.Entry<PageKey, Long> e = it.next();
            if (!clipmap.contains(e.getKey())) { it.remove(); continue; }
            if (e.getValue() > nowMs) continue;
            it.remove();
            submitCoarseJob(e.getKey());
            coarseBudget--;
        }
    }

    /**
     * Extra work between ticks (call once per rendered frame, client thread, after the first tick): captures
     * within budgetMs, then due coarse pages (coarseJobsPerTick). Ticks run at 20 Hz, so a per-tick budget alone
     * gives the captures only ~30 ms of client time per second, and coarse pages 160 submissions per second.
     * Measured in game: most of a capture's cost is the biome tint sampling (~40 us per non-empty section), so the
     * first build was limited by that time slice, then by the coarse rate. Nearly free once the queues are empty.
     */
    public void frameWork(float budgetMs, long nowMs) {
        if (tickCount == 0) return;   // window not placed yet
        if (!captureQueued.isEmpty() && budgetMs > 0f) {
            long t0 = clock.getAsLong();
            captureStep(budgetMs);
            double ms = (clock.getAsLong() - t0) / 1e6;
            if (frameCaptureCalls++ == 0) frameCaptureFirstMs = ms; else frameCaptureMsMax = Math.max(frameCaptureMsMax, ms);
        }
        if (budgetMs > 0f) coarseStep(nowMs);
    }

    /**
     * Longest capture step of frameWork() since creation, first call excluded (ms). The budget is checked before
     * each capture, so one capture can overshoot it (cold biome color cache: several ms).
     */
    public double frameCaptureMsMax() { return frameCaptureMsMax; }
    /** Capture time of the first frameWork() call that had captures to do (ms). */
    public double frameCaptureFirstMs() { return frameCaptureFirstMs; }

    private void captureStep(float budgetMs) {
        long c0 = clock.getAsLong();
        long deadline = c0 + (long) (budgetMs * 1e6);
        int budget = cfg.maxCapturesPerTick;
        int attempts = cfg.maxCapturesPerTick * 64;
        boolean first = true;
        captureLoop:
        for (int b = 0; b < DIST_BUCKETS && budget > 0 && attempts > 0; b++) {
            ArrayDeque<Long> q = captureBuckets[b];
            while (budget > 0 && attempts > 0 && !q.isEmpty()) {
                if (!first && clock.getAsLong() - deadline >= 0) break captureLoop;
                first = false;
                long key = q.poll();
                captureQueued.remove(key);
                int sx = GsrLayout.sectionKeyX(key), sy = GsrLayout.sectionKeyY(key), sz = GsrLayout.sectionKeyZ(key);
                if (!clipmap.sectionInCoverage(sx, sy, sz)) continue;
                attempts--;
                VoxelSource src = source.capture(sx, sy, sz);
                if (src == null) { capturesMissing++; continue; } // not loaded: will be signaled again when the chunk loads
                if (src == VoxelSource.EMPTY) capturesEmpty++; else capturesCopied++;
                budget--;
                submitSectionJob(key, sx, sy, sz, src);
            }
        }
        captureNanos += clock.getAsLong() - c0;
    }

    private long bumpVersion(PageKey k) {
        long v = versions.incrementAndGet();
        latestVersion.put(k, v);
        return v;
    }

    private void submitSectionJob(long key, int sx, int sy, int sz, VoxelSource src) {
        final PageKey l0 = clipmap.contains(0, sx, sy, sz) ? new PageKey(0, sx, sy, sz) : null;
        final long ver = l0 != null ? bumpVersion(l0) : 0L;
        inFlight.incrementAndGet();
        workers.execute(() -> {
            long j0 = System.nanoTime();
            try {
                SectionSummary s = SectionSummary.build(src);
                SectionSummary prev = summaries.put(key, s);
                if (prev == null || prev.isEmpty() != s.isEmpty() || prev.contentHash() != s.contentHash()) {
                    summarized.add(key);
                }
                if (l0 != null) {
                    PageData pd = src == VoxelSource.EMPTY
                            ? PageData.empty(l0, ver)
                            : SplatGenerator.generate(l0, ver, VoxelGrid.fromSource(src), cfg);
                    ready.add(pd);
                }
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                workerNanos.addAndGet(System.nanoTime() - j0);
                workerJobs.incrementAndGet();
                inFlight.decrementAndGet();
            }
        });
    }

    private void submitCoarseJob(PageKey page) {
        final long ver = bumpVersion(page);
        inFlight.incrementAndGet();
        workers.execute(() -> {
            long j0 = System.nanoTime();
            try {
                VoxelGrid g = VoxelGrid.fromSummaries(page,
                        (sx, sy, sz) -> summaries.get(GsrLayout.sectionKey(sx, sy, sz)));
                ready.add(SplatGenerator.generate(page, ver, g, cfg));
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                workerNanos.addAndGet(System.nanoTime() - j0);
                workerJobs.incrementAndGet();
                inFlight.decrementAndGet();
            }
        });
    }

    // ------------------------------------------------------------------ Upload (render thread)

    /** @return number of pages sent to the GPU. */
    public int drainUploads(GpuSink sink, int budget) {
        for (int slot : pendingInvalidations) { sink.invalidate(slot); slotSplats[slot] = -1; }
        pendingInvalidations.clear();
        int n = 0;
        while (n < budget) {
            PageData pd = ready.poll();
            if (pd == null) break;
            Long v = latestVersion.get(pd.key());
            if (v == null || v != pd.version() || !clipmap.contains(pd.key())) continue; // stale
            int slot = clipmap.slotIndex(pd.key());
            sink.upload(slot, pd);
            slotSplats[slot] = pd.splatCount();
            n++;
        }
        totalUploads += n;
        return n;
    }

    // ------------------------------------------------------------------ Statistics (main thread)

    /**
     * Snapshot for /gsr stats. Per level: valid pages on the GPU, of which non-empty, splats.
     * Queues: pending captures, coarse pages in debounce, worker tasks, ready pages not yet sent.
     * CPU: client-thread tick() average and maximum since the previous call (ms), worker jobs and their total time.
     */
    public record Stats(int slotsPerLevel, int[] pages, int[] nonEmptyPages, long[] splats,
                        int captureQueue, int coarsePending, int inFlight, int readyQueue,
                        int summaries, long totalUploads,
                        double tickMsAvg, double tickMsMax, long workerJobs, double workerMs) {}

    /** Pages uploaded since creation. Cheap, and unlike {@link #stats()} it does not reset the tick maximum. */
    public long totalUploads() { return totalUploads; }

    /** Build progress for the log (cheap, resets nothing): queues and capture counters. */
    public String progressLine() {
        return "captures queued " + captureQueued.size() + ", copied " + capturesCopied + ", empty " + capturesEmpty
                + ", not loaded " + capturesMissing + " (" + Math.round(captureNanos / 1e6) + " ms); coarse " + coarseDue.size() + ", workers " + inFlight.get()
                + ", ready " + ready.size() + ", uploads " + totalUploads + ", jobs " + workerJobs.get();
    }

    public Stats stats() {
        int levels = clipmap.levels(), per = cfg.slotsPerLevel();
        int[] pages = new int[levels], nonEmpty = new int[levels];
        long[] splats = new long[levels];
        for (int s = 0; s < slotSplats.length; s++) {
            int c = slotSplats[s];
            if (c < 0) continue;
            int L = s / per;
            pages[L]++;
            if (c > 0) nonEmpty[L]++;
            splats[L] += c;
        }
        double max = tickMsMax;
        tickMsMax = 0;
        return new Stats(per, pages, nonEmpty, splats, captureQueued.size(), coarseDue.size(),
                inFlight.get(), ready.size(), summaries.size(), totalUploads,
                tickMsAvg, max, workerJobs.get(), workerNanos.get() / 1e6);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        try { workers.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
