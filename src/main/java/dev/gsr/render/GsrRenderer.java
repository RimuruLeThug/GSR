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
 * Orchestration GPU par frame.
 *
 *  WorldRenderEvents.START : téléversement des pages prêtes, écriture du SSBO "frame", liaison des 4 SSBO
 *                            (ils restent liés toute la frame -> utilisables par un shaderpack Iris).
 *  WorldRenderEvents.LAST  : (mode autonome, sans shaderpack) 3 dispatchs compute sur le framebuffer
 *                            principal : reflect (SSR->GSR) -> temporal -> composite. La main et le HUD
 *                            sont rendus ensuite par le jeu, donc jamais réfléchis par erreur.
 *  WorldRenderEvents.END   : mémorise les matrices pour la reprojection de la frame suivante.
 *
 * Hygiène d'état : Minecraft met en cache son état GL (GlStateManager). On relit et on restaure
 * exactement ce qu'on touche (programme, unité active, textures 0..5) pour que ce cache reste vrai.
 */
public final class GsrRenderer {
    private boolean initialized, failed;
    private GsrGpuResources gpu;
    private final GsrScreenTargets targets = new GsrScreenTargets();
    private int progDepthCopy, progReflect, progTemporal, progComposite;
    /** Vrai si la profondeur opaque a été copiée pendant cette frame (sinon le masque serait faux). */
    private boolean opaqueDepthCaptured;

    // Chronométrage (/gsr stats) : sections GPU disjointes + temps CPU des téléversements.
    static final int T_DEPTH = 0, T_REFLECT = 1, T_TEMPORAL = 2, T_COMPOSITE = 3;
    private static final String[] T_NAMES = {"copie prof.", "reflet", "temporel", "composition"};
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
            GsrClient.LOG.warn("GSR désactivé : compute shaders + SSBO indisponibles ({}). macOS plafonne à 4.1.", glInfo);
            return false;
        }
        GsrClient.LOG.info("GSR : contexte {}", glInfo);
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
            GsrClient.LOG.error("GSR désactivé : initialisation GPU impossible", e);
        }
        return initialized;
    }

    /**
     * Minecraft demande un contexte 3.2 Core. Certains pilotes (AMD notamment) annoncent alors exactement
     * "3.2" bien que le matériel gère 4.6 : OpenGL43 est faux, mais les fonctions sont exposées via les
     * extensions ARB (LWJGL partage les pointeurs core/ARB). On accepte donc l'un ou l'autre.
     */
    private static boolean supportsGl43Features() {
        GLCapabilities c = GL.getCapabilities();
        if (c.OpenGL43) return true;
        boolean ext = c.GL_ARB_compute_shader && c.GL_ARB_shader_storage_buffer_object
                && c.GL_ARB_shader_image_load_store && c.GL_ARB_texture_storage;
        if (!ext) return false;
        // Le source utilise "#version 430 core" : il faut un compilateur GLSL >= 4.30.
        String glsl = glGetString(GL_SHADING_LANGUAGE_VERSION);
        try {
            String[] v = glsl.trim().split("[ .]");
            int major = Integer.parseInt(v[0]), minor = Integer.parseInt(v[1]);
            return major > 4 || (major == 4 && minor >= 30);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ début de frame

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

    // ------------------------------------------------------------------ mode autonome

    /** Unités de texture touchées par les passes (0..5) : sauvegardées puis restaurées à l'identique. */
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
     * Profondeur de la scène vue par les passes : en mode graphique « Fabuleux », l'eau est rendue dans
     * un framebuffer translucide séparé, dont la profondeur part d'une copie de l'opaque.
     */
    private static int sceneDepthTexture(WorldRenderContext ctx, Framebuffer main) {
        Framebuffer tr = ctx.worldRenderer().getTranslucentFramebuffer();
        return tr != null ? tr.getDepthAttachment() : main.getDepthAttachment();
    }

    /** WorldRenderEvents.BEFORE_DEBUG_RENDER : la couche translucide n'est pas encore dessinée. */
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
        // Sans profondeur opaque de CETTE frame, le masque eau/translucide serait faux : on saute.
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
            // Passes reflet + temporel en basse résolution ; la composition reste en pleine résolution.
            int lgx = (targets.lowWidth + 7) / 8, lgy = (targets.lowHeight + 7) / 8;
            int cur = targets.current, prev = targets.prev();

            // 1) SSR -> GSR -> ciel
            glUseProgram(progReflect);
            setCommon(progReflect, proj, invProj, view, invView, w, h, cfg);
            set1f(progReflect, "uMaxTrace", cfg.maxTraceDistance);
            glUniform1i(loc(progReflect, "uDebugView"), cfg.debugView);
            bindTex(0, depthTex);
            bindTex(1, fb.getColorAttachment());
            glBindImageTexture(0, targets.reflection, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            timer.begin(T_REFLECT);
            glDispatchCompute(lgx, lgy, 1);
            timer.end(T_REFLECT);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

            // 2) accumulation temporelle
            glUseProgram(progTemporal);
            setCommon(progTemporal, proj, invProj, view, invView, w, h, cfg);
            setMat(progTemporal, "uPrevViewProj", prevViewProj);
            Vec3d cam = ctx.camera().getPos();
            Vec3d delta = prevCam == null ? Vec3d.ZERO : cam.subtract(prevCam);
            glUniform3f(loc(progTemporal, "uCamDelta"), (float) delta.x, (float) delta.y, (float) delta.z);
            glUniform1i(loc(progTemporal, "uHistoryValid"), targets.historyValid && prevCam != null ? 1 : 0);
            bindTex(0, depthTex);
            bindTex(2, targets.reflection);
            bindTex(3, targets.history[prev]);
            bindTex(4, targets.distance[prev]);
            glBindImageTexture(0, targets.history[cur], 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glBindImageTexture(1, targets.distance[cur], 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            timer.begin(T_TEMPORAL);
            glDispatchCompute(lgx, lgy, 1);
            timer.end(T_TEMPORAL);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

            // 3) composition dans le framebuffer principal
            glUseProgram(progComposite);
            setCommon(progComposite, proj, invProj, view, invView, w, h, cfg);
            bindTex(0, depthTex);
            bindTex(2, targets.history[cur]);
            bindTex(4, targets.distance[cur]);
            glBindImageTexture(0, fb.getColorAttachment(), 0, false, 0, GL_READ_WRITE, GL_RGBA8);
            timer.begin(T_COMPOSITE);
            glDispatchCompute(gx, gy, 1);
            timer.end(T_COMPOSITE);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            targets.swap();
        } finally {
            restoreGlState(state);
        }
    }

    // ------------------------------------------------------------------ fin de frame

    public void endFrame(WorldRenderContext ctx) {
        prevViewProj.set(ctx.projectionMatrix()).mul(ctx.positionMatrix());
        prevCam = ctx.camera().getPos();
        frameIndex++;
        if (initialized) timer.endFrame();
    }

    /** Lignes « rendu » de /gsr stats (thread client). */
    public void appendStats(java.util.List<String> out, GsrConfig cfg, boolean standalone) {
        if (failed) { out.add("Rendu : désactivé (voir le log)"); return; }
        if (!initialized) { out.add("Rendu : pas encore initialisé"); return; }
        StringBuilder gpuLine = new StringBuilder("GPU (moy.) :");
        double total = 0;
        for (int s = 0; s < T_NAMES.length; s++) {
            double ms = timer.avgMs(s);
            total += ms;
            gpuLine.append(String.format(" %s %.2f ms,", T_NAMES[s], ms));
        }
        gpuLine.append(String.format(" total %.2f ms", total));
        out.add(standalone ? gpuLine.toString() : "GPU : mode service (shaderpack) ou autonome coupé, passes non dispatchées");
        out.add(String.format("Téléversement : %d pages à la dernière frame, CPU %.2f ms (moy.)", lastUploads, uploadCpuMs));
        long ssbo = GsrFrameData.BYTES + (long) cfg.totalSlots() * (dev.gsr.core.GsrLayout.PAGE_META_BYTES
                + dev.gsr.core.GsrLayout.CELLS_PER_PAGE * dev.gsr.core.GsrLayout.CELL_BYTES
                + (long) cfg.maxSplatsPerPage * dev.gsr.core.GsrLayout.SPLAT_BYTES);
        out.add(String.format("VRAM : SSBO %.1f Mio, cibles écran %.1f Mio (%dx%d, reflet 1/%d)",
                ssbo / 1048576.0, targets.bytes() / 1048576.0, Math.max(0, targets.width), Math.max(0, targets.height),
                cfg.standaloneResolutionScale));
    }

    /** Changement de monde : l'historique temporel n'a plus de sens. */
    public void resetHistory() { prevCam = null; targets.historyValid = false; }

    // ------------------------------------------------------------------ helpers

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
        glDeleteProgram(progDepthCopy); glDeleteProgram(progReflect); glDeleteProgram(progTemporal); glDeleteProgram(progComposite);
        initialized = false;
    }
}
