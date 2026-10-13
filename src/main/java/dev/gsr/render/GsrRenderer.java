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
 * exactement ce qu'on touche (programme, unité active, textures 0..4) pour que ce cache reste vrai.
 */
public final class GsrRenderer {
    private boolean initialized, failed;
    private GsrGpuResources gpu;
    private final GsrScreenTargets targets = new GsrScreenTargets();
    private int progReflect, progTemporal, progComposite;
    private final Map<Integer, Map<String, Integer>> uniformCache = new HashMap<>();

    private final GsrFrameData frame = new GsrFrameData();
    private final ByteBuffer frameBytes = MemoryUtil.memAlloc(GsrFrameData.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private final Matrix4f prevViewProj = new Matrix4f();
    private Vec3d prevCam;
    private int frameIndex;

    public boolean available() { return !failed; }

    private boolean ensureInit(GsrConfig cfg) {
        if (initialized || failed) return initialized;
        if (!GL.getCapabilities().OpenGL43) {
            failed = true;
            GsrClient.LOG.warn("GSR désactivé : OpenGL 4.3 (compute + SSBO) indisponible (macOS plafonne à 4.1).");
            return false;
        }
        try {
            gpu = new GsrGpuResources(cfg);
            progReflect = GsrShaderLoader.compileCompute("gsr_reflect.comp", cfg.ssboBindingBase);
            progTemporal = GsrShaderLoader.compileCompute("gsr_temporal.comp", cfg.ssboBindingBase);
            progComposite = GsrShaderLoader.compileCompute("gsr_composite.comp", cfg.ssboBindingBase);
            initialized = true;
        } catch (RuntimeException e) {
            failed = true;
            GsrClient.LOG.error("GSR désactivé : initialisation GPU impossible", e);
        }
        return initialized;
    }

    // ------------------------------------------------------------------ début de frame

    public void beginFrame(WorldRenderContext ctx, GaussianChunkManager mgr, GsrConfig cfg) {
        if (!ensureInit(cfg)) return;
        mgr.drainUploads(gpu, cfg.uploadsPerFrame);

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

    public void renderStandalone(WorldRenderContext ctx, GsrConfig cfg) {
        if (!initialized) return;
        Framebuffer fb = MinecraftClient.getInstance().getFramebuffer();
        int w = fb.textureWidth, h = fb.textureHeight;

        // --- sauvegarde de l'état GL touché ---
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevTex = new int[5];
        for (int i = 0; i < 5; i++) { glActiveTexture(GL_TEXTURE0 + i); prevTex[i] = glGetInteger(GL_TEXTURE_BINDING_2D); }

        try {
            targets.ensureSize(w, h);
            Matrix4f proj = new Matrix4f(ctx.projectionMatrix());
            Matrix4f view = new Matrix4f(ctx.positionMatrix());
            Matrix4f invProj = new Matrix4f(proj).invert();
            Matrix4f invView = new Matrix4f(view).invert();
            int gx = (w + 7) / 8, gy = (h + 7) / 8;
            int cur = targets.current, prev = targets.prev();

            // 1) SSR -> GSR -> ciel
            glUseProgram(progReflect);
            setCommon(progReflect, proj, invProj, view, invView, w, h, cfg);
            set1f(progReflect, "uMaxTrace", cfg.maxTraceDistance);
            bindTex(0, fb.getDepthAttachment());
            bindTex(1, fb.getColorAttachment());
            glBindImageTexture(0, targets.reflection, 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glDispatchCompute(gx, gy, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

            // 2) accumulation temporelle
            glUseProgram(progTemporal);
            setCommon(progTemporal, proj, invProj, view, invView, w, h, cfg);
            setMat(progTemporal, "uPrevViewProj", prevViewProj);
            Vec3d cam = ctx.camera().getPos();
            Vec3d delta = prevCam == null ? Vec3d.ZERO : cam.subtract(prevCam);
            glUniform3f(loc(progTemporal, "uCamDelta"), (float) delta.x, (float) delta.y, (float) delta.z);
            glUniform1i(loc(progTemporal, "uHistoryValid"), targets.historyValid && prevCam != null ? 1 : 0);
            bindTex(0, fb.getDepthAttachment());
            bindTex(2, targets.reflection);
            bindTex(3, targets.history[prev]);
            bindTex(4, targets.distance[prev]);
            glBindImageTexture(0, targets.history[cur], 0, false, 0, GL_WRITE_ONLY, GL_RGBA16F);
            glBindImageTexture(1, targets.distance[cur], 0, false, 0, GL_WRITE_ONLY, GL_R32F);
            glDispatchCompute(gx, gy, 1);
            glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT);

            // 3) composition dans le framebuffer principal
            glUseProgram(progComposite);
            setCommon(progComposite, proj, invProj, view, invView, w, h, cfg);
            bindTex(0, fb.getDepthAttachment());
            bindTex(2, targets.history[cur]);
            glBindImageTexture(0, fb.getColorAttachment(), 0, false, 0, GL_READ_WRITE, GL_RGBA8);
            glDispatchCompute(gx, gy, 1);
            glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT);

            targets.swap();
        } finally {
            glBindImageTexture(0, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            glBindImageTexture(1, 0, 0, false, 0, GL_READ_ONLY, GL_RGBA8);
            for (int i = 0; i < 5; i++) { glActiveTexture(GL_TEXTURE0 + i); glBindTexture(GL_TEXTURE_2D, prevTex[i]); }
            glActiveTexture(prevActive);
            glUseProgram(prevProgram);
        }
    }

    // ------------------------------------------------------------------ fin de frame

    public void endFrame(WorldRenderContext ctx) {
        prevViewProj.set(ctx.projectionMatrix()).mul(ctx.positionMatrix());
        prevCam = ctx.camera().getPos();
        frameIndex++;
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
        targets.delete();
        glDeleteProgram(progReflect); glDeleteProgram(progTemporal); glDeleteProgram(progComposite);
        initialized = false;
    }
}
