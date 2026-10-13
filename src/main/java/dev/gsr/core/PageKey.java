package dev.gsr.core;

/** Identifiant d'une page de clipmap : niveau + coordonnées de page (en pages du niveau). */
public record PageKey(int level, int x, int y, int z) {
    /** Taille de la page en blocs. */
    public int sizeBlocks() { return GsrLayout.PAGE_VOXELS << level; }
    public int originBlockX() { return x * sizeBlocks(); }
    public int originBlockY() { return y * sizeBlocks(); }
    public int originBlockZ() { return z * sizeBlocks(); }
    /** Taille d'un voxel du niveau, en blocs. */
    public int voxelBlocks() { return 1 << level; }
}
