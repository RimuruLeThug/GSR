// =============================================================================================
//  GSR — utilitaires espace écran (mode autonome). Les shaderpacks Iris ont leurs propres équivalents.
// =============================================================================================
#ifndef GSR_SCREEN_GLSL
#define GSR_SCREEN_GLSL

uniform mat4  uProj;        // projection de la frame
uniform mat4  uInvProj;
uniform mat4  uView;        // rotation caméra (WorldRenderContext.positionMatrix) : relatif-caméra -> vue
uniform mat4  uInvView;
uniform vec2  uScreenSize;
uniform int   uFrame;
uniform float uRoughness;   // rugosité uniforme (mode autonome : pas de G-buffer matériau)
uniform float uStrength;
uniform float uFloorStrength; // force sur les sols OPAQUES orientés vers le haut (0 = eau/translucides seuls)
uniform int   uResScale;    // passes reflet + temporel : 1 = pleine résolution, 2 = demi-résolution

// Profondeur opaque (copiée avant la couche translucide) : sert au masque eau/translucide.
layout(binding = 5) uniform sampler2D uOpaqueDepth;

const float GSR_PI = 3.14159265;

// Profondeur [0,1] -> z vue (négatif devant la caméra, convention OpenGL de Minecraft)
float gsrLinearViewZ(float d) {
    vec4 v = uInvProj * vec4(0.0, 0.0, d * 2.0 - 1.0, 1.0);
    return v.z / v.w;
}

vec3 gsrViewPos(vec2 uv, float d) {
    vec4 v = uInvProj * vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

vec2 gsrProject(mat4 viewProj, vec3 p) {
    vec4 c = viewProj * vec4(p, 1.0);
    return c.xy / c.w * 0.5 + 0.5;
}

// Normale vue reconstruite depuis la profondeur : on choisit, sur chaque axe, le voisin dont la
// profondeur est la plus proche (évite les normales fausses sur les silhouettes).
vec3 gsrViewNormalFromDepth(sampler2D depthTex, ivec2 px, vec3 vp) {
    ivec2 mx = ivec2(uScreenSize) - 1;
    vec3 l = gsrViewPos((vec2(px + ivec2(-1, 0)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2(-1, 0), ivec2(0), mx), 0).r);
    vec3 r = gsrViewPos((vec2(px + ivec2( 1, 0)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2( 1, 0), ivec2(0), mx), 0).r);
    vec3 d = gsrViewPos((vec2(px + ivec2(0, -1)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2(0, -1), ivec2(0), mx), 0).r);
    vec3 u = gsrViewPos((vec2(px + ivec2(0,  1)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2(0,  1), ivec2(0), mx), 0).r);
    vec3 dx = abs(l.z - vp.z) < abs(r.z - vp.z) ? vp - l : r - vp;
    vec3 dy = abs(d.z - vp.z) < abs(u.z - vp.z) ? vp - d : u - vp;
    return normalize(cross(dx, dy));
}

// 1 si une surface translucide (eau, glace, verre) a été dessinée devant l'opaque à ce pixel.
// d = profondeur finale (avec translucides). Marge relative : insensible aux arrondis de profondeur.
float gsrTranslucentMask(ivec2 px, float d) {
    float zFinal  = -gsrLinearViewZ(d);
    float zOpaque = -gsrLinearViewZ(texelFetch(uOpaqueDepth, px, 0).r);
    return step(0.02 + 0.002 * zFinal, zOpaque - zFinal);
}

// Mode autonome : rugosité uniforme ; masque = surfaces orientées vers le haut, à pleine force sur
// l'eau / les translucides, à uFloorStrength sur les sols opaques (herbe, pierre : 0 par défaut).
// En intégration Iris, ces valeurs viennent du G-buffer du pack (labPBR : specular.r = lissé).
void gsrStandaloneMaterial(vec3 nWorld, ivec2 px, float d, out float rough, out float mask) {
    rough = uRoughness;
    float kind = mix(uFloorStrength, 1.0, gsrTranslucentMask(px, d));
    mask  = smoothstep(0.85, 0.97, nWorld.y) * kind * uStrength;
}

// ---- Basse résolution (passes reflet + temporel) ----
ivec2 gsrLowResSize() { return (ivec2(uScreenSize) + uResScale - 1) / uResScale; }

// uv écran -> uv de la cible basse résolution (exact aussi pour une taille d'écran impaire).
vec2 gsrScreenToLowUV(vec2 uv) { return uv * uScreenSize / vec2(gsrLowResSize() * uResScale); }

/*
 * Pixel plein écran représentant le texel basse résolution lp : le premier pixel du bloc
 * uResScale² (ordre fixe -> stable d'une frame à l'autre) qui porte un reflet. Un bloc à cheval
 * sur un bord d'eau a ainsi toujours un échantillon d'eau. mask = 0 si aucun pixel ne reflète.
 * Déterministe : les passes reflet et temporelle choisissent le même pixel.
 */
ivec2 gsrRepresentative(sampler2D depthTex, ivec2 lp, out float d, out vec3 vp, out vec3 nV, out vec3 N,
                        out float rough, out float mask) {
    ivec2 base = lp * uResScale;
    d = 1.0; vp = vec3(0.0); nV = vec3(0.0, 0.0, 1.0); N = vec3(0.0, 1.0, 0.0); rough = uRoughness; mask = 0.0;
    for (int j = 0; j < uResScale; j++)
        for (int i = 0; i < uResScale; i++) {
            ivec2 px = base + ivec2(i, j);
            if (any(greaterThanEqual(px, ivec2(uScreenSize)))) continue;
            d = texelFetch(depthTex, px, 0).r;
            if (d >= 1.0) continue;
            // Test bon marché d'abord : sans reflet sur les sols opaques, seuls les translucides comptent.
            if (uFloorStrength <= 0.0 && gsrTranslucentMask(px, d) == 0.0) continue;
            vp = gsrViewPos((vec2(px) + 0.5) / uScreenSize, d);
            nV = gsrViewNormalFromDepth(depthTex, px, vp);
            N  = normalize(mat3(uInvView) * nV);
            gsrStandaloneMaterial(N, px, d, rough, mask);
            if (mask > 0.001) return px;
        }
    mask = 0.0;
    return base;
}

// Bruit : interleaved gradient noise + séquence R2 (faible discrépance) décalée par frame.
float gsrIGN(vec2 p, int frame) {
    p += 5.588238 * float(frame & 63);
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}
vec2 gsrR2(int frame) { return fract(vec2(0.7548776662, 0.5698402910) * float(frame & 1023)); }

void gsrBasis(vec3 n, out vec3 t, out vec3 b) {
    float s = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (s + n.z), c = n.x * n.y * a;
    t = vec3(1.0 + s * n.x * n.x * a, s * c, -s * n.x);
    b = vec3(c, s + n.y * n.y * a, -n.y);
}

// Échantillonnage GGX des normales visibles (Heitz 2018). Ve en espace tangent, vers l'observateur.
vec3 gsrSampleGGXVNDF(vec3 Ve, float alpha, vec2 U) {
    vec3 Vh = normalize(vec3(alpha * Ve.x, alpha * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    vec3 T1 = lensq > 0.0 ? vec3(-Vh.y, Vh.x, 0.0) * inversesqrt(lensq) : vec3(1.0, 0.0, 0.0);
    vec3 T2 = cross(Vh, T1);
    float r = sqrt(U.x), phi = 2.0 * GSR_PI * U.y;
    float t1 = r * cos(phi), t2 = r * sin(phi);
    float s = 0.5 * (1.0 + Vh.z);
    t2 = (1.0 - s) * sqrt(max(0.0, 1.0 - t1 * t1)) + s * t2;
    vec3 Nh = t1 * T1 + t2 * T2 + sqrt(max(0.0, 1.0 - t1 * t1 - t2 * t2)) * Vh;
    return normalize(vec3(alpha * Nh.x, alpha * Nh.y, max(0.0, Nh.z)));
}

#endif // GSR_SCREEN_GLSL
