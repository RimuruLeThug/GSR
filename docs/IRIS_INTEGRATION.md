# Using GSR from an Iris shaderpack

When a shaderpack is active, GSR does not draw anything itself. Every frame (`WorldRenderEvents.START`
event), it updates and **binds its 4 SSBOs to binding points 10 to 13**
(configurable: `ssboBindingBase`). Indexed SSBO bindings are global OpenGL state: any pack program
that declares these binding points reads the Gaussian clipmap directly.

Requirements on the pack side: `#version 430` (or higher) in the program that computes the reflections, and
do not use binding points 10–13 yourself (Iris's `bufferObject.*` uses the indices declared by
the pack; in case of a conflict, change `ssboBindingBase`).

## Steps

1. Copy `gsr_common.glsl` into the pack (e.g. `shaders/lib/gsr_common.glsl`).
2. In the reflection pass (often a `deferred` or `composite`):

```glsl
#version 430 compatibility
#define GSR_BINDING_FRAME  10
#define GSR_BINDING_META   11
#define GSR_BINDING_CELLS  12
#define GSR_BINDING_SPLATS 13
#include "/lib/gsr_common.glsl"

uniform mat4 gbufferModelViewInverse;
// ... your usual uniforms / samplers (depthtex0, colortex*, etc.)

// playerPos: Iris "player space" position = world − cameraPosition (= GSR space)
// N: world normal, rough: roughness (labPBR: 1 − specular.r), lmSky: sky light at the receiver
vec3 reflection(vec3 playerPos, vec3 N, float rough, float lmSky,
                vec2 ssrHitUV, float ssrConfidence, vec3 ssrColorLinear) {
    vec3 V = normalize(playerPos);
    vec3 R = reflect(V, N);              // or a sampled GGX direction, as in gsr_reflect.comp
    if (ssrConfidence >= 0.999) return ssrColorLinear;

    float coneTan = mix(0.002, 0.35, rough * rough);
    GsrHit h = gsrTrace(playerPos + N * 0.05, R, 0.02, 384.0, coneTan, 0.0);
    // The pack can replace gsrSkyRadiance() with its own sky (sky texture, clouds...)
    vec3 gsr = h.radiance + h.transmittance * gsrSkyRadiance(R) * lmSky;
    return mix(gsr, ssrColorLinear, ssrConfidence);
}
```

3. The distance `h.hitDist` can feed the pack's temporal reprojection (virtual image for smooth
   surfaces, see `gsr_temporal.comp`).

## Notes

* `gsrShadeSplat()` lights the splats with the sun/sky provided by GSR (`GsrLighting`). A pack that
  wants its own lighting model (shadows, sky color) can copy `gsrTrace()` and replace
  the call to `gsrShadeSplat()`: the raw data (albedo, normal, sky/block light, emission) is
  available in `GsrSplat`.
* If GSR is not installed, the SSBOs are not bound: provide a pack option (`#ifdef GSR`) that
  disables the call.

## Test pack

`examples/gsr-testpack` is a minimal pack that follows exactly these steps (water reflection through
`gsrTrace()` alone, without SSR). `./gradlew gsrTestPack` copies it together with `gsr_common.glsl` into
`run/shaderpacks/GSR-TestPack`; `./gradlew runClient -PwithIris` does it automatically. Verified with
Iris 1.8.8 on an AMD driver.

## Complementary packs (Reimagined, Unbound)

Verified on 2026-10-13 (r5.9.3, Iris 1.8.8, AMD): with GSR installed, both packs load and
display normally, and GSR switches to service mode. They do not call `gsrTrace()`: their reflections
remain their own (screen-space marching, then sky or their voxelized world-space reflections), with no
contribution from GSR. The natural hook point would be `AddBackgroundReflection()` in
`lib/materials/materialMethods/reflections.glsl`, called when their screen-space march fails
(`reflection.a < 1`). Their license (Complementary License 1.7) does not cover modifying the code outside
a "Modified Pack" redistributed under conditions: integration is therefore up to their authors, or to a
derived pack that complies with that license. No file from these packs is copied into this repository.
