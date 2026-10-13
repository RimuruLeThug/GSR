package dev.gsr;

import dev.gsr.compat.IrisCompat;
import dev.gsr.core.GaussianChunkManager;
import dev.gsr.core.GsrConfig;
import dev.gsr.mc.BlockMaterialTable;
import dev.gsr.mc.McSnapshotSource;
import dev.gsr.render.GsrRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Point d'entrée Fabric : câble les événements du monde vers le GaussianChunkManager et les
 * événements de rendu vers le GsrRenderer. Tout s'exécute sur le thread client (tick + rendu).
 */
public final class GsrClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("gsr");

    private static final GsrConfig CONFIG = new GsrConfig().validate();
    private static final GsrRenderer RENDERER = new GsrRenderer();
    private static BlockMaterialTable materials;
    private static GaussianChunkManager manager;
    private static ClientWorld currentWorld;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(GsrClient::tick);

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            ensureWorld(world);
            if (manager != null) manager.onChunkLoaded(chunk.getPos().x, chunk.getPos().z,
                    world.getBottomSectionCoord(), world.getTopSectionCoord() - 1);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (manager != null && world == currentWorld) manager.onChunkUnloaded(chunk.getPos().x, chunk.getPos().z,
                    world.getBottomSectionCoord(), world.getTopSectionCoord() - 1);
        });

        WorldRenderEvents.START.register(ctx -> {
            if (manager != null && RENDERER.available()) RENDERER.beginFrame(ctx, manager, CONFIG);
        });
        // Avant la couche translucide (vérifié dans WorldRenderer#render 1.21.1 : le rendu debug la précède).
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(ctx -> {
            if (standaloneActive()) RENDERER.captureOpaqueDepth(ctx);
        });
        WorldRenderEvents.LAST.register(ctx -> {
            if (standaloneActive()) RENDERER.renderStandalone(ctx, CONFIG);
        });
        WorldRenderEvents.END.register(RENDERER::endFrame);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> ensureWorld(null)));
        LOG.info("GSR (Gaussian Space Reflections) initialisé");
    }

    private static boolean standaloneActive() {
        return manager != null && CONFIG.standaloneEnabled && !IrisCompat.isShaderPackInUse();
    }

    private static void tick(MinecraftClient client) {
        ensureWorld(client.world);
        if (manager != null && client.player != null) {
            manager.tick(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ(), Util.getMeasuringTimeMs());
        }
    }

    /** Changement de monde / dimension : on repart de zéro (les SSBO sont invalidés par le recentrage). */
    private static void ensureWorld(ClientWorld world) {
        if (world == currentWorld) return;
        if (manager != null) manager.close();
        manager = null;
        currentWorld = world;
        RENDERER.resetHistory();
        if (world == null) return;
        if (materials == null) materials = BlockMaterialTable.build();
        manager = new GaussianChunkManager(CONFIG, new McSnapshotSource(world, materials));
    }

    // ---- appelés par ClientWorldMixin (thread client) ----
    public static void onBlockChanged(int x, int y, int z) {
        if (manager != null) manager.onBlockChanged(x, y, z);
    }

    public static void onSectionDirty(int sx, int sy, int sz) {
        if (manager != null) manager.onSectionDirty(sx, sy, sz);
    }

    public static GsrConfig config() { return CONFIG; }
}
