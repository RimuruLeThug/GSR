package dev.gsr;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.gsr.compat.IrisCompat;
import dev.gsr.core.GaussianChunkManager;
import dev.gsr.core.GsrConfig;
import dev.gsr.core.GsrLayout;
import dev.gsr.mc.BlockMaterialTable;
import dev.gsr.mc.McSnapshotSource;
import dev.gsr.render.GsrRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourceReloadListenerKeys;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Point d'entrée Fabric : câble les événements du monde vers le GaussianChunkManager et les
 * événements de rendu vers le GsrRenderer. Tout s'exécute sur le thread client (tick + rendu).
 */
public final class GsrClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("gsr");

    private static final java.nio.file.Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("gsr.json");
    private static final GsrConfig CONFIG = GsrConfigFile.load(CONFIG_FILE);
    private static final GsrRenderer RENDERER = new GsrRenderer();
    private static BlockMaterialTable materials;
    private static GaussianChunkManager manager;
    private static McSnapshotSource source;
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
        if (AUTOSHOT != null) WorldRenderEvents.END.register(ctx -> videoFrame(MinecraftClient.getInstance()));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("gsr")
                        .then(ClientCommandManager.literal("stats").executes(c -> {
                            for (String line : statsLines()) c.getSource().sendFeedback(Text.literal(line));
                            return 1;
                        }))
                        // /gsr reload : relit config/gsr.json (champs structurels : au redémarrage)
                        .then(ClientCommandManager.literal("reload").executes(c -> {
                            boolean restart = CONFIG.copyRuntimeFrom(GsrConfigFile.load(CONFIG_FILE));
                            c.getSource().sendFeedback(Text.literal("GSR : configuration rechargée"
                                    + (restart ? " (niveaux, pages, splats/page, liaisons ou threads : appliqués au prochain démarrage)" : "")));
                            return 1;
                        }))
                        // /gsr scale 1|2 : résolution des passes reflet + temporel (mesures avant/après)
                        .then(ClientCommandManager.literal("scale")
                                .then(ClientCommandManager.argument("factor", IntegerArgumentType.integer(1, 2)).executes(c -> {
                                    CONFIG.standaloneResolutionScale = IntegerArgumentType.getInteger(c, "factor");
                                    c.getSource().sendFeedback(Text.literal("GSR : reflet en 1/" + CONFIG.standaloneResolutionScale + " de résolution"));
                                    return 1;
                                })))
                        // /gsr debug normal|ssr|gsr|raw|hit|refl : isole la source du reflet ; raw = GSR sans Fresnel, hit = T / distance / niveau en r / g / b, refl = reflet hybride sans Fresnel
                        .then(ClientCommandManager.literal("debug")
                                .then(debugView("normal", 0)).then(debugView("ssr", 1)).then(debugView("gsr", 2))
                                .then(debugView("raw", 3)).then(debugView("hit", 4)).then(debugView("refl", 5)))
                        // /gsr bricks on|off : masque de briques (mesure avant/après au même endroit)
                        .then(ClientCommandManager.literal("bricks")
                                .then(brickToggle("on", true)).then(brickToggle("off", false)))));

        // Après les modèles : BlockMaterialTable lit les quads cuits et l'atlas.
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("gsr", "materials"); }
            @Override public Collection<Identifier> getFabricDependencies() { return List.of(ResourceReloadListenerKeys.MODELS); }
            @Override public void reload(ResourceManager resourceManager) { onResourcesReloaded(); }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> ensureWorld(null)));
        LOG.info("GSR (Gaussian Space Reflections) initialisé");
    }

    /**
     * Développement : -Dgsr.autoshot=<préfixe> prend, 15 s après l'entrée dans le monde, une capture par
     * vue de debug (normal, ssr, gsr, raw, hit) dans run/screenshots/<préfixe>_<vue>.png, sans intervention.
     * Le joueur réapparaît où il s'est déconnecté : on retrouve la vue d'une session précédente.
     */
    private static final String AUTOSHOT = System.getProperty("gsr.autoshot");
    private static final String[] AUTOSHOT_VIEWS = {"normal", "ssr", "gsr", "raw", "hit"};
    /**
     * Rafales de scintillement, après les vues : par configuration, FLICKER_FRAMES captures à un tick
     * d'intervalle, caméra immobile. « off » (mod coupé) mesure l'animation propre de l'eau vanilla.
     * Comparer avec : java tools/FlickerDiff.java run/screenshots/<préfixe>_flicker_<config>_*.png
     */
    private static final String[] FLICKER_CONFIGS = System.getProperty("gsr.autoshotBursts", "off,default,nobricks,scale1,gsronly").split(",");
    /**
     * -Dgsr.autoshotMove="dx,dy,dz,dyaw" : déplacement de la caméra par tick pendant les rafales (blocs,
     * degrés), pour observer le reflet en mouvement. Les rafales passent alors à 24 captures.
     */
    private static final float[] AUTOSHOT_MOVE = parseMove(System.getProperty("gsr.autoshotMove"));
    private static final int FLICKER_FRAMES = AUTOSHOT_MOVE != null ? 24 : 8;

    private static float[] parseMove(String s) {
        if (s == null || s.isBlank()) return null;
        String[] p = s.split(",");
        float[] m = new float[4];
        for (int i = 0; i < Math.min(4, p.length); i++) m[i] = Float.parseFloat(p[i].strip());
        return m;
    }
    private static int autoShotTicks;

    /**
     * -Dgsr.autoshotCmds="locate biome minecraft:swamp;time set noon" : commandes envoyées 5 s après
     * l'entrée dans le monde (téléportation, heure, météo), avant les captures. Le résultat apparaît
     * dans le log ([CHAT]). Ce qui suit un « | » est envoyé 13 s avant la première capture (ex. gsr stats : le chat a disparu). « f3t » recharge les ressources.
     */
    private static final String AUTOSHOT_CMDS = System.getProperty("gsr.autoshotCmds");

    private static void autoShot(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        client.options.pauseOnLostFocus = false;   // fenêtre en arrière-plan : pas de menu pause (non sauvegardé)
        if (AUTOSHOT_CMDS != null && (autoShotTicks == 100 || autoShotTicks == 640)) {
            String[] parts = AUTOSHOT_CMDS.split("\\|", 2);
            String part = autoShotTicks == 100 ? parts[0] : parts.length > 1 ? parts[1] : "";
            for (String cmd : part.split(";")) {
                if (cmd.strip().equals("f3t")) client.reloadResources();   // équivalent de F3+T
                else if (!cmd.isBlank()) client.player.networkHandler.sendChatCommand(cmd.strip());
            }
        }
        // Vue i à 300 + 60·i ticks ; entre deux, 60 ticks pour que l'historique temporel se stabilise.
        int t = ++autoShotTicks - (AUTOSHOT_CMDS != null ? 900 : 300);   // téléportation : laisser générer
        if (t < 0) return;
        if (client.currentScreen != null) client.setScreen(null);   // chat ou menu ouvert : masquerait l'eau
        if (t < 60 * AUTOSHOT_VIEWS.length) {
            if (t % 60 != 0) return;
            int i = t / 60;
            saveShot(client, AUTOSHOT + "_" + AUTOSHOT_VIEWS[i]);
            CONFIG.debugView = (i + 1) % AUTOSHOT_VIEWS.length;   // vue suivante ; normal après la dernière
            return;
        }
        if (AUTOSHOT_VIDEO != null) { videoTick(client); return; }
        // Rafales : 60 ticks de stabilisation par configuration, puis une capture par tick.
        t -= 60 * AUTOSHOT_VIEWS.length;
        int c = t / (60 + FLICKER_FRAMES), k = t % (60 + FLICKER_FRAMES) - 60;
        if (c >= FLICKER_CONFIGS.length) {
            if (k == -60) applyFlickerConfig("default");
            if (frozenPos != null) client.options.getBobView().setValue(frozenBob);
            frozenPos = null;
            return;
        }
        freezeCamera(client, Math.max(k, 0));
        if (k == -60) applyFlickerConfig(FLICKER_CONFIGS[c]);
        if (k >= 0) saveShot(client, AUTOSHOT + "_flicker_" + FLICKER_CONFIGS[c] + "_" + k);
    }

    /**
     * -Dgsr.autoshotVideo="frames,dyaw" : à la place des rafales par tick, une « vidéo » par configuration :
     * la caméra tourne de dyaw degrés à CHAQUE FRAME, capturée juste après le rendu du monde (sans main ni
     * HUD). Pas angulaire fixe par frame, indépendant de la cadence réelle : reproduit une rotation à la
     * souris filmée à très haute fréquence. Fichiers <préfixe>_video_<config>_<n>.png.
     */
    private static final float[] AUTOSHOT_VIDEO = parseMove(System.getProperty("gsr.autoshotVideo"));
    private static int videoConfig, videoWait, videoLeft, videoIndex;
    private static float videoYaw;
    private static String videoName;

    private static void videoTick(MinecraftClient client) {
        if (videoLeft > 0) return;   // vidéo en cours : pilotée par videoFrame
        if (videoConfig > FLICKER_CONFIGS.length) return;
        if (videoConfig == FLICKER_CONFIGS.length) {
            applyFlickerConfig("default");
            if (frozenPos != null) client.options.getBobView().setValue(frozenBob);
            frozenPos = null;
            videoConfig++;
            return;
        }
        freezeCamera(client, 0);
        if (videoWait == 0) applyFlickerConfig(FLICKER_CONFIGS[videoConfig]);
        if (++videoWait < 60) return;
        videoWait = 0;
        videoName = AUTOSHOT + "_video_" + FLICKER_CONFIGS[videoConfig++];
        videoIndex = 0;
        videoYaw = frozenYaw;
        videoLeft = (int) AUTOSHOT_VIDEO[0];
    }

    /** WorldRenderEvents.END : capture la frame qui vient d'être rendue, puis avance la caméra d'un pas. */
    private static void videoFrame(MinecraftClient client) {
        if (videoLeft <= 0 || client.player == null) return;
        saveShot(client, videoName + "_" + videoIndex++);
        videoYaw += AUTOSHOT_VIDEO[1];
        if (--videoLeft == 0) videoYaw = frozenYaw;
        client.player.setYaw(videoYaw);
        client.player.prevYaw = videoYaw;
    }

    private static Vec3d frozenPos;
    private static float frozenYaw, frozenPitch;
    private static boolean frozenBob;

    /** Caméra strictement immobile pendant les rafales : un joueur dans l'eau flotte et dérive sinon. */
    private static void freezeCamera(MinecraftClient client, int step) {
        var p = client.player;
        if (frozenPos == null) {
            frozenPos = p.getPos();
            frozenYaw = p.getYaw();
            frozenPitch = p.getPitch();
            frozenBob = client.options.getBobView().getValue();
            client.options.getBobView().setValue(false);
            p.getAbilities().flying = true;
        }
        float[] m = AUTOSHOT_MOVE != null ? AUTOSHOT_MOVE : new float[4];
        int s0 = Math.max(step - 1, 0);   // position du tick précédent : interpolation fluide entre ticks
        p.refreshPositionAndAngles(frozenPos.x + m[0] * step, frozenPos.y + m[1] * step, frozenPos.z + m[2] * step,
                frozenYaw + m[3] * step, frozenPitch);
        p.resetPosition();
        p.prevX = frozenPos.x + m[0] * s0; p.prevY = frozenPos.y + m[1] * s0; p.prevZ = frozenPos.z + m[2] * s0;
        p.prevYaw = frozenYaw + m[3] * s0;
        p.prevPitch = frozenPitch;
        p.setVelocity(0, 0, 0);
    }

    private static float baseRoughness = -1f;
    /** Configuration de rafale : mots-clés combinables par « + » (ex. « refl+scale1 »). */
    private static void applyFlickerConfig(String name) {
        List<String> w = List.of(name.split("[+]"));
        CONFIG.standaloneEnabled = !w.contains("off");
        CONFIG.brickMask = !w.contains("nobricks");
        CONFIG.standaloneResolutionScale = w.contains("scale1") ? 1 : 2;
        CONFIG.debugView = w.contains("gsronly") ? 2 : w.contains("refl") ? 5 : 0;
        if (baseRoughness < 0f) baseRoughness = CONFIG.standaloneRoughness;
        CONFIG.standaloneRoughness = w.contains("mirror") ? 0f : baseRoughness;
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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && materials != null && currentWorld != null) {
            var pos = mc.player.getBlockPos();
            int grass = currentWorld.getColor(pos, BiomeColors.GRASS_COLOR) & 0xFFFFFF;
            int foliage = currentWorld.getColor(pos, BiomeColors.FOLIAGE_COLOR) & 0xFFFFFF;
            int water = currentWorld.getColor(pos, BiomeColors.WATER_COLOR) & 0xFFFFFF;
            int id = Block.getRawIdFromState(Blocks.GRASS_BLOCK.getDefaultState());
            out.add(String.format("Biome : herbe #%06X, feuillage #%06X, eau #%06X -> dessus d'herbe #%06X (plaine #%06X)",
                    grass, foliage, water, materials.albedo(id, GsrLayout.FACE_UP, grass) & 0xFFFFFF,
                    materials.albedo(id, GsrLayout.FACE_UP) & 0xFFFFFF));
        }
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
        source = new McSnapshotSource(world, materials);
        manager = new GaussianChunkManager(CONFIG, source);
    }

    /**
     * Pack de ressources changé (F3+T, menu) : couleurs des modèles et teintes changent. On reconstruit la
     * table puis on recapture les sections chargées ; les pages se mettent à jour au fil des captures.
     */
    private static void onResourcesReloaded() {
        if (materials == null) return; // première construction : paresseuse, à l'entrée dans un monde
        materials = BlockMaterialTable.build();
        MinecraftClient client = MinecraftClient.getInstance();
        if (manager == null || client.player == null) return;
        source.setMaterials(materials);
        int pcx = client.player.getBlockX() >> 4, pcz = client.player.getBlockZ() >> 4;
        int r = client.options.getClampedViewDistance() + 1;
        int minSy = currentWorld.getBottomSectionCoord(), maxSy = currentWorld.getTopSectionCoord() - 1;
        int columns = 0;
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++)
                if (currentWorld.getChunkManager().isChunkLoaded(cx, cz)) {
                    manager.onChunkLoaded(cx, cz, minSy, maxSy);
                    columns++;
                }
        LOG.info("GSR : ressources rechargées, {} colonnes recapturées", columns);
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
