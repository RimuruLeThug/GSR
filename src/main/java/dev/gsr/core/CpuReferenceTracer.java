package dev.gsr.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Miroir CPU ligne à ligne de gsrTrace() (gsr_common.glsl), opérant sur les mêmes octets que le GPU.
 * Sert de test de référence (format, adressage toroïdal, maths des gaussiennes) et de commande de
 * debug "/gsr probe". Ne pas optimiser : la valeur est dans la fidélité au shader.
 */
public final class CpuReferenceTracer implements GpuSink {
    /** = GSR_ALPHA_GAIN */
    public static final float ALPHA_GAIN = 1.6f;
    private final GsrConfig cfg;
    private final int[] meta;
    private final int[] cells;
    private final ByteBuffer splats;
    public GsrFrameData frame = new GsrFrameData();
    public int[][] windowMin = new int[GsrLayout.MAX_LEVELS][3];

    public CpuReferenceTracer(GsrConfig cfg) {
        this.cfg = cfg;
        int slots = cfg.totalSlots();
        meta = new int[slots * 8]; // = GsrPageMeta : ivec4 page + uvec4 mask
        for (int i = 0; i < slots; i++) meta[i * 8] = GsrLayout.INVALID_PAGE_COORD;
        cells = new int[slots * GsrLayout.CELLS_PER_PAGE];
        splats = ByteBuffer.allocate(slots * cfg.maxSplatsPerPage * GsrLayout.SPLAT_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    }

    // ---------------------------------------------------------------- GpuSink (même layout que le GPU)
    @Override public void upload(int slot, PageData p) {
        System.arraycopy(p.cells(), 0, cells, slot * GsrLayout.CELLS_PER_PAGE, GsrLayout.CELLS_PER_PAGE);
        int base = slot * cfg.maxSplatsPerPage * GsrLayout.SPLAT_BYTES;
        for (int i = 0; i < p.splatCount() * GsrLayout.SPLAT_BYTES; i++) splats.put(base + i, p.splats().get(i));
        meta[slot * 8] = p.key().x(); meta[slot * 8 + 1] = p.key().y(); meta[slot * 8 + 2] = p.key().z();
        meta[slot * 8 + 3] = p.splatCount();
        meta[slot * 8 + 4] = (int) p.brickMask(); meta[slot * 8 + 5] = (int) (p.brickMask() >>> 32);
    }
    @Override public void invalidate(int slot) {
        meta[slot * 8] = GsrLayout.INVALID_PAGE_COORD; meta[slot * 8 + 3] = 0;
        meta[slot * 8 + 4] = 0; meta[slot * 8 + 5] = 0;
    }

    /** Splats du slot selon la méta « GPU », −1 si le slot est invalide (tests). */
    int metaSplats(int slot) {
        return meta[slot * 8] == GsrLayout.INVALID_PAGE_COORD ? -1 : meta[slot * 8 + 3];
    }

    public void syncFrom(GaussianClipmap clip) {
        for (int L = 0; L < cfg.levels; L++) windowMin[L] = clip.windowMin(L).clone();
    }

    // ---------------------------------------------------------------- miroir GLSL
    public static final class Hit { public float r, g, b, transmittance = 1f, hitDist; public int level = -1, steps; }

    private float relX(int b) { return (float) (b - frame.camBx) - frame.camFx; }
    private float relY(int b) { return (float) (b - frame.camBy) - frame.camFy; }
    private float relZ(int b) { return (float) (b - frame.camBz) - frame.camFz; }
    private static float safeInv(float d) { return 1f / (d >= 0 ? Math.max(d, 1e-6f) : Math.min(d, -1e-6f)); }

    private int slotIndex(int L, int px, int py, int pz) {
        int sx = Math.floorMod(px, cfg.pagesX), sy = Math.floorMod(py, cfg.pagesY), sz = Math.floorMod(pz, cfg.pagesZ);
        return L * cfg.slotsPerLevel() + sx + cfg.pagesX * (sy + cfg.pagesY * sz);
    }

    public Hit trace(float ox, float oy, float oz, float dx, float dy, float dz, float tMin, float tMax, float coneTan, float coneBase) {
        Hit h = new Hit();
        h.hitDist = tMax;
        int levels = cfg.levels;
        float ix = safeInv(dx), iy = safeInv(dy), iz = safeInv(dz);
        float hitW = 0, hitAcc = 0;
        float t = tMin;
        for (int step = 0; step < 160 && t < tMax; step++) {
            h.steps = step + 1;
            float px = ox + dx * t, py = oy + dy * t, pz = oz + dz * t;
            float fp = coneBase + coneTan * t;
            int L = Math.max(0, Math.min(levels - 1, (int) Math.ceil(Math.log(Math.max(fp, 1f)) / Math.log(2))));
            int bx = frame.camBx + (int) Math.floor(px + frame.camFx);
            int by = frame.camBy + (int) Math.floor(py + frame.camFy);
            int bz = frame.camBz + (int) Math.floor(pz + frame.camFz);
            int vx = 0, vy = 0, vz = 0, pgx = 0, pgy = 0, pgz = 0;
            boolean covered = false;
            for (; L < levels; L++) {
                vx = bx >> L; vy = by >> L; vz = bz >> L;
                pgx = vx >> 4; pgy = vy >> 4; pgz = vz >> 4;
                int rx = pgx - windowMin[L][0], ry = pgy - windowMin[L][1], rz = pgz - windowMin[L][2];
                if (rx >= 0 && ry >= 0 && rz >= 0 && rx < cfg.pagesX && ry < cfg.pagesY && rz < cfg.pagesZ) { covered = true; break; }
            }
            if (!covered) break;

            int slot = slotIndex(L, pgx, pgy, pgz);
            int vs = 1 << L;
            boolean valid = meta[slot * 8] == pgx && meta[slot * 8 + 1] == pgy && meta[slot * 8 + 2] == pgz && meta[slot * 8 + 3] > 0;
            int cx = (vx & 15) >> 1, cy = (vy & 15) >> 1, cz = (vz & 15) >> 1;
            int brx = cx >> 1, bry = cy >> 1, brz = cz >> 1;
            int bi = brx + 4 * (bry + 4 * brz);
            int bits = bi < 32 ? meta[slot * 8 + 4] : meta[slot * 8 + 5];
            boolean occupied = valid && ((bits >>> (bi & 31)) & 1) != 0;
            int mx, my, mz; float size;
            if (occupied) { mx = (pgx * 16 + cx * 2) * vs; my = (pgy * 16 + cy * 2) * vs; mz = (pgz * 16 + cz * 2) * vs; size = 2 * vs; }
            else if (valid) { mx = (pgx * 16 + brx * 4) * vs; my = (pgy * 16 + bry * 4) * vs; mz = (pgz * 16 + brz * 4) * vs; size = 4 * vs; }
            else { mx = pgx * 16 * vs; my = pgy * 16 * vs; mz = pgz * 16 * vs; size = 16 * vs; }
            float bminX = relX(mx), bminY = relY(my), bminZ = relZ(mz);
            float tfx = Math.max((bminX - ox) * ix, (bminX + size - ox) * ix);
            float tfy = Math.max((bminY - oy) * iy, (bminY + size - oy) * iy);
            float tfz = Math.max((bminZ - oz) * iz, (bminZ + size - oz) * iz);
            float tExit = Math.min(Math.min(tfx, tfy), tfz);

            if (occupied) {
                int c = cells[slot * 512 + cx + 8 * (cy + 8 * cz)];
                int off = c & 0xFFFF, count = c >>> 16;
                if (count > 0) {
                    float pox = relX(pgx * 16 * vs), poy = relY(pgy * 16 * vs), poz = relZ(pgz * 16 * vs);
                    float sumA = 0, keep = 1, sumT = 0, sr = 0, sg = 0, sb = 0;
                    int base = slot * cfg.maxSplatsPerPage + off;
                    for (int k = 0; k < count; k++) {
                        int o8 = (base + k) * GsrLayout.SPLAT_BYTES;
                        float sx = splats.getFloat(o8), sy = splats.getFloat(o8 + 4), sz = splats.getFloat(o8 + 8);
                        float st = splats.getFloat(o8 + 12);
                        int rgba = splats.getInt(o8 + 16), data = splats.getInt(o8 + 20);
                        float sn = splats.getFloat(o8 + 24);
                        float[] n = GsrLayout.octDecode(data & 0xFFFF);
                        float dn = dx * n[0] + dy * n[1] + dz * n[2];
                        if (dn >= 0) continue;
                        float ovx = ox - (pox + sx), ovy = oy - (poy + sy), ovz = oz - (poz + sz);
                        float a = 1f / (st * st), b = 1f / (sn * sn) - a;
                        float on = ovx * n[0] + ovy * n[1] + ovz * n[2];
                        float dSd = a + b * dn * dn;
                        float dSo = a * (dx * ovx + dy * ovy + dz * ovz) + b * dn * on;
                        float ts = -dSo / dSd;
                        if (ts < tMin || ts < t - 2f * st || ts > tExit + 2f * st) continue;
                        float m2 = a * (ovx * ovx + ovy * ovy + ovz * ovz) + b * on * on - dSo * dSo / dSd;
                        float opacity = (rgba >>> 24) / 255f;
                        float alpha = Math.min(opacity * Math.min(1f, ALPHA_GAIN * (float) Math.exp(-0.5f * Math.max(m2, 0f))), 0.99f);
                        if (alpha < 1f / 255f) continue;
                        sumA += alpha; keep *= 1f - alpha; sumT += alpha * ts;
                        // radiance simplifiée : albédo linéaire (l'éclairage est testé séparément)
                        sr += alpha * (float) Math.pow(((rgba >> 16) & 0xFF) / 255f, 2.2);
                        sg += alpha * (float) Math.pow(((rgba >> 8) & 0xFF) / 255f, 2.2);
                        sb += alpha * (float) Math.pow((rgba & 0xFF) / 255f, 2.2);
                    }
                    if (sumA > 0) {
                        float w = h.transmittance * (1f - keep);
                        h.r += w * sr / sumA; h.g += w * sg / sumA; h.b += w * sb / sumA;
                        hitAcc += w * (sumT / sumA); hitW += w;
                        h.transmittance *= keep;
                        if (h.level < 0) h.level = L;
                        if (h.transmittance < 0.02f) break;
                    }
                }
            }
            t = Math.max(tExit, t) + 1e-3f * vs;
        }
        if (hitW > 0) h.hitDist = hitAcc / hitW;
        return h;
    }
}
