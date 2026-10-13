package dev.gsr;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.gsr.compat.IrisCompat;
import dev.gsr.core.GaussianChunkManager;
import dev.gsr.core.GsrConfig;
import dev.gsr.mc.BlockMaterialTable;
import dev.gsr.mc.McSnapshotSource;
import dev.gsr.render.GsrRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
        if (AUTOSHOT != null) ClientTickEvents.END_CLIENT_TICK.register(GsrClient::autoShot);

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
            if (standaloneActive()) RENDERER.captureOpaqueDepth(ctx, CONFIG);
        });
        WorldRenderEvents.LAST.register(ctx -> {
            if (standaloneActive()) RENDERER.renderStandalone(ctx, CONFIG);
        });
        WorldRenderEvents.END.register(RENDERER::endFrame);

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("gsr")
                        .then(ClientCommandManager.literal("stats").executes(c -> {
                            for (String line : statsLines()) c.getSource().sendFeedback(Text.literal(line));
                            return 1;
                        }))
                        // /gsr scale 1|2 : résolution des passes reflet + temporel (mesures avant/après)
                        .then(ClientCommandManager.literal("scale")
                                .then(ClientCommandManager.argument("factor", IntegerArgumentType.integer(1, 2)).executes(c -> {
                                    CONFIG.standaloneResolutionScale = IntegerArgumentType.getInteger(c, "factor");
                                    c.getSource().sendFeedback(Text.literal("GSR : reflet en 1/" + CONFIG.standaloneResolutionScale + " de résolution"));
                                    return 1;
                                })))
                        // /gsr debug normal|ssr|gsr : isole la source du reflet (diagnostic d'artefacts)
                        .then(ClientCommandManager.literal("debug")
                                .then(debugView("normal", 0)).then(debugView("ssr", 1)).then(debugView("gsr", 2)))
                        // /gsr bricks on|off : masque de briques (mesure avant/après au même endroit)
                        .then(ClientCommandManager.literal("bricks")
                                .then(brickToggle("on", true)).then(brickToggle("off", false)))));

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> ensureWorld(null)));
        LOG.info("GSR (Gaussian Space Reflections) initialisé");
    }

    /**
     * Développement : -Dgsr.autoshot=<préfixe> prend, 15 s après l'entrée dans le monde, une capture par
     * vue de debug (normal, ssr, gsr) dans run/screenshots/<préfixe>_<vue>.png, sans intervention.
     * Le joueur réapparaît où il s'est déconnecté : on retrouve la vue d'une session précédente.
     */
    private static final String AUTOSHOT = System.getProperty("gsr.autoshot");
    private static final String[] AUTOSHOT_VIEWS = {"normal", "ssr", "gsr"};
    /**
     * Rafales de scintillement, après les vues : par configuration, FLICKER_FRAMES captures à un tick
     * d'intervalle, caméra immobile. « off » (mod coupé) mesure l'animation propre de l'eau vanilla.
     * Comparer avec : java tools/FlickerDiff.java run/screenshots/<préfixe>_flicker_<config>_*.png
     */
    private static final String[] FLICKER_CONFIGS = {"off", "default", "nobricks", "scale1", "gsronly"};
    private static final int FLICKER_FRAMES = 8;
    private static int autoShotTicks;

    private static void autoShot(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        // Vue i à 300 + 60·i ticks ; entre deux, 60 ticks pour que l'historique temporel se stabilise.
        int t = ++autoShotTicks - 300;
        if (t < 0) return;
        if (t < 60 * AUTOSHOT_VIEWS.length) {
            if (t % 60 != 0) return;
            int i = t / 60;
            saveShot(client, AUTOSHOT + "_" + AUTOSHOT_VIEWS[i]);
            CONFIG.debugView = (i + 1) % AUTOSHOT_VIEWS.length;   // vue suivante ; normal après la dernière
            return;
        }
        // Rafales : 60 ticks de stabilisation par configuration, puis une capture par tick.
        t -= 60 * AUTOSHOT_VIEWS.length;
        int c = t / (60 + FLICKER_FRAMES), k = t % (60 + FLICKER_FRAMES) - 60;
        if (c >= FLICKER_CONFIGS.length) {
            if (k == -60) applyFlickerConfig("default");
            if (frozenPos != null) client.options.getBobView().setValue(frozenBob);
            frozenPos = null;
            return;
        }
        freezeCamera(client);
        if (k == -60) applyFlickerConfig(FLICKER_CONFIGS[c]);
        if (k >= 0) saveShot(client, AUTOSHOT + "_flicker_" + FLICKER_CONFIGS[c] + "_" + k);
    }

    private static Vec3d frozenPos;
    private static float frozenYaw, frozenPitch;
    private static boolean frozenBob;

    /** Caméra strictement immobile pendant les rafales : un joueur dans l'eau flotte et dérive sinon. */
    private static void freezeCamera(MinecraftClient client) {
        var p = client.player;
        if (frozenPos == null) {
            frozenPos = p.getPos();
            frozenYaw = p.getYaw();
            frozenPitch = p.getPitch();
            frozenBob = client.options.getBobView().getValue();
            client.options.getBobView().setValue(false);
            p.getAbilities().flying = true;
        }
        p.refreshPositionAndAngles(frozenPos.x, frozenPos.y, frozenPos.z, frozenYaw, frozenPitch);
        p.resetPosition();
        p.prevYaw = frozenYaw;
        p.prevPitch = frozenPitch;
        p.setVelocity(0, 0, 0);
    }

    private static void applyFlickerConfig(String name) {
        CONFIG.standaloneEnabled = !name.equals("off");
        CONFIG.brickMask = !name.equals("nobricks");
        CONFIG.standaloneResolutionScale = name.equals("scale1") ? 1 : 2;
        CONFIG.debugView = name.equals("gsronly") ? 2 : 0;
    }

    private static void saveShot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png",
                client.getFramebuffer(), msg -> LOG.info("GSR autoshot : {}", msg.getString()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> debugView(String name, int mode) {
        return ClientCommandManager.literal(name).executes(c -> {
            CONFIG.debugView = mode;
            c.getSource().sendFeedback(Text.literal("GSR : vue de debug « " + name + " »"));
            return 1;
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> brickToggle(String name, boolean on) {
        return ClientCommandManager.literal(name).executes(c -> {
            CONFIG.brickMask = on;
            c.getSource().sendFeedback(Text.literal("GSR : masque de briques " + (on ? "activé" : "désactivé (cellule par cellule)")));
            return 1;
        });
    }

    /** Contenu de /gsr stats (thread client). */
    private static List<String> statsLines() {
        List<String> out = new ArrayList<>();
        boolean iris = IrisCompat.isShaderPackInUse();
        out.add("§6GSR§r — mode " + (iris ? "service (shaderpack Iris)" : CONFIG.standaloneEnabled ? "autonome" : "inactif"));
        if (manager == null) { out.add("Aucun monde chargé"); return out; }
        GaussianChunkManager.Stats s = manager.stats();
        for (int L = 0; L < s.pages().length; L++) {
            out.add(String.format("L%d (voxel %d) : %d/%d pages, %d non vides, %,d splats",
                    L, 1 << L, s.pages()[L], s.slotsPerLevel(), s.nonEmptyPages()[L], s.splats()[L]));
        }
        out.add(String.format("Files : captures %d, grossières %d, workers %d, prêtes %d ; résumés %,d ; uploads %,d",
                s.captureQueue(), s.coarsePending(), s.inFlight(), s.readyQueue(), s.summaries(), s.totalUploads()));
        RENDERER.appendStats(out, CONFIG, standaloneActive());
        return out;
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
