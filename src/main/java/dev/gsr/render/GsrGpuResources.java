package dev.gsr.render;

import dev.gsr.core.GpuSink;
import dev.gsr.core.GsrConfig;
import dev.gsr.core.GsrFrameData;
import dev.gsr.core.GsrLayout;
import dev.gsr.core.PageData;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL43C.*;

/**
 * The 4 GSR SSBOs. Fixed allocation at startup (no fragmentation, no reallocation):
 *
 *  binding base+0  frame   : 208 B
 *  binding base+1  meta    : slots × 32 B              (ivec4 pageXYZ + count, uvec4 brick mask)
 *  binding base+2  cells   : slots × 512 × 4 B         (offset | count << 16)
 *  binding base+3  splats  : slots × maxSplats × 32 B  (fixed slab per slot)
 *
 * Default config: 4 levels × 256 slots = 1024 slots -> ~64 MiB of splats + 2 MiB of cells.
 * The slot of a page is determined by the clipmap (toroidal): no allocator needed.
 */
public final class GsrGpuResources implements GpuSink {
    private final GsrConfig cfg;
    private final int frameBuf, metaBuf, cellBuf, splatBuf;
    private final long splatSlotBytes;
    private final ByteBuffer metaScratch = MemoryUtil.memAlloc(GsrLayout.PAGE_META_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final IntBuffer cellScratch = MemoryUtil.memAllocInt(GsrLayout.CELLS_PER_PAGE);

    public GsrGpuResources(GsrConfig cfg) {
        this.cfg = cfg;
        int slots = cfg.totalSlots();
        splatSlotBytes = (long) cfg.maxSplatsPerPage * GsrLayout.SPLAT_BYTES;

        frameBuf = createBuffer(GsrFrameData.BYTES);
        cellBuf = createBuffer((long) slots * GsrLayout.CELLS_PER_PAGE * GsrLayout.CELL_BYTES);
        splatBuf = createBuffer(slots * splatSlotBytes);

        ByteBuffer meta = MemoryUtil.memAlloc(slots * GsrLayout.PAGE_META_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < slots; i++) meta.putInt(GsrLayout.INVALID_PAGE_COORD).putInt(0).putInt(0).putInt(0).putLong(0L).putLong(0L);
        meta.flip();
        metaBuf = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, metaBuf);
        glBufferData(GL_SHADER_STORAGE_BUFFER, meta, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        MemoryUtil.memFree(meta);
    }

    private static int createBuffer(long bytes) {
        int b = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        return b;
    }

    @Override
    public void upload(int slot, PageData page) {
        // Order: data first, meta last (the shader validates the page via the meta).
        if (page.splatCount() > 0) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, splatBuf);
            ByteBuffer src = page.splats().duplicate().position(0).limit(page.splatCount() * GsrLayout.SPLAT_BYTES);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, slot * splatSlotBytes, src);
        }
        cellScratch.clear(); cellScratch.put(page.cells()).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, cellBuf);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) slot * GsrLayout.CELLS_PER_PAGE * GsrLayout.CELL_BYTES, cellScratch);
        writeMeta(slot, page.key().x(), page.key().y(), page.key().z(), page.splatCount(), page.brickMask());
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    @Override
    public void invalidate(int slot) {
        writeMeta(slot, GsrLayout.INVALID_PAGE_COORD, 0, 0, 0, 0L);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    private void writeMeta(int slot, int x, int y, int z, int count, long brickMask) {
        metaScratch.clear();
        // Little-endian: putLong writes mask.x = bits 0..31 then mask.y = bits 32..63.
        metaScratch.putInt(x).putInt(y).putInt(z).putInt(count).putLong(brickMask).putLong(0L).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, metaBuf);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) slot * GsrLayout.PAGE_META_BYTES, metaScratch);
    }

    public void writeFrame(ByteBuffer frame) {
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, frameBuf);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, frame);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Global indexed bindings: they stay active for ALL programs of the frame,
     * including those of an Iris shaderpack that declares the same binding points.
     */
    public void bindAll() {
        int base = cfg.ssboBindingBase;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, base, frameBuf);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, base + 1, metaBuf);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, base + 2, cellBuf);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, base + 3, splatBuf);
    }

    public void close() {
        glDeleteBuffers(new int[]{frameBuf, metaBuf, cellBuf, splatBuf});
        MemoryUtil.memFree(metaScratch);
        MemoryUtil.memFree(cellScratch);
    }
}
