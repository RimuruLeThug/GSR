package dev.gsr.core;

/** System parameters (loaded from config/gsr.json by the Minecraft layer). */
public final class GsrConfig {
    /** Number of clipmap levels (1..4). Level L: voxels of 2^L blocks, pages of 16*2^L blocks. */
    public int levels = 4;
    /**
     * Dimensions of the page window per level. With 8x4x8:
     *  L0 covers 128x64x128 blocks (+/-64), L1 +/-128, L2 +/-256, L3 +/-512 blocks around the player.
     */
    public int pagesX = 8, pagesY = 4, pagesZ = 8;
    /** Fixed capacity of a page in splats (GPU slab). 2048 x 32 B = 64 KiB / page. */
    public int maxSplatsPerPage = 2048;

    /** Tangential standard deviation of a splat, as a fraction of the voxel size. */
    public float sigmaTangent = 0.55f;
    /** Normal standard deviation (thickness of the Gaussian disc), as a fraction of the voxel. */
    public float sigmaNormal = 0.1f;
    /** Base opacity of an opaque splat. */
    public float baseOpacity = 1.0f;   // surfels cut to their face: a single one covers a point, 0.95 let 5% of sky through

    public int workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    /** Section snapshots taken on the main thread per tick (cost: PalettedContainer copy). */
    public int capturesPerTick = 48;
    /** Coarse pages (L>=1) submitted per tick. */
    public int coarseJobsPerTick = 8;
    /** Pages uploaded to the GPU per frame. */
    public int uploadsPerFrame = 32;
    /** Debounce delay (ms) before rebuilding an L1 page; multiplied by 3 at each level. */
    public long coarseDebounceMs = 300;

    /** First reserved SSBO binding point (frame, pageMeta, cells, splats = base..base+3). */
    public int ssboBindingBase = 10;

    // ---- Standalone mode (without an Iris shaderpack) ----
    public boolean standaloneEnabled = true;
    /** 0 = mirror (calm water). Above 0.03: GGX directions drawn per frame, which make the edges of rotating reflections "boil". */
    public float standaloneRoughness = 0.0f;
    public float standaloneStrength = 1.0f;
    /**
     * Strength of reflections on OPAQUE upward-facing floors (grass, stone...). Water and
     * translucent surfaces are detected by depth comparison and always reflect at full strength.
     * 0 by default: a matte floor rendered as a mirror gives a milky veil at grazing angles (Fresnel).
     */
    public float standaloneFloorStrength = 0.0f;
    /**
     * Resolution reduction of the reflection + temporal passes (1 = full, 2 = half resolution: 1/4 of the pixels).
     * Compositing stays at full resolution with depth-guided upsampling.
     */
    public int standaloneResolutionScale = 2;
    /** Max distance (blocks) of a GSR ray. */
    public float maxTraceDistance = 384f;

    // ---- Debug (not persisted, /gsr debug and /gsr bricks) ----
    /** 0 = normal, 1 = SSR only (sky fallback, no GSR), 2 = GSR only (no SSR). */
    public transient int debugView = 0;
    /** False = cell-by-cell traversal (frame.iparams.y bit 0), to measure the gain of the mask. */
    public transient boolean brickMask = true;

    public long debounceForLevel(int level) {
        long d = coarseDebounceMs;
        for (int i = 1; i < level; i++) d *= 3;
        return d;
    }

    public int slotsPerLevel() { return pagesX * pagesY * pagesZ; }
    public int totalSlots() { return slotsPerLevel() * levels; }

    public GsrConfig validate() {
        levels = Math.max(1, Math.min(GsrLayout.MAX_LEVELS, levels));
        pagesX = clamp(pagesX, 2, 32); pagesY = clamp(pagesY, 2, 32); pagesZ = clamp(pagesZ, 2, 32);
        if (maxSplatsPerPage > 0xFFFF) throw new IllegalArgumentException("maxSplatsPerPage > 65535 (offset 16 bits)");
        maxSplatsPerPage = Math.max(64, maxSplatsPerPage);
        if (!(sigmaTangent > 0f)) sigmaTangent = 0.55f;
        if (!(sigmaNormal > 0f)) sigmaNormal = 0.1f;
        baseOpacity = clampF(baseOpacity, 0f, 1f);
        workerThreads = clamp(workerThreads, 1, 64);
        capturesPerTick = Math.max(1, capturesPerTick);
        coarseJobsPerTick = Math.max(1, coarseJobsPerTick);
        uploadsPerFrame = Math.max(1, uploadsPerFrame);
        coarseDebounceMs = Math.max(0, coarseDebounceMs);
        ssboBindingBase = clamp(ssboBindingBase, 0, 92);
        standaloneRoughness = clampF(standaloneRoughness, 0f, 1f);
        standaloneStrength = clampF(standaloneStrength, 0f, 4f);
        standaloneFloorStrength = clampF(standaloneFloorStrength, 0f, 4f);
        standaloneResolutionScale = Math.max(1, Math.min(2, standaloneResolutionScale));
        if (!(maxTraceDistance > 0f)) maxTraceDistance = 384f;
        return this;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    /** NaN -> lower bound. */
    private static float clampF(float v, float lo, float hi) { return v >= lo ? Math.min(hi, v) : lo; }

    /**
     * Hot reload: copies the fields that can change during a session. Fields that fix the
     * SSBO size, binding points or threads only change on restart.
     * @return true if one of these structural fields differs (restart needed to apply it)
     */
    public boolean copyRuntimeFrom(GsrConfig o) {
        sigmaTangent = o.sigmaTangent; sigmaNormal = o.sigmaNormal; baseOpacity = o.baseOpacity;
        capturesPerTick = o.capturesPerTick; coarseJobsPerTick = o.coarseJobsPerTick;
        uploadsPerFrame = o.uploadsPerFrame; coarseDebounceMs = o.coarseDebounceMs;
        standaloneEnabled = o.standaloneEnabled; standaloneRoughness = o.standaloneRoughness;
        standaloneStrength = o.standaloneStrength; standaloneFloorStrength = o.standaloneFloorStrength;
        standaloneResolutionScale = o.standaloneResolutionScale; maxTraceDistance = o.maxTraceDistance;
        return levels != o.levels || pagesX != o.pagesX || pagesY != o.pagesY || pagesZ != o.pagesZ
                || maxSplatsPerPage != o.maxSplatsPerPage || ssboBindingBase != o.ssboBindingBase
                || workerThreads != o.workerThreads;
    }
}
