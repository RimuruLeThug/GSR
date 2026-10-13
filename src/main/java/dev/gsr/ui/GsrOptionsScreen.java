package dev.gsr.ui;

import com.mojang.serialization.Codec;
import dev.gsr.GsrClient;
import dev.gsr.core.GsrConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * GSR settings, in the style of the vanilla option screens. Values apply live; they are saved to
 * config/gsr.json when the screen closes (debug view, brick mask and overlay are not persisted).
 * Reached from Video Settings, Mod Menu, or the "Open GSR settings" key.
 */
public final class GsrOptionsScreen extends GameOptionsScreen {
    public GsrOptionsScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Text.translatable("gsr.options.title"));
    }

    @Override
    protected void addOptions() {
        GsrConfig c = GsrClient.config();
        body.addAll(
                SimpleOption.ofBoolean("gsr.options.enabled", tooltip("gsr.options.enabled"), c.standaloneEnabled,
                        v -> c.standaloneEnabled = v),
                cycling("gsr.options.resolution", IntStream.rangeClosed(1, GsrConfig.MAX_RESOLUTION_SCALE).boxed().toList(),
                        c.standaloneResolutionScale, v -> Text.translatable("gsr.options.resolution." + v),
                        v -> c.standaloneResolutionScale = v),
                percent("gsr.options.strength", 0, 200, c.standaloneStrength, v -> c.standaloneStrength = v / 100f),
                percent("gsr.options.floor", 0, 200, c.standaloneFloorStrength, v -> c.standaloneFloorStrength = v / 100f),
                percent("gsr.options.roughness", 0, 100, c.standaloneRoughness, v -> c.standaloneRoughness = v / 100f),
                slider("gsr.options.distance", 64, 512, Math.round(c.maxTraceDistance),
                        v -> Text.translatable("gsr.options.blocks", v), v -> c.maxTraceDistance = v));
        body.addAll(
                cycling("gsr.options.debug_view", IntStream.range(0, GsrConfig.DEBUG_VIEWS.length).boxed().toList(),
                        c.debugView, v -> Text.translatable("gsr.debug_view." + GsrConfig.DEBUG_VIEWS[v]), v -> c.debugView = v),
                SimpleOption.ofBoolean("gsr.options.overlay", tooltip("gsr.options.overlay"), c.statsOverlay,
                        v -> c.statsOverlay = v),
                SimpleOption.ofBoolean("gsr.options.brick_mask", tooltip("gsr.options.brick_mask"), c.brickMask,
                        v -> c.brickMask = v));
    }

    @Override
    public void removed() {
        super.removed();
        GsrClient.saveConfig();
    }

    private static <T> SimpleOption.TooltipFactory<T> tooltip(String key) {
        return SimpleOption.constantTooltip(Text.translatable(key + ".tooltip"));
    }

    /** Cycling button: the button shows "name: value", valueText only returns the value. */
    private static SimpleOption<Integer> cycling(String key, List<Integer> values, int initial,
                                                 java.util.function.IntFunction<Text> valueText, IntConsumer apply) {
        return new SimpleOption<>(key, tooltip(key), (prefix, v) -> valueText.apply(v),
                new SimpleOption.PotentialValuesBasedCallbacks<>(values, Codec.INT),
                values.contains(initial) ? initial : values.get(0), apply::accept);
    }

    private static SimpleOption<Integer> percent(String key, int min, int max, float value, IntConsumer apply) {
        return slider(key, min, max, Math.round(value * 100f), v -> Text.translatable("gsr.options.percent", v), apply);
    }

    private static SimpleOption<Integer> slider(String key, int min, int max, int initial,
                                                java.util.function.IntFunction<Text> valueText, IntConsumer apply) {
        return new SimpleOption<>(key, tooltip(key),
                (prefix, v) -> GameOptions.getGenericValueText(prefix, valueText.apply(v)),
                new SimpleOption.ValidatingIntSliderCallbacks(min, max), Math.max(min, Math.min(max, initial)), apply::accept);
    }
}
