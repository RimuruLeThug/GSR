package dev.gsr.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Pont optionnel vers Iris. Quand un shaderpack est actif, Iris remplace le pipeline : le mode autonome
 * se désactive et GSR devient un SERVICE (SSBO liés à des points fixes, lus par le pack via
 * gsr_common.glsl). Voir docs/IRIS_INTEGRATION.md.
 */
public final class IrisCompat {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");

    private IrisCompat() {}

    public static boolean isShaderPackInUse() {
        return IRIS && Holder.inUse();
    }

    /** Classe imbriquée : n'est chargée (et ne résout les classes Iris) que si Iris est présent. */
    private static final class Holder {
        static boolean inUse() {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isShaderPackInUse();
        }
    }
}
