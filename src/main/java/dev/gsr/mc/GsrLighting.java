package dev.gsr.mc;

import dev.gsr.core.GsrFrameData;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Remplit l'éclairage global de la frame. Les splats ne stockent que albédo + niveaux de lumière :
 * le cycle jour/nuit et la météo changent les reflets SANS reconstruire un seul splat.
 */
public final class GsrLighting {
    private GsrLighting() {}

    public static void fill(GsrFrameData f, ClientWorld world, Vec3d cam, float tickDelta) {
        if (!world.getDimension().hasSkyLight()) {
            f.skyBrightness = 0f;
            f.skyZenith = new float[]{0.05f, 0.03f, 0.03f};
            f.skyHorizon = new float[]{0.10f, 0.05f, 0.04f};
            return;
        }
        float a = world.getSkyAngleRadians(tickDelta);
        // Rotation vanilla du ciel autour de Z : lever à l'est (+X), zénith à midi.
        float sx = (float) -Math.sin(a), sy = (float) Math.cos(a);
        boolean moon = sy < 0f;
        f.sunDir = moon ? new float[]{-sx, -sy, 0f} : new float[]{sx, sy, 0f};

        float rain = world.getRainGradient(tickDelta);
        float bright = world.getSkyBrightness(tickDelta);          // ~0.2 (nuit) .. 1 (jour)
        f.skyBrightness = bright;

        Vec3d sky = world.getSkyColor(cam, tickDelta);
        float zr = (float) Math.pow(sky.x, 2.2), zg = (float) Math.pow(sky.y, 2.2), zb = (float) Math.pow(sky.z, 2.2);
        f.skyZenith = new float[]{zr, zg, zb};
        f.skyHorizon = new float[]{zr * 0.6f + 0.4f * bright, zg * 0.6f + 0.4f * bright, zb * 0.6f + 0.4f * bright};

        float elev = Math.max(0f, f.sunDir[1]);
        float warm = 1f - (float) Math.pow(elev, 0.35);            // soleil orangé près de l'horizon
        float sunI = (moon ? 0.08f : 2.2f) * (1f - 0.85f * rain);
        f.sunColor = moon
                ? new float[]{0.6f * sunI, 0.7f * sunI, 1.0f * sunI}
                : new float[]{sunI, sunI * (1f - 0.35f * warm), sunI * (1f - 0.65f * warm)};
    }
}
