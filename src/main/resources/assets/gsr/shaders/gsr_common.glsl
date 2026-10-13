// =============================================================================================
//  GSR — Gaussian Space Reflections : bibliothèque partagée
//  Inclus par gsr_reflect.comp (mode autonome) ET par les shaderpacks Iris (docs/IRIS_INTEGRATION.md).
//  Requiert GLSL 4.30 (SSBO). Miroir CPU exact : dev.gsr.core.CpuReferenceTracer (testé).
//
//  Espace de travail : "relatif caméra, non tourné" (P_monde = camBlock + camFrac + P),
//  identique au rendu vanilla et à l'espace "player" d'Iris.
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
// Gain de saturation : noyau "à sommet plat" min(1, g·e^(−m²/2)). Une surface pleine pavée de
// surfels devient réellement opaque, sans gonfler σ (ce qui épaissirait les silhouettes).
#ifndef GSR_ALPHA_GAIN
#define GSR_ALPHA_GAIN 1.6
#endif

struct GsrFrame {
    ivec4 camBlock;                    // xyz bloc caméra, w index de frame
    vec4  camFrac;                     // xyz fraction,    w nombre de niveaux
    ivec4 pageDims;                    // xyz pages/niveau, w slots/niveau
    ivec4 windowMin[GSR_MAX_LEVELS];   // coin min de chaque fenêtre (coord. de page)
    ivec4 iparams;                     // x maxSplatsPerPage, y bit 0 = ignorer le masque de briques (debug)
    vec4  sunDir;                      // xyz, w intensité du ciel
    vec4  sunColor;
    vec4  skyZenith;
    vec4  skyHorizon;
    vec4  blockLight;                  // rgb, w force d'émission
};

struct GsrPageMeta {
    ivec4 page;        // xyz coord. de page, w nombre de splats
    uvec4 mask;        // xy masque 64 bits des briques (2³ cellules = 4³ voxels) non vides, zw réservés
};

struct GsrSplat {
    vec4  posSigmaT;   // xyz relatif à l'origine de la page (blocs), w = σ tangentiel
    uvec4 data;        // x albédo 0xAARRGGBB | y normale(16) lumière(8) émission(4) niveau(2) | z bits(σ normal) | w réservé
};

layout(std430, binding = GSR_BINDING_FRAME)  readonly buffer GsrFrameBuffer { GsrFrame gsrFrame; };
layout(std430, binding = GSR_BINDING_META)   readonly buffer GsrMetaBuffer  { GsrPageMeta gsrPageMeta[]; };
layout(std430, binding = GSR_BINDING_CELLS)  readonly buffer GsrCellBuffer  { uint     gsrCells[]; };
layout(std430, binding = GSR_BINDING_SPLATS) readonly buffer GsrSplatBuffer { GsrSplat gsrSplats[]; };

// ------------------------------------------------------------------------------- utilitaires

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

// Miroir de GaussianClipmap.slotIndex()
int gsrSlotIndex(int level, ivec3 page) {
    ivec3 d = gsrFrame.pageDims.xyz;
    ivec3 s = gsrFloorMod(page, d);
    return level * gsrFrame.pageDims.w + s.x + d.x * (s.y + d.y * s.z);
}

// Bloc entier (monde) -> espace relatif caméra, sans jamais convertir de grands entiers en float.
vec3 gsrToRel(ivec3 blockPos) { return vec3(blockPos - gsrFrame.camBlock.xyz) - gsrFrame.camFrac.xyz; }

vec3 gsrSafeInv(vec3 d) {
    return 1.0 / vec3(d.x >= 0.0 ? max(d.x, 1e-6) : min(d.x, -1e-6),
                      d.y >= 0.0 ? max(d.y, 1e-6) : min(d.y, -1e-6),
                      d.z >= 0.0 ? max(d.z, 1e-6) : min(d.z, -1e-6));
}

// Ciel analytique (repli final). Linéaire.
vec3 gsrSkyRadiance(vec3 dir) {
    float h = clamp(dir.y, 0.0, 1.0);
    vec3 sky = mix(gsrFrame.skyHorizon.rgb, gsrFrame.skyZenith.rgb, sqrt(h)) * gsrFrame.sunDir.w;
    float sd = max(dot(dir, gsrFrame.sunDir.xyz), 0.0);
    // Disque ~1,2° de demi-largeur, pic ≈ 20 × sunColor + halo discret. Réglé en jeu : 800/×30 donnait
    // un rond blanc aveuglant de ~2,4°, 4000/×8 un reflet terne. La composition compresse au-delà de 8.
    sky += gsrFrame.sunColor.rgb * (pow(sd, 3000.0) * 20.0 + pow(sd, 16.0) * 0.12) * gsrFrame.sunDir.w;
    if (dir.y < 0.0) sky *= mix(1.0, 0.3, clamp(-dir.y * 4.0, 0.0, 1.0));
    return sky;
}

// Éclairage d'un splat : dynamique (soleil / heure du jour) SANS reconstruire les splats.
vec3 gsrShadeSplat(vec3 albedoSrgb, vec3 n, uint data) {
    vec3  albedo = pow(albedoSrgb, vec3(2.2));
    float sky = float((data >> 20u) & 0xFu) / 15.0;
    float blk = float((data >> 16u) & 0xFu) / 15.0;
    float em  = float((data >> 24u) & 0xFu) / 15.0;
    float day = gsrFrame.sunDir.w;
    vec3 amb  = mix(gsrFrame.skyHorizon.rgb, gsrFrame.skyZenith.rgb, n.y * 0.5 + 0.5) * (sky * sky) * day;
    vec3 sun  = gsrFrame.sunColor.rgb * max(dot(n, gsrFrame.sunDir.xyz), 0.0) * pow(sky, 4.0) * day;
    vec3 bl   = gsrFrame.blockLight.rgb * (blk * blk);
    return albedo * (amb * 0.6 + sun + bl) + albedo * em * gsrFrame.blockLight.w;
}

// ------------------------------------------------------------------------------- traversée

struct GsrHit {
    vec3  radiance;       // radiance linéaire accumulée (ciel NON inclus)
    float transmittance;  // 1 = rien touché ; le ciel doit être pondéré par cette valeur
    float hitDist;        // distance moyenne pondérée par l'opacité (reprojection temporelle)
    int   level;          // niveau de clipmap du premier impact (-1 si aucun)
};

/*
 * Cône-marche dans la clipmap gaussienne.
 *  ro, rd    : origine relative caméra, direction NORMALISÉE
 *  coneTan   : demi-angle du cône (rugosité) -> choix du niveau de détail
 *  coneBase  : empreinte initiale (blocs)
 *
 * À chaque pas : niveau L = le plus fin dont le voxel (2^L) couvre l'empreinte du cône ET dont
 * la fenêtre contient le point. On évalue les splats de la cellule courante, puis on saute à la
 * sortie de la cellule, de la brique 4³ si son bit de masque est nul (sans lire gsrCells), ou de
 * toute la page si elle est vide / pas encore construite.
 *
 * Évaluation analytique d'une gaussienne 3D le long du rayon, Σ⁻¹ = a·I + b·nnᵀ
 * (a = 1/σt², b = 1/σn² − 1/σt²) :
 *   t* = −(dᵀΣ⁻¹o)/(dᵀΣ⁻¹d),  m² = oᵀΣ⁻¹o − (dᵀΣ⁻¹o)²/(dᵀΣ⁻¹d),  α = opacité · min(1, gain·exp(−m²/2))
 * Dans une cellule les splats sont composés sans tri (moyenne pondérée α, transmittance Π(1−α)) ;
 * entre cellules l'ordre avant -> arrière est exact grâce au DDA.
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
            voxel = blk >> L;                  // décalage arithmétique = floorDiv
            page  = voxel >> 4;
            ivec3 rel = page - gsrFrame.windowMin[L].xyz;
            if (all(greaterThanEqual(rel, ivec3(0))) && all(lessThan(rel, gsrFrame.pageDims.xyz))) { covered = true; break; }
        }
        if (!covered) break;                   // sorti de la clipmap -> le ciel prend le relais

        int   slot = gsrSlotIndex(L, page);
        GsrPageMeta meta = gsrPageMeta[slot];
        int   vs   = 1 << L;
        bool  valid = all(equal(meta.page.xyz, page)) && meta.page.w > 0;
        ivec3 cell  = (voxel & 15) >> 1;
        ivec3 brick = cell >> 1;
        int   bi    = brick.x + 4 * (brick.y + 4 * brick.z);
        uint  bits  = bi < 32 ? meta.mask.x : meta.mask.y;
        bool  occupied = valid && (noMask || ((bits >> uint(bi & 31)) & 1u) != 0u);

        // Boîte à franchir : cellule (brique occupée), brique vide (4³ voxels) ou page entière.
        ivec3 boxMin = occupied ? (page * 16 + cell * 2) * vs : valid ? (page * 16 + brick * 4) * vs : page * 16 * vs;
        float boxSize = float(occupied ? 2 * vs : valid ? 4 * vs : 16 * vs);
        vec3  bmin = gsrToRel(boxMin);
        vec3  t0 = (bmin - ro) * invD, t1 = (bmin + boxSize - ro) * invD;
        vec3  tf = max(t0, t1);
        float tExit = min(min(tf.x, tf.y), tf.z);
        // Un saut à vide ne franchit pas la distance où l'empreinte du cône dépasse 2^L : au-delà, le
        // niveau L+1 doit être consulté, et il peut avoir du contenu dans la zone sautée.
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
                    if (dn >= 0.0) continue;                       // surfels à sens unique
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
                    float alpha = min(col.a * min(1.0, GSR_ALPHA_GAIN * exp(-0.5 * max(m2, 0.0))), 0.99);
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

// Raccourci : GSR + ciel pondéré par la transmittance. skyVisibility ∈ [0,1] (lumière du ciel au récepteur).
vec3 gsrTraceWithSky(vec3 ro, vec3 rd, float tMin, float tMax, float coneTan, float skyVisibility, out float hitDist) {
    GsrHit h = gsrTrace(ro, rd, tMin, tMax, coneTan, 0.0);
    hitDist = h.hitDist;
    return h.radiance + h.transmittance * gsrSkyRadiance(rd) * skyVisibility;
}

#endif // GSR_COMMON_GLSL
