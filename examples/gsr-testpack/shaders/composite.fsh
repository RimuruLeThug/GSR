#version 430 compatibility
// =============================================================================================
//  Pack de test GSR : reflet de l'eau tiré UNIQUEMENT de gsrTrace() (pas de SSR), pour vérifier le
//  mode service (SSBO liés par GSR, lus par un programme Iris). Voir docs/IRIS_INTEGRATION.md.
// =============================================================================================
#define GSR_BINDING_FRAME  10
#define GSR_BINDING_META   11
#define GSR_BINDING_CELLS  12
#define GSR_BINDING_SPLATS 13
#include "/lib/gsr_common.glsl"

uniform sampler2D colortex0;
uniform sampler2D colortex2;
uniform sampler2D depthtex0;
uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;

in vec2 texcoord;

/* RENDERTARGETS: 0 */
layout(location = 0) out vec4 outColor;

void main() {
    vec4 col  = texture(colortex0, texcoord);
    vec4 mask = texture(colortex2, texcoord);
    if (mask.r > 0.5) {
        float d = texture(depthtex0, texcoord).r;
        vec4 v = gbufferProjectionInverse * vec4(vec3(texcoord, d) * 2.0 - 1.0, 1.0);
        vec3 playerPos = (gbufferModelViewInverse * vec4(v.xyz / v.w, 1.0)).xyz;   // = espace GSR
        vec3 N = vec3(0.0, 1.0, 0.0);
        vec3 V = normalize(playerPos);
        vec3 R = reflect(V, N);
        GsrHit h = gsrTrace(playerPos + N * 0.05, R, 0.02, 384.0, 0.002, 0.0);
        vec3 refl = h.radiance + h.transmittance * gsrSkyRadiance(R) * mask.g;
        // Fresnel relevé (0,25 minimum) : le reflet doit se voir nettement pour le test.
        float f = 0.25 + 0.75 * pow(1.0 - max(dot(-V, N), 0.0), 5.0);
        col.rgb = mix(col.rgb, pow(refl, vec3(1.0 / 2.2)), f);
    }
    outColor = col;
}
