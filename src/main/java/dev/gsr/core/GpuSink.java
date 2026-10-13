package dev.gsr.core;

/** Destination des pages (implémentée en OpenGL par GsrGpuResources ; en mémoire pour les tests). */
public interface GpuSink {
    /** Écrit la page dans son slot : splats, table de cellules, puis méta (pageXYZ, count). */
    void upload(int slotIndex, PageData page);
    /** Marque le slot invalide (méta = INVALID) : le shader le traitera comme "non construit". */
    void invalidate(int slotIndex);
}
