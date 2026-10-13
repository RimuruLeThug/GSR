package dev.gsr.core;

/**
 * Format constants shared between Java and GLSL.
 * Any change here MUST be mirrored in assets/gsr/shaders/gsr_common.glsl.
 *
 * Units:
 *  - "block"  : 1 Minecraft block.
 *  - "voxel"  : voxel of clipmap level L = (1 << L) blocks per side.
 *  - "page"   : 16 x 16 x 16 voxels of level L  (at level 0: exactly one Minecraft section).
 *  - "cell"   : 2 x 2 x 2 voxels -> 8 x 8 x 8 = 512 cells per page (ray tracing acceleration grid).
 */
public final class GsrLayout {
    private GsrLayout() {}

    public static final int MAX_LEVELS = 4;
    public static final int PAGE_VOXELS = 16;
    public static final int CELL_VOXELS = 2;
    public static final int CELLS_PER_AXIS = PAGE_VOXELS / CELL_VOXELS; // 8
    public static final int CELLS_PER_PAGE = CELLS_PER_AXIS * CELLS_PER_AXIS * CELLS_PER_AXIS; // 512

    /** std430 : struct GsrSplat { vec4 posSigmaT; uvec4 data; } */
    public static final int SPLAT_BYTES = 32;
    /**
     * std430 : struct GsrPageMeta { ivec4 page; uvec4 mask; }
     *   page = (pageX, pageY, pageZ, splatCount), mask.xy = 64-bit brick mask, mask.zw reserved.
     */
    public static final int PAGE_META_BYTES = 32;
    /** Brick = 2 x 2 x 2 cells = 4³ voxels -> 4 x 4 x 4 = 64 bricks per page (1 bit each). */
    public static final int BRICKS_PER_AXIS = CELLS_PER_AXIS / 2; // 4
    /** std430 : uint (offset | count << 16) */
    public static final int CELL_BYTES = 4;

    public static final int KIND_AIR = 0;
    public static final int KIND_OPAQUE = 1;
    public static final int KIND_TRANSLUCENT = 2;

    /** Meta value meaning "empty / invalid slot". */
    public static final int INVALID_PAGE_COORD = Integer.MIN_VALUE;

    // Directions of the 6 faces: -X +X -Y +Y -Z +Z
    public static final int[][] DIRS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    /** Face groups for albedo: a grass block is green on top, dirt on the sides and bottom. */
    public static final int FACE_SIDE = 0, FACE_UP = 1, FACE_DOWN = 2, FACE_GROUPS = 3;

    /** Direction index (order of {@link #DIRS}) -> face group. */
    public static int faceGroup(int dir) { return dir == 3 ? FACE_UP : dir == 2 ? FACE_DOWN : FACE_SIDE; }

    public static int cellIndex(int cx, int cy, int cz) {
        return cx + CELLS_PER_AXIS * (cy + CELLS_PER_AXIS * cz);
    }

    public static int brickIndex(int bx, int by, int bz) {
        return bx + BRICKS_PER_AXIS * (by + BRICKS_PER_AXIS * bz);
    }

    /** Mask of the bricks containing at least one splat, from the cell table (offset | count << 16). */
    public static long brickMask(int[] cells) {
        long mask = 0;
        for (int c = 0; c < CELLS_PER_PAGE; c++) {
            if ((cells[c] >>> 16) == 0) continue;
            int cx = c % CELLS_PER_AXIS, cy = (c / CELLS_PER_AXIS) % CELLS_PER_AXIS, cz = c / (CELLS_PER_AXIS * CELLS_PER_AXIS);
            mask |= 1L << brickIndex(cx >> 1, cy >> 1, cz >> 1);
        }
        return mask;
    }

    // ---- Packing of data.y (must match gsr_common.glsl) ----
    //  bits  0..15 : octahedral normal 8+8
    //  bits 16..23 : light (sky << 4 | block)
    //  bits 24..27 : emission 0..15
    //  bits 28..29 : clipmap level
    //  bit  30     : no per-face shading (cross plants)
    public static int packSplatData(int oct16, int light8, int emission4, int level) {
        return packSplatData(oct16, light8, emission4, level, false);
    }

    /** Bit 30: per-face shading disabled (= GSR_DATA_UNSHADED). */
    public static int packSplatData(int oct16, int light8, int emission4, int level, boolean unshaded) {
        return (oct16 & 0xFFFF) | ((light8 & 0xFF) << 16) | ((emission4 & 0xF) << 24) | ((level & 0x3) << 28) | (unshaded ? 1 << 30 : 0);
    }

    private static float signNotZero(float v) { return v >= 0f ? 1f : -1f; }

    /** Octahedral encoding, 8+8 bits. */
    public static int octEncode(float x, float y, float z) {
        float inv = 1f / (Math.abs(x) + Math.abs(y) + Math.abs(z));
        float u = x * inv, v = y * inv;
        if (z < 0f) {
            float nu = (1f - Math.abs(v)) * signNotZero(u);
            float nv = (1f - Math.abs(u)) * signNotZero(v);
            u = nu; v = nv;
        }
        int iu = Math.round((u * 0.5f + 0.5f) * 255f);
        int iv = Math.round((v * 0.5f + 0.5f) * 255f);
        return (iu & 0xFF) | ((iv & 0xFF) << 8);
    }

    /** Decoding (exact mirror of gsrOctDecode in GLSL). Returns a normalized normal. */
    public static float[] octDecode(int p) {
        float ex = (p & 0xFF) / 255f * 2f - 1f;
        float ey = ((p >>> 8) & 0xFF) / 255f * 2f - 1f;
        float nz = 1f - Math.abs(ex) - Math.abs(ey);
        float nx = ex, ny = ey;
        if (nz < 0f) {
            nx = (1f - Math.abs(ey)) * signNotZero(ex);
            ny = (1f - Math.abs(ex)) * signNotZero(ey);
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return new float[]{nx / len, ny / len, nz / len};
    }

    // ---- Section key (same idea as ChunkSectionPos.asLong) ----
    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFFL);
    }
    public static int sectionKeyX(long k) { return (int) (k >> 42); }
    public static int sectionKeyZ(long k) { return (int) (k << 22 >> 42); }
    public static int sectionKeyY(long k) { return (int) (k << 44 >> 44); }
}
