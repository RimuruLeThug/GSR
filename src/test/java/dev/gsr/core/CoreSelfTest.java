package dev.gsr.core;

import java.util.HashMap;
import java.util.Map;

/**
 * GSR core tests without Minecraft or GPU: synthetic world -> manager -> in-memory "GPU"
 * (same byte layout) -> reference tracer identical to the shader.
 * Run with javac + java (see README); non-zero exit code on failure.
 */
public final class CoreSelfTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------------ synthetic world
    static final class World {
        final Map<Long, Integer> overrides = new HashMap<>();
        static final int STONE = 0xFF7F7F7F, GRASS = 0xFF7FB238, SAND = 0xFFF7E9A3, WATER = 0x993F76E4, PILLAR = 0xFFFFFFFF;

        int height(int x, int z) { return 64; }
        boolean inPond(int x, int z) { return x >= -20 && x < -10 && z >= -5 && z < 5; }
        boolean inPillar(int x, int z) { return x >= 48 && x < 50 && z >= 7 && z < 9; }

        /** KIND_AIR = air, otherwise ARGB, with the kind encoded separately. */
        int[] block(int x, int y, int z) {
            Integer o = overrides.get(key(x, y, z));
            if (o != null) return o == 0 ? new int[]{GsrLayout.KIND_AIR, 0} : new int[]{GsrLayout.KIND_OPAQUE, o};
            if (y < 0) return new int[]{GsrLayout.KIND_OPAQUE, STONE};
            int h = height(x, z);
            if (inPond(x, z)) {
                if (y == h || y == h - 1) return new int[]{GsrLayout.KIND_TRANSLUCENT, WATER};
                if (y < h - 1) return new int[]{GsrLayout.KIND_OPAQUE, SAND};
                return new int[]{GsrLayout.KIND_AIR, 0};
            }
            if (inPillar(x, z) && y > h && y <= 90) return new int[]{GsrLayout.KIND_OPAQUE, PILLAR};
            if (y < h) return new int[]{GsrLayout.KIND_OPAQUE, STONE};
            if (y == h) return new int[]{GsrLayout.KIND_OPAQUE, GRASS};
            return new int[]{GsrLayout.KIND_AIR, 0};
        }
        static long key(int x, int y, int z) { return GsrLayout.sectionKey(x, y, z); }

        VoxelSource capture(int sx, int sy, int sz) {
            if (sy < 0 || sy > 7) return null;
            final int ox = sx * 16, oy = sy * 16, oz = sz * 16;
            boolean any = false;
            for (int z = -1; z <= 16 && !any; z++) for (int y = -1; y <= 16 && !any; y++) for (int x = -1; x <= 16 && !any; x++)
                if (block(ox + x, oy + y, oz + z)[0] != GsrLayout.KIND_AIR) any = true;
            if (!any) return VoxelSource.EMPTY;
            return new VoxelSource() {
                public int kind(int x, int y, int z) { return block(ox + x, oy + y, oz + z)[0]; }
                public int albedo(int x, int y, int z) { return block(ox + x, oy + y, oz + z)[1]; }
                public int light(int x, int y, int z) { return (oy + y > height(ox + x, oz + z) ? 15 : 4) << 4; }
                public int emission(int x, int y, int z) { return 0; }
            };
        }
    }

    static void pump(GaussianChunkManager m, CpuReferenceTracer gpu, int bx, int by, int bz) throws InterruptedException {
        long now = 0;
        for (int i = 0; i < 2000; i++) {
            m.tick(bx, by, bz, now += 50);
            m.drainUploads(gpu, 1 << 20);
            if (m.isIdle()) { Thread.sleep(5); if (m.isIdle()) break; }
            Thread.sleep(1);
        }
        gpu.syncFrom(m.clipmap());
        gpu.frame.setCamera(bx + 0.5, by + 0.5, bz + 0.5);
    }

    /** /gsr stats must exactly reflect the meta received by the "GPU", level by level. */
    static void checkStats(GaussianChunkManager m, CpuReferenceTracer gpu, GsrConfig cfg, String when) {
        GaussianChunkManager.Stats st = m.stats();
        int per = cfg.slotsPerLevel();
        boolean same = true, allLevels = true;
        for (int L = 0; L < cfg.levels; L++) {
            int pages = 0, nonEmpty = 0; long splats = 0;
            for (int s = L * per; s < (L + 1) * per; s++) {
                int c = gpu.metaSplats(s);
                if (c < 0) continue;
                pages++; if (c > 0) nonEmpty++; splats += c;
            }
            same &= pages == st.pages()[L] && nonEmpty == st.nonEmptyPages()[L] && splats == st.splats()[L];
            allLevels &= st.nonEmptyPages()[L] > 0;
        }
        boolean idle = st.captureQueue() == 0 && st.coarsePending() == 0 && st.inFlight() == 0 && st.readyQueue() == 0;
        check(same && allLevels && idle, "stats " + when + " = GPU meta (L0: " + st.pages()[0] + " pages, "
                + st.splats()[0] + " splats; queues empty: " + idle + ")");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== Layout");
        for (int[] d : GsrLayout.DIRS) {
            float[] n = GsrLayout.octDecode(GsrLayout.octEncode(d[0], d[1], d[2]));
            float dot = n[0] * d[0] + n[1] * d[1] + n[2] * d[2];
            check(dot > 0.999f, "octahedral round trip " + java.util.Arrays.toString(d) + " dot=" + dot);
        }
        long k = GsrLayout.sectionKey(-1234, -4, 98765);
        check(GsrLayout.sectionKeyX(k) == -1234 && GsrLayout.sectionKeyY(k) == -4 && GsrLayout.sectionKeyZ(k) == 98765, "signed section key");
        check(GsrFrameData.BYTES == 208, "GsrFrame std430 size = 208");

        System.out.println("== Configuration (out-of-range values from config/gsr.json)");
        {
            GsrConfig bad = new GsrConfig();
            bad.levels = 9; bad.pagesY = 0; bad.baseOpacity = Float.NaN; bad.sigmaTangent = -1f;
            bad.standaloneResolutionScale = 7; bad.capturesPerTick = 0; bad.maxTraceDistance = Float.NaN;
            bad.validate();
            check(bad.levels == GsrLayout.MAX_LEVELS && bad.pagesY == 2 && bad.baseOpacity == 0f && bad.sigmaTangent > 0f
                    && bad.standaloneResolutionScale == 2 && bad.capturesPerTick == 1 && bad.maxTraceDistance > 0f,
                    "validate() clamps out-of-range values");
            GsrConfig live = new GsrConfig(), file = new GsrConfig();
            file.standaloneStrength = 0.5f;
            boolean restart = live.copyRuntimeFrom(file);
            file.pagesX = 16;
            boolean restart2 = live.copyRuntimeFrom(file);
            check(!restart && restart2 && live.standaloneStrength == 0.5f && live.pagesX == 8,
                    "hot reload: structural fields unchanged and flagged");
        }

        System.out.println("== Budget / per-cell sorting (3D checkerboard, worst case)");
        {
            GsrConfig cfg = new GsrConfig();
            VoxelSource checker = new VoxelSource() {
                public int kind(int x, int y, int z) { return ((x + y + z) & 1) == 0 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return 0xFF808080; }
                public int light(int x, int y, int z) { return (y & 15) << 4; }
                public int emission(int x, int y, int z) { return 0; }
            };
            PageData pd = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(checker), cfg);
            check(pd.splatCount() == cfg.maxSplatsPerPage, "capped at " + cfg.maxSplatsPerPage + " (got " + pd.splatCount() + ")");
            int sum = 0, expectedOff = 0; boolean contiguous = true, inCell = true;
            for (int c = 0; c < GsrLayout.CELLS_PER_PAGE; c++) {
                int off = pd.cells()[c] & 0xFFFF, cnt = pd.cells()[c] >>> 16;
                if (off != expectedOff) contiguous = false;
                for (int i = off; i < off + cnt; i++) {
                    float x = pd.splats().getFloat(i * 32), y = pd.splats().getFloat(i * 32 + 4), z = pd.splats().getFloat(i * 32 + 8);
                    int cx = c % 8, cy = (c / 8) % 8, cz = c / 64;
                    if (x < cx * 2 - 0.01f || x > cx * 2 + 2.01f || y < cy * 2 - 0.01f || y > cy * 2 + 2.01f || z < cz * 2 - 0.01f || z > cz * 2 + 2.01f) inCell = false;
                }
                expectedOff += cnt; sum += cnt;
            }
            check(sum == pd.splatCount() && contiguous, "cell table contiguous and complete");
            check(inCell, "each splat lies on the boundary of its cell");
        }

        System.out.println("== Brick mask");
        {
            // A single block at (5, 9, 13) -> cell (2, 4, 6) -> brick (1, 2, 3); its faces spill over
            // into neighboring cells, but all stay within bricks whose bit must be set.
            VoxelSource one = new VoxelSource() {
                public int kind(int x, int y, int z) { return x == 5 && y == 9 && z == 13 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return 0xFF808080; }
                public int light(int x, int y, int z) { return 0xF0; }
                public int emission(int x, int y, int z) { return 0; }
            };
            PageData pd = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(one), new GsrConfig());
            boolean exact = true;
            for (int c = 0; c < GsrLayout.CELLS_PER_PAGE; c++) {
                int cx = c % 8, cy = (c / 8) % 8, cz = c / 64;
                boolean bit = ((pd.brickMask() >>> GsrLayout.brickIndex(cx >> 1, cy >> 1, cz >> 1)) & 1) != 0;
                if ((pd.cells()[c] >>> 16) > 0 && !bit) exact = false;
            }
            check(exact && Long.bitCount(pd.brickMask()) == 1 && pd.brickMask() == 1L << GsrLayout.brickIndex(1, 2, 3),
                    "isolated block -> a single bit, that of its brick (mask=" + Long.toHexString(pd.brickMask()) + ")");
            long high = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(new VoxelSource() {
                public int kind(int x, int y, int z) { return x == 15 && y == 15 && z == 15 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return 0xFF808080; }
                public int light(int x, int y, int z) { return 0xF0; }
                public int emission(int x, int y, int z) { return 0; }
            }), new GsrConfig()).brickMask();
            check(high == 1L << 63, "brick 63 -> bit 63 (upper half, mask.y)");
        }

        System.out.println("== Albedo per face group (grass block: green on top, dirt elsewhere)");
        {
            final int GREEN = 0xFF5FA032, DIRT = 0xFF866043;
            VoxelSource grass = new VoxelSource() {
                public int kind(int x, int y, int z) { return y < 8 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return DIRT; }
                public int albedo(int x, int y, int z, int f) { return f == GsrLayout.FACE_UP ? GREEN : DIRT; }
                public int light(int x, int y, int z) { return 0xF0; }
                public int emission(int x, int y, int z) { return 0; }
            };
            // L0: an isolated grass block in the grid -> each face has its group's color
            VoxelSource one = new VoxelSource() {
                public int kind(int x, int y, int z) { return x == 4 && y == 4 && z == 4 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return DIRT; }
                public int albedo(int x, int y, int z, int f) { return grass.albedo(x, y, z, f); }
                public int light(int x, int y, int z) { return 0xF0; }
                public int emission(int x, int y, int z) { return 0; }
            };
            PageData pd = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(one), new GsrConfig());
            boolean ok = pd.splatCount() == 6;
            for (int i = 0; i < pd.splatCount(); i++) {
                int rgb = pd.splats().getInt(i * 32 + 16) & 0xFFFFFF;
                float[] n = GsrLayout.octDecode(pd.splats().getInt(i * 32 + 20) & 0xFFFF);
                ok &= rgb == ((n[1] > 0.5f ? GREEN : DIRT) & 0xFFFFFF);
            }
            check(ok, "L0: top face green, sides and bottom dirt-colored (" + pd.splatCount() + " splats)");
            // Mip 1 (L1): the summary keeps the top / sides distinction
            SectionSummary s = SectionSummary.build(grass);
            check(s.albedo(1, 0, 3, 0, GsrLayout.FACE_UP) == GREEN && s.albedo(1, 0, 3, 0, GsrLayout.FACE_SIDE) == DIRT,
                    "mip1: top albedo = grass, sides = dirt");
        }

        System.out.println("== Cross-shaped plants: splats without per-face shading (bit 30)");
        {
            // Bug: grasses ("shade": false in vanilla) took the 0.6/0.8 side shading -> GSR
            // reflection of tufts darker than SSR at the screen edge.
            VoxelSource plant = new VoxelSource() {
                public int kind(int x, int y, int z) {
                    return y == 3 && z == 4 && (x == 4 || x == 8) ? (x == 4 ? GsrLayout.KIND_TRANSLUCENT : GsrLayout.KIND_OPAQUE) : GsrLayout.KIND_AIR;
                }
                public int albedo(int x, int y, int z) { return 0x80608040; }
                public int light(int x, int y, int z) { return 0xF0; }
                public int emission(int x, int y, int z) { return 0; }
                public boolean unshaded(int x, int y, int z) { return x == 4; }
            };
            PageData pd = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(plant), new GsrConfig());
            int flagged = 0, plain = 0;
            for (int i = 0; i < pd.splatCount(); i++) {
                int data = pd.splats().getInt(i * 32 + 20);
                float x = pd.splats().getFloat(i * 32);
                boolean bit = (data & (1 << 30)) != 0;
                if (x < 6f && bit) flagged++;
                if (x > 6f && !bit) plain++;
            }
            check(pd.splatCount() == 12 && flagged == 6 && plain == 6,
                    "plant: 6 splats flagged unshaded, opaque block: 6 shaded splats (" + flagged + "/" + plain + ")");
            check(GsrLayout.packSplatData(0xFFFF, 0xFF, 0xF, 3, false) >>> 30 == 0, "bit 30 clear without the flag (level 3, everything set to 1)");

            // "Green cubes" bug: a plant's face was solid all the way up, with straight edges.
            // Blades: solid at the foot, irregular upper silhouette, mean coverage ≈ 1 / TUFT_GAIN.
            float[] side = {1, 0, 0}, up = {0, 1, 0};
            float foot = 1f, top = 0f, mean = 0f;
            int samples = 0;
            java.util.Set<Float> heights = new java.util.HashSet<>();
            for (int sp = 0; sp < 20; sp++) {
                float px0 = 4.5f + sp, pz0 = 3.5f;
                for (int i = 0; i < 40; i++) {
                    float hz = -0.5f + (i + 0.5f) / 40f;
                    foot = Math.min(foot, CpuReferenceTracer.tuft(0, -0.4f, hz, side, px0, 3.5f, pz0, 1f));
                    top += CpuReferenceTracer.tuft(0, 0.45f, hz, side, px0, 3.5f, pz0, 1f) / 800f;
                    for (int j = 0; j < 20; j++) { mean += CpuReferenceTracer.tuft(0, -0.5f + (j + 0.5f) / 20f, hz, side, px0, 3.5f, pz0, 1f); samples++; }
                }
                float hcut = 0f;   // height of the 1st blade: first v where the mask drops below 0.5
                for (int j = 0; j < 100 && CpuReferenceTracer.tuft(0, -0.5f + j / 100f, -0.4f, side, px0, 3.5f, pz0, 1f) >= 0.5f; j++) hcut = j;
                heights.add(hcut);
            }
            mean /= samples;
            check(foot == 1f && top < 0.35f && heights.size() >= 5,
                    String.format("plant: blades solid at the foot, sparse top (%.2f), %d distinct heights out of 20", top, heights.size()));
            check(Math.abs(mean * CpuReferenceTracer.TUFT_GAIN - 1f) < 0.15f,
                    String.format("mean blade coverage × gain ≈ 1 (%.2f)", mean * CpuReferenceTracer.TUFT_GAIN));
            check(CpuReferenceTracer.tuft(0.3f, 0.45f, 0.3f, up, 1, 2, 3, 1f) == 1f, "plant top / bottom faces unchanged");
        }

        System.out.println("== Section summaries");
        {
            World w = new World();
            SectionSummary s = SectionSummary.build(w.capture(0, 4, 0)); // y 64..79: grass at y=64
            check(s.kind(1, 0, 0, 0) == GsrLayout.KIND_OPAQUE, "mip1: voxel (y 64-65) 50% full -> opaque (threshold >= 50%)");
            check(s.kind(1, 0, 1, 0) == GsrLayout.KIND_AIR, "mip1: voxel (y 66-67) empty -> air");
            SectionSummary below = SectionSummary.build(w.capture(0, 3, 0)); // y 48..63: stone
            check(below.kind(3, 0, 1, 0) == GsrLayout.KIND_OPAQUE, "mip3: solid stone -> opaque");
            check(SectionSummary.build(w.capture(0, 6, 0)).isEmpty(), "air section -> EMPTY singleton");
        }

        System.out.println("== Full pipeline + reference tracer");
        GsrConfig cfg = new GsrConfig();
        cfg.levels = 3; cfg.pagesX = 4; cfg.pagesY = 2; cfg.pagesZ = 4;
        cfg.capturesPerTick = 100_000; cfg.coarseJobsPerTick = 10_000; cfg.coarseDebounceMs = 0;
        World world = new World();
        CpuReferenceTracer gpu = new CpuReferenceTracer(cfg);
        try (GaussianChunkManager m = new GaussianChunkManager(cfg, world::capture)) {
            int px = 8, py = 70, pz = 8;
            m.tick(px, py, pz, 0); // initialize the windows
            for (int cx = -20; cx <= 20; cx++) for (int cz = -20; cz <= 20; cz++) m.onChunkLoaded(cx, cz, 0, 7);
            long t0 = System.nanoTime();
            pump(m, gpu, px, py, pz);
            System.out.printf("  (initial generation: %.0f ms)%n", (System.nanoTime() - t0) / 1e6);
            checkStats(m, gpu, cfg, "after initial generation");

            CpuReferenceTracer.Hit h = gpu.trace(0, 0, 0, 0, -1, 0, 0.05f, 300, 0, 0);
            check(Math.abs(h.hitDist - 5.5f) < 0.3f && h.transmittance < 0.05f && h.level == 0,
                    String.format("vertical ray -> ground at 5.5 (dist=%.2f, T=%.3f, L=%d)", h.hitDist, h.transmittance, h.level));
            check(h.g > h.r && h.g > h.b, "hit color = grass (green)");

            h = gpu.trace(0, 0, 0, 1, 0, 0, 0.05f, 300, 0, 0);
            check(Math.abs(h.hitDist - 39.5f) < 1.2f && h.transmittance < 0.1f && h.level == 1,
                    String.format("horizontal ray -> pillar outside L0, found in L1 (dist=%.2f, T=%.3f, L=%d)", h.hitDist, h.transmittance, h.level));

            h = gpu.trace(0, 0, 0, 0, 1, 0, 0.05f, 300, 0, 0);
            check(h.transmittance > 0.99f, "ray toward the sky: nothing hit (T=" + h.transmittance + ")");

            float dx = -15f - 8.5f, dy = 65f - 70.5f, dz = 0.5f - 8.5f;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            h = gpu.trace(0, 0, 0, dx / len, dy / len, dz / len, 0.05f, 300, 0, 0);
            check(h.transmittance < 0.05f && h.b > h.r, String.format("ray toward the pond: translucent water then bottom (T=%.3f, b>r)", h.transmittance));

            // Brown reflection bug: grazing ray 0.25 above the water (y 65.25) toward the bank (x = -10).
            // The submerged flank of the bank (face -X, center y 64.5) must not spill above its face.
            h = gpu.trace(-23.5f, -5.25f, -7.5f, 1, 0, 0, 0.05f, 20, 0, 0);
            check(h.transmittance > 0.95f, String.format("grazing ray above the bank: the submerged flank does not spill over (T=%.3f)", h.transmittance));
            h = gpu.trace(-23.5f, -5.75f, -7.5f, 1, 0, 0, 0.05f, 20, 0, 0);
            check(h.transmittance < 0.05f && Math.abs(h.hitDist - 5f) < 0.3f,
                    String.format("ray under the water toward the bank: flank hit (T=%.3f, dist=%.2f)", h.transmittance, h.hitDist));

            // Horizontal ray at y = 69 over 20 blocks: valid pages (the ground is there), but bricks y 68-71
            // empty. Cell by cell: ≥ 10 steps; with the brick mask: ~5.
            h = gpu.trace(0, -1.5f, 0, 0, 0, 1, 0.05f, 20, 0, 0);
            check(h.transmittance > 0.99f && h.steps <= 6,
                    "empty bricks crossed without reading the cells (" + h.steps + " steps for 20 blocks, T=" + h.transmittance + ")");

            {   // The mask must only change the step count, never what is hit.
                java.util.Random rnd = new java.util.Random(42);
                int diff = 0, capped = 0, total = 4000; String first = null;
                for (int i = 0; i < total; i++) {
                    float rx = (float) rnd.nextGaussian(), ry = (float) rnd.nextGaussian(), rz = (float) rnd.nextGaussian();
                    float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                    float ox = rnd.nextFloat() * 40 - 20, oy = rnd.nextFloat() * 10 - 5.4f, oz = rnd.nextFloat() * 40 - 20; // oy: in the air (ground at -5.5)
                    float cone = rnd.nextBoolean() ? 0 : rnd.nextFloat() * 0.35f;
                    gpu.ignoreBrickMask = true;
                    CpuReferenceTracer.Hit a = gpu.trace(ox, oy, oz, rx / rl, ry / rl, rz / rl, 0.02f, 300, cone, 0);
                    gpu.ignoreBrickMask = false;
                    CpuReferenceTracer.Hit b = gpu.trace(ox, oy, oz, rx / rl, ry / rl, rz / rl, 0.02f, 300, cone, 0);
                    // Same visible hit: both hit or both missed (T 0.5), same distance, same color.
                    boolean same = (a.transmittance < 0.5f) == (b.transmittance < 0.5f)
                            && Math.abs(a.transmittance - b.transmittance) < 0.1f && Math.abs(a.g - b.g) < 0.05f
                            && (a.transmittance > 0.9f || Math.abs(a.hitDist - b.hitDist) < 0.25f);
                    if (a.steps >= 160 && b.transmittance < 0.5f && a.transmittance > 0.5f) capped++;
                    if (!same && a.steps < 160) {
                        diff++;
                        if (first == null) first = String.format("o=(%.2f,%.2f,%.2f) d=(%.3f,%.3f,%.3f) cone=%.2f : without mask T=%.3f dist=%.2f L=%d, with T=%.3f dist=%.2f L=%d",
                                ox, oy, oz, rx / rl, ry / rl, rz / rl, cone, a.transmittance, a.hitDist, a.level, b.transmittance, b.hitDist, b.level);
                    }
                }
                // Tolerance 1/1000: without the mask, an occupied cell does not apply the cone's level bound
                // (empty skip only); a rough ray can stay in L1 past the switch to L2 and
                // miss a floor that L2 contains. Before surfels were clipped to their face, the disc spill-over
                // hid this gap (0/4000); since then, 1/4000 (cone 0.27). The shader follows the WITH-mask path.
                check(diff <= total / 1000, "brick mask: same hits as cell-by-cell traversal (" + diff + "/" + total + " mismatches)"
                        + (first == null ? "" : "\n         e.g. " + first));
                System.out.println("  (rays hitting 160 steps without the mask, which now hit: " + capped + ")");
            }

            h = gpu.trace(0, 0, 0, 0, -1, 0, 0.05f, 300, 0.5f, 0);
            check(Math.abs(h.hitDist - 5.5f) < 1.5f && h.transmittance < 0.1f,
                    String.format("rough cone (tan=0.5): ground found at a coarser level (dist=%.2f, L=%d)", h.hitDist, h.level));

            System.out.println("== Block modification (pillar removal)");
            for (int y = 65; y <= 90; y++) for (int x = 48; x < 50; x++) for (int z = 7; z < 9; z++) {
                world.overrides.put(World.key(x, y, z), 0);
                m.onBlockChanged(x, y, z);
            }
            pump(m, gpu, px, py, pz);
            h = gpu.trace(0, 0, 0, 1, 0, 0, 0.05f, 300, 0, 0);
            check(h.transmittance > 0.99f, "pillar removed -> the ray passes (T=" + h.transmittance + ")");

            System.out.println("== Toroidal recentering (player moved by +100 in X)");
            for (int y = 65; y <= 90; y++) for (int x = 48; x < 50; x++) for (int z = 7; z < 9; z++) {
                world.overrides.remove(World.key(x, y, z));
                m.onBlockChanged(x, y, z);
            }
            px = 108;
            pump(m, gpu, px, py, pz);
            h = gpu.trace(0, 0, 0, -1, 0, 0, 0.05f, 300, 0, 0);
            float expect = 108.5f - 50f;
            check(Math.abs(h.hitDist - expect) < 1.5f && h.transmittance < 0.1f,
                    String.format("pillar seen from the other side after recentering (expected %.1f, dist=%.2f, L=%d)", expect, h.hitDist, h.level));
            h = gpu.trace(0, 0, 0, 0, -1, 0, 0.05f, 300, 0, 0);
            check(Math.abs(h.hitDist - 5.5f) < 0.3f && h.level == 0, "ground under the player after recentering (L0 pages recycled)");
            checkStats(m, gpu, cfg, "after recentering (slots invalidated then rewritten)");
        }

        System.out.println("== Unloaded sections (L3 coverage > render distance)");
        {
            // Bug seen in game: every unloaded section consumed a capture from the budget; the queue
            // (~100,000 entries) never drained. Here only 5x5 columns are "loaded".
            GsrConfig c2 = new GsrConfig();
            c2.capturesPerTick = 8; c2.coarseDebounceMs = 0;
            World w2 = new World();
            SectionSnapshotSource src = (sx, sy, sz) -> Math.abs(sx) <= 2 && Math.abs(sz) <= 2 ? w2.capture(sx, sy, sz) : null;
            try (GaussianChunkManager m2 = new GaussianChunkManager(c2, src)) {
                long now = 0;
                m2.tick(8, 70, 8, now);                  // enqueues all sections of the coarse pages
                int initial = m2.stats().captureQueue();
                for (int i = 0; i < 400; i++) m2.tick(8, 70, 8, now += 50);
                int left = m2.stats().captureQueue();
                check(initial > 50_000 && left == 0,
                        "capture queue drained in 400 ticks despite unloaded sections (" + initial + " -> " + left + ")");
            }
        }

        System.out.println(failures == 0 ? "\nALL TESTS PASSED" : "\n" + failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
