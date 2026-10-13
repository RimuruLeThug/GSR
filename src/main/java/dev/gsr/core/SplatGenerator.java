package dev.gsr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Conversion voxels -> Gaussiennes 3D (surfels gaussiens anisotropes).
 *
 * Pour chaque face EXPOSÉE d'un voxel non vide (voisin = air, ou opaque contre translucide),
 * on émet un splat :
 *  - centre au milieu de la face,
 *  - covariance oblate : σt dans le plan de la face, σn (petit) le long de la normale,
 *    Σ = σt²(I − nnᵀ) + σn² nnᵀ  -> un "disque" gaussien orienté,
 *  - albédo + opacité, lumière (sky/block) prise dans le voxel voisin exposé, émission.
 *
 * Ne garder que les faces exposées revient à n'extraire que la "peau" visible du monde :
 * l'intérieur des montagnes ne coûte rien. Si une page dépasse son budget, on garde en priorité
 * les faces exposées au ciel (surfaces extérieures, les plus utiles en reflet).
 *
 * Le résultat est trié par cellule (2³ voxels) : le shader parcourt la grille de cellules par DDA
 * et n'évalue que les splats de la cellule traversée.
 */
public final class SplatGenerator {
    private SplatGenerator() {}

    public static PageData generate(PageKey key, long version, VoxelGrid grid, GsrConfig cfg) {
        final int N = VoxelGrid.N;
        final float vb = key.voxelBlocks();
        final int cap = N * N * N * 6;
        float[] px = new float[64], py = new float[64], pz = new float[64];
        int[] alb = new int[64], dat = new int[64], cell = new int[64], prio = new int[64];
        int n = 0;

        int[] octs = new int[6];
        for (int d = 0; d < 6; d++) octs[d] = GsrLayout.octEncode(GsrLayout.DIRS[d][0], GsrLayout.DIRS[d][1], GsrLayout.DIRS[d][2]);

        for (int z = 0; z < N; z++)
            for (int y = 0; y < N; y++)
                for (int x = 0; x < N; x++) {
                    int k = grid.kind(x, y, z);
                    if (k == GsrLayout.KIND_AIR) continue;
                    int albedo = grid.albedo(x, y, z);
                    int em = grid.emission(x, y, z);
                    int c = GsrLayout.cellIndex(x >> 1, y >> 1, z >> 1);
                    for (int d = 0; d < 6; d++) {
                        int[] dir = GsrLayout.DIRS[d];
                        int nx = x + dir[0], ny = y + dir[1], nz = z + dir[2];
                        int nk = grid.kind(nx, ny, nz);
                        boolean exposed = nk == GsrLayout.KIND_AIR
                                || (k == GsrLayout.KIND_OPAQUE && nk == GsrLayout.KIND_TRANSLUCENT);
                        if (!exposed) continue;
                        if (n == px.length) {
                            int nc = Math.min(cap, n * 2);
                            px = java.util.Arrays.copyOf(px, nc); py = java.util.Arrays.copyOf(py, nc);
                            pz = java.util.Arrays.copyOf(pz, nc); alb = java.util.Arrays.copyOf(alb, nc);
                            dat = java.util.Arrays.copyOf(dat, nc); cell = java.util.Arrays.copyOf(cell, nc);
                            prio = java.util.Arrays.copyOf(prio, nc);
                        }
                        int lt = grid.light(nx, ny, nz);
                        px[n] = (x + 0.5f + 0.5f * dir[0]) * vb;
                        py[n] = (y + 0.5f + 0.5f * dir[1]) * vb;
                        pz[n] = (z + 0.5f + 0.5f * dir[2]) * vb;
                        alb[n] = albedo;
                        dat[n] = GsrLayout.packSplatData(octs[d], lt, em, key.level());
                        cell[n] = c;
                        prio[n] = (lt >> 4) + (nk == GsrLayout.KIND_AIR ? 16 : 0); // 0..31
                        n++;
                    }
                }

        // ---- Budget : sélection par priorité (seau 0..31, ciel ouvert d'abord) ----
        int[] order;
        int kept;
        if (n > cfg.maxSplatsPerPage) {
            int[] bucket = new int[33];
            for (int i = 0; i < n; i++) bucket[31 - prio[i] + 1]++;
            for (int b = 1; b < 33; b++) bucket[b] += bucket[b - 1];
            int[] sorted = new int[n];
            for (int i = 0; i < n; i++) sorted[bucket[31 - prio[i]]++] = i;
            kept = cfg.maxSplatsPerPage;
            order = java.util.Arrays.copyOf(sorted, kept);
        } else {
            kept = n;
            order = new int[n];
            for (int i = 0; i < n; i++) order[i] = i;
        }

        // ---- Tri par cellule (counting sort) + table de cellules ----
        int[] counts = new int[GsrLayout.CELLS_PER_PAGE];
        for (int j = 0; j < kept; j++) counts[cell[order[j]]]++;
        int[] cells = new int[GsrLayout.CELLS_PER_PAGE];
        int[] cursor = new int[GsrLayout.CELLS_PER_PAGE];
        int off = 0;
        for (int c = 0; c < GsrLayout.CELLS_PER_PAGE; c++) {
            cells[c] = off | (counts[c] << 16);
            cursor[c] = off;
            off += counts[c];
        }

        float sigmaT = cfg.sigmaTangent * vb;
        float sigmaN = cfg.sigmaNormal * vb;
        ByteBuffer buf = ByteBuffer.allocateDirect(kept * GsrLayout.SPLAT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (int j = 0; j < kept; j++) {
            int i = order[j];
            int a = alb[i];
            int opacity = Math.round(((a >>> 24) / 255f) * cfg.baseOpacity * 255f);
            int rgba = (a & 0x00FFFFFF) | (opacity << 24);
            int base = cursor[cell[i]]++ * GsrLayout.SPLAT_BYTES;
            buf.putFloat(base, px[i]);
            buf.putFloat(base + 4, py[i]);
            buf.putFloat(base + 8, pz[i]);
            buf.putFloat(base + 12, sigmaT);
            buf.putInt(base + 16, rgba);          // data.x : 0xAARRGGBB
            buf.putInt(base + 20, dat[i]);        // data.y : normale | lumière | émission | niveau
            buf.putFloat(base + 24, sigmaN);      // data.z : floatBitsToUint(σn)
            buf.putInt(base + 28, 0);             // data.w : réservé (ID matériau, rugosité...)
        }
        return new PageData(key, version, kept, buf, cells);
    }
}
