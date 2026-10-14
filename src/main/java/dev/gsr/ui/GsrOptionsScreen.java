package dev.gsr.ui;

import com.mojang.serialization.Codec;
import dev.gsr.GsrClient;
import dev.gsr.core.GsrConfig;
import dev.gsr.core.GsrPreset;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * GSR settings, in the style of the vanilla option screens. Values apply live; they are saved to
 * config/gsr.json when the screen closes (debug view, brick mask and overlay are not persisted).
 * Reached from Video Settings, Mod Menu, or the "Open GSR settings" key.
 *
 * The Preset row is derived from the current values ({@link GsrPreset#current}): "Custom" when they match no preset,
 * so the F8 key and manual edits need no extra state. Choosing a preset or a resolution rebuilds the screen so that
 * the other rows show the new values.
 */
public final class GsrOptionsScreen extends GameOptionsScreen {
    /** Index into {@link GsrPreset#QUALITY}; -1 = Custom (only offered while the values match no preset). */
    private static final int CUSTOM = -1;
    private SimpleOption<Integer> presetOption;

    public GsrOptionsScreen(Screen parent) {
        super(parent, MinecraftClient.getInstance().options, Text.translatable("gsr.options.title"));
    }

    @Override
    protected void addOptions() {
        GsrConfig c = GsrClient.config();
        GsrPreset current = GsrClient.currentPreset();
        List<Integer> presets = IntStream.range(current == null ? CUSTOM : 0, GsrPreset.QUALITY.length).boxed().toList();
        presetOption = cycling("gsr.options.preset", presets, current == null ? CUSTOM : List.of(GsrPreset.QUALITY).indexOf(current),
                GsrOptionsScreen::presetText, v -> {
                    if (v == CUSTOM) return;
                    GsrClient.applyPreset(GsrPreset.QUALITY[v]);
                    refresh();
                });
        body.addSingleOptionEntry(presetOption);
        body.addAll(
                SimpleOption.ofBoolean("gsr.options.enabled", tooltip("gsr.options.enabled"), c.standaloneEnabled,
                        v -> c.standaloneEnabled = v),
                cycling("gsr.options.resolution", IntStream.rangeClosed(1, GsrConfig.MAX_RESOLUTION_SCALE).boxed().toList(),
                        c.standaloneResolutionScale, v -> Text.translatable("gsr.options.resolution." + v),
                        v -> { c.standaloneResolutionScale = v; refresh(); }),
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

    private static Text presetText(int v) {
        if (v == CUSTOM) return Text.translatable("gsr.options.preset.custom");
        Text name = Text.translatable("gsr.options.preset." + GsrPreset.QUALITY[v].name().toLowerCase(Locale.ROOT));
        return GsrClient.presetNeedsRestart() && GsrPreset.QUALITY[v] == GsrClient.currentPreset()
                ? Text.translatable("gsr.options.preset.restart", name) : name;
    }

    /**
     * Shows the new values on the next frame (not inside the click handler that is iterating over the rows). A fresh
     * screen, not clearAndInit(): GameOptionsScreen's layout keeps its first body, so both would be drawn on top of
     * each other (seen in game).
     */
    private void refresh() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> { if (mc.currentScreen == this) mc.setScreen(new GsrOptionsScreen(parent)); });
    }

    /** Development (-Dgsr.uiTest): same effect as clicking the Preset row once. */
    public void cyclePreset() {
        List<Integer> values = IntStream.range(0, GsrPreset.QUALITY.length).boxed().toList();
        int v = presetOption.getValue();
        presetOption.setValue(values.get((values.indexOf(v) + 1) % values.size()));   // Custom -> Low, like the button
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
