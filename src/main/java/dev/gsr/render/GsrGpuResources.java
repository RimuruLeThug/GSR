package dev.gsr.render;

import dev.gsr.GsrClient;
import dev.gsr.core.GpuSink;
import dev.gsr.core.GsrConfig;
import dev.gsr.core.GsrFrameData;
import dev.gsr.core.GsrLayout;
import dev.gsr.core.PageData;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p>The constructor first checks the driver's SSBO limits and throws an {@link IllegalStateException}
 * with an actionable message when the configuration exceeds them, or when an allocation fails
 * ({@code GL_OUT_OF_MEMORY}). Buffers already created are deleted before throwing: no leak on the
 * failure path. The caller ({@code GsrRenderer.ensureInit}) turns the exception into a clean disable.
 */
public final class GsrGpuResources implements GpuSink {
    /** Number of SSBOs / binding points GSR uses (frame, meta, cells, splats). */
    private static final int BUFFER_COUNT = 4;

    /** Dev fault injection: overrides GL_MAX_SHADER_STORAGE_BLOCK_SIZE (bytes). Absent = real limit. */
    private static final String PROP_FAKE_MAX_BLOCK = "gsr.fakeMaxSsboBlock";
    /** Dev fault injection: simulates GL_OUT_OF_MEMORY on the splat buffer allocation. */
    private static final String PROP_FAKE_OOM = "gsr.fakeSsboOom";

    private final GsrConfig cfg;
    private final int frameBuf, metaBuf, cellBuf, splatBuf;
    private final long splatSlotBytes;
    private final ByteBuffer metaScratch;
    private final IntBuffer cellScratch;

    public GsrGpuResources(GsrConfig cfg) {
        this.cfg = cfg;
        int slots = cfg.totalSlots();
        splatSlotBytes = (long) cfg.maxSplatsPerPage * GsrLayout.SPLAT_BYTES;
        long frameBytes = GsrFrameData.BYTES;
        long metaBytes = (long) slots * GsrLayout.PAGE_META_BYTES;
        long cellBytes = (long) slots * GsrLayout.CELLS_PER_PAGE * GsrLayout.CELL_BYTES;
        long splatBytes = slots * splatSlotBytes;

        checkLimits(cfg, slots, frameBytes, metaBytes, cellBytes, splatBytes);

        // Minecraft never reads glGetError: drain stale errors so the checks below only see ours.
        drainGlErrors();

        List<Integer> created = new ArrayList<>(BUFFER_COUNT);
        ByteBuffer meta = null;
        try {
            int frame = createBuffer("frame", frameBytes, false, created);
            int cell = createBuffer("cells", cellBytes, false, created);
            int splat = createBuffer("splats", splatBytes, Boolean.getBoolean(PROP_FAKE_OOM), created);

            meta = MemoryUtil.memAlloc((int) metaBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < slots; i++) meta.putInt(GsrLayout.INVALID_PAGE_COORD).putInt(0).putInt(0).putInt(0).putLong(0L).putLong(0L);
            meta.flip();
            int metaB = glGenBuffers();
            created.add(metaB);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, metaB);
            glBufferData(GL_SHADER_STORAGE_BUFFER, meta, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            checkAllocation("meta", metaBytes, false);

            frameBuf = frame; cellBuf = cell; splatBuf = splat; metaBuf = metaB;
        } catch (RuntimeException e) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            for (int b : created) glDeleteBuffers(b);
            GsrClient.LOG.info("GSR: deleted the {} SSBO(s) created before the failure", created.size());
            throw e;
        } finally {
            if (meta != null) MemoryUtil.memFree(meta);
        }

        // Scratch buffers last: nothing to free on the failure paths above.
        metaScratch = MemoryUtil.memAlloc(GsrLayout.PAGE_META_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        cellScratch = MemoryUtil.memAllocInt(GsrLayout.CELLS_PER_PAGE);
    }

    /** Queries the driver's SSBO limits, logs them with the planned sizes, throws when the config exceeds them. */
    private static void checkLimits(GsrConfig cfg, int slots, long frameBytes, long metaBytes, long cellBytes, long splatBytes) {
        // 64-bit query: the block size limit can exceed 2^31 (glGetInteger would clamp it).
        long maxBlock = glGetInteger64(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        int maxBindings = glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        int maxComputeBlocks = glGetInteger(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS);
        String fake = System.getProperty(PROP_FAKE_MAX_BLOCK);
        if (fake != null && !fake.isBlank()) {
            long real = maxBlock;
            maxBlock = Long.parseLong(fake.trim());
            GsrClient.LOG.warn("GSR: dev fault injection, max SSBO block size forced to {} B (driver: {} B)", maxBlock, real);
        }
        GsrClient.LOG.info("GSR: SSBO limits: max block {} MiB ({} B), {} bindings, {} compute blocks; "
                        + "planned: splats {} MiB ({} slots x {} splats), cells {} KiB, meta {} KiB, frame {} B, bindings {}..{}",
                mib(maxBlock), maxBlock, maxBindings, maxComputeBlocks,
                mib(splatBytes), slots, cfg.maxSplatsPerPage, cellBytes >> 10, metaBytes >> 10, frameBytes,
                cfg.ssboBindingBase, cfg.ssboBindingBase + BUFFER_COUNT - 1);

        long largest = Math.max(Math.max(splatBytes, cellBytes), Math.max(metaBytes, frameBytes));
        if (largest > maxBlock) {
            throw new IllegalStateException(String.format(
                    "GPU limit: the largest GSR buffer needs %d MiB (%d B) but the driver allows %d MiB (%d B) per "
                            + "shader storage block (GL_MAX_SHADER_STORAGE_BLOCK_SIZE). Lower maxSplatsPerPage "
                            + "(now %d), pagesX/Y/Z (now %d/%d/%d) or levels (now %d) in config/gsr.json.",
                    mib(largest), largest, mib(maxBlock), maxBlock,
                    cfg.maxSplatsPerPage, cfg.pagesX, cfg.pagesY, cfg.pagesZ, cfg.levels));
        }
        if ((long) cfg.ssboBindingBase + BUFFER_COUNT > maxBindings) {
            throw new IllegalStateException(String.format(
                    "GPU limit: GSR binds its %d SSBOs at %d..%d but the driver has only %d binding points "
                            + "(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS). Lower ssboBindingBase in config/gsr.json "
                            + "to at most %d.",
                    BUFFER_COUNT, cfg.ssboBindingBase, cfg.ssboBindingBase + BUFFER_COUNT - 1, maxBindings,
                    Math.max(0, maxBindings - BUFFER_COUNT)));
        }
        if (maxComputeBlocks < BUFFER_COUNT) {
            throw new IllegalStateException(String.format(
                    "GPU limit: GSR compute shaders use %d shader storage blocks but the driver allows only %d "
                            + "(GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS). This GPU/driver cannot run GSR.",
                    BUFFER_COUNT, maxComputeBlocks));
        }
    }

    private static long mib(long bytes) { return (bytes + (1L << 20) - 1) >> 20; }

    private static void drainGlErrors() {
        // Bounded: a lost context can keep returning errors.
        for (int i = 0; i < 32; i++) {
            int err = glGetError();
            if (err == GL_NO_ERROR) return;
            GsrClient.LOG.debug("GSR: drained stale GL error 0x{} before allocation", Integer.toHexString(err));
        }
    }

    private static int createBuffer(String name, long bytes, boolean fakeOom, List<Integer> created) {
        int b = glGenBuffers();
        created.add(b);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, b);
        glBufferData(GL_SHADER_STORAGE_BUFFER, bytes, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        checkAllocation(name, bytes, fakeOom);
        return b;
    }

    /** Throws when the last allocation raised a GL error ({@code fakeOom}: dev fault injection). */
    private static void checkAllocation(String name, long bytes, boolean fakeOom) {
        int err = glGetError();
        if (fakeOom && err == GL_NO_ERROR) {
            GsrClient.LOG.warn("GSR: dev fault injection, GL_OUT_OF_MEMORY simulated on the {} buffer", name);
            err = GL_OUT_OF_MEMORY;
        }
        if (err == GL_NO_ERROR) return;
        drainGlErrors();
        String what = err == GL_OUT_OF_MEMORY ? "GL_OUT_OF_MEMORY" : "GL error 0x" + Integer.toHexString(err);
        throw new IllegalStateException(String.format(
                "GPU allocation failed: %s while allocating the %s buffer (%d MiB). Lower maxSplatsPerPage, "
                        + "pagesX/Y/Z or levels in config/gsr.json to reduce GSR's VRAM use.",
                what, name, mib(bytes)));
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
