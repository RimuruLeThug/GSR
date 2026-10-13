package dev.gsr.core;

import java.util.Arrays;

/**
 * Multi-resolution summary of a section (mips 1, 2, 3 -> voxels of 2, 4, 8 blocks).
 *
 * This is the key to the cost of the coarse levels: an L3 page covers 8x8x8 = 512 sections, and rebuilding it
 * by re-reading 2 million blocks would be too expensive. So this summary is computed ONCE per section
 * rebuild (4096 reads), and the L1..L3 pages are then assembled solely from summaries.
 *
 * Memory: (512 + 64 + 8) voxels x 7 bytes ≈ 4 KiB per non-empty section; air sections share
 * the {@link #EMPTY} singleton.
 */
public final class SectionSummary {
    public static final int MIPS = 3; // mip m = 1..3
    public static final SectionSummary EMPTY = new SectionSummary(true);

    private final boolean empty;
    private final byte[][] kind = new byte[MIPS][];
    private final int[][] albedo = new int[MIPS][];   // [mip][voxel * FACE_GROUPS + group]
    private final byte[][] light = new byte[MIPS][];
    private final byte[][] emission = new byte[MIPS][];
    private int hash;

    private SectionSummary(boolean empty) { this.empty = empty; }

    public boolean isEmpty() { return empty; }

    public static int mipSize(int mip) { return 16 >> mip; }

    private static int idx(int n, int x, int y, int z) { return x + n * (y + n * z); }

    public int kind(int mip, int x, int y, int z) {
        if (empty) return GsrLayout.KIND_AIR;
        return kind[mip - 1][idx(mipSize(mip), x, y, z)];
    }
    public int albedo(int mip, int x, int y, int z, int faceGroup) {
        return empty ? 0 : albedo[mip - 1][idx(mipSize(mip), x, y, z) * GsrLayout.FACE_GROUPS + faceGroup];
    }
    public int light(int mip, int x, int y, int z) {
        return empty ? 0xF0 : light[mip - 1][idx(mipSize(mip), x, y, z)] & 0xFF;
    }
    public int emission(int mip, int x, int y, int z) {
        return empty ? 0 : emission[mip - 1][idx(mipSize(mip), x, y, z)];
    }

    /** Content fingerprint: avoids rebuilding coarse pages if nothing changed at their scale. */
    public int contentHash() { return hash; }

    private static boolean exposed(int k, int nk) {
        return nk == GsrLayout.KIND_AIR || (k == GsrLayout.KIND_OPAQUE && nk == GsrLayout.KIND_TRANSLUCENT);
    }

    public static SectionSummary build(VoxelSource src) {
        if (src == VoxelSource.EMPTY) return EMPTY;
        final int G = GsrLayout.FACE_GROUPS;
        int[] a = new int[G];
        // Per-mip accumulators
        int[][] opaque = new int[MIPS][], transl = new int[MIPS][];
        long[][] surfRGBA = new long[MIPS][], allRGBA = new long[MIPS][];
        int[][] surfN = new int[MIPS][], allN = new int[MIPS][];
        int[][] airSky = new int[MIPS][], airBlk = new int[MIPS][], airN = new int[MIPS][];
        int[][] sSky = new int[MIPS][], sBlk = new int[MIPS][], sN = new int[MIPS][];
        int[][] emis = new int[MIPS][];
        for (int m = 0; m < MIPS; m++) {
            int n = mipSize(m + 1), c = n * n * n;
            opaque[m] = new int[c]; transl[m] = new int[c];
            surfRGBA[m] = new long[c * 4 * G]; allRGBA[m] = new long[c * 4 * G];
            surfN[m] = new int[c]; allN[m] = new int[c];
            airSky[m] = new int[c]; airBlk[m] = new int[c]; airN[m] = new int[c];
            sSky[m] = new int[c]; sBlk[m] = new int[c]; sN[m] = new int[c];
            emis[m] = new int[c];
        }

        boolean anySolid = false;
        for (int z = 0; z < 16; z++)
            for (int y = 0; y < 16; y++)
                for (int x = 0; x < 16; x++) {
                    int k = src.kind(x, y, z);
                    boolean surface = false;
                    int nbSky = 0, nbBlk = 0, nbN = 0;
                    if (k != GsrLayout.KIND_AIR) {
                        anySolid = true;
                        for (int[] d : GsrLayout.DIRS) {
                            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                            int nk = src.kind(nx, ny, nz);
                            if (exposed(k, nk)) {
                                surface = true;
                                int l = src.light(nx, ny, nz);
                                nbSky += l >> 4; nbBlk += l & 15; nbN++;
                            }
                        }
                    }
                    int l = 0, e = 0;
                    if (k != GsrLayout.KIND_AIR) {
                        for (int f = 0; f < G; f++) a[f] = src.albedo(x, y, z, f);
                        e = src.emission(x, y, z);
                    }
                    if (k != GsrLayout.KIND_OPAQUE) l = src.light(x, y, z);

                    for (int m = 0; m < MIPS; m++) {
                        int n = mipSize(m + 1), sh = m + 1;
                        int i = idx(n, x >> sh, y >> sh, z >> sh);
                        if (k == GsrLayout.KIND_OPAQUE) opaque[m][i]++;
                        else if (k == GsrLayout.KIND_TRANSLUCENT) transl[m][i]++;
                        if (k != GsrLayout.KIND_AIR) {
                            long[] acc = surface ? surfRGBA[m] : allRGBA[m];
                            for (int f = 0; f < G; f++) {
                                int o = (i * G + f) * 4, af = a[f];
                                acc[o] += (af >> 16) & 0xFF; acc[o + 1] += (af >> 8) & 0xFF;
                                acc[o + 2] += af & 0xFF; acc[o + 3] += (af >>> 24);
                            }
                            if (surface) surfN[m][i]++; else allN[m][i]++;
                            if (e > emis[m][i]) emis[m][i] = e;
                            if (nbN > 0) { sSky[m][i] += nbSky; sBlk[m][i] += nbBlk; sN[m][i] += nbN; }
                        }
                        if (k != GsrLayout.KIND_OPAQUE) { airSky[m][i] += l >> 4; airBlk[m][i] += l & 15; airN[m][i]++; }
                    }
                }
        if (!anySolid) return EMPTY;

        SectionSummary s = new SectionSummary(false);
        int h = 17;
        for (int m = 0; m < MIPS; m++) {
            int n = mipSize(m + 1), c = n * n * n, total = 1 << (3 * (m + 1));
            s.kind[m] = new byte[c]; s.albedo[m] = new int[c * G]; s.light[m] = new byte[c]; s.emission[m] = new byte[c];
            for (int i = 0; i < c; i++) {
                int op = opaque[m][i], tr = transl[m][i];
                int kk = (op + tr) * 2 >= total ? (op >= tr ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_TRANSLUCENT) : GsrLayout.KIND_AIR;
                long[] acc = surfN[m][i] > 0 ? surfRGBA[m] : allRGBA[m];
                int cnt = Math.max(1, surfN[m][i] > 0 ? surfN[m][i] : allN[m][i]);
                for (int f = 0; f < G; f++) {
                    int o = (i * G + f) * 4;
                    int r = (int) (acc[o] / cnt), g = (int) (acc[o + 1] / cnt), b = (int) (acc[o + 2] / cnt);
                    int al = kk == GsrLayout.KIND_TRANSLUCENT ? (int) (acc[o + 3] / cnt) : 255;
                    int alb = (al << 24) | (r << 16) | (g << 8) | b;
                    s.albedo[m][i * G + f] = alb;
                    h = 31 * h + alb;
                }
                int lt;
                if (airN[m][i] > 0) lt = ((airSky[m][i] / airN[m][i]) << 4) | (airBlk[m][i] / airN[m][i]);
                else if (sN[m][i] > 0) lt = ((sSky[m][i] / sN[m][i]) << 4) | (sBlk[m][i] / sN[m][i]);
                else lt = 0;
                s.kind[m][i] = (byte) kk; s.light[m][i] = (byte) lt; s.emission[m][i] = (byte) emis[m][i];
                h = 31 * h + kk; h = 31 * h + lt;
            }
        }
        s.hash = h;
        return s;
    }

    @Override public String toString() {
        return empty ? "SectionSummary[EMPTY]" : "SectionSummary[hash=" + hash + ", mip1Kinds=" + Arrays.hashCode(kind[0]) + "]";
    }
}
