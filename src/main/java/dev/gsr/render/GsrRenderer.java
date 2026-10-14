package dev.gsr.render;

import dev.gsr.GsrClient;
import dev.gsr.core.GaussianChunkManager;
import dev.gsr.core.GsrConfig;
import dev.gsr.core.GsrFrameData;
import dev.gsr.mc.GsrLighting;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Per-frame GPU orchestration.
 *
 *  WorldRenderEvents.START : upload of ready pages, writing of the "frame" SSBO, binding of the 4 SSBOs
 *                            (they stay bound for the whole frame -> usable by an Iris shaderpack).
 *  WorldRenderEvents.LAST  : (standalone mode, no shaderpack) 3 compute dispatches on the main
 *                            framebuffer: reflect (SSR->GSR) -> temporal -> composite. The hand and HUD
 *                            are rendered afterwards by the game, so they are never reflected by mistake.
 *  WorldRenderEvents.END   : stores the matrices for the reprojection of the next frame.
 *
 * State hygiene: Minecraft caches its GL state (GlStateManager). We re-read and restore
 * exactly what we touch (program, active unit, textures 0..5) so that this cache stays accurate.
 */
public final class GsrRenderer {
    private boolean initialized, failed;
    private GsrGpuResources gpu;
    private final GsrScreenTargets targets = new GsrScreenTargets();
    private int progDepthCopy, progReflect, progTemporal, progComposite;
    /** True if the opaque depth was copied during this frame (otherwise the mask would be wrong). */
    private boolean opaqueDepthCaptured;

    // Timing (/gsr stats): disjoint GPU sections + CPU time of uploads.
    static final int T_DEPTH = 0, T_REFLECT = 1, T_TEMPORAL = 2, T_COMPOSITE = 3;
    private static final String[] T_NAMES = {"depth copy", "reflection", "temporal", "composite"};
    /**
     * Development (-Dgsr.benchRepeat=N, gradle -PgsrBench=N): the reflection, temporal and composite passes are
     * dispatched N times per frame and their times divided by N. Keeps the GPU at its working clock (with vsync it idles and
     * down-clocks, making sub-millisecond measurements noisy). The repeats are separated by a barrier,
     * as the single pass is in a real frame.
     */
    private static final int BENCH_REPEAT = Math.max(1, Integer.getInteger("gsr.benchRepeat", 1));
    /**
     * -Dgsr.benchOverlap (gradle -PgsrBenchOverlap): no barrier between the repeats, the GPU overlaps them and
     * the bench measures throughput — closer to a small GPU, where the pass is never latency-bound.
     */
    private static final boolean BENCH_OVERLAP = Boolean.getBoolean("gsr.benchOverlap");
    private GsrGpuTimer timer;
    private double uploadCpuMs;
    private int lastUploads;
    private final Map<Integer, Map<String, Integer>> uniformCache = new HashMap<>();

    private final GsrFrameData frame = new GsrFrameData();
    private final ByteBuffer frameBytes = MemoryUtil.memAlloc(GsrFrameData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4f prevViewProj = new Matrix4f();
    private Vec3d prevCam;
    private int frameIndex;

    public boolean available() { return !failed; }

    private boolean ensureInit(GsrConfig cfg) {
        if (initialized || failed) return initialized;
        String glInfo = glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER) + " / GLSL " + glGetString(GL_SHADING_LANGUAGE_VERSION);
        if (!supportsGl43Features()) {
            failed = true;
            GsrClient.LOG.warn("GSR disabled: compute shaders + SSBO unavailable ({}). macOS caps at 4.1.", glInfo);
            return false;
        }
        GsrClient.LOG.info("GSR: context {}", glInfo);
        try {
            gpu = new GsrGpuResources(cfg);
            progDepthCopy = GsrShaderLoader.compileCompute("gsr_depthcopy.comp", cfg.ssboBindingBase);
            progReflect = GsrShaderLoader.compileCompute("gsr_reflect.comp", cfg.ssboBindingBase);
            progTemporal = GsrShaderLoader.compileCompute("gsr_temporal.comp", cfg.ssboBindingBase);
            progComposite = GsrShaderLoader.compileCompute("gsr_composite.comp", cfg.ssboBindingBase);
            timer = new GsrGpuTimer(T_NAMES.length);
            initialized = true;
        } catch (RuntimeException e) {
            failed = true;
            GsrClient.LOG.error("GSR disabled: GPU initialization failed", e);
        }
        return initialized;
    }

    /**
     * Minecraft requests a 3.2 Core context. Some drivers (AMD in particular) then report exactly
     * "3.2" even though the hardware handles 4.6: OpenGL43 is false, but the functions are exposed via the
     * ARB extensions (LWJGL shares core/ARB pointers). So we accept either one.
     */
    private static boolean supportsGl43Features() {
        GLCapabilities c = GL.getCapabilities();
        if (c.OpenGL43) return true;
        boolean ext = c.GL_ARB_compute_shader && c.GL_ARB_shader_storage_buffer_object
                && c.GL_ARB_shader_image_load_store && c.GL_ARB_texture_storage;
        if (!ext) return false;
        // The source uses "#version 430 core": a GLSL compiler >= 4.30 is required.
        String glsl = glGetString(GL_SHADING_LANGUAGE_VERSION);
        try {
            String[] v = glsl.trim().split("[ .]");
            int major = Integer.parseInt(v[0]), minor = Integer.parseInt(v[1]);
            return major > 4 || (major == 4 && minor >= 30);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ start of frame

    public void beginFrame(WorldRenderContext ctx, GaussianChunkManager mgr, GsrConfig cfg) {
        if (!ensureInit(cfg)) return;
        long t0 = System.nanoTime();
        lastUploads = mgr.drainUploads(gpu, cfg.uploadsPerFrame);
        uploadCpuMs = uploadCpuMs * 0.9 + (System.nanoTime() - t0) / 1e6 * 0.1;

        Vec3d cam = ctx.camera().getPos();
        frame.setCamera(cam.x, cam.y, cam.z);
        frame.frameIndex = frameIndex;
        GsrLighting.fill(frame, ctx.world(), cam, ctx.tickCounter().getTickDelta(true));
        frameBytes.clear();
        frame.write(frameBytes, mgr.clipmap(), cfg);
        frameBytes.flip();
        gpu.writeFrame(frameBytes);
        gpu.bindAll();
    }

    // ------------------------------------------------------------------ standalone mode

    /** Texture units touched by the passes (0..5): saved then restored identically. */
    private static final int TEX_UNITS = 6;

    private static int[] saveGlState() {
        int[] s = new int[TEX_UNITS + 2];
        s[0] = glGetInteger(GL_CURRENT_PROGRAM);
        s[1] = glGetInteger(GL_ACTIVE_TEXTURE);
        for (int i = 0; i < TEX_UNITS; i++) { glActiveTexture(GL_TEXTURE0 + i); s[2 + i] = glGetInteger(GL_TEXTURE_BINDING_2D); }
        return s;
    }

    private static void restoreGlState(int[] s) {
        glBindImageTexture(0, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
        glBindImageTexture(1, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
        for (int i = 0; i < TEX_UNITS; i++) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, s[2 + i]); }
        glActiveTexture(s[1]);
        glUseProgram(s[0]);
    }

    /**
     * Depth of the scene seen by the passes: in "Fabulous" graphics mode, water is rendered into
     * a separate translucent framebuffer, whose depth starts from a copy of the opaque one.
     */
    private static int sceneDepthTexture(WorldRenderContext ctx, Framebuffer main) {
        Framebuffer tr = ctx.worldRenderer().getTranslucentFramebuffer();
        return tr != null ? tr.getDepthAttachment() : main.getDepthAttachment();
    }

    /** WorldRenderEvents.BEFORE_DEBUG_RENDER: the translucent layer is not drawn yet. */
    public void captureOpaqueDepth(WorldRenderContext ctx, GsrConfig cfg) {
        if (!initialized) return;
        Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        int w = fb.textureWidth, h = fb.textureHeight;
        int[] state = saveGlState();
        try {
            targets.ensureSize(w, h, cfg.standaloneResolutionScale);
            glUseProgram(progDepthCopy);
            glUniform2f(loc(progDepthCopy, "uScreenSize"), w, h);
            bindTex(0, fb.getDepthAttachment());
            glBindImageTexture(0, targets.opaqueDepth, 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            timer.begin(T_DEPTH);
            glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
            timer.end(T_DEPTH);
            glMemoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
            opaqueDepthCaptured = true;
        } finally {
            restoreGlState(state);
        }
    }

    public void renderStandalone(WorldRenderContext ctx, GsrConfig cfg) {
        if (!initialized) return;
        Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        int w = fb.textureWidth, h = fb.textureHeight;
        // Without this FRAME's opaque depth, the water/translucent mask would be wrong: skip.
        if (!opaqueDepthCaptured || targets.width != w || targets.height != h
                || targets.scale != cfg.standaloneResolutionScale) return;
        opaqueDepthCaptured = false;
        int depthTex = sceneDepthTexture(ctx, fb);

        int[] state = saveGlState();
        try {
            bindTex(5, targets.opaqueDepth);
            Matrix4f proj = new Matrix4f(ctx.projectionMatrix());
            Matrix4f view = new Matrix4f(ctx.positionMatrix());
            Matrix4f invProj = new Matrix4f(proj).invert();
            Matrix4f invView = new Matrix4f(view).invert();
            int gx = (w + 7) / 8, gy = (h + 7) / 8;
            // Reflection + temporal passes at low resolution; compositing stays at full resolution.
            int lgx = (targets.lowWidth + 7) / 8, lgy = (targets.lowHeight + 7) / 8;
            int cur = targets.current, prev = targets.prev();

            // 1) SSR -> GSR -> sky
            glUseProgram(progReflect);
            setCommon(progReflect, proj, invProj, view, invView, w, h, cfg);
            set1f(progReflect, "uMaxTrace", cfg.maxTraceDistance);
            glUniform1i(loc(progReflect, "uDebugView"), cfg.debugView);
            bindTex(0, depthTex);
            bindTex(1, fb.getColorAttachment());
            glBindImageTexture(0, targets.reflection, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glBindImageTexture(1, targets.representative, 0, false, 0, GL_WRITE_ONLY, GL_R8UI);
            timer.begin(T_REFLECT);
            benchDispatch(lgx, lgy);
            timer.end(T_REFLECT);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

            // 2) temporal accumulation
            glUseProgram(progTemporal);
            setCommon(progTemporal, proj, invProj, view, invView, w, h, cfg);
            setMat(progTemporal, "uPrevViewProj", prevViewProj);
            Vec3d cam = ctx.camera().getPos();
            Vec3d delta = prevCam == null ? Vec3d.ZERO : cam.subtract(prevCam);
            glUniform3f(loc(progTemporal, "uCamDelta"), (float) delta.x, (float) delta.y, (float) delta.z);
            glUniform1i(loc(progTemporal, "uHistoryValid"), targets.historyValid && prevCam != null ? 1 : 0);
            bindTex(0, depthTex);
            bindTex(1, targets.representative);
            bindTex(2, targets.reflection);
            bindTex(3, targets.history[prev]);
            bindTex(4, targets.distance[prev]);
            glBindImageTexture(0, targets.history[cur], 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glBindImageTexture(1, targets.distance[cur], 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            timer.begin(T_TEMPORAL);
            benchDispatch(lgx, lgy);   // idempotent: same inputs, same output
            timer.end(T_TEMPORAL);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

            // 3) compositing into the main framebuffer
            glUseProgram(progComposite);
            setCommon(progComposite, proj, invProj, view, invView, w, h, cfg);
            glUniform1i(loc(progComposite, "uDebugView"), cfg.debugView);
            // Bench scratch created BEFORE the inputs are bound: its creation binds it on the active unit
            // (unit 4 here), which the composite would otherwise read as uReflDist on that frame.
            if (BENCH_REPEAT > 1 && (benchScratch == 0 || benchScratchW != w || benchScratchH != h)) {
                if (benchScratch != 0) glDeleteTextures(benchScratch);
                benchScratch = benchScratchTexture(w, h);
            }
            bindTex(0, depthTex);
            bindTex(2, targets.history[cur]);
            bindTex(4, targets.distance[cur]);
            timer.begin(T_COMPOSITE);
            if (BENCH_REPEAT > 1) {   // not idempotent (blends into the scene): the extra repeats write a scratch image
                glBindImageTexture(0, benchScratch, 0, false, 0, GL_READ_WRITE, GL_RGBA8);
                for (int r = 1; r < BENCH_REPEAT; r++) benchStep(gx, gy);
            }
            glBindImageTexture(0, fb.getColorAttachment(), 0, false, 0, GL_READ_WRITE, GL_RGBA8);
            glDispatchCompute(gx, gy, 1);
            timer.end(T_COMPOSITE);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            targets.swap();
        } finally {
            restoreGlState(state);
        }
    }

    // ------------------------------------------------------------------ end of frame

    public void endFrame(WorldRenderContext ctx) {
        prevViewProj.set(ctx.projectionMatrix()).mul(ctx.positionMatrix());
        prevCam = ctx.camera().getPos();
        frameIndex++;
        if (initialized) timer.endFrame();
    }

    /** "Render" lines of /gsr stats (client thread). */
    public void appendStats(java.util.List<String> out, GsrConfig cfg, boolean standalone) {
        if (failed) { out.add("Render: disabled (see the log)"); return; }
        if (!initialized) { out.add("Render: not initialized yet"); return; }
        out.add(standalone ? gpuLine() : "GPU: service mode (shaderpack) or standalone off, passes not dispatched");
        out.add(String.format("Upload: %d pages in the last frame, CPU %.2f ms (avg)", lastUploads, uploadCpuMs));
        long ssbo = GsrFrameData.BYTES + (long) cfg.totalSlots() * (dev.gsr.core.GsrLayout.PAGE_META_BYTES
                + dev.gsr.core.GsrLayout.CELLS_PER_PAGE * dev.gsr.core.GsrLayout.CELL_BYTES
                + (long) cfg.maxSplatsPerPage * dev.gsr.core.GsrLayout.SPLAT_BYTES);
        out.add(String.format("VRAM: SSBO %.1f MiB, screen targets %.1f MiB (%dx%d, reflection 1/%d)",
                ssbo / 1048576.0, targets.bytes() / 1048576.0, Math.max(0, targets.width), Math.max(0, targets.height),
                cfg.standaloneResolutionScale));
    }

    /** Average GPU time per pass and total (3 decimals: the passes are well under a millisecond). */
    public String gpuLine() {
        if (!initialized) return "GPU: not initialized";
        StringBuilder line = new StringBuilder("GPU (avg):");
        double total = 0;
        for (int s = 0; s < T_NAMES.length; s++) {
            double ms = timer.avgMs(s) / (s == T_DEPTH ? 1 : BENCH_REPEAT);
            total += ms;
            line.append(String.format(" %s %.3f ms,", T_NAMES[s], ms));
        }
        return line.append(String.format(" total %.3f ms", total)).toString();
    }

    /** World change: the temporal history no longer makes sense. */
    public void resetHistory() { prevCam = null; targets.historyValid = false; }

    // ------------------------------------------------------------------ helpers

    /** Dispatch, repeated BENCH_REPEAT times in bench mode (the GPU timer divides by BENCH_REPEAT). */
    private static void benchDispatch(int gx, int gy) {
        for (int r = 0; r < BENCH_REPEAT; r++) benchStep(gx, gy);
    }

    private static void benchStep(int gx, int gy) {
        glDispatchCompute(gx, gy, 1);
        // Without a barrier the driver overlaps the repeats, which hid ~1/3 of the real cost of one pass.
        if (BENCH_REPEAT > 1 && !BENCH_OVERLAP) glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);
    }

    /** Bench only: full-resolution RGBA8 target for the extra composite repeats (resized: recreated). */
    private int benchScratch, benchScratchW, benchScratchH;

    private int benchScratchTexture(int w, int h) {
        benchScratchW = w; benchScratchH = h;
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, w, h);
        return t;
    }

    private void setCommon(int p, Matrix4f proj, Matrix4f invProj, Matrix4f view, Matrix4f invView, int w, int h, GsrConfig cfg) {
        setMat(p, "uProj", proj); setMat(p, "uInvProj", invProj);
        setMat(p, "uView", view); setMat(p, "uInvView", invView);
        glUniform2f(loc(p, "uScreenSize"), w, h);
        glUniform1i(loc(p, "uFrame"), frameIndex);
        set1f(p, "uRoughness", cfg.standaloneRoughness);
        set1f(p, "uStrength", cfg.standaloneStrength);
        set1f(p, "uFloorStrength", cfg.standaloneFloorStrength);
        glUniform1i(loc(p, "uResScale"), targets.scale);
    }

    private static void bindTex(int unit, int tex) { glActiveTexture(GL_TEXTURE0 + unit); glBindTexture(GL_TEXTURE_2D, tex); }

    private int loc(int prog, String name) {
        return uniformCache.computeIfAbsent(prog, k -> new HashMap<>()).computeIfAbsent(name, n -> glGetUniformLocation(prog, n));
    }
    private void set1f(int p, String n, float v) { glUniform1f(loc(p, n), v); }
    private void setMat(int p, String n, Matrix4f m) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(loc(p, n), false, fb);
        }
    }

    public void close() {
        if (!initialized) return;
        gpu.close();
        timer.close();
        targets.delete();
        if (benchScratch != 0) glDeleteTextures(benchScratch);
        benchScratch = 0; benchScratchW = benchScratchH = 0;
        glDeleteProgram(progDepthCopy); glDeleteProgram(progReflect); glDeleteProgram(progTemporal); glDeleteProgram(progComposite);
        initialized = false;
    }
}
