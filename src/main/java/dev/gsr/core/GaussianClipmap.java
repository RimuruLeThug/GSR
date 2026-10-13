package dev.gsr.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Clipmap gaussienne en cascade (analogue des Cascaded Shadow Maps, mais volumique).
 *
 * Chaque niveau L est une fenêtre de pagesX x pagesY x pagesZ pages centrée sur le joueur,
 * stockée de façon toroïdale : une page de coordonnées p occupe le slot floorMod(p, dims).
 * Quand le joueur se déplace, seules les pages qui ENTRENT dans la fenêtre sont reconstruites ;
 * les autres restent en place dans la VRAM (pas de copie, pas de recentrage des données).
 *
 * Les positions des splats sont stockées relativement à l'origine de leur page : aucune perte de
 * précision flottante même à ±30 000 000 blocs.
 */
public final class GaussianClipmap {
    private final GsrConfig cfg;
    private final int[][] windowMin; // [level][xyz] en coordonnées de page
    private boolean initialized;

    public GaussianClipmap(GsrConfig cfg) {
        this.cfg = cfg;
        this.windowMin = new int[cfg.levels][3];
    }

    public int levels() { return cfg.levels; }
    public int[] windowMin(int level) { return windowMin[level]; }

    /**
     * Recentre toutes les fenêtres sur la position du joueur.
     * @return les pages nouvellement entrées dans une fenêtre (à reconstruire).
     */
    public List<PageKey> update(int bx, int by, int bz) {
        List<PageKey> entered = new ArrayList<>();
        int[] dims = {cfg.pagesX, cfg.pagesY, cfg.pagesZ};
        for (int L = 0; L < cfg.levels; L++) {
            int size = GsrLayout.PAGE_VOXELS << L;
            int[] nm = {
                    Math.floorDiv(bx, size) - dims[0] / 2,
                    Math.floorDiv(by, size) - dims[1] / 2,
                    Math.floorDiv(bz, size) - dims[2] / 2
            };
            int[] om = windowMin[L];
            if (initialized && nm[0] == om[0] && nm[1] == om[1] && nm[2] == om[2]) continue;
            for (int z = nm[2]; z < nm[2] + dims[2]; z++)
                for (int y = nm[1]; y < nm[1] + dims[1]; y++)
                    for (int x = nm[0]; x < nm[0] + dims[0]; x++) {
                        boolean wasInside = initialized
                                && x >= om[0] && x < om[0] + dims[0]
                                && y >= om[1] && y < om[1] + dims[1]
                                && z >= om[2] && z < om[2] + dims[2];
                        if (!wasInside) entered.add(new PageKey(L, x, y, z));
                    }
            windowMin[L] = nm;
        }
        initialized = true;
        return entered;
    }

    public boolean contains(int level, int px, int py, int pz) {
        if (!initialized || level >= cfg.levels) return false;
        int[] m = windowMin[level];
        return px >= m[0] && px < m[0] + cfg.pagesX
                && py >= m[1] && py < m[1] + cfg.pagesY
                && pz >= m[2] && pz < m[2] + cfg.pagesZ;
    }

    public boolean contains(PageKey k) { return contains(k.level(), k.x(), k.y(), k.z()); }

    /** Index global du slot (niveau * slotsParNiveau + slot toroïdal). Miroir de gsrSlotIndex() en GLSL. */
    public int slotIndex(PageKey k) {
        int sx = Math.floorMod(k.x(), cfg.pagesX);
        int sy = Math.floorMod(k.y(), cfg.pagesY);
        int sz = Math.floorMod(k.z(), cfg.pagesZ);
        return k.level() * cfg.slotsPerLevel() + sx + cfg.pagesX * (sy + cfg.pagesY * sz);
    }

    /** Page du niveau L qui contient la section (sx, sy, sz). */
    public static PageKey pageOfSection(int level, int sx, int sy, int sz) {
        return new PageKey(level, sx >> level, sy >> level, sz >> level);
    }

    /** Vrai si la section est couverte par le niveau le plus grossier (on garde son résumé). */
    public boolean sectionInCoverage(int sx, int sy, int sz) {
        int L = cfg.levels - 1;
        return contains(L, sx >> L, sy >> L, sz >> L);
    }
}
