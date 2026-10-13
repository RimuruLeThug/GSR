package dev.gsr.ui;

import dev.gsr.GsrClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Util;

import java.util.List;

/**
 * Statistics overlay (F9 or the options screen): the content of /gsr stats in the top-left corner,
 * refreshed 4 times per second. Hidden with the F3 screen and F1.
 */
public final class GsrStatsOverlay {
    private static final long REFRESH_MS = 250;
    private static List<String> lines = List.of();
    private static long lastRefresh;

    private GsrStatsOverlay() {}

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!GsrClient.config().statsOverlay || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
        long now = Util.getMeasuringTimeMs();
        if (now - lastRefresh >= REFRESH_MS) {
            lines = GsrClient.statsLines();
            lastRefresh = now;
        }
        var tr = client.textRenderer;
        int y = 4;
        for (String line : lines) {
            int w = tr.getWidth(line);
            ctx.fill(2, y - 1, 4 + w + 2, y + tr.fontHeight, 0x90000000);
            ctx.drawTextWithShadow(tr, line, 4, y, 0xFFFFFFFF);
            y += tr.fontHeight + 1;
        }
    }
}
