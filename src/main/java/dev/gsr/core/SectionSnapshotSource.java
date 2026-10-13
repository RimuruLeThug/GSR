package dev.gsr.core;

/**
 * Takes an immutable snapshot of a section (called on the main thread only).
 * Returns null if the section is not loaded, {@link VoxelSource#EMPTY} if it is empty.
 */
@FunctionalInterface
public interface SectionSnapshotSource {
    VoxelSource capture(int sx, int sy, int sz);
}
