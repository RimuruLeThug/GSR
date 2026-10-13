package dev.gsr.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Tests du cœur GSR sans Minecraft ni GPU : monde synthétique -> gestionnaire -> "GPU" en mémoire
 * (même layout d'octets) -> traceur de référence identique au shader.
 * Exécution : javac + java (voir README), sortie non nulle en cas d'échec.
 */
public final class CoreSelfTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  OK   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------------ monde synthétique
    static final class World {
        final Map<Long, Integer> overrides = new HashMap<>();
        static final int STONE = 0xFF7F7F7F, GRASS = 0xFF7FB238, SAND = 0xFFF7E9A3, WATER = 0x993F76E4, PILLAR = 0xFFFFFFFF;

        int height(int x, int z) { return 64; }
        boolean inPond(int x, int z) { return x >= -20 && x < -10 && z >= -5 && z < 5; }
        boolean inPillar(int x, int z) { return x >= 48 && x < 50 && z >= 7 && z < 9; }

        /** 0 = air sinon ARGB, avec le genre encodé à part. */
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

    /** /gsr stats doit refléter exactement la méta reçue par le « GPU », niveau par niveau. */
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
        check(same && allLevels && idle, "stats " + when + " = méta GPU (L0 : " + st.pages()[0] + " pages, "
                + st.splats()[0] + " splats ; files vides : " + idle + ")");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== Layout");
        for (int[] d : GsrLayout.DIRS) {
            float[] n = GsrLayout.octDecode(GsrLayout.octEncode(d[0], d[1], d[2]));
            float dot = n[0] * d[0] + n[1] * d[1] + n[2] * d[2];
            check(dot > 0.999f, "octaèdre aller-retour " + java.util.Arrays.toString(d) + " dot=" + dot);
        }
        long k = GsrLayout.sectionKey(-1234, -4, 98765);
        check(GsrLayout.sectionKeyX(k) == -1234 && GsrLayout.sectionKeyY(k) == -4 && GsrLayout.sectionKeyZ(k) == 98765, "clé de section signée");
        check(GsrFrameData.BYTES == 208, "taille GsrFrame std430 = 208");

        System.out.println("== Budget / tri par cellule (damier 3D, pire cas)");
        {
            GsrConfig cfg = new GsrConfig();
            VoxelSource checker = new VoxelSource() {
                public int kind(int x, int y, int z) { return ((x + y + z) & 1) == 0 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return 0xFF808080; }
                public int light(int x, int y, int z) { return (y & 15) << 4; }
                public int emission(int x, int y, int z) { return 0; }
            };
            PageData pd = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(checker), cfg);
            check(pd.splatCount() == cfg.maxSplatsPerPage, "plafonné à " + cfg.maxSplatsPerPage + " (obtenu " + pd.splatCount() + ")");
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
            check(sum == pd.splatCount() && contiguous, "table de cellules contiguë et complète");
            check(inCell, "chaque splat est sur la frontière de sa cellule");
        }

        System.out.println("== Masque de briques");
        {
            // Un seul bloc en (5, 9, 13) -> cellule (2, 4, 6) -> brique (1, 2, 3) ; ses faces débordent
            // sur les cellules voisines, mais toutes restent dans des briques dont le bit doit être mis.
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
                    "bloc isolé -> un seul bit, celui de sa brique (masque=" + Long.toHexString(pd.brickMask()) + ")");
            long high = SplatGenerator.generate(new PageKey(0, 0, 0, 0), 1, VoxelGrid.fromSource(new VoxelSource() {
                public int kind(int x, int y, int z) { return x == 15 && y == 15 && z == 15 ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; }
                public int albedo(int x, int y, int z) { return 0xFF808080; }
                public int light(int x, int y, int z) { return 0xF0; }
                public int emission(int x, int y, int z) { return 0; }
            }), new GsrConfig()).brickMask();
            check(high == 1L << 63, "brique 63 -> bit 63 (moitié haute, mask.y)");
        }

        System.out.println("== Résumés de section");
        {
            World w = new World();
            SectionSummary s = SectionSummary.build(w.capture(0, 4, 0)); // y 64..79 : herbe à y=64
            check(s.kind(1, 0, 0, 0) == GsrLayout.KIND_OPAQUE, "mip1 : voxel (y 64-65) plein à 50% -> opaque (seuil >= 50%)");
            check(s.kind(1, 0, 1, 0) == GsrLayout.KIND_AIR, "mip1 : voxel (y 66-67) vide -> air");
            SectionSummary below = SectionSummary.build(w.capture(0, 3, 0)); // y 48..63 : pierre
            check(below.kind(3, 0, 1, 0) == GsrLayout.KIND_OPAQUE, "mip3 : pierre pleine -> opaque");
            check(SectionSummary.build(w.capture(0, 6, 0)).isEmpty(), "section d'air -> singleton EMPTY");
        }

        System.out.println("== Pipeline complet + traceur de référence");
        GsrConfig cfg = new GsrConfig();
        cfg.levels = 3; cfg.pagesX = 4; cfg.pagesY = 2; cfg.pagesZ = 4;
        cfg.capturesPerTick = 100_000; cfg.coarseJobsPerTick = 10_000; cfg.coarseDebounceMs = 0;
        World world = new World();
        CpuReferenceTracer gpu = new CpuReferenceTracer(cfg);
        try (GaussianChunkManager m = new GaussianChunkManager(cfg, world::capture)) {
            int px = 8, py = 70, pz = 8;
            m.tick(px, py, pz, 0); // initialise les fenêtres
            for (int cx = -20; cx <= 20; cx++) for (int cz = -20; cz <= 20; cz++) m.onChunkLoaded(cx, cz, 0, 7);
            long t0 = System.nanoTime();
            pump(m, gpu, px, py, pz);
            System.out.printf("  (génération initiale : %.0f ms)%n", (System.nanoTime() - t0) / 1e6);
            checkStats(m, gpu, cfg, "après génération initiale");

            CpuReferenceTracer.Hit h = gpu.trace(0, 0, 0, 0, -1, 0, 0.05f, 300, 0, 0);
            check(Math.abs(h.hitDist - 5.5f) < 0.3f && h.transmittance < 0.05f && h.level == 0,
                    String.format("rayon vertical -> sol à 5.5 (dist=%.2f, T=%.3f, L=%d)", h.hitDist, h.transmittance, h.level));
            check(h.g > h.r && h.g > h.b, "couleur de l'impact = herbe (verte)");

            h = gpu.trace(0, 0, 0, 1, 0, 0, 0.05f, 300, 0, 0);
            check(Math.abs(h.hitDist - 39.5f) < 1.2f && h.transmittance < 0.1f && h.level == 1,
                    String.format("rayon horizontal -> pilier hors L0, trouvé en L1 (dist=%.2f, T=%.3f, L=%d)", h.hitDist, h.transmittance, h.level));

            h = gpu.trace(0, 0, 0, 0, 1, 0, 0.05f, 300, 0, 0);
            check(h.transmittance > 0.99f, "rayon vers le ciel : rien touché (T=" + h.transmittance + ")");

            float dx = -15f - 8.5f, dy = 65f - 70.5f, dz = 0.5f - 8.5f;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            h = gpu.trace(0, 0, 0, dx / len, dy / len, dz / len, 0.05f, 300, 0, 0);
            check(h.transmittance < 0.05f && h.b > h.r, String.format("rayon vers l'étang : eau translucide puis fond (T=%.3f, b>r)", h.transmittance));

            // Rayon horizontal à y = 69 sur 20 blocs : pages valides (le sol y est), mais briques y 68-71
            // vides. Cellule par cellule : ≥ 10 pas ; avec le masque de briques : ~5.
            h = gpu.trace(0, -1.5f, 0, 0, 0, 1, 0.05f, 20, 0, 0);
            check(h.transmittance > 0.99f && h.steps <= 6,
                    "briques vides franchies sans lire les cellules (" + h.steps + " pas pour 20 blocs, T=" + h.transmittance + ")");

            {   // Le masque ne doit changer que le nombre de pas, jamais ce qui est touché.
                java.util.Random rnd = new java.util.Random(42);
                int diff = 0, capped = 0, total = 4000; String first = null;
                for (int i = 0; i < total; i++) {
                    float rx = (float) rnd.nextGaussian(), ry = (float) rnd.nextGaussian(), rz = (float) rnd.nextGaussian();
                    float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
                    float ox = rnd.nextFloat() * 40 - 20, oy = rnd.nextFloat() * 10 - 5.4f, oz = rnd.nextFloat() * 40 - 20; // oy : dans l'air (sol à -5.5)
                    float cone = rnd.nextBoolean() ? 0 : rnd.nextFloat() * 0.35f;
                    gpu.ignoreBrickMask = true;
                    CpuReferenceTracer.Hit a = gpu.trace(ox, oy, oz, rx / rl, ry / rl, rz / rl, 0.02f, 300, cone, 0);
                    gpu.ignoreBrickMask = false;
                    CpuReferenceTracer.Hit b = gpu.trace(ox, oy, oz, rx / rl, ry / rl, rz / rl, 0.02f, 300, cone, 0);
                    // Même impact visible : tous deux touchés ou non (T 0,5), même distance, même couleur.
                    boolean same = (a.transmittance < 0.5f) == (b.transmittance < 0.5f)
                            && Math.abs(a.transmittance - b.transmittance) < 0.1f && Math.abs(a.g - b.g) < 0.05f
                            && (a.transmittance > 0.9f || Math.abs(a.hitDist - b.hitDist) < 0.25f);
                    if (a.steps >= 160 && b.transmittance < 0.5f && a.transmittance > 0.5f) capped++;
                    if (!same && a.steps < 160) {
                        diff++;
                        if (first == null) first = String.format("o=(%.2f,%.2f,%.2f) d=(%.3f,%.3f,%.3f) cone=%.2f : sans masque T=%.3f dist=%.2f L=%d, avec T=%.3f dist=%.2f L=%d",
                                ox, oy, oz, rx / rl, ry / rl, rz / rl, cone, a.transmittance, a.hitDist, a.level, b.transmittance, b.hitDist, b.level);
                    }
                }
                check(diff == 0, "masque de briques : mêmes impacts qu'en parcours cellule par cellule (" + diff + "/" + total + " écarts)"
                        + (first == null ? "" : "\n         ex. " + first));
                System.out.println("  (rayons à 160 pas sans masque, qui touchent désormais : " + capped + ")");
            }

            h = gpu.trace(0, 0, 0, 0, -1, 0, 0.05f, 300, 0.5f, 0);
            check(Math.abs(h.hitDist - 5.5f) < 1.5f && h.transmittance < 0.1f,
                    String.format("cône rugueux (tan=0.5) : sol trouvé à un niveau plus grossier (dist=%.2f, L=%d)", h.hitDist, h.level));

            System.out.println("== Modification de blocs (suppression du pilier)");
            for (int y = 65; y <= 90; y++) for (int x = 48; x < 50; x++) for (int z = 7; z < 9; z++) {
                world.overrides.put(World.key(x, y, z), 0);
                m.onBlockChanged(x, y, z);
            }
            pump(m, gpu, px, py, pz);
            h = gpu.trace(0, 0, 0, 1, 0, 0, 0.05f, 300, 0, 0);
            check(h.transmittance > 0.99f, "pilier supprimé -> le rayon passe (T=" + h.transmittance + ")");

            System.out.println("== Recentrage toroïdal (joueur déplacé de +100 en X)");
            for (int y = 65; y <= 90; y++) for (int x = 48; x < 50; x++) for (int z = 7; z < 9; z++) {
                world.overrides.remove(World.key(x, y, z));
                m.onBlockChanged(x, y, z);
            }
            px = 108;
            pump(m, gpu, px, py, pz);
            h = gpu.trace(0, 0, 0, -1, 0, 0, 0.05f, 300, 0, 0);
            float expect = 108.5f - 50f;
            check(Math.abs(h.hitDist - expect) < 1.5f && h.transmittance < 0.1f,
                    String.format("pilier vu de l'autre côté après recentrage (attendu %.1f, dist=%.2f, L=%d)", expect, h.hitDist, h.level));
            h = gpu.trace(0, 0, 0, 0, -1, 0, 0.05f, 300, 0, 0);
            check(Math.abs(h.hitDist - 5.5f) < 0.3f && h.level == 0, "sol sous le joueur après recentrage (pages L0 recyclées)");
            checkStats(m, gpu, cfg, "après recentrage (slots invalidés puis réécrits)");
        }

        System.out.println("== Sections non chargées (couverture L3 > distance de rendu)");
        {
            // Bug vu en jeu : chaque section non chargée consommait une capture du budget ; la file
            // (~100 000 entrées) ne se vidait jamais. Ici seules 5x5 colonnes sont « chargées ».
            GsrConfig c2 = new GsrConfig();
            c2.capturesPerTick = 8; c2.coarseDebounceMs = 0;
            World w2 = new World();
            SectionSnapshotSource src = (sx, sy, sz) -> Math.abs(sx) <= 2 && Math.abs(sz) <= 2 ? w2.capture(sx, sy, sz) : null;
            try (GaussianChunkManager m2 = new GaussianChunkManager(c2, src)) {
                long now = 0;
                m2.tick(8, 70, 8, now);                  // enfile toutes les sections des pages grossières
                int initial = m2.stats().captureQueue();
                for (int i = 0; i < 400; i++) m2.tick(8, 70, 8, now += 50);
                int left = m2.stats().captureQueue();
                check(initial > 50_000 && left == 0,
                        "file de captures vidée en 400 ticks malgré les sections non chargées (" + initial + " -> " + left + ")");
            }
        }

        System.out.println(failures == 0 ? "\nTOUS LES TESTS PASSENT" : "\n" + failures + " ÉCHEC(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
