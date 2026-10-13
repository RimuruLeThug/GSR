package dev.gsr.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Optional bridge to Iris. When a shaderpack is active, Iris replaces the pipeline: standalone mode
 * is disabled and GSR becomes a SERVICE (SSBOs bound at fixed points, read by the pack via
 * gsr_common.glsl). See docs/IRIS_INTEGRATION.md.
 */
public final class IrisCompat {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

    private IrisCompat() {}

    public static boolean isShaderPackInUse() {
        return IRIS && Holder.inUse();
    }

    /** Nested class: only loaded (and only resolves Iris classes) when Iris is present. */
    private static final class Holder {
        static boolean inUse() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
