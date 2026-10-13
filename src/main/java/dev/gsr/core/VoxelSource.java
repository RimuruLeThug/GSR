package dev.gsr.core;

/**
 * Immutable view (snapshot) of a 16³ section plus its one-block border on the 6 faces.
 * Local coordinates: -1..16. Only "face" neighbours (a single axis outside 0..15) are guaranteed.
 *
 * Implemented on the Minecraft side by {@code McSectionSnapshot} (copy of PalettedContainer + nibble arrays
 * taken on the main thread), which allows the data to be read on worker threads
 * without touching the live world.
 */
public interface VoxelSource {
    /** {@link GsrLayout#KIND_AIR}, {@link GsrLayout#KIND_OPAQUE} or {@link GsrLayout#KIND_TRANSLUCENT}. */
    int kind(int x, int y, int z);
    /** Approximate albedo 0xAARRGGBB (A = opacity). */
    int albedo(int x, int y, int z);
    /** Albedo of a face group ({@link GsrLayout#FACE_SIDE}, UP, DOWN). Default: the same everywhere. */
    default int albedo(int x, int y, int z, int faceGroup) { return albedo(x, y, z); }
    /** Light (sky << 4 | block), 0..255. */
    int light(int x, int y, int z);
    /** Emission (block luminance), 0..15. */
    int emission(int x, int y, int z);
    /** Model drawn without per-face shading (cross plants, `"shade": false`): splats with shading 1.0. */
    default boolean unshaded(int x, int y, int z) { return false; }

    /** Sentinel: entirely empty section (air). */
    VoxelSource EMPTY = new VoxelSource() {
        public int kind(int x, int y, int z) { return GsrLayout.KIND_AIR; }
        public int albedo(int x, int y, int z) { return 0; }
        public int light(int x, int y, int z) { return 0xF0; }
        public int emission(int x, int y, int z) { return 0; }
    };
}
