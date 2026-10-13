package dev.gsr.core;

/** Paramètres du système (chargés depuis config/gsr.json par la couche Minecraft). */
public final class GsrConfig {
    /** Nombre de niveaux de clipmap (1..4). Niveau L : voxels de 2^L blocs, pages de 16·2^L blocs. */
    public int levels = 4;
    /**
     * Dimensions de la fenêtre de pages par niveau. Avec 8x4x8 :
     *  L0 couvre 128x64x128 blocs (±64), L1 ±128, L2 ±256, L3 ±512 blocs autour du joueur.
     */
    public int pagesX = 8, pagesY = 4, pagesZ = 8;
    /** Capacité fixe d'une page en splats (slab GPU). 2048 x 32 o = 64 Kio / page. */
    public int maxSplatsPerPage = 2048;

    /** Écart-type tangentiel d'un splat, en fraction de la taille du voxel. */
    public float sigmaTangent = 0.55f;
    /** Écart-type normal (épaisseur du disque gaussien), en fraction du voxel. */
    public float sigmaNormal = 0.1f;
    /** Opacité de base d'un splat opaque. */
    public float baseOpacity = 0.95f;

    public int workerThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    /** Snapshots de sections pris sur le thread principal par tick (coût : copie de PalettedContainer). */
    public int capturesPerTick = 48;
    /** Pages grossières (L>=1) soumises par tick. */
    public int coarseJobsPerTick = 8;
    /** Pages téléversées vers le GPU par frame. */
    public int uploadsPerFrame = 32;
    /** Délai de regroupement (ms) avant reconstruction d'une page L1 ; multiplié par 3 à chaque niveau. */
    public long coarseDebounceMs = 300;

    /** Premier point de liaison SSBO réservé (frame, pageMeta, cells, splats = base..base+3). */
    public int ssboBindingBase = 10;

    // ---- Mode autonome (sans shaderpack Iris) ----
    public boolean standaloneEnabled = true;
    public float standaloneRoughness = 0.08f;
    public float standaloneStrength = 1.0f;
    /**
     * Force des reflets sur les sols OPAQUES orientés vers le haut (herbe, pierre...). L'eau et les
     * translucides sont détectés par comparaison de profondeur et reflètent toujours à pleine force.
     * 0 par défaut : un sol mat en miroir donne un voile laiteux aux angles rasants (Fresnel).
     */
    public float standaloneFloorStrength = 0.0f;
    /**
     * Réduction de résolution des passes reflet + temporel (1 = pleine, 2 = demi-résolution : ÷4 pixels).
     * La composition reste en pleine résolution avec un upsampling guidé par la profondeur.
     */
    public int standaloneResolutionScale = 2;
    /** Distance max (blocs) d'un rayon GSR. */
    public float maxTraceDistance = 384f;

    // ---- Debug (non persistant, /gsr debug et /gsr bricks) ----
    /** 0 = normal, 1 = SSR seul (ciel en repli, sans GSR), 2 = GSR seul (sans SSR). */
    public int debugView = 0;
    /** Faux = parcours cellule par cellule (frame.iparams.y bit 0), pour mesurer le gain du masque. */
    public boolean brickMask = true;

    public long debounceForLevel(int level) {
        long d = coarseDebounceMs;
        for (int i = 1; i < level; i++) d *= 3;
        return d;
    }

    public int slotsPerLevel() { return pagesX * pagesY * pagesZ; }
    public int totalSlots() { return slotsPerLevel() * levels; }

    public GsrConfig validate() {
        levels = Math.max(1, Math.min(GsrLayout.MAX_LEVELS, levels));
        standaloneResolutionScale = Math.max(1, Math.min(2, standaloneResolutionScale));
        if (maxSplatsPerPage > 0xFFFF) throw new IllegalArgumentException("maxSplatsPerPage > 65535 (offset 16 bits)");
        return this;
    }
}
