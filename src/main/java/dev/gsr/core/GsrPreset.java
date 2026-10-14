package dev.gsr.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Quality presets and the first-launch GPU classifier (pure: plain strings in, no GL call here).
 *
 * A preset only sets the fields that measurably change the cost on a modest GPU: the reflection resolution
 * (the main lever), the splat slab size (VRAM: 64 -> 32 MiB of SSBO, which matters on shared-memory iGPUs)
 * and the upload budget per frame. {@code maxTraceDistance} and {@code levels} are deliberately not used
 * (measured: -3..6 % only). {@code maxSplatsPerPage} is structural: it only applies at the next startup.
 *
 * {@link #OFF} is not a selectable quality level: it is what the classifier returns for a software renderer
 * (LOW values with standalone mode disabled).
 */
public enum GsrPreset {
    OFF(4, 1024, 16),
    LOW(4, 1024, 16),
    MEDIUM(3, 2048, 32),
    HIGH(2, 2048, 32),     // = the GsrConfig defaults
    ULTRA(1, 2048, 32);

    /** Presets offered in the settings screen, in cycling order. */
    public static final GsrPreset[] QUALITY = {LOW, MEDIUM, HIGH, ULTRA};

    public final int resolutionScale, maxSplatsPerPage, uploadsPerFrame;

    GsrPreset(int resolutionScale, int maxSplatsPerPage, int uploadsPerFrame) {
        this.resolutionScale = resolutionScale;
        this.maxSplatsPerPage = maxSplatsPerPage;
        this.uploadsPerFrame = uploadsPerFrame;
    }

    /** Writes the preset values into {@code c} (OFF also disables standalone mode). */
    public void apply(GsrConfig c) {
        c.standaloneResolutionScale = resolutionScale;
        c.maxSplatsPerPage = maxSplatsPerPage;
        c.uploadsPerFrame = uploadsPerFrame;
        if (this == OFF) c.standaloneEnabled = false;
    }

    /**
     * True when {@code c} holds exactly this preset's values. Quality presets ignore {@code standaloneEnabled}
     * (the on/off key does not turn a preset into "Custom"); OFF requires standalone mode to be disabled.
     */
    public boolean matches(GsrConfig c) {
        return c.standaloneResolutionScale == resolutionScale && c.maxSplatsPerPage == maxSplatsPerPage
                && c.uploadsPerFrame == uploadsPerFrame && (this != OFF || !c.standaloneEnabled);
    }

    /** The quality preset matching {@code c}, or null ("Custom"). */
    public static GsrPreset current(GsrConfig c) {
        for (GsrPreset p : QUALITY) if (p.matches(c)) return p;
        return null;
    }

    private static final Pattern SOFTWARE = Pattern.compile("llvmpipe|softpipe|swiftshader|microsoft basic render|gdi generic");
    /** Intel discrete Arc (A380, A770, B580...): a model number after "Arc". The Meteor Lake iGPU is "Arc(TM) Graphics". */
    private static final Pattern INTEL_ARC_DISCRETE = Pattern.compile("arc(\\(tm\\))?\\s+[ab]\\d{3}");
    /**
     * AMD integrated GPUs: "AMD Radeon(TM) Graphics" / "AMD Radeon Graphics (radeonsi, renoir...)" (no model),
     * "Radeon(TM) Vega 8 Graphics", "Radeon 780M Graphics" / "Radeon(TM) 760M" (3 digits + M right after Radeon;
     * mobile discrete parts are "Radeon RX 6500M" and do not match).
     */
    private static final Pattern AMD_APU = Pattern.compile(
            "radeon(\\(tm\\))?\\s+graphics|vega\\s*\\d+\\s+graphics|radeon(\\(tm\\))?\\s+\\d{3}m\\b");
    /** NVIDIA entry level: GeForce MX (laptops) and GeForce GT (GT 710/730/1030...). */
    private static final Pattern NVIDIA_ENTRY = Pattern.compile("geforce\\s+(mx\\s*\\d+|gt\\s+\\d+)");

    /**
     * Preset for a GPU, from GL_VENDOR, GL_RENDERER and the dedicated video memory in MiB (-1 when unknown).
     * Order: software renderer -> OFF; Intel -> LOW (discrete Arc -> HIGH); AMD APU -> LOW; GeForce MX / GT -> LOW;
     * known VRAM < 1 GiB -> LOW, < 3 GiB -> MEDIUM; everything else -> HIGH (the defaults). ULTRA is never chosen
     * automatically (full resolution costs ~3x half resolution for a small gain).
     */
    public static GsrPreset classify(String vendor, String renderer, long vramMiB) {
        String v = vendor == null ? "" : vendor.toLowerCase(Locale.ROOT);
        String r = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
        if (SOFTWARE.matcher(r).find()) return OFF;
        if (v.contains("intel") || r.contains("intel")) return INTEL_ARC_DISCRETE.matcher(r).find() ? HIGH : LOW;
        if (AMD_APU.matcher(r).find()) return LOW;
        if (NVIDIA_ENTRY.matcher(r).find()) return LOW;
        if (vramMiB >= 0 && vramMiB < 1024) return LOW;
        if (vramMiB >= 0 && vramMiB < 3072) return MEDIUM;
        return HIGH;
    }
}
