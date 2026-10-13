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
 * Gestionnaire des "Gaussian Chunks".
 *
 * Pipeline (threads) :
 * <pre>
 *  [Thread principal]                        [Workers]                         [Thread de rendu]
 *  bloc modifié / chunk chargé
 *    -> file de capture (priorité distance)
 *  tick(): capture(section) = snapshot ──► SectionSummary.build()
 *                                          + si dans L0 : VoxelGrid → splats ──► file "ready"
 *          résumé changé ◄──────────────── (file "summarized")
 *    -> pages L1..L3 sales (anti-rebond)
 *  tick(): page grossière due ───────────► VoxelGrid.fromSummaries → splats ─► file "ready"
 *                                                                              drainUploads():
 *                                                                              version à jour ? dans
 *                                                                              la fenêtre ? -> GpuSink
 * </pre>
 * Sous Minecraft, tick et rendu sont sur le même thread client ; toutes les méthodes publiques
 * sauf le constructeur doivent y être appelées.
 */
public final class GaussianChunkManager implements AutoCloseable {
    private static final int DIST_BUCKETS = 64;

    private final GsrConfig cfg;
    private final GaussianClipmap clipmap;
    private final SectionSnapshotSource source;
    private final ExecutorService workers;

    // Partagé avec les workers
    private final ConcurrentHashMap<Long, SectionSummary> summaries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PageKey, Long> latestVersion = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<PageData> ready = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> summarized = new ConcurrentLinkedQueue<>();
    private final AtomicLong versions = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();

    // Thread principal uniquement
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Long>[] captureBuckets = new ArrayDeque[DIST_BUCKETS];
    private final Set<Long> captureQueued = new HashSet<>();
    private final Map<PageKey, Long> coarseDue = new HashMap<>();
    private final List<Integer> pendingInvalidations = new ArrayList<>();
    private int psx, psy, psz;
    private long tickCount;
    /** Splats par slot GPU tel que téléversé (−1 = slot invalide). Miroir CPU de la méta, pour les stats. */
    private final int[] slotSplats;
    private long totalUploads;

    public GaussianChunkManager(GsrConfig cfg, SectionSnapshotSource source) {
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
    /** Travail en cours ou en attente (utile pour les tests et le HUD de debug). */
    public boolean isIdle() {
        return inFlight.get() == 0 && ready.isEmpty() && summarized.isEmpty() && captureQueued.isEmpty() && coarseDue.isEmpty();
    }

    // ------------------------------------------------------------------ Événements du monde

    /** Un bloc a changé : on reconstruit sa section, et la voisine si le bloc touche une frontière. */
    public void onBlockChanged(int bx, int by, int bz) {
        int sx = bx >> 4, sy = by >> 4, sz = bz >> 4;
        enqueueCapture(sx, sy, sz);
        int lx = bx & 15, ly = by & 15, lz = bz & 15;
        if (lx == 0) enqueueCapture(sx - 1, sy, sz); else if (lx == 15) enqueueCapture(sx + 1, sy, sz);
        if (ly == 0) enqueueCapture(sx, sy - 1, sz); else if (ly == 15) enqueueCapture(sx, sy + 1, sz);
        if (lz == 0) enqueueCapture(sx, sy, sz - 1); else if (lz == 15) enqueueCapture(sx, sy, sz + 1);
    }

    /** La lumière d'une section a changé (le résumé ne déclenchera une reconstruction grossière que s'il diffère). */
    public void onSectionDirty(int sx, int sy, int sz) { enqueueCapture(sx, sy, sz); }

    /** Chunk chargé : ses sections + celles des 4 colonnes voisines (leurs faces de bordure changent). */
    public void onChunkLoaded(int cx, int cz, int minSy, int maxSy) {
        for (int sy = minSy; sy <= maxSy; sy++) {
            enqueueCapture(cx, sy, cz);
            enqueueCapture(cx - 1, sy, cz); enqueueCapture(cx + 1, sy, cz);
            enqueueCapture(cx, sy, cz - 1); enqueueCapture(cx, sy, cz + 1);
        }
    }

    public void onChunkUnloaded(int cx, int cz, int minSy, int maxSy) {
        for (int sy = minSy; sy <= maxSy; sy++) summaries.remove(GsrLayout.sectionKey(cx, sy, cz));
        // Les pages déjà construites restent valides jusqu'à leur sortie de fenêtre : pas de trou visuel.
    }

    private void enqueueCapture(int sx, int sy, int sz) {
        if (!clipmap.sectionInCoverage(sx, sy, sz)) return;
        long key = GsrLayout.sectionKey(sx, sy, sz);
        if (!captureQueued.add(key)) return;
        int d = Math.max(Math.abs(sx - psx), Math.max(Math.abs(sy - psy), Math.abs(sz - psz)));
        captureBuckets[Math.min(DIST_BUCKETS - 1, d)].add(key);
    }

    // ------------------------------------------------------------------ Tick (thread principal)

    public void tick(int playerBx, int playerBy, int playerBz, long nowMs) {
        tickCount++;
        psx = playerBx >> 4; psy = playerBy >> 4; psz = playerBz >> 4;

        // 1. Recentrage toroïdal : seules les pages entrantes sont à (re)construire.
        for (PageKey k : clipmap.update(playerBx, playerBy, playerBz)) {
            pendingInvalidations.add(clipmap.slotIndex(k));
            latestVersion.remove(k);
            if (k.level() == 0) {
                enqueueCapture(k.x(), k.y(), k.z());
            } else {
                coarseDue.putIfAbsent(k, nowMs); // les résumés existants suffisent souvent
                // Sections déjà chargées mais jamais résumées (elles étaient hors couverture) :
                // aucun événement de chargement ne les signalera, on les demande explicitement.
                int span = 1 << k.level();
                for (int dz = 0; dz < span; dz++)
                    for (int dy = 0; dy < span; dy++)
                        for (int dx = 0; dx < span; dx++) {
                            int sx = k.x() * span + dx, sy = k.y() * span + dy, sz = k.z() * span + dz;
                            if (!summaries.containsKey(GsrLayout.sectionKey(sx, sy, sz))) enqueueCapture(sx, sy, sz);
                        }
            }
        }

        // 2. Résumés modifiés -> pages grossières sales (avec anti-rebond croissant par niveau).
        for (Long key; (key = summarized.poll()) != null; ) {
            int sx = GsrLayout.sectionKeyX(key), sy = GsrLayout.sectionKeyY(key), sz = GsrLayout.sectionKeyZ(key);
            for (int L = 1; L < clipmap.levels(); L++) {
                PageKey p = GaussianClipmap.pageOfSection(L, sx, sy, sz);
                if (clipmap.contains(p)) coarseDue.putIfAbsent(p, nowMs + cfg.debounceForLevel(L));
            }
        }

        // 3. Captures, de la plus proche à la plus lointaine. Une section non chargée (null) ne coûte
        //    qu'une recherche de chunk : elle ne consomme pas le budget de copies, sinon la couverture
        //    L3 (±512 blocs, bien au-delà de la distance de rendu) remplit la file de sections vides qui
        //    ne se vide jamais (vu en jeu : ~100 000 entrées). Plafond séparé pour borner le tick.
        int budget = cfg.capturesPerTick;
        int attempts = cfg.capturesPerTick * 64;
        for (int b = 0; b < DIST_BUCKETS && budget > 0 && attempts > 0; b++) {
            ArrayDeque<Long> q = captureBuckets[b];
            while (budget > 0 && attempts > 0 && !q.isEmpty()) {
                long key = q.poll();
                captureQueued.remove(key);
                int sx = GsrLayout.sectionKeyX(key), sy = GsrLayout.sectionKeyY(key), sz = GsrLayout.sectionKeyZ(key);
                if (!clipmap.sectionInCoverage(sx, sy, sz)) continue;
                attempts--;
                VoxelSource src = source.capture(sx, sy, sz);
                if (src == null) continue; // non chargée : sera re-signalée au chargement du chunk
                budget--;
                submitSectionJob(key, sx, sy, sz, src);
            }
        }

        // 4. Pages grossières dont l'anti-rebond est écoulé.
        int coarseBudget = cfg.coarseJobsPerTick;
        for (Iterator<Map.Entry<PageKey, Long>> it = coarseDue.entrySet().iterator(); it.hasNext() && coarseBudget > 0; ) {
            Map.Entry<PageKey, Long> e = it.next();
            if (!clipmap.contains(e.getKey())) { it.remove(); continue; }
            if (e.getValue() > nowMs) continue;
            it.remove();
            submitCoarseJob(e.getKey());
            coarseBudget--;
        }

        // 5. Éviction périodique des résumés hors couverture.
        if (tickCount % 200 == 0) {
            summaries.keySet().removeIf(k -> !clipmap.sectionInCoverage(
                    GsrLayout.sectionKeyX(k), GsrLayout.sectionKeyY(k), GsrLayout.sectionKeyZ(k)));
        }
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
                inFlight.decrementAndGet();
            }
        });
    }

    private void submitCoarseJob(PageKey page) {
        final long ver = bumpVersion(page);
        inFlight.incrementAndGet();
        workers.execute(() -> {
            try {
                VoxelGrid g = VoxelGrid.fromSummaries(page,
                        (sx, sy, sz) -> summaries.get(GsrLayout.sectionKey(sx, sy, sz)));
                ready.add(SplatGenerator.generate(page, ver, g, cfg));
            } catch (Throwable t) {
                t.printStackTrace();
            } finally {
                inFlight.decrementAndGet();
            }
        });
    }

    // ------------------------------------------------------------------ Téléversement (thread de rendu)

    /** @return nombre de pages envoyées au GPU. */
    public int drainUploads(GpuSink sink, int budget) {
        for (int slot : pendingInvalidations) { sink.invalidate(slot); slotSplats[slot] = -1; }
        pendingInvalidations.clear();
        int n = 0;
        while (n < budget) {
            PageData pd = ready.poll();
            if (pd == null) break;
            Long v = latestVersion.get(pd.key());
            if (v == null || v != pd.version() || !clipmap.contains(pd.key())) continue; // obsolète
            int slot = clipmap.slotIndex(pd.key());
            sink.upload(slot, pd);
            slotSplats[slot] = pd.splatCount();
            n++;
        }
        totalUploads += n;
        return n;
    }

    // ------------------------------------------------------------------ Statistiques (thread principal)

    /**
     * Instantané pour /gsr stats. Par niveau : pages valides sur le GPU, dont non vides, splats.
     * Files : captures en attente, pages grossières en anti-rebond, tâches workers, pages prêtes non envoyées.
     */
    public record Stats(int slotsPerLevel, int[] pages, int[] nonEmptyPages, long[] splats,
                        int captureQueue, int coarsePending, int inFlight, int readyQueue,
                        int summaries, long totalUploads) {}

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
        return new Stats(per, pages, nonEmpty, splats, captureQueued.size(), coarseDue.size(),
                inFlight.get(), ready.size(), summaries.size(), totalUploads);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        try { workers.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
