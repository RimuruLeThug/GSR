# Utiliser GSR depuis un shaderpack Iris

Quand un shaderpack est actif, GSR ne dessine rien lui-même. À chaque frame (événement
`WorldRenderEvents.START`), il met à jour et **lie ses 4 SSBO aux points de liaison 10 à 13**
(configurable : `ssboBindingBase`). Les liaisons indexées SSBO sont un état global d'OpenGL : tout
programme du pack qui déclare ces points de liaison lit directement la clipmap gaussienne.

Prérequis côté pack : `#version 430` (ou plus) dans le programme qui calcule les reflets, et ne pas
utiliser lui-même les points de liaison 10–13 (`bufferObject.*` d'Iris utilise les index déclarés par
le pack ; en cas de conflit, changez `ssboBindingBase`).

## Étapes

1. Copier `gsr_common.glsl` dans le pack (ex. `shaders/lib/gsr_common.glsl`).
2. Dans la passe de reflets (souvent un `deferred` ou `composite`) :

```glsl
#version 430 compatibility
#define GSR_BINDING_FRAME  10
#define GSR_BINDING_META   11
#define GSR_BINDING_CELLS  12
#define GSR_BINDING_SPLATS 13
#include "/lib/gsr_common.glsl"

uniform mat4 gbufferModelViewInverse;
// ... vos uniformes / samplers habituels (depthtex0, colortex*, etc.)

// playerPos : position "player space" d'Iris = monde − cameraPosition (= espace GSR)
// N : normale monde, rough : rugosité (labPBR : 1 − specular.r), lmSky : lumière du ciel au récepteur
vec3 reflection(vec3 playerPos, vec3 N, float rough, float lmSky,
                vec2 ssrHitUV, float ssrConfidence, vec3 ssrColorLinear) {
    vec3 V = normalize(playerPos);
    vec3 R = reflect(V, N);              // ou une direction GGX tirée, comme dans gsr_reflect.comp
    if (ssrConfidence >= 0.999) return ssrColorLinear;

    float coneTan = mix(0.002, 0.35, rough * rough);
    GsrHit h = gsrTrace(playerPos + N * 0.05, R, 0.02, 384.0, coneTan, 0.0);
    // Le pack peut remplacer gsrSkyRadiance() par son propre ciel (texture de ciel, nuages...)
    vec3 gsr = h.radiance + h.transmittance * gsrSkyRadiance(R) * lmSky;
    return mix(gsr, ssrColorLinear, ssrConfidence);
}
```

3. La distance `h.hitDist` peut alimenter la reprojection temporelle du pack (image virtuelle pour les
   surfaces lisses, cf. `gsr_temporal.comp`).

## Notes

* `gsrShadeSplat()` éclaire les splats avec le soleil/ciel fournis par GSR (`GsrLighting`). Un pack qui
  veut son propre modèle d'éclairage (ombres, couleur de ciel) peut copier `gsrTrace()` et remplacer
  l'appel à `gsrShadeSplat()` : les données brutes (albédo, normale, lumière ciel/bloc, émission) sont
  disponibles dans `GsrSplat`.
* Si GSR n'est pas installé, les SSBO ne sont pas liés : prévoir une option du pack (`#ifdef GSR`) qui
  désactive l'appel.
