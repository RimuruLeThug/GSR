package dev.gsr.core;

/**
 * Prend un snapshot immuable d'une section (appelé sur le thread principal uniquement).
 * Retourne null si la section n'est pas chargée, {@link VoxelSource#EMPTY} si elle est vide.
 */
@FunctionalInterface
public interface SectionSnapshotSource {
    VoxelSource capture(int sx, int sy, int sz);
}
