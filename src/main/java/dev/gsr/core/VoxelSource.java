package dev.gsr.core;

/**
 * Vue immuable (snapshot) d'une section 16³ + sa bordure d'un bloc sur les 6 faces.
 * Coordonnées locales : -1..16. Seuls les voisins "de face" (un seul axe hors 0..15) sont garantis.
 *
 * Implémentée côté Minecraft par {@code McSectionSnapshot} (copie de PalettedContainer + nibble arrays
 * prise sur le thread principal), ce qui permet de lire les données sur des threads de travail
 * sans toucher au monde vivant.
 */
public interface VoxelSource {
    /** {@link GsrLayout#KIND_AIR}, {@link GsrLayout#KIND_OPAQUE} ou {@link GsrLayout#KIND_TRANSLUCENT}. */
    int kind(int x, int y, int z);
    /** Albédo approché 0xAARRGGBB (A = opacité). */
    int albedo(int x, int y, int z);
    /** Albédo d'un groupe de faces ({@link GsrLayout#FACE_SIDE}, UP, DOWN). Par défaut : le même partout. */
    default int albedo(int x, int y, int z, int faceGroup) { return albedo(x, y, z); }
    /** Lumière (sky << 4 | block), 0..255. */
    int light(int x, int y, int z);
    /** Émission (luminance du bloc), 0..15. */
    int emission(int x, int y, int z);

    /** Sentinelle : section entièrement vide (air). */
    VoxelSource EMPTY = new VoxelSource() {
        public int kind(int x, int y, int z) { return GsrLayout.KIND_AIR; }
        public int albedo(int x, int y, int z) { return 0; }
        public int light(int x, int y, int z) { return 0xF0; }
        public int emission(int x, int y, int z) { return 0; }
    };
}
