package dev.gsr.ui;

import dev.gsr.GsrClient;
import dev.gsr.compat.IrisCompat;
import dev.gsr.core.GsrConfig;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;

/**
 * GSR keys (rebindable in Options > Controls > Key Binds, "GSR Reflections" category).
 * F6 on/off, F7 debug view (Shift: previous), F8 reflection resolution (Shift: previous), F9 statistics
 * overlay; "Open GSR settings" is unbound by default. Feedback in the action bar.
 */
public final class GsrKeys {
    private static final String CATEGORY = "key.categories.gsr";
    private static KeyBinding toggle, debugView, resolution, overlay, settings;

    private GsrKeys() {}

    public static void register() {
        toggle = key("key.gsr.toggle", GLFW.GLFW_KEY_F6);
        debugView = key("key.gsr.debug_view", GLFW.GLFW_KEY_F7);
        resolution = key("key.gsr.resolution", GLFW.GLFW_KEY_F8);
        overlay = key("key.gsr.overlay", GLFW.GLFW_KEY_F9);
        settings = key("key.gsr.settings", GLFW.GLFW_KEY_UNKNOWN);
    }

    private static KeyBinding key(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(name, InputUtil.Type.KEYSYM, code, CATEGORY));
    }

    /** END_CLIENT_TICK: keys only register when no screen is open. */
    public static void tick(MinecraftClient client) {
        GsrConfig c = GsrClient.config();
        int step = Screen.hasShiftDown() ? -1 : 1;
        while (toggle.wasPressed()) {
            c.standaloneEnabled = !c.standaloneEnabled;
            GsrClient.saveConfig();
            show(client, Text.translatable("gsr.msg.enabled", ScreenTexts.onOrOff(c.standaloneEnabled)));
        }
        while (debugView.wasPressed()) {
            c.debugView = Math.floorMod(c.debugView + step, GsrConfig.DEBUG_VIEWS.length);
            show(client, Text.translatable("gsr.msg.debug_view", Text.translatable("gsr.debug_view." + GsrConfig.DEBUG_VIEWS[c.debugView])));
        }
        while (resolution.wasPressed()) {
            c.standaloneResolutionScale = Math.floorMod(c.standaloneResolutionScale - 1 + step, GsrConfig.MAX_RESOLUTION_SCALE) + 1;
            GsrClient.saveConfig();
            show(client, Text.translatable("gsr.msg.resolution", Text.translatable("gsr.options.resolution." + c.standaloneResolutionScale)));
        }
        while (overlay.wasPressed()) {
            c.statsOverlay = !c.statsOverlay;
            show(client, Text.translatable("gsr.msg.overlay", ScreenTexts.onOrOff(c.statsOverlay)));
        }
        while (settings.wasPressed()) client.setScreen(new GsrOptionsScreen(null));
    }

    private static void show(MinecraftClient client, Text msg) {
        Text out = Text.literal("GSR: ").formatted(Formatting.GOLD).append(msg.copy().formatted(Formatting.WHITE));
        // A shaderpack drives the reflections itself (service mode): the standalone settings have no visible effect.
        if (IrisCompat.isShaderPackInUse()) out = out.copy().append(Text.translatable("gsr.msg.shaderpack").formatted(Formatting.GRAY));
        client.inGameHud.setOverlayMessage(out, false);
    }
}
