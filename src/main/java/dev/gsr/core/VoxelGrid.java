package dev.gsr.core;

/**
 * Grille 18³ (16³ + bordure) de voxels d'une page, à la résolution de son niveau.
 * Format unique pour tous les niveaux : le même extracteur de surfaces sert de L0 à L3.
 */
public final class VoxelGrid {
    public static final int N = GsrLayout.PAGE_VOXELS;
    private static final int S = N + 2;

    final byte[] kind = new byte[S * S * S];
    final int[][] albedo = new int[GsrLayout.FACE_GROUPS][S * S * S];   // [groupe de faces][voxel]
    final byte[] light = new byte[S * S * S];
    final byte[] emission = new byte[S * S * S];
    final boolean[] unshaded = new boolean[S * S * S];   // niveau 0 seulement

    private static int idx(int x, int y, int z) { return (x + 1) + S * ((y + 1) + S * (z + 1)); }

    public int kind(int x, int y, int z) { return kind[idx(x, y, z)]; }
    public int albedo(int x, int y, int z, int faceGroup) { return albedo[faceGroup][idx(x, y, z)]; }
    public int light(int x, int y, int z) { return light[idx(x, y, z)] & 0xFF; }
    public int emission(int x, int y, int z) { return emission[idx(x, y, z)]; }
    public boolean unshaded(int x, int y, int z) { return unshaded[idx(x, y, z)]; }

    private static boolean faceNeighbourOrInner(int x, int y, int z) {
        int out = 0;
        if (x < 0 || x >= N) out++;
        if (y < 0 || y >= N) out++;
        if (z < 0 || z >= N) out++;
        return out <= 1; // arêtes et coins de la bordure jamais lus
    }

    /** Niveau 0 : directement depuis le snapshot pleine résolution de la section. */
    public static VoxelGrid fromSource(VoxelSource src) {
        VoxelGrid g = new VoxelGrid();
        for (int z = -1; z <= N; z++)
            for (int y = -1; y <= N; y++)
                for (int x = -1; x <= N; x++) {
                    if (!faceNeighbourOrInner(x, y, z)) continue;
                    int i = idx(x, y, z);
                    int k = src.kind(x, y, z);
                    g.kind[i] = (byte) k;
                    g.light[i] = (byte) src.light(x, y, z);
                    if (k != GsrLayout.KIND_AIR) {
                        for (int f = 0; f < GsrLayout.FACE_GROUPS; f++) g.albedo[f][i] = src.albedo(x, y, z, f);
                        g.emission[i] = (byte) src.emission(x, y, z);
                        g.unshaded[i] = src.unshaded(x, y, z);
                    }
                }
        return g;
    }

    /** Fournit le résumé d'une section, ou null si inconnue (traitée comme de l'air). */
    @FunctionalInterface
    public interface SummaryLookup { SectionSummary get(int sx, int sy, int sz); }

    /** Niveaux 1..3 : assemblé à partir des résumés (mip = niveau) des (2^L)³ sections couvertes. */
    public static VoxelGrid fromSummaries(PageKey page, SummaryLookup lookup) {
        int L = page.level();
        if (L < 1 || L > SectionSummary.MIPS) throw new IllegalArgumentException("niveau " + L);
        VoxelGrid g = new VoxelGrid();
        int vb = 1 << L;
        int ox = page.originBlockX(), oy = page.originBlockY(), oz = page.originBlockZ();
        for (int z = -1; z <= N; z++)
            for (int y = -1; y <= N; y++)
                for (int x = -1; x <= N; x++) {
                    if (!faceNeighbourOrInner(x, y, z)) continue;
                    int bx = ox + x * vb, by = oy + y * vb, bz = oz + z * vb;
                    SectionSummary s = lookup.get(Math.floorDiv(bx, 16), Math.floorDiv(by, 16), Math.floorDiv(bz, 16));
                    int i = idx(x, y, z);
                    if (s == null) { g.kind[i] = GsrLayout.KIND_AIR; g.light[i] = 0; continue; }
                    int lx = Math.floorMod(bx, 16) >> L, ly = Math.floorMod(by, 16) >> L, lz = Math.floorMod(bz, 16) >> L;
                    g.kind[i] = (byte) s.kind(L, lx, ly, lz);
                    for (int f = 0; f < GsrLayout.FACE_GROUPS; f++) g.albedo[f][i] = s.albedo(L, lx, ly, lz, f);
                    g.light[i] = (byte) s.light(L, lx, ly, lz);
                    g.emission[i] = (byte) s.emission(L, lx, ly, lz);
                }
        return g;
    }
}
