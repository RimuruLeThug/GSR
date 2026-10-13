package dev.gsr.core;

/** Identifier of a clipmap page: level + page coordinates (in pages of that level). */
public record PageKey(int level, int x, int y, int z) {
    /** Page size in blocks. */
    public int sizeBlocks() { return GsrLayout.PAGE_VOXELS << level; }
    public int originBlockX() { return x * sizeBlocks(); }
    public int originBlockY() { return y * sizeBlocks(); }
    public int originBlockZ() { return z * sizeBlocks(); }
    /** Size of one voxel of the level, in blocks. */
    public int voxelBlocks() { return 1 << level; }
}
