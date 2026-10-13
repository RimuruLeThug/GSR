package dev.gsr.core;

import java.nio.ByteBuffer;

/**
 * Données par frame (SSBO binding base+0), std430. Miroir exact de "struct GsrFrame" en GLSL :
 * <pre>
 *  ivec4 camBlock;      // xyz = bloc entier de la caméra, w = index de frame
 *  vec4  camFrac;       // xyz = partie fractionnaire,     w = nombre de niveaux
 *  ivec4 pageDims;      // xyz = pages par niveau,         w = slots par niveau
 *  ivec4 windowMin[4];  // coin min de chaque fenêtre (coord. de page)
 *  ivec4 iparams;       // x = maxSplatsPerPage
 *  vec4  sunDir;        // xyz normalisé,                  w = intensité ciel (jour/nuit)
 *  vec4  sunColor;
 *  vec4  skyZenith;
 *  vec4  skyHorizon;
 *  vec4  blockLight;    // rgb torches,                    w = force d'émission
 * </pre>
 * L'espace de travail de tous les shaders est "relatif caméra, non tourné" (comme le rendu vanilla
 * et l'espace "player" d'Iris) : P_monde = camBlock + camFrac + P.
 */
public final class GsrFrameData {
    public static final int BYTES = 13 * 16;

    public int camBx, camBy, camBz, frameIndex;
    public float camFx, camFy, camFz;
    public float[] sunDir = {0.3f, 0.9f, 0.2f}, sunColor = {1.0f, 0.95f, 0.85f};
    public float skyBrightness = 1f;
    public float[] skyZenith = {0.35f, 0.55f, 1.0f}, skyHorizon = {0.75f, 0.85f, 1.0f};
    public float[] blockLight = {1.0f, 0.75f, 0.45f};
    public float emissionStrength = 2.0f;

    public void setCamera(double x, double y, double z) {
        camBx = (int) Math.floor(x); camBy = (int) Math.floor(y); camBz = (int) Math.floor(z);
        camFx = (float) (x - camBx); camFy = (float) (y - camBy); camFz = (float) (z - camBz);
    }

    public void write(ByteBuffer b, GaussianClipmap clipmap, GsrConfig cfg) {
        int p = b.position();
        b.putInt(camBx).putInt(camBy).putInt(camBz).putInt(frameIndex);
        b.putFloat(camFx).putFloat(camFy).putFloat(camFz).putFloat(cfg.levels);
        b.putInt(cfg.pagesX).putInt(cfg.pagesY).putInt(cfg.pagesZ).putInt(cfg.slotsPerLevel());
        for (int L = 0; L < GsrLayout.MAX_LEVELS; L++) {
            int[] m = L < cfg.levels ? clipmap.windowMin(L) : new int[]{GsrLayout.INVALID_PAGE_COORD, 0, 0};
            b.putInt(m[0]).putInt(m[1]).putInt(m[2]).putInt(0);
        }
        b.putInt(cfg.maxSplatsPerPage).putInt(0).putInt(0).putInt(0);
        vec4(b, sunDir, skyBrightness);
        vec4(b, sunColor, 0f);
        vec4(b, skyZenith, 0f);
        vec4(b, skyHorizon, 0f);
        vec4(b, blockLight, emissionStrength);
        if (b.position() - p != BYTES) throw new IllegalStateException("GsrFrame layout mismatch");
    }

    private static void vec4(ByteBuffer b, float[] v, float w) {
        b.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putFloat(w);
    }
}
