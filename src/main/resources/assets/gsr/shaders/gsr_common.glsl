// =============================================================================================
//  GSR — Gaussian Space Reflections : shared library
//  Included by gsr_reflect.comp (standalone mode) AND by Iris shaderpacks (docs/IRIS_INTEGRATION.md).
//  Requires GLSL 4.30 (SSBO). Exact CPU mirror: dev.gsr.core.CpuReferenceTracer (tested).
//
//  Working space: "camera-relative, unrotated" (P_world = camBlock + camFrac + P),
//  identical to vanilla rendering and to Iris's "player" space.
// =============================================================================================
#ifndef GSR_COMMON_GLSL
#define GSR_COMMON_GLSL

#ifndef GSR_BINDING_FRAME
#define GSR_BINDING_FRAME  10
#define GSR_BINDING_META   11
#define GSR_BINDING_CELLS  12
#define GSR_BINDING_SPLATS 13
#endif

#define GSR_MAX_LEVELS 4
#ifndef GSR_MAX_STEPS
#define GSR_MAX_STEPS 160
#endif
#define GSR_MIN_TRANSMITTANCE 0.02
// Saturation gain: "flat-top" kernel min(1, g·e^(−m²/2)). A solid surface tiled with surfels
// becomes truly opaque without inflating σ (which would thicken silhouettes).
#ifndef GSR_ALPHA_GAIN
#define GSR_ALPHA_GAIN 1.6
#endif
// Clipping of surfels to the square of their face (half-side = ½ voxel), faded over this fraction of a voxel.
// Without it, the disc (opaque out to ~0.53 voxel from the center, still ~55 % at 0.8) spilled past its face:
// the submerged flank of a shore block hid the tufts and banks above the water (brown reflection).
#define GSR_EDGE_SOFT 0.15
// Cross-shaped plants (bit 30): a full face reflected as a translucent green cube. Each side face
// is cut into GSR_TUFT_BLADES vertical blades of pseudo-random heights (fixed per splat), solid
// at the bottom and fading at the top; opacity is raised by GSR_TUFT_GAIN to keep the texture's
// average coverage. Mirror: CpuReferenceTracer#tuft.
#define GSR_TUFT_BLADES 4.0
#define GSR_TUFT_GAIN 1.7
uint gsrHash(uint h) { h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u; return h; }
float gsrTuft(vec3 q, vec3 n, vec3 pos, float vs) {
    if (abs(n.y) > 0.5) return 1.0;                                // top / bottom: unchanged
    float h01 = clamp((abs(n.x) > 0.5 ? q.z : q.x) / vs + 0.5, 0.0, 0.999);
    float v01 = q.y / vs + 0.5;
    uint seed = (floatBitsToUint(pos.x) * 73856093u) ^ (floatBitsToUint(pos.y) * 19349663u) ^ (floatBitsToUint(pos.z) * 83492791u);
    float hgt = 0.45 + 0.55 * float(gsrHash(seed + uint(h01 * GSR_TUFT_BLADES)) >> 8u) / 16777216.0;
    return 1.0 - smoothstep(hgt - 0.25, hgt, v01);
}

struct GsrFrame {
    ivec4 camBlock;                    // xyz camera block, w frame index
    vec4  camFrac;                     // xyz fraction,    w level count
    ivec4 pageDims;                    // xyz pages/level, w slots/level
    ivec4 windowMin[GSR_MAX_LEVELS];   // min corner of each window (page coords)
    ivec4 iparams;                     // x maxSplatsPerPage, y bit 0 = ignore the brick mask (debug)
    vec4  sunDir;                      // xyz, w sky intensity
    vec4  sunColor;
    vec4  skyZenith;
    vec4  skyHorizon;
    vec4  blockLight;                  // rgb, w emission strength
};

struct GsrPageMeta {
    ivec4 page;        // xyz page coord, w splat count
    uvec4 mask;        // xy 64-bit mask of non-empty bricks (2³ cells = 4³ voxels), zw reserved
};

struct GsrSplat {
    vec4  posSigmaT;   // xyz relative to the page origin (blocks), w = tangential σ
    uvec4 data;        // x albedo 0xAARRGGBB | y normal(16) light(8) emission(4) level(2) | z bits(normal σ) | w reserved
};

layout(std430, binding = GSR_BINDING_FRAME)  readonly buffer GsrFrameBuffer { GsrFrame gsrFrame; };
layout(std430, binding = GSR_BINDING_META)   readonly buffer GsrMetaBuffer  { GsrPageMeta gsrPageMeta[]; };
layout(std430, binding = GSR_BINDING_CELLS)  readonly buffer GsrCellBuffer  { uint     gsrCells[]; };
layout(std430, binding = GSR_BINDING_SPLATS) readonly buffer GsrSplatBuffer { GsrSplat gsrSplats[]; };

// ------------------------------------------------------------------------------- utilities

vec3 gsrOctDecode(uint p) {
    vec2 e = vec2(float(p & 0xFFu), float((p >> 8u) & 0xFFu)) / 255.0 * 2.0 - 1.0;
    vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
    if (n.z < 0.0) {
        vec2 s = vec2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);
        n.xy = (1.0 - abs(e.yx)) * s;
    }
    return normalize(n);
}

vec4 gsrUnpackRGBA(uint c) {
    return vec4(float((c >> 16u) & 0xFFu), float((c >> 8u) & 0xFFu), float(c & 0xFFu), float(c >> 24u)) / 255.0;
}

ivec3 gsrFloorMod(ivec3 a, ivec3 m) { return a - m * ivec3(floor(vec3(a) / vec3(m))); }

// Mirror of GaussianClipmap.slotIndex()
int gsrSlotIndex(int level, ivec3 page) {
    ivec3 d = gsrFrame.pageDims.xyz;
    ivec3 s = gsrFloorMod(page, d);
    return level * gsrFrame.pageDims.w + s.x + d.x * (s.y + d.y * s.z);
}

// Integer (world) block -> camera-relative space, never converting large integers to float.
vec3 gsrToRel(ivec3 blockPos) { return vec3(blockPos - gsrFrame.camBlock.xyz) - gsrFrame.camFrac.xyz; }

vec3 gsrSafeInv(vec3 d) {
    return 1.0 / vec3(d.x >= 0.0 ? max(d.x, 1e-6) : min(d.x, -1e-6),
                      d.y >= 0.0 ? max(d.y, 1e-6) : min(d.y, -1e-6),
                      d.z >= 0.0 ? max(d.z, 1e-6) : min(d.z, -1e-6));
}

// Analytic sky (final fallback). Linear.
vec3 gsrSkyRadiance(vec3 dir) {
    float h = clamp(dir.y, 0.0, 1.0);
    vec3 sky = mix(gsrFrame.skyHorizon.rgb, gsrFrame.skyZenith.rgb, sqrt(h)) * gsrFrame.sunDir.w;
    float sd = max(dot(dir, gsrFrame.sunDir.xyz), 0.0);
    // Disc ~1.2° half-width, peak ≈ 20 × sunColor + subtle halo. Tuned in game: 800/×30 gave
    // a blinding white disc of ~2.4°, 4000/×8 a dull reflection. Compositing compresses above 8.
    sky += gsrFrame.sunColor.rgb * (pow(sd, 3000.0) * 20.0 + pow(sd, 16.0) * 0.12) * gsrFrame.sunDir.w;
    if (dir.y < 0.0) sky *= mix(1.0, 0.3, clamp(-dir.y * 4.0, 0.0, 1.0));
    return sky;
}

// Splat lighting: dynamic (time of day) WITHOUT rebuilding the splats.
// By default, imitates vanilla lighting (lightmap + fixed per-face shading, computed in sRGB):
// the GSR reflection must blend seamlessly with SSR, which reflects the vanilla image. The former
// "physical" model (directional sun ×2.2) made hills twice too bright (seen in game).
// A shaderpack can define GSR_SHADE_PHYSICAL to light with its own sun.
#ifndef GSR_SHADE_PHYSICAL
float gsrVanillaBrightness(float level) { float f = level / 15.0; return f / (4.0 - 3.0 * f); }

vec3 gsrShadeSplat(vec3 albedoSrgb, vec3 n, uint data) {
    float sky = float((data >> 20u) & 0xFu);
    float blk = float((data >> 16u) & 0xFu);
    float em  = float((data >> 24u) & 0xFu) / 15.0;
    vec3  l   = vec3(gsrVanillaBrightness(sky) * gsrFrame.sunDir.w) + gsrVanillaBrightness(blk) * gsrFrame.blockLight.rgb;
    l = clamp(l, 0.0, 1.0);
    l = mix(l, 1.0 - pow(1.0 - l, vec3(4.0)), 0.5);           // default "Brightness" option (50 %)
    l = max(l, vec3(em));                                      // light-emitting blocks: fully lit
    // Vanilla fixed shading: top 1.0, bottom 0.5, north/south 0.8, east/west 0.6
    vec3 n2 = n * n;
    float shade = 0.6 * n2.x + 0.8 * n2.z + (n.y > 0.0 ? 1.0 : 0.5) * n2.y;
    if ((data & (1u << 30u)) != 0u) shade = 1.0;              // cross-shaped plants: "shade": false
    return pow(albedoSrgb * l * shade, vec3(2.2));
}
#else
vec3 gsrShadeSplat(vec3 albedoSrgb, vec3 n, uint data) {
    vec3  albedo = pow(albedoSrgb, vec3(2.2));
    float sky = float((data >> 20u) & 0xFu) / 15.0;
    float blk = float((data >> 16u) & 0xFu) / 15.0;
    float em  = float((data >> 24u) & 0xFu) / 15.0;
    float day = gsrFrame.sunDir.w;
    vec3 amb  = mix(gsrFrame.skyHorizon.rgb, gsrFrame.skyZenith.rgb, n.y * 0.5 + 0.5) * (sky * sky) * day;
    vec3 sun  = gsrFrame.sunColor.rgb * ((data & (1u << 30u)) != 0u ? 0.6 : max(dot(n, gsrFrame.sunDir.xyz), 0.0)) * pow(sky, 4.0) * day;
    vec3 bl   = gsrFrame.blockLight.rgb * (blk * blk);
    return albedo * (amb * 0.6 + sun + bl) + albedo * em * gsrFrame.blockLight.w;
}
#endif

// ------------------------------------------------------------------------------- traversal

struct GsrHit {
    vec3  radiance;       // accumulated linear radiance (sky NOT included)
    float transmittance;  // 1 = nothing hit; the sky must be weighted by this value
    float hitDist;        // opacity-weighted mean distance (temporal reprojection)
    int   level;          // clipmap level of the first hit (-1 if none)
};

/*
 * Cone-march through the Gaussian clipmap.
 *  ro, rd    : camera-relative origin, NORMALIZED direction
 *  coneTan   : cone half-angle (roughness) -> level-of-detail selection
 *  coneBase  : initial footprint (blocks)
 *
 * At each step: level L = the finest whose voxel (2^L) covers the cone footprint AND whose
 * window contains the point. The splats of the current cell are evaluated, then we jump to the
 * cell exit, to the exit of the 4³ brick if its mask bit is zero (without reading gsrCells), or to
 * the exit of the whole page if it is empty / not built yet.
 *
 * Analytic evaluation of a 3D Gaussian along the ray, Σ⁻¹ = a·I + b·nnᵀ
 * (a = 1/σt², b = 1/σn² − 1/σt²):
 *   t* = −(dᵀΣ⁻¹o)/(dᵀΣ⁻¹d),  m² = oᵀΣ⁻¹o − (dᵀΣ⁻¹o)²/(dᵀΣ⁻¹d),  α = opacity · min(1, gain·exp(−m²/2))
 * Within a cell the splats are composited without sorting (α-weighted mean, transmittance Π(1−α));
 * between cells the front -> back order is exact thanks to the DDA.
 */
GsrHit gsrTrace(vec3 ro, vec3 rd, float tMin, float tMax, float coneTan, float coneBase) {
    GsrHit h;
    h.radiance = vec3(0.0); h.transmittance = 1.0; h.hitDist = tMax; h.level = -1;

    int   levels    = int(gsrFrame.camFrac.w);
    uint  maxSplats = uint(gsrFrame.iparams.x);
    bool  noMask    = (gsrFrame.iparams.y & 1) != 0;
    vec3  invD      = gsrSafeInv(rd);
    float hitW = 0.0, hitAcc = 0.0;
    float t = tMin;

    for (int step = 0; step < GSR_MAX_STEPS && t < tMax; step++) {
        vec3  p  = ro + rd * t;
        float fp = coneBase + coneTan * t;
        int   L  = clamp(int(ceil(log2(max(fp, 1.0)))), 0, levels - 1);

        ivec3 blk = gsrFrame.camBlock.xyz + ivec3(floor(p + gsrFrame.camFrac.xyz));
        ivec3 voxel = ivec3(0), page = ivec3(0);
        bool covered = false;
        for (; L < levels; L++) {
            voxel = blk >> L;                  // arithmetic shift = floorDiv
            page  = voxel >> 4;
            ivec3 rel = page - gsrFrame.windowMin[L].xyz;
            if (all(greaterThanEqual(rel, ivec3(0))) && all(lessThan(rel, gsrFrame.pageDims.xyz))) { covered = true; break; }
        }
        if (!covered) break;                   // left the clipmap -> the sky takes over

        int   slot = gsrSlotIndex(L, page);
        GsrPageMeta meta = gsrPageMeta[slot];
        int   vs   = 1 << L;
        bool  valid = all(equal(meta.page.xyz, page)) && meta.page.w > 0;
        ivec3 cell  = (voxel & 15) >> 1;
        ivec3 brick = cell >> 1;
        int   bi    = brick.x + 4 * (brick.y + 4 * brick.z);
        uint  bits  = bi < 32 ? meta.mask.x : meta.mask.y;
        bool  occupied = valid && (noMask || ((bits >> uint(bi & 31)) & 1u) != 0u);

        // Box to cross: cell (occupied brick), empty brick (4³ voxels) or whole page.
        ivec3 boxMin = occupied ? (page * 16 + cell * 2) * vs : valid ? (page * 16 + brick * 4) * vs : page * 16 * vs;
        float boxSize = float(occupied ? 2 * vs : valid ? 4 * vs : 16 * vs);
        vec3  bmin = gsrToRel(boxMin);
        vec3  t0 = (bmin - ro) * invD, t1 = (bmin + boxSize - ro) * invD;
        vec3  tf = max(t0, t1);
        float tExit = min(min(tf.x, tf.y), tf.z);
        // An empty skip does not cross the distance where the cone footprint exceeds 2^L: beyond it,
        // level L+1 must be consulted, and it may have content in the skipped zone.
        if (!occupied && coneTan > 0.0) tExit = min(tExit, max((float(vs) - coneBase) / coneTan, t));

        if (occupied) {
            uint c = gsrCells[uint(slot) * 512u + uint(cell.x + 8 * (cell.y + 8 * cell.z))];
            uint off = c & 0xFFFFu, count = c >> 16u;
            if (count > 0u) {
                vec3  pageOrigin = gsrToRel(page * 16 * vs);
                float sumA = 0.0, keep = 1.0, sumT = 0.0;
                vec3  sumC = vec3(0.0);
                uint  base = uint(slot) * maxSplats + off;
                for (uint k = 0u; k < count; k++) {
                    GsrSplat s = gsrSplats[base + k];
                    vec3  n  = gsrOctDecode(s.data.y);
                    float dn = dot(rd, n);
                    if (dn >= 0.0) continue;                       // one-sided surfels
                    vec3  o  = ro - (pageOrigin + s.posSigmaT.xyz);
                    float st = s.posSigmaT.w, sn = uintBitsToFloat(s.data.z);
                    float a  = 1.0 / (st * st), b = 1.0 / (sn * sn) - a;
                    float on = dot(o, n);
                    float dSd = a + b * dn * dn;
                    float dSo = a * dot(rd, o) + b * dn * on;
                    float ts  = -dSo / dSd;
                    if (ts < tMin || ts < t - 2.0 * st || ts > tExit + 2.0 * st) continue;
                    float m2 = a * dot(o, o) + b * on * on - dSo * dSo / dSd;
                    vec4  col = gsrUnpackRGBA(s.data.x);
                    vec3  q  = o + rd * ts;                        // ray point, relative to the center
                    vec3  qt = abs(q - n * dot(q, n));             // axis-aligned faces
                    float e  = max(qt.x, max(qt.y, qt.z));
                    float edge = 1.0 - smoothstep(0.5 * float(vs), (0.5 + GSR_EDGE_SOFT) * float(vs), e);
                    if ((s.data.y & (1u << 30u)) != 0u) {
                        col.a = min(1.0, col.a * GSR_TUFT_GAIN);
                        edge *= gsrTuft(q, n, s.posSigmaT.xyz, float(vs));
                    }
                    float alpha = min(col.a * min(1.0, GSR_ALPHA_GAIN * exp(-0.5 * max(m2, 0.0))), 0.99) * edge;
                    if (alpha < 1.0 / 255.0) continue;
                    sumA += alpha; keep *= 1.0 - alpha; sumT += alpha * ts;
                    sumC += alpha * gsrShadeSplat(col.rgb, n, s.data.y);
                }
                if (sumA > 0.0) {
                    float w = h.transmittance * (1.0 - keep);
                    h.radiance += w * (sumC / sumA);
                    hitAcc += w * (sumT / sumA);
                    hitW += w;
                    h.transmittance *= keep;
                    if (h.level < 0) h.level = L;
                    if (h.transmittance < GSR_MIN_TRANSMITTANCE) break;
                }
            }
        }
        t = max(tExit, t) + 1e-3 * float(vs);
    }
    if (hitW > 0.0) h.hitDist = hitAcc / hitW;
    return h;
}

// Shortcut: GSR + sky weighted by transmittance. skyVisibility ∈ [0,1] (sky light at the receiver).
vec3 gsrTraceWithSky(vec3 ro, vec3 rd, float tMin, float tMax, float coneTan, float skyVisibility, out float hitDist) {
    GsrHit h = gsrTrace(ro, rd, tMin, tMax, coneTan, 0.0);
    hitDist = h.hitDist;
    return h.radiance + h.transmittance * gsrSkyRadiance(rd) * skyVisibility;
}

#endif // GSR_COMMON_GLSL
