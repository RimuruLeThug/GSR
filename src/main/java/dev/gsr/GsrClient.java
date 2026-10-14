package dev.gsr;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.gsr.compat.IrisCompat;
import dev.gsr.core.GaussianChunkManager;
import dev.gsr.core.GsrConfig;
import dev.gsr.core.GsrLayout;
import dev.gsr.core.GsrPreset;
import dev.gsr.mc.BlockMaterialTable;
import dev.gsr.mc.McSnapshotSource;
import dev.gsr.render.GsrGpuInfo;
import dev.gsr.render.GsrRenderer;
import dev.gsr.ui.GsrKeys;
import dev.gsr.ui.GsrOptionsScreen;
import dev.gsr.ui.GsrStatsOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourceReloadListenerKeys;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.OptionListWidget;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Fabric entry point: wires world events to the GaussianChunkManager and
 * render events to the GsrRenderer. Everything runs on the client thread (tick + rendering).
 */
public final class GsrClient implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("gsr");

    private static final java.nio.file.Path CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("gsr.json");
    /** No config file before this launch (evaluated before CONFIG, whose loading writes the file). */
    private static final boolean FRESH_CONFIG = !java.nio.file.Files.exists(CONFIG_FILE);
    private static final GsrConfig CONFIG = GsrConfigFile.load(CONFIG_FILE);
    private static final GsrRenderer RENDERER = new GsrRenderer();
    private static BlockMaterialTable materials;
    private static GaussianChunkManager manager;
    private static McSnapshotSource source;
    private static ClientWorld currentWorld;

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(GsrClient::detectGpu);
        ClientTickEvents.END_CLIENT_TICK.register(GsrClient::tick);
        GsrKeys.register();
        ClientTickEvents.END_CLIENT_TICK.register(GsrKeys::tick);
        HudRenderCallback.EVENT.register(GsrStatsOverlay::render);
        ScreenEvents.AFTER_INIT.register(GsrClient::addSettingsButton);
        if (AUTOSHOT != null) ClientTickEvents.END_CLIENT_TICK.register(GsrClient::autoShot);
        if (UI_TEST != null) ClientTickEvents.END_CLIENT_TICK.register(GsrClient::uiTest);

        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            ensureWorld(world);
            buildChunkLoads++;
            lastChunkLoadMs = Util.getMeasuringTimeMs();
            if (manager != null) manager.onChunkLoaded(chunk.getPos().x, chunk.getPos().z,
                    world.getBottomSectionCoord(), world.getTopSectionCoord() - 1);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (manager != null && world == currentWorld) manager.onChunkUnloaded(chunk.getPos().x, chunk.getPos().z,
                    world.getBottomSectionCoord(), world.getTopSectionCoord() - 1);
        });

        WorldRenderEvents.START.register(ctx -> {
            if (manager != null) manager.frameWork(CONFIG.frameCaptureBudgetMs, Util.getMeasuringTimeMs());   // sections still waiting (world entry, teleport)
            if (manager != null && RENDERER.available()) RENDERER.beginFrame(ctx, manager, CONFIG);
        });
        // Before the translucent layer (checked in WorldRenderer#render 1.21.1: debug rendering precedes it).
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
                        // /gsr reload : re-reads config/gsr.json (structural fields: on restart)
                        .then(ClientCommandManager.literal("reload").executes(c -> {
                            boolean restart = CONFIG.copyRuntimeFrom(GsrConfigFile.load(CONFIG_FILE));
                            c.getSource().sendFeedback(Text.literal("GSR: configuration reloaded"
                                    + (restart ? " (levels, pages, splats/page, bindings or threads: applied at next startup)" : "")));
                            return 1;
                        }))
                        // /gsr scale 1|2 : resolution of the reflection + temporal passes (before/after measurements)
                        .then(ClientCommandManager.literal("scale")
                                .then(ClientCommandManager.argument("factor", IntegerArgumentType.integer(1, GsrConfig.MAX_RESOLUTION_SCALE)).executes(c -> {
                                    CONFIG.standaloneResolutionScale = IntegerArgumentType.getInteger(c, "factor");
                                    c.getSource().sendFeedback(Text.literal("GSR: reflection at 1/" + CONFIG.standaloneResolutionScale + " resolution"));
                                    return 1;
                                })))
                        // /gsr debug normal|ssr|gsr|raw|hit|refl|cost : isolates the reflection source; raw = GSR without Fresnel, hit = T / distance / level in r / g / b, refl = hybrid reflection without Fresnel
                        .then(ClientCommandManager.literal("debug")
                                .then(debugView("normal", 0)).then(debugView("ssr", 1)).then(debugView("gsr", 2))
                                .then(debugView("raw", 3)).then(debugView("hit", 4)).then(debugView("refl", 5)).then(debugView("cost", 6)))
                        // /gsr bricks on|off : brick mask (before/after measurement at the same spot)
                        .then(ClientCommandManager.literal("bricks")
                                .then(brickToggle("on", true)).then(brickToggle("off", false)))));

        // After the models: BlockMaterialTable reads the baked quads and the atlas.
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("gsr", "materials"); }
            @Override public Collection<Identifier> getFabricDependencies() { return List.of(ResourceReloadListenerKeys.MODELS); }
            @Override public void reload(ResourceManager resourceManager) { onResourcesReloaded(); }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> ensureWorld(null)));
        LOG.info("GSR (Gaussian Space Reflections) initialized");
    }

    /**
     * Development: -Dgsr.autoshot=<prefix> takes, 15 s after entering the world, one screenshot per
     * debug view (normal, ssr, gsr, raw, hit) in run/screenshots/<prefix>_<view>.png, with no intervention.
     * The player respawns where they disconnected: the view from a previous session can be found again.
     */
    private static final String AUTOSHOT = System.getProperty("gsr.autoshot");
    private static final String[] AUTOSHOT_VIEWS = {"normal", "ssr", "gsr", "raw", "hit", "refl", "cost"};
    /**
     * Flicker bursts, after the views: per configuration, FLICKER_FRAMES screenshots one tick
     * apart, camera still. "off" (mod disabled) measures the vanilla water animation on its own.
     * Compare with: java tools/FlickerDiff.java run/screenshots/<prefix>_flicker_<config>_*.png
     */
    private static final String[] FLICKER_CONFIGS = System.getProperty("gsr.autoshotBursts", "off,default,nobricks,scale1,gsronly").split(",");
    /**
     * -Dgsr.autoshotMove="dx,dy,dz,dyaw" : camera movement per tick during the bursts (blocks,
     * degrees), to observe the reflection in motion. Bursts then go up to 24 screenshots.
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
     * -Dgsr.autoshotCmds="locate biome minecraft:swamp;time set noon" : commands sent 5 s after
     * entering the world (teleport, time, weather), before the screenshots. The result appears
     * in the log ([CHAT]). Whatever follows a "|" is sent 13 s before the first screenshot (e.g. gsr stats: the chat has faded). "f3t" reloads the resources.
     */
    private static final String AUTOSHOT_CMDS = System.getProperty("gsr.autoshotCmds");

    private static void autoShot(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        client.options.pauseOnLostFocus = false;   // background window: no pause menu (not saved)
        if (client.mouse.isCursorLocked()) client.mouse.unlockCursor();   // the user's mouse no longer turns the camera
        if (AUTOSHOT_CMDS != null && (autoShotTicks == 100 || autoShotTicks == 640)) {
            String[] parts = AUTOSHOT_CMDS.split("\\|", 2);
            String part = autoShotTicks == 100 ? parts[0] : parts.length > 1 ? parts[1] : "";
            for (String cmd : part.split(";")) {
                if (cmd.strip().equals("f3t")) client.reloadResources();   // equivalent of F3+T
                else if (!cmd.isBlank()) client.player.networkHandler.sendChatCommand(cmd.strip());
            }
        }
        // View i at 300 + 60*i ticks; in between, 60 ticks so the temporal history settles.
        int t = ++autoShotTicks - (AUTOSHOT_CMDS != null ? 900 : 300);   // teleport: let it generate
        // Camera frozen from 0.5 s after the late commands (teleport): the user's mouse or keyboard,
        // with the window in the foreground, made it drift between two runs to be compared.
        if (t >= -250 && t < 60 * AUTOSHOT_VIEWS.length) freezeCamera(client, 0);
        if (t < 0) return;
        if (client.currentScreen != null) client.setScreen(null);   // chat or menu open: would hide the water
        if (t < 60 * AUTOSHOT_VIEWS.length) {
            if (t % 60 != 0) return;
            int i = t / 60;
            saveShot(client, AUTOSHOT + "_" + AUTOSHOT_VIEWS[i]);
            if (i == 0) LOG.info("GSR autoshot: camera tp @s {} {} {} {} {}", client.player.getX(), client.player.getY(), client.player.getZ(), client.player.getYaw(), client.player.getPitch());
            if (i == 0) for (String line : statsLines()) LOG.info("GSR autoshot stats: {}", line);
            CONFIG.debugView = (i + 1) % AUTOSHOT_VIEWS.length;   // next view; normal after the last one
            return;
        }
        if (AUTOSHOT_VIDEO != null) { videoTick(client); return; }
        // Bursts: 60 stabilization ticks per configuration, then one screenshot per tick.
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
        // GPU time of each configuration (stabilized for 60 ticks): before/after measurements at the same spot
        if (k == 0) LOG.info("GSR autoshot gpu [{}]: {}", FLICKER_CONFIGS[c], RENDERER.gpuLine());
    }

    /**
     * -Dgsr.autoshotVideo="frames,dyaw" : instead of per-tick bursts, one "video" per configuration:
     * the camera turns by dyaw degrees on EACH FRAME, captured right after the world is rendered (no hand or
     * HUD). Fixed angular step per frame, independent of the real frame rate: reproduces a mouse
     * rotation filmed at very high frequency. Files <prefix>_video_<config>_<n>.png.
     */
    private static final float[] AUTOSHOT_VIDEO = parseMove(System.getProperty("gsr.autoshotVideo"));
    private static int videoConfig, videoWait, videoLeft, videoIndex;
    private static float videoYaw;
    private static String videoName;

    private static void videoTick(MinecraftClient client) {
        if (videoLeft > 0) return;   // video in progress: driven by videoFrame
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

    /** WorldRenderEvents.END: captures the frame that was just rendered, then advances the camera by one step. */
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

    /** Camera strictly still during the bursts: a player in water floats and drifts otherwise. */
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
        int s0 = Math.max(step - 1, 0);   // position of the previous tick: smooth interpolation between ticks
        p.refreshPositionAndAngles(frozenPos.x + m[0] * step, frozenPos.y + m[1] * step, frozenPos.z + m[2] * step,
                frozenYaw + m[3] * step, frozenPitch);
        p.resetPosition();
        p.prevX = frozenPos.x + m[0] * s0; p.prevY = frozenPos.y + m[1] * s0; p.prevZ = frozenPos.z + m[2] * s0;
        p.prevYaw = frozenYaw + m[3] * s0;
        p.prevPitch = frozenPitch;
        p.setVelocity(0, 0, 0);
    }

    private static float baseRoughness = -1f;
    /** Burst configuration: keywords combinable with "+" (e.g. "refl+scale1"). */
    private static void applyFlickerConfig(String name) {
        List<String> w = List.of(name.split("[+]"));
        CONFIG.standaloneEnabled = !w.contains("off");
        CONFIG.brickMask = !w.contains("nobricks");
        CONFIG.standaloneResolutionScale = w.contains("scale1") ? 1 : w.contains("scale3") ? 3 : w.contains("scale4") ? 4 : 2;
        CONFIG.debugView = w.contains("gsronly") ? 2 : w.contains("ssronly") ? 1 : w.contains("cost") ? 6 : w.contains("refl") ? 5 : 0;
        if (baseRoughness < 0f) baseRoughness = CONFIG.standaloneRoughness;
        CONFIG.standaloneRoughness = w.contains("mirror") ? 0f : baseRoughness;
    }

    /**
     * Development: -Dgsr.uiTest=<prefix> (gradle -PgsrUiTest=<prefix>) scripts the settings UI with no
     * intervention: Video Settings scrolled to the GSR entry, the GSR screen (Preset row cycled to Low and back to
     * High), then the keys (F9 overlay, F8 resolution 3 and 4, F7 cost view), one screenshot each in
     * run/screenshots/<prefix>_ui_*.png; the preset after each step is logged ("GSR ui test: ...").
     * Ends with the resolution back to 2 and the debug view off.
     */
    private static final String UI_TEST = System.getProperty("gsr.uiTest");
    private static int uiTicks;

    private static void uiTest(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        client.options.pauseOnLostFocus = false;
        if (client.currentScreen == null && client.mouse.isCursorLocked()) client.mouse.unlockCursor();
        int t = ++uiTicks;
        if (t == 100) for (String cmd : List.of("gamemode spectator", "tp @s 130.13 62.68 113.65 -119.4 15.3")) client.player.networkHandler.sendChatCommand(cmd);
        if (t == 300) client.setScreen(new VideoOptionsScreen(null, client, client.options));
        if (t == 310 && client.currentScreen != null)
            for (var child : client.currentScreen.children()) if (child instanceof OptionListWidget list) list.setScrollAmount(list.getMaxScroll());
        if (t == 330) saveShot(client, UI_TEST + "_ui_video");
        if (t == 340) client.setScreen(new GsrOptionsScreen(null));
        if (t == 370) { saveShot(client, UI_TEST + "_ui_gsr"); logPreset("settings screen"); }
        // Preset row clicked twice (High -> Ultra -> Low: "(restart)" shown), then back to High before closing
        if ((t == 372 || t == 374) && client.currentScreen instanceof GsrOptionsScreen s) s.cyclePreset();
        if (t == 377) { saveShot(client, UI_TEST + "_ui_preset_low"); logPreset("after selecting Low"); }
        if (t == 378 && client.currentScreen instanceof GsrOptionsScreen s) s.cyclePreset();
        if (t == 379 && client.currentScreen instanceof GsrOptionsScreen s) { s.cyclePreset(); logPreset("back to High"); }
        if (t == 380) { client.setScreen(null); press(GLFW.GLFW_KEY_F9); }
        if (t == 420) saveShot(client, UI_TEST + "_ui_overlay");
        if (t == 430 || t == 510 || t == 590 || t == 600) press(GLFW.GLFW_KEY_F8);   // 2 -> 3 -> 4 -> 1 -> 2
        if (t == 500) { saveShot(client, UI_TEST + "_ui_scale3"); logPreset("F8 -> 1/3"); }
        if (t == 580) { saveShot(client, UI_TEST + "_ui_scale4"); logPreset("F8 -> 1/4"); }
        if (t == 595) logPreset("F8 -> 1/1");
        if (t == 605) logPreset("F8 -> 1/2");
        if (t >= 610 && t < 616) press(GLFW.GLFW_KEY_F7);   // normal -> ... -> cost
        if (t == 660) saveShot(client, UI_TEST + "_ui_cost");
        if (t == 670) { press(GLFW.GLFW_KEY_F7); press(GLFW.GLFW_KEY_F9); }
        if (t == 700) {
            saveShot(client, UI_TEST + "_ui_final");
            LOG.info("GSR ui test done: scale {}, debug view {}, overlay {}", CONFIG.standaloneResolutionScale, CONFIG.debugView, CONFIG.statsOverlay);
        }
    }

    private static void logPreset(String step) {
        GsrPreset p = currentPreset();
        LOG.info("GSR ui test: {}: preset {}{} (scale {}, {} splats/page live, {} uploads/frame)", step, p == null ? "Custom" : p,
                presetNeedsRestart() ? " (restart pending)" : "", CONFIG.standaloneResolutionScale, CONFIG.maxSplatsPerPage, CONFIG.uploadsPerFrame);
    }

    private static void press(int glfwKey) { net.minecraft.client.option.KeyBinding.onKeyPressed(net.minecraft.client.util.InputUtil.fromKeyCode(glfwKey, 0)); }

    private static void saveShot(MinecraftClient client, String name) {
        ScreenshotRecorder.saveScreenshot(client.runDirectory, name + ".png",
                client.getFramebuffer(), msg -> LOG.info("GSR autoshot: {}", msg.getString()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> debugView(String name, int mode) {
        return ClientCommandManager.literal(name).executes(c -> {
            CONFIG.debugView = mode;
            c.getSource().sendFeedback(Text.literal("GSR: debug view \"" + name + "\""));
            return 1;
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> brickToggle(String name, boolean on) {
        return ClientCommandManager.literal(name).executes(c -> {
            CONFIG.brickMask = on;
            c.getSource().sendFeedback(Text.literal("GSR: brick mask " + (on ? "enabled" : "disabled (cell by cell)")));
            return 1;
        });
    }

    /** Contents of /gsr stats (client thread). */
    public static List<String> statsLines() {
        List<String> out = new ArrayList<>();
        boolean iris = IrisCompat.isShaderPackInUse();
        out.add("§6GSR§r — mode " + (iris ? "service (Iris shaderpack)" : CONFIG.standaloneEnabled ? "standalone" : "inactive")
                + ", reflection 1/" + CONFIG.standaloneResolutionScale + ", debug view " + GsrConfig.DEBUG_VIEWS[CONFIG.debugView]
                + (CONFIG.brickMask ? "" : ", brick mask off"));
        if (manager == null) { out.add("No world loaded"); return out; }
        GaussianChunkManager.Stats s = manager.stats();
        for (int L = 0; L < s.pages().length; L++) {
            out.add(String.format("L%d (voxel %d): %d/%d pages, %d non-empty, %,d splats",
                    L, 1 << L, s.pages()[L], s.slotsPerLevel(), s.nonEmptyPages()[L], s.splats()[L]));
        }
        out.add(String.format("Queues: captures %d, coarse %d, workers %d, ready %d; summaries %,d; uploads %,d",
                s.captureQueue(), s.coarsePending(), s.inFlight(), s.readyQueue(), s.summaries(), s.totalUploads()));
        out.add(String.format("CPU: tick %.3f ms avg, %.3f ms max; workers %d threads, %,d jobs, %.2f ms/job",
                s.tickMsAvg(), s.tickMsMax(), CONFIG.workerThreads, s.workerJobs(), s.workerJobs() == 0 ? 0.0 : s.workerMs() / s.workerJobs()));
        RENDERER.appendStats(out, CONFIG, standaloneActive());
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && materials != null && currentWorld != null) {
            var pos = mc.player.getBlockPos();
            int grass = currentWorld.getColor(pos, BiomeColors.GRASS_COLOR) & 0xFFFFFF;
            int foliage = currentWorld.getColor(pos, BiomeColors.FOLIAGE_COLOR) & 0xFFFFFF;
            int water = currentWorld.getColor(pos, BiomeColors.WATER_COLOR) & 0xFFFFFF;
            int id = Block.getRawIdFromState(Blocks.GRASS_BLOCK.getDefaultState());
            out.add(String.format("Biome: grass #%06X, foliage #%06X, water #%06X -> grass top #%06X (plains #%06X)",
                    grass, foliage, water, materials.albedo(id, GsrLayout.FACE_UP, grass) & 0xFFFFFF,
                    materials.albedo(id, GsrLayout.FACE_UP) & 0xFFFFFF));
        }
        return out;
    }

    /** Saves the settings changed in game (options screen, keys) to config/gsr.json. */
    public static void saveConfig() { GsrConfigFile.saveRuntime(CONFIG_FILE, CONFIG, nextStartupSplatsPerPage); }

    /**
     * Splats per page of the preset chosen in the settings screen during this session (-1 = none chosen). Structural:
     * the frame data and the SSBO slab size use the live value, so it is only written to the file, for the next startup.
     */
    private static int nextStartupSplatsPerPage = -1;

    /** The live settings as they will be after a restart (pending splats per page included): what the Preset row shows. */
    private static GsrConfig presetView() {
        GsrConfig v = CONFIG.copy();
        if (nextStartupSplatsPerPage > 0) v.maxSplatsPerPage = nextStartupSplatsPerPage;
        return v;
    }

    /** Quality preset matching the current settings, or null ("Custom"). */
    public static GsrPreset currentPreset() { return GsrPreset.current(presetView()); }

    /** True when a preset chosen in game changes the splats per page at the next startup. */
    public static boolean presetNeedsRestart() { return nextStartupSplatsPerPage > 0 && nextStartupSplatsPerPage != CONFIG.maxSplatsPerPage; }

    /** Settings screen: runtime fields of the preset apply now, splats per page at the next startup (saved on close). */
    public static void applyPreset(GsrPreset p) {
        GsrConfig target = presetView();
        p.apply(target);
        CONFIG.copyRuntimeFrom(target);
        nextStartupSplatsPerPage = target.maxSplatsPerPage;
        LOG.info("GSR: preset {} selected (resolution 1/{}, {} uploads/frame, {} splats/page{})", p, CONFIG.standaloneResolutionScale,
                CONFIG.uploadsPerFrame, target.maxSplatsPerPage, presetNeedsRestart() ? " at next startup" : "");
    }

    /**
     * First launch (CLIENT_STARTED: GL context current, no world yet, so the structural maxSplatsPerPage still applies):
     * GPU classified once. The preset is applied only if config/gsr.json did not exist; an existing file keeps its values
     * and only gets the flag.
     */
    private static void detectGpu(MinecraftClient client) {
        if (CONFIG.gpuDetectionDone) return;
        GsrGpuInfo gpu;
        try {
            gpu = GsrGpuInfo.detect();
        } catch (RuntimeException e) {
            LOG.warn("GSR: GPU detection failed ({}), default settings kept", e.toString());
            return;
        }
        GsrPreset p = GsrPreset.classify(gpu.vendor(), gpu.renderer(), gpu.vramMiB());
        String vram = gpu.vramMiB() < 0 ? "VRAM unknown" : gpu.vramMiB() + " MiB" + ("ATI".equals(gpu.vramSource()) ? " free" : "");
        CONFIG.gpuDetectionDone = true;
        if (FRESH_CONFIG) {
            p.apply(CONFIG);
            CONFIG.validate();
            LOG.info("GSR: GPU \"{}\" / \"{}\" ({}) -> preset {}", gpu.vendor(), gpu.renderer(), vram, p);
            if (p == GsrPreset.OFF) LOG.warn("GSR: software OpenGL renderer, reflections disabled (can be enabled in the settings)");
            GsrConfigFile.save(CONFIG_FILE, CONFIG);
        } else {
            LOG.info("GSR: GPU \"{}\" / \"{}\" ({}) -> preset {} suggested; existing {} kept unchanged", gpu.vendor(), gpu.renderer(), vram, p, CONFIG_FILE.getFileName());
            GsrConfigFile.saveRuntime(CONFIG_FILE, CONFIG);
        }
    }

    /**
     * "GSR Reflections..." entry at the end of Video Settings (Fabric screen events, no mixin). Sodium replaces
     * that screen with its own: then a button is added to the top-left corner of the Options screen instead.
     */
    private static void addSettingsButton(MinecraftClient client, Screen screen, int width, int height) {
        Text label = Text.translatable("gsr.options.button");
        if (screen instanceof VideoOptionsScreen) {
            for (var child : screen.children()) {
                if (child instanceof OptionListWidget list) {
                    list.addWidgetEntry(ButtonWidget.builder(label, b -> client.setScreen(new GsrOptionsScreen(screen))).width(310).build(), null);
                    return;
                }
            }
        } else if (screen instanceof OptionsScreen && FabricLoader.getInstance().isModLoaded("sodium")) {
            Screens.getButtons(screen).add(ButtonWidget.builder(label, b -> client.setScreen(new GsrOptionsScreen(screen)))
                    .dimensions(6, 6, 100, 20).build());
        }
    }

    private static boolean standaloneActive() {
        return manager != null && CONFIG.standaloneEnabled && !IrisCompat.isShaderPackInUse();
    }

    /** Time the world was entered, until the first complete clipmap build is logged (-1 = logged). */
    private static long buildStartMs = -1;
    /** Chunks loaded by Minecraft since entering the world, and when the last one arrived (separates MC loading from GSR's own time). */
    private static int buildChunkLoads;
    private static long lastChunkLoadMs;
    /** Development (autoshot runs): build progress logged every second until the clipmap is built. */
    private static final boolean BUILD_TRACE = AUTOSHOT != null;
    private static int buildTraceTicks, buildTicks;
    private static double buildFirstTickMs, buildTickMaxMs;

    private static void tick(MinecraftClient client) {
        ensureWorld(client.world);
        if (manager != null && client.player != null) {
            long t0 = System.nanoTime();
            manager.tick(client.player.getBlockX(), client.player.getBlockY(), client.player.getBlockZ(), Util.getMeasuringTimeMs());
            if (buildStartMs >= 0) {   // first tick (whole clipmap windows enqueued) reported apart from the others
                double ms = (System.nanoTime() - t0) / 1e6;
                if (buildTicks++ == 0) buildFirstTickMs = ms; else buildTickMaxMs = Math.max(buildTickMaxMs, ms);
                if (BUILD_TRACE && ms > 2.0) LOG.info("GSR build trace: slow tick {} ({} ms): {}", buildTicks, String.format("%.3f", ms), manager.progressLine());
            } else if (BUILD_TRACE) {   // after the build: ticks above 0.8 ms, to explain the /gsr stats maximum
                double ms = (System.nanoTime() - t0) / 1e6;
                if (ms > 0.8) LOG.info("GSR trace: tick {} ms after the build (autoshot tick {}): {}", String.format("%.3f", ms), autoShotTicks, manager.progressLine());
            }
            // totalUploads(), not stats(): stats() resets the tick maximum shown by /gsr stats
            if (buildStartMs >= 0 && manager.totalUploads() > 0 && manager.isIdle()) {
                GaussianChunkManager.Stats s = manager.stats();
                long now = Util.getMeasuringTimeMs();
                LOG.info("GSR: clipmap built in {} s ({} jobs, {} ms of worker CPU, {} worker threads; GSR tick: first {} ms, max of the others {} ms; frame captures: first {} ms, max of the others {} ms; {} chunks loaded, last one at {} s)",
                        String.format("%.1f", (now - buildStartMs) / 1000.0), s.workerJobs(), Math.round(s.workerMs()), CONFIG.workerThreads,
                        String.format("%.3f", buildFirstTickMs), String.format("%.3f", buildTickMaxMs), String.format("%.3f", manager.frameCaptureFirstMs()), String.format("%.3f", manager.frameCaptureMsMax()),
                        buildChunkLoads, String.format("%.1f", (lastChunkLoadMs - buildStartMs) / 1000.0));
                if (BUILD_TRACE) LOG.info("GSR build trace: {}", manager.progressLine());
                buildStartMs = -1;
            } else if (BUILD_TRACE && buildStartMs >= 0 && ++buildTraceTicks % 20 == 0) {
                LOG.info("GSR build trace {} s: {} chunks loaded; {}", String.format("%.1f", (Util.getMeasuringTimeMs() - buildStartMs) / 1000.0),
                        buildChunkLoads, manager.progressLine());
            }
        }
    }

    /** World / dimension change: start from scratch (the SSBOs are invalidated by the recentering). */
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
        buildStartMs = Util.getMeasuringTimeMs();
        buildChunkLoads = 0;
        lastChunkLoadMs = buildStartMs;
        buildTraceTicks = 0;
        buildTicks = 0;
        buildFirstTickMs = buildTickMaxMs = 0;
    }

    /**
     * Resource pack changed (F3+T, menu): model colors and tints change. The table is rebuilt
     * then the loaded sections are recaptured; pages update as the captures come in.
     */
    private static void onResourcesReloaded() {
        if (materials == null) return; // first construction: lazy, on entering a world
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
        LOG.info("GSR: resources reloaded, {} columns recaptured", columns);
    }

    // ---- called by ClientWorldMixin (client thread) ----
    public static void onBlockChanged(int x, int y, int z) {
        if (manager != null) manager.onBlockChanged(x, y, z);
    }

    public static void onSectionDirty(int sx, int sy, int sz) {
        if (manager != null) manager.onSectionDirty(sx, sy, sz);
    }

    public static GsrConfig config() { return CONFIG; }
}
