package dev.gsr.core;

/** Destination of the pages (implemented with OpenGL by GsrGpuResources; in memory for tests). */
public interface GpuSink {
    /** Writes the page into its slot: splats, cell table, then meta (pageXYZ, count). */
    void upload(int slotIndex, PageData page);
    /** Marks the slot invalid (meta = INVALID): the shader will treat it as "not built". */
    void invalidate(int slotIndex);
}
