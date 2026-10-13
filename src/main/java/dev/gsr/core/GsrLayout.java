package dev.gsr.core;

/**
 * Constantes de format partagées entre Java et GLSL.
 * Toute modification ici DOIT être répercutée dans assets/gsr/shaders/gsr_common.glsl.
 *
 * Unités :
 *  - "bloc"   : 1 bloc Minecraft.
 *  - "voxel"  : voxel du niveau L de la clipmap = (1 << L) blocs de côté.
 *  - "page"   : 16 x 16 x 16 voxels du niveau L  (au niveau 0 : exactement une section Minecraft).
 *  - "cellule": 2 x 2 x 2 voxels -> 8 x 8 x 8 = 512 cellules par page (grille d'accélération du lancer de rayon).
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
     *   page = (pageX, pageY, pageZ, splatCount), mask.xy = masque de briques 64 bits, mask.zw réservés.
     */
    public static final int PAGE_META_BYTES = 32;
    /** Brique = 2 x 2 x 2 cellules = 4³ voxels -> 4 x 4 x 4 = 64 briques par page (1 bit chacune). */
    public static final int BRICKS_PER_AXIS = CELLS_PER_AXIS / 2; // 4
    /** std430 : uint (offset | count << 16) */
    public static final int CELL_BYTES = 4;

    public static final int KIND_AIR = 0;
    public static final int KIND_OPAQUE = 1;
    public static final int KIND_TRANSLUCENT = 2;

    /** Valeur de méta signifiant "slot vide / invalide". */
    public static final int INVALID_PAGE_COORD = Integer.MIN_VALUE;

    // Directions des 6 faces : -X +X -Y +Y -Z +Z
    public static final int[][] DIRS = {
            {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}
    };

    public static int cellIndex(int cx, int cy, int cz) {
        return cx + CELLS_PER_AXIS * (cy + CELLS_PER_AXIS * cz);
    }

    public static int brickIndex(int bx, int by, int bz) {
        return bx + BRICKS_PER_AXIS * (by + BRICKS_PER_AXIS * bz);
    }

    /** Masque des briques contenant au moins un splat, d'après la table de cellules (offset | count << 16). */
    public static long brickMask(int[] cells) {
        long mask = 0;
        for (int c = 0; c < CELLS_PER_PAGE; c++) {
            if ((cells[c] >>> 16) == 0) continue;
            int cx = c % CELLS_PER_AXIS, cy = (c / CELLS_PER_AXIS) % CELLS_PER_AXIS, cz = c / (CELLS_PER_AXIS * CELLS_PER_AXIS);
            mask |= 1L << brickIndex(cx >> 1, cy >> 1, cz >> 1);
        }
        return mask;
    }

    // ---- Packing de data.y (doit correspondre à gsr_common.glsl) ----
    //  bits  0..15 : normale octaédrique 8+8
    //  bits 16..23 : lumière (sky << 4 | block)
    //  bits 24..27 : émission 0..15
    //  bits 28..29 : niveau de clipmap
    public static int packSplatData(int oct16, int light8, int emission4, int level) {
        return (oct16 & 0xFFFF) | ((light8 & 0xFF) << 16) | ((emission4 & 0xF) << 24) | ((level & 0x3) << 28);
    }

    private static float signNotZero(float v) { return v >= 0f ? 1f : -1f; }

    /** Encodage octaédrique 8+8 bits. */
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

    /** Décodage (miroir exact de gsrOctDecode en GLSL). Retourne une normale normalisée. */
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

    // ---- Clé de section (même idée que ChunkSectionPos.asLong) ----
    public static long sectionKey(int sx, int sy, int sz) {
        return ((long) (sx & 0x3FFFFF) << 42) | ((long) (sz & 0x3FFFFF) << 20) | (sy & 0xFFFFFL);
    }
    public static int sectionKeyX(long k) { return (int) (k >> 42); }
    public static int sectionKeyZ(long k) { return (int) (k << 22 >> 42); }
    public static int sectionKeyY(long k) { return (int) (k << 44 >> 44); }
}
