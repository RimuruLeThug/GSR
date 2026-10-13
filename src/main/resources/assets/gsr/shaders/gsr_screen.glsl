// =============================================================================================
//  GSR — screen-space utilities (standalone mode). Iris shaderpacks have their own equivalents.
// =============================================================================================
#ifndef GSR_SCREEN_GLSL
#define GSR_SCREEN_GLSL

uniform mat4  uProj;        // frame projection
uniform mat4  uInvProj;
uniform mat4  uView;        // camera rotation (WorldRenderContext.positionMatrix): camera-relative -> view
uniform mat4  uInvView;
uniform vec2  uScreenSize;
uniform int   uFrame;
uniform float uRoughness;   // uniform roughness (standalone mode: no material G-buffer)
uniform float uStrength;
uniform float uFloorStrength; // strength on upward-facing OPAQUE floors (0 = water/translucent only)
uniform int   uResScale;    // reflection + temporal passes: 1 = full resolution, 2 = half resolution

// Opaque depth (copied before the translucent layer): used for the water/translucent mask.
layout(binding = 5) uniform sampler2D uOpaqueDepth;

const float GSR_PI = 3.14159265;

// Depth [0,1] -> view z (negative in front of the camera, Minecraft's OpenGL convention)
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

// View normal reconstructed from depth: on each axis we pick the neighbor whose
// depth is closest (avoids wrong normals on silhouettes).
vec3 gsrViewNormalFromDepth(sampler2D depthTex, ivec2 px, vec3 vp) {
    ivec2 mx = ivec2(uScreenSize) - 1;
    vec3 l = gsrViewPos((vec2(px + ivec2(-1, 0)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2(-1, 0), ivec2(0), mx), 0).r);
    vec3 r = gsrViewPos((vec2(px + ivec2( 1, 0)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2( 1, 0), ivec2(0), mx), 0).r);
    vec3 d = gsrViewPos((vec2(px + ivec2(0, -1)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2(0, -1), ivec2(0), mx), 0).r);
    vec3 u = gsrViewPos((vec2(px + ivec2(0,  1)) + 0.5) / uScreenSize, texelFetch(depthTex, clamp(px + ivec2(0,  1), ivec2(0), mx), 0).r);
    // At the screen edge, the off-screen neighbor carries the pixel's own depth (clamp): it would
    // always be "the closest" and give a wrong slope (ray toward the background -> green reflection at the
    // edge). We then take the other neighbor.
    bool useL = px.x >= mx.x || (px.x > 0 && abs(l.z - vp.z) < abs(r.z - vp.z));
    bool useD = px.y >= mx.y || (px.y > 0 && abs(d.z - vp.z) < abs(u.z - vp.z));
    vec3 dx = useL ? vp - l : r - vp;
    vec3 dy = useD ? vp - d : u - vp;
    return normalize(cross(dx, dy));
}

// 1 if a translucent surface (water, ice, glass) was drawn in front of the opaque one at this pixel.
// d = final depth (with translucents). Relative margin: insensitive to depth rounding.
float gsrTranslucentMask(ivec2 px, float d) {
    float zFinal  = -gsrLinearViewZ(d);
    float zOpaque = -gsrLinearViewZ(texelFetch(uOpaqueDepth, px, 0).r);
    return step(0.02 + 0.002 * zFinal, zOpaque - zFinal);
}

// Standalone mode: uniform roughness; mask = upward-facing surfaces, at full strength on
// water / translucents, at uFloorStrength on opaque floors (grass, stone: 0 by default).
// With Iris integration, these values come from the pack's G-buffer (labPBR: specular.r = smoothness).
void gsrStandaloneMaterial(vec3 nWorld, ivec2 px, float d, out float rough, out float mask) {
    rough = uRoughness;
    float kind = mix(uFloorStrength, 1.0, gsrTranslucentMask(px, d));
    mask  = smoothstep(0.85, 0.97, nWorld.y) * kind * uStrength;
}

// ---- Low resolution (reflection + temporal passes) ----
ivec2 gsrLowResSize() { return (ivec2(uScreenSize) + uResScale - 1) / uResScale; }

// screen uv -> low-resolution target uv (exact for odd screen sizes too).
vec2 gsrScreenToLowUV(vec2 uv) { return uv * uScreenSize / vec2(gsrLowResSize() * uResScale); }

/*
 * Full-screen pixel representing the low-resolution texel lp: the first pixel of the
 * uResScale² block (fixed order -> stable from frame to frame) that carries a reflection. A block straddling
 * a water edge thus always has a water sample. mask = 0 if no pixel reflects.
 * Fixed order: stable from frame to frame. The reflection pass writes its choice (uReprOut), re-read by the temporal pass.
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
            // Cheap test first: with no reflection on opaque floors, only translucents count.
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

// Noise: interleaved gradient noise + R2 sequence (low discrepancy) offset per frame.
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

// GGX visible-normal sampling (Heitz 2018). Ve in tangent space, toward the viewer.
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
