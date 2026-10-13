# GSR — Gaussian Space Reflections: architecture

> Credits: All the code of this mod was written by Claude (Anthropic), an AI agent, under the direction and with the review of Rimuru, who tested it in game and validated each step. The original idea for GSR came from Garloulou.

Fabric mod (Minecraft 1.21.1, Yarn) adding hybrid reflections: **SSR** when the information is
on screen, a **360° 3D Gaussian field** around the player when it is not, and an analytic sky
as a last resort. No hardware ray tracing: OpenGL 4.3 only (compute + SSBO).

## 1. Overview

```
 WORLD (ClientWorld)                CPU (workers)                         GPU
 ──────────────────                 ─────────────                         ───
 block changed ─┐                                                    SSBO frame   (208 B)
 light ─────────┼─► capture queue ─► snapshot  ──► SectionSummary ─┐  SSBO meta   (32 B / page)
 chunk loaded ──┘   (distance        (copy of       (mips 2/4/8 b)  │  SSBO cells  (512 / page)
                     priority)        Paletted-                     │  SSBO splats (slab / page)
                                      Container)  ┌─ if L0 page ────┤        │
                                                  ▼                 ▼        │
                                          VoxelGrid 18³  ◄── summaries (L1..L3)
                                                  │                          │
                                          SplatGenerator ──► PageData ──► upload (≤ N/frame)
                                          (exposed faces → Gaussian surfels,
                                           per-cell sort, per-page budget)

 FRAME :  START ─ bind SSBO ─┬─ [standalone mode] LAST : reflect.comp ─► temporal.comp ─► composite.comp
                             └─ [Iris active]     the shaderpack calls gsrTrace() in its own reflection pass
```

| Package | Role | Depends on MC |
|---|---|---|
| `dev.gsr.core` | clipmap, summaries, splat generation, manager, GPU format, reference CPU tracer | **no** (tested on its own) |
| `dev.gsr.mc` | section snapshots, material table, global lighting | yes |
| `dev.gsr.render` | SSBOs, screen targets, compute shader compilation and dispatch | yes (LWJGL) |
| `dev.gsr.mixin` | 1 mixin on `ClientWorld` | yes |
| `assets/gsr/shaders` | `gsr_common.glsl` (GSR traversal), `gsr_screen.glsl`, 3 compute passes | — |

### Fabric integration points — and why these ones

* **`ClientWorldMixin`** (`updateListeners`, `scheduleBlockRenders`): the only mixin. We do **not**
  intercept the `ChunkBuilder`: Sodium replaces it entirely (and Iris builds on Sodium). `ClientWorld`
  is touched by neither → a single code base for vanilla / Sodium / Iris.
* **Fabric API**: `ClientChunkEvents` (load/unload), `ClientTickEvents` (scheduling),
  `WorldRenderEvents.START/LAST/END` (GPU). These events are also emitted under Sodium.
* **Iris**: when a shaderpack is active, standalone mode is switched off and GSR becomes a *service*:
  its 4 SSBOs stay bound to fixed binding points (10–13 by default) for the whole frame. The
  pack includes `gsr_common.glsl` and calls `gsrTrace()` in its own reflection pass, with its real
  G-buffer (normals, labPBR roughness). No mixin in Iris → no version fragility.
  See `IRIS_INTEGRATION.md`.

## 2. Dynamic splat generation

**Choice: asynchronous CPU threads, no compute shader.** The block data lives on the CPU; sending
it to the GPU to convert it would cost more bandwidth (4096 states × 7 sections) than sending
the result (a few hundred 32-byte splats). The GPU is reserved for rendering.

1. **Snapshot (main thread, ~µs)**: copy of the `PalettedContainer` + `ChunkNibbleArray` of the
   section and of its 6 face neighbors. Workers never touch the live world.
2. **`SectionSummary` (worker)**: in one pass over 4096 blocks, mips 1/2/3 (voxels of 2/4/8 blocks):
   majority kind (50 % threshold), average albedo *of the surface blocks*, average air light,
   max emission. ≈ 4 KiB/section; air sections share a singleton.
3. **18³ `VoxelGrid`**: a single grid for all levels. L0 comes from the snapshot; L1–L3 are
   assembled **only from the summaries** (an L3 page covers 512 sections: re-reading 2 M blocks
   would be prohibitive).
4. **`SplatGenerator`**: one Gaussian per **exposed face** (neighbor = air, or opaque against
   translucent). The interior of mountains costs nothing. Each splat is an oblate Gaussian *surfel*:
   `Σ = σt²(I − nnᵀ) + σn²nnᵀ` with σt = 0.55 voxel, σn = 0.1 voxel.
   Beyond the budget (2048/page), faces exposed to the sky are kept first.
   Per-cell (2³ voxels) counting sort → `offset | count<<16` table.
5. **Debouncing**: coarse pages are rebuilt after 300 ms (L1), 0.9 s (L2), 2.7 s (L3),
   and only if the summary fingerprint changed (a light change invisible at 8-block
   resolution costs nothing).

Splat format (std430, 32 B):

| field | contents |
|---|---|
| `posSigmaT.xyz` | position **relative to the page origin** (no precision loss at ±30 M blocks) |
| `posSigmaT.w` | tangential σ |
| `data.x` | sRGB albedo + opacity `0xAARRGGBB` |
| `data.y` | octahedral normal 8+8 · sky/block light 4+4 · emission 4 · level 2 · unshaded 1 (bit 30) |
| `data.z` | normal σ (float bits) |
| `data.w` | reserved (material ID, reflected roughness…) |

Splats do **not** store radiance: only albedo + light levels. Lighting
(sun, time of day, rain, dimension) is applied at sampling time → the day/night cycle
triggers no rebuild.

## 3. Memory: the cascaded Gaussian clipmap

| Level | Voxel | Page | 8×4×8-page window | Use |
|---|---|---|---|---|
| L0 | 1 block | 16 blocks (= 1 section) | ±64 h / ±32 v | sharp nearby reflections (mirrors, calm water) |
| L1 | 2 blocks | 32 | ±128 / ±64 | |
| L2 | 4 blocks | 64 | ±256 / ±128 | |
| L3 | 8 blocks | 128 | ±512 / ±256 | distant terrain, rough reflections |

* **Toroidal storage**: page `p` occupies slot `floorMod(p, dims)`. When the player moves, only
  the pages entering the window are rebuilt; nothing is copied or shifted in VRAM.
* **Slot = address**: 1 page ↔ 1 fixed slab → no allocator, no fragmentation. The
  `GsrPageMeta { ivec4(pageXYZ, count); uvec4 mask }` meta lets the shader reject a stale slot;
  `mask.xy` is the **brick mask**: 1 bit per brick of 2×2×2 cells (4³ voxels), 64 per page.
* **Default budget**: 1024 slots × 64 KiB = **64 MiB** of splats + 2 MiB of cells + 32 KiB of
  meta. Configurable (`pagesX/Y/Z`, `levels`, `maxSplatsPerPage`).
* Standalone-mode screen targets: full-resolution opaque depth (4 B/pixel) + reflection,
  history and distance at low resolution (32 B/texel). At half resolution (default): 12 B/pixel,
  ≈ 40 MiB at 2560×1369 (computed; 120 MiB measured at full resolution).

### Half resolution (`standaloneResolutionScale`, `/gsr scale 1|2`)
Passes 1 and 2 work on a grid ÷`scale`. Each texel picks a **representative pixel**
in its `scale²` block: the first one, in a fixed order, that carries a reflection (`gsrRepresentative`).
A block straddling a water edge therefore keeps a water sample, and the choice is deterministic:
both passes find the same pixel without an intermediate target. The temporal pass reads the
history offset by (texel center − representative pixel). Without this offset, every frame
would resample the history by ¼ texel, which would continuously blur the image even with a still camera.
The composite (full resolution) aggregates the 4 neighboring texels with **bilinear weighting ×
camera-distance similarity** (tolerance 2 % + 5 cm). This distance is that of the temporal pass's
`distance` target. With `scale = 1`, the old behavior is recovered exactly.

## 4. Rendering pipeline

### Pass 0 — `gsr_depthcopy.comp`: standalone-mode material mask (no mixin)
Without a G-buffer, standalone mode has to guess *where* to reflect. A simple "normal pointing up" mask
turned grass and treetops into mirrors (milky veil at grazing angles, seen in game). At
`WorldRenderEvents.BEFORE_DEBUG_RENDER` — verified in the bytecode of `WorldRenderer#render` 1.21.1:
debug rendering precedes the translucent layer — the **opaque** depth is copied (R32F). In the following
passes, a pixel whose final depth is closer than the opaque depth (margin 0.02 + 0.2 % of the
distance) carries a translucent surface (water, ice, glass) → full-strength reflection; upward-facing
opaque floors receive `standaloneFloorStrength` (0 by default). In "Fabulous" mode, water
is in the translucent framebuffer: the passes then read its depth. If the copy did not happen
during the frame (resize), the reflection is skipped rather than wrong.

### Pass 1 — `gsr_reflect.comp`: SSR → GSR → sky
1. Normal (reconstructed from depth in standalone mode; G-buffer under Iris), roughness, mask.
2. Direction: `reflect()` if roughness < 0.03, otherwise **GGX VNDF** microfacet (Heitz 2018) sampled with
   IGN + R2 sequence offset per frame.
3. **SSR**: screen-space march with perspective-interpolated z + binary refinement. Confidence = 0 if
   the ray leaves the screen **or passes behind an object** (thickness exceeded), fade near the edges (over the closest 2 %, `smoothstep(0.96 ; 1)`; 7.5 % before 2026-10-13, blurry reflections at the edge).
4. **GSR fallback** if confidence < 1: `gsrTrace()` = cone-march in the clipmap. At each step:
   * level = the finest one whose voxel (2^L) ≥ the cone footprint **and** whose window contains the
     point (`coneTan` = f(roughness): a blurry reflection automatically uses larger splats → fewer steps);
   * page missing/empty → skip a whole page; empty brick (zero bit in the meta, already read) →
     skip 4³ voxels without reading `cells[]`; otherwise evaluate the cell's splats;
   * **analytic integral of the Gaussian along the ray** (no rasterization, no sorting):
     `t* = −dᵀΣ⁻¹o / dᵀΣ⁻¹d`, `m² = oᵀΣ⁻¹o − (dᵀΣ⁻¹o)²/dᵀΣ⁻¹d`, `α = op·min(1, 1.6·e^(−m²/2))`
     ("flat-top" kernel: a wall tiled with surfels is really opaque);
   * exact front-to-back compositing between cells (DDA), α-weighted average within a cell;
   * one-way surfels (no self-intersection, no leaking through a face).
5. `mix(GSR + T·sky, SSR, confidence)`; output rgb + hit distance.

> Why a cone-march and not a "projected GSR buffer"? Projecting the splats from the camera
> only contains, by construction, what the camera sees — exactly the limit of SSR. For rays
> off screen, the 3D representation has to be queried in an arbitrary direction. The cell grid
> per page makes this query bounded (~80 cells in the worst case for a 512-block mirror ray).

### Pass 2 — `gsr_temporal.comp`: accumulation
* **Hybrid reprojection** depending on roughness: surface (rough) vs **virtual image** `P + V·d_impact`
  (smooth) — a mirror's reflection follows the parallax of the reflected object.
* Disocclusion rejection via the previous frame's camera distance (ping-pong R32F target).
* YCoCg clipping of the 3×3 neighborhood: γ = 1 (smooth, tight) → 2.5 (rough, wide).
* History: 12 frames max (smooth: no trails) → 48 (rough: removes GGX noise).

**Honest limitation**: accumulation removes noise and aliasing, but it does not create detail
beyond the splat resolution (1 block in L0). Details finer than a block come from SSR;
to go further, L0 faces would need to be subdivided (2×2 surfels colored from the atlas).

### Pass 3 — `gsr_composite.comp`
Fresnel via an analytic approximation of the environment BRDF (Karis), F0 = 0.02; blending in
linear, `rgba8` written into the main framebuffer **before** the hand and the HUD.

## 5. Threads, synchronization, robustness

* Client thread: events, captures (budget 48/tick, nearest first), scheduling, uploads
  (budget 32 pages/frame), rendering.
* Workers (`cores/4`, low priority): summaries + generation. Immutable input data, `PageData`
  as output through a lock-free queue.
* **Per-page versioning**: a result is discarded if a more recent generation was started or if the
  page left the window in the meantime.
* Upload order: splats → cells → meta (the meta validates the page last).
* GL state: program, active unit and textures 0–5 are read back then restored identically (consistency of the
  `GlStateManager` cache). SSBOs and image units are not used by Minecraft.
* No OpenGL 4.3 (macOS) → GSR disables itself cleanly. **Detection**: Minecraft requests a 3.2 Core
  context and AMD drivers then report exactly "3.2" (seen in game: `3.2.0 Core Profile
  Context` on RX 7800 XT, GLSL 4.60), so `GLCapabilities.OpenGL43` is false. We also accept
  `ARB_compute_shader + ARB_shader_storage_buffer_object + ARB_shader_image_load_store +
  ARB_texture_storage` with GLSL ≥ 4.30 (LWJGL shares function pointers between core/ARB).

## 6. State and roadmap

**Verified**: all of `dev.gsr.core` compiles and passes 42 checks (`CoreSelfTest`, including stats, the capture queue and the brick mask): std430 format, signed section
key, budget and per-cell sort, summaries, and — via `CpuReferenceTracer`, the line-by-line mirror
of `gsrTrace()` on the same bytes — hits on the ground (L0), on a pillar outside the L0 window (found in
L1), in translucent water, in a rough cone, after block destruction and after toroidal recentering.

**Verified on 2026-10-13** (Windows 11, RX 7800 XT, AMD driver 26.8.1, vanilla without Sodium/Iris):
* compilation against Minecraft 1.21.1 / Yarn build.3 / Fabric API 0.115.0 (versions in
  `gradle.properties` unchanged); mixin targets (`updateListeners`, `scheduleBlockRenders`) seen
  in the remapped jar with the right signatures;
* loading: mod initialized, entering a singleplayer world without exception, the 4 compute shaders
  compile and link on the AMD driver;
* rendering (screenshots, still camera): water reflects terrain and the sun, grass and
  trees are no longer reflective, hand and HUD intact.

* sun reflection validated by the user: disc `pow(sd, 3000) × 20`, logarithmic compression
  beyond a luminance of 8 (`gsr_composite.comp`);
* stability in motion: no flicker observed by the user (walking and turning
  the camera at the water's edge);
* `/gsr stats` (client command): valid / non-empty pages / splats per level, queues, GPU time
  per pass (`GL_TIME_ELAPSED` queries in a 4-frame ring, never blocking), VRAM.

**Reference measurements** (before any optimization; 2560×1369, RX 7800 XT, plain + lake, daytime):

| Pass | Still, facing water | After 10 s of running |
|---|---|---|
| depth copy | 0.02 ms | 0.02 ms |
| reflection (SSR + GSR) | 2.48 ms | 1.93 ms |
| temporal | 0.32 ms | 0.44 ms |
| composite | 0.13 ms | 0.08 ms |
| **total** | **2.95 ms** | **2.47 ms** |

Loaded splats: ≈ 60–80 k (L0), 120–160 k (L1), 100–115 k (L2), 15–18 k (L3); VRAM 66 MiB of SSBO
+ 120 MiB of screen targets (36 B/pixel). Uploads when stopped: 0 pages/frame.

**Bug fixed thanks to these stats**: the capture queue stayed at ~100,000 entries. L3 coverage
(±512 blocks) exceeds the render distance; every unloaded section consumed a capture from the
budget, so the queue never drained. Now only a real copy consumes the budget
(attempts capped at 64 × budget per tick). Regression test in `CoreSelfTest`.

**Half resolution (2026-10-13), verified in game**: no fringe at water edges or around
silhouettes (observed by the user). `/gsr stats` measurements at the same spot, facing water
(2560×1369, RX 7800 XT):

| Pass | `/gsr scale 1` | `/gsr scale 2` (default) |
|---|---|---|
| depth copy | 0.02 ms | 0.02 ms |
| reflection (SSR + GSR) | 2.49 ms | **0.81 ms** |
| temporal | 0.41 ms | 0.33 ms |
| composite | 0.13 ms | 0.08 ms |
| **total** | **3.04 ms** | **1.24 ms** (−59 %) |
| screen targets (VRAM) | 120.3 MiB | 40.1 MiB |

The temporal pass gains almost nothing: the search for the representative pixel (up to 4 normal
reconstructions per texel) offsets the reduction in pixel count. Fixed further down: the reflection pass writes
the index of the representative, the temporal pass reads it back.

**Verified on 2026-10-13 (2nd session, automatic captures `-PgsrCmds`, 854×480 window)**:
flicker measured by `tools/FlickerDiff` (share of water pixels whose amplitude exceeds 16/255,
camera frozen):

| Configuration | Result | Mod off | Mod on |
|---|---|---|---|
| Night (`time set 18000`) | water slightly darkened, hill reflected, no bright highlights | — | — |
| Rain | gray sky reflection, drops drawn on top | — | — |
| Nether | clean dimension change, opaque lava not reflective, 0.03 ms GPU | — | — |
| Sodium 0.6.13 | correct reflections, hand and HUD intact | 1.52 % | 0.40 % |
| Iris 1.8.8 without shaderpack | standalone mode, correct reflections | 0.84 % | 0.37 % |
| Iris 1.8.8 + Complementary Reimagined r5.9.3 | service mode (standalone passes off), no exception | — | — |
| Iris 1.8.8 + Complementary Unbound r5.9.3 (2026-10-13) | service mode, no exception; the pack's own reflections only (no call to `gsrTrace()`) | — | — |
| "Fabulous" mode | correct reflections (depth read from the translucent framebuffer) | 1.89 % | 0.64 % |

Compatibility tests: `./gradlew runClient -PwithSodium` or `-PwithIris` (Sodium + Iris + their
jcpp / glsl-transformer / antlr libraries, not loaded from the Iris jar in dev). Sodium 0.6.13
requires Loom ≥ 1.8.9: the project moved to Loom 1.8. The `[MIXIN] Cannot resolve class
org/lwjgl/glfw/GLFW` errors at startup come from Sodium's pre-check in dev, and are harmless.
With Complementary, service mode is only verified as "does not crash" (the pack does not call `gsrTrace()`); see the test pack further down.

**Not verified**: NVIDIA/Intel drivers; a real public shaderpack integrating GSR (only the minimal test pack is verified).

**Lightened temporal pass (2026-10-13)**: the reflection pass writes the index of the representative pixel it
chose (low-resolution `R8UI` target, 1 B/texel); the temporal pass reads it back instead of redoing the
search (up to 4 normal reconstructions per texel). Same position, same view (2560×1369, RX 7800 XT):
temporal **0.13 → 0.07 ms**, total 0.78 → 0.72 ms. Before/after images identical apart from the
water texture animation (`raw` and `hit` views: < 0.03 % of pixels differ).

**Service mode verified with a pack that calls `gsrTrace()` (2026-10-13)**: minimal test pack
`examples/gsr-testpack` (water marked in `colortex2` by `gbuffers_water`, `composite` in
`#version 430 compatibility` which includes `/lib/gsr_common.glsl` and traces the reflection **only**
through `gsrTrace()`, without SSR). `./gradlew gsrTestPack` assembles it into `run/shaderpacks/GSR-TestPack` (done
automatically with `-PwithIris`). Iris 1.8.8 + Sodium 0.6.13, AMD: the pack compiles, the SSBOs bound to
points 10–13 are read, the water reflects the hill, the trees and the cherry tree, no errors in the log.

**Problems solved (verified by the user on 2026-10-13, commit `c30a21b`)**: no more
water flicker when walking and turning the camera, no more green cloud at water level. The
color and SSR fixes (`2265201`) were enough.

**Brown tint at the screen edge: fixed** (2026-10-13, user's view replayed in spectator
mode). Near the edge, SSR hands over to GSR. Two GSR defects there produced an earth or sky
reflection instead of grass:
1. Plants (tall grass, flowers, ferns, sugar cane) were air in
   `BlockMaterialTable`: under a meadow, the GSR reflection showed the sky, or the dirt bank behind.
   They are now `KIND_TRANSLUCENT`, with the average coverage of their textures as opacity
   (clamped 40..200), if their outline is at least ½ block tall and 6/16 wide (torches, rails and
   lily pads excluded).
2. Surfels overflowed their face: opaque up to ~0.53 voxel from the center, still ~55 % at 0.8. The
   submerged side of a shore block therefore hid, above the water, the tufts and the bank.
   `gsrTrace()` and `CpuReferenceTracer` now clip each splat to the square of its face
   (`GSR_EDGE_SOFT` = `EDGE_SOFT` = 0.15 voxel of fade). A point no longer being covered by more than one splat,
   `baseOpacity` goes from 0.95 to 1.0 (otherwise 5 % of the sky came through the floors). Tests added: "grazing
   ray above the shore" (T = 0.364 before, > 0.95 after) and "ray under the water towards the shore".
   The brick mask test now tolerates 1/1000 mismatches. The unmasked traversal (debug)
   does not apply the cone's level bound in occupied cells, and 1 rough ray in 4000 there
   misses a floor that L2 contains. Before, the disc overflow hid this discrepancy.

Measurement at the left edge (x = 60, under the shore): #757875 (washed-out sky) before, #445859 (dark grass)
after. Tools added: `/gsr debug raw` (GSR reflection without Fresnel) and `/gsr debug hit` (r =
transmittance, g = distance / 16, b = (level + 1) / 4), also captured by the autoshot. Limitation: the
GSR reflection of a tuft remains a blurry green veil (σ = 0.55 block), less sharp than SSR.

**Darkened tufts and agitated reflection edges when rotating: fixed** (2026-10-13, reported by
the user after the fix above):
* *Grass darker in the GSR reflection than in SSR.* Vanilla draws cross-shaped plants without
  per-face shading (`"shade": false` in `block/cross.json`, verified in the 1.21.1 jar); GSR
  applied 0.6 / 0.8 on the sides. `BlockMaterialTable` marks models where no quad is shaded,
  `VoxelSource#unshaded` → `VoxelGrid` (level 0 only) → bit 30 of `data.y` → `gsrShadeSplat` takes
  a shading of 1.0. Test: "Cross plants" in `CoreSelfTest`.
* *Reflection edges that "boil" when rotating the camera (not when moving forward).* In pure rotation,
  each reflection ray is the same in the world: only the per-frame GGX sample varies. With the
  default roughness of 0.08 (inherited from the initial import), ±0.7° of spread, i.e. half a block at 40 blocks; the
  temporal pass averages the noise, but in rotation the reprojection and neighborhood clipping
  make the silhouette change from one frame to the next (seen frame by frame on the trees on the left, half and full
  resolution). In mirror mode (roughness 0), silhouettes slide without deforming, at both resolutions, and
  the reflection is markedly sharper (the blur came from the accumulation of noise). **Default standalone
  roughness: 0** (calm water = mirror). The GGX path remains for shaderpacks and `standaloneRoughness` > 0.03.
* Tools: `/gsr debug refl` (hybrid SSR + GSR reflection after the temporal pass, without Fresnel or scene);
  autoshot `-PgsrVideo=<frames>,<degrees>` ("video": rotation by a fixed step on **every frame**, capture
  right after world rendering, equivalent to a very-high-frame-rate film), `-PgsrMove=dx,dy,dz,dyaw`
  (per-tick bursts with a moving camera), `-PgsrBursts=refl,refl+scale1,refl+mirror,...`
  (combinable burst configurations); `tools/JerkDiff.java` (deviation of each frame from the average of its
  neighbors, heat map). JerkDiff does not settle the cases: a sharper reflection contains more
  detail, hence more legitimate jerks; the diagnosis was made on frame-by-frame image strips.
* Tested at the user's resolution (2560×1369 window); the SSR and upsampling thresholds are in
  view space or relative to the screen size, half resolution follows `uScreenSize`. Not tested at other
  window sizes.
* *Grass reflected as translucent green cubes* (user capture `claudemaisblock.png`). A
  plant voxel was a uniform veil over its whole face: under a meadow, the GSR fallback showed pale
  cubes with straight edges. The side faces of plants (bit 30) are now cut into 4
  vertical blades of pseudo-random heights (hash of the splat position, 0.45 to 1 voxel), solid at the base
  and fading over 0.25 voxel at the top; opacity × 1.7 to keep the average coverage of the texture
  (`gsrTuft`, mirror `CpuReferenceTracer#tuft`, 3 tests). Verified in A/B at the same position
  (`tp @s 130.13 62.68 113.65 -119.4 15.3` in "GSR Repro"): cubes before, tuft silhouettes after.
  The autoshot now freezes the camera during the views (the user's mouse was moving it).
* *Blurry reflections near the screen edge.* SSR handed over to GSR (one color per face, blurry) from
  7.5 % of the edge (`smoothstep(0.85 ; 1)` on the hit position), i.e. ~190 px at 2560 wide. Band
  reduced to 2 % (0.96). A/B at the same position: the reflected tuft at the right edge shows its blades instead
  of a veil; no visible seam, including in rotation (24-frame video at 0.5°/frame). Reflections remain
  GSR, hence blurry, where SSR does not have the information (ray hidden by a foreground, camera at water level):
  that is the role of the fallback. The autoshot releases the cursor (the mouse no longer turns the camera).
* Noticed, not fixed: Minecraft's projection matrix includes the view bobbing when walking,
  so the eye is not exactly at the origin of view space while the shaders take `normalize(P)`
  as the view direction (error ~1° near the camera). The user sees no defect when moving forward.

Diagnostic history:
* **Water flicker** reported by the user, absent before the brick mask and the
  half resolution. Still present after the mask's LOD bound (`7ecff59`). **Not rechecked since
  the color and SSR fixes** (`2265201`): an SSR/GSR alternation of very
  different colors from one frame to the next was a probable cause. Planned diagnosis:
  `/gsr bricks off`, then `/gsr scale 1`, to isolate the cause. An objective measurement is possible:
  several still-camera captures, then difference of the water pixels.
* **Objective measurement of 2026-10-13 (camera frozen, commit following `7058515`)**: bursts of 8 captures
  one tick apart (`-PgsrAutoshot`, then `java tools/FlickerDiff.java ...`), water area, share of
  pixels whose temporal amplitude exceeds 16/255: mod off 1.49 % (vanilla water animation),
  default 0.30 %, without bricks 0.32 %, full resolution 1.04 %, GSR only 0.25 %. **With a still camera, the
  mod does not flicker** (it even smooths the animation). Still to be verified in motion, where temporal
  reprojection comes into play: the automatic measurement does not cover it.
* **"Cloud" of green pixels** above the water when the camera is almost at water level (user
  capture 17:35). Probably the same green GSR blob, not rechecked.
* The 0.81 → 0.13 ms gain seen before the LOD bound was wrong (rays that missed). The correct
  measurement is 0.51 ms.

**Biome tint (done, 2026-10-13, verified in game)**:
* Quad composition is **affine in the tint**: per face group, color = A + B ⊙ tint
  / 255 (A = untinted part, B = texture of the tinted quads). `BlockMaterialTable` stores A, B and a
  tint type (none, grass, foliage, water). The type is deduced by comparing
  `BlockColors.getColor(state, null, null, i)` to `GrassColors/FoliageColors.getDefaultColor()`. Constant
  tints (birch, spruce, redstone) are baked into A. Water (and submerged blocks):
  A = 0, B = white. 9241 tinted states out of 26684 (mostly `waterlogged` variants).
* `McSnapshotSource` reads `ClientWorld#getColor(pos, BiomeColors.GRASS/FOLIAGE/WATER_COLOR)` (already smoothed
  by "biome blend") at the center of each 4×4 square of the section, at mid-height, i.e. 16 reads
  per type, and only for the types present in the section's palette. Bilinear
  interpolation per column; border neighbors take the nearest column. The core did not
  change: the snapshot directly returns the tinted albedo, and the L1–L3 summaries inherit it.
* Verification: `/gsr stats` displays the tint under the player and the albedo of the top of a grass
  block. In the swamp (775, 2041): grass #677039, water #617B64 → grass top #3B4121 (plain
  #476D3E); the `/gsr debug gsr` view shows a dark olive reflection there.
* Limitations: sugar cane (tint `-1` without a world) classified as untinted; a single height per
  section (3D cave biomes approximated).
* Resource pack change (F3+T, menu): a `SimpleSynchronousResourceReloadListener`
  (after `MODELS`) rebuilds the table, passes it to `McSnapshotSource` and recaptures the loaded
  columns. Verified (autoshot `f3t`): table rebuilt in 60 ms, 609 columns recaptured, queues
  empty and reflections intact afterwards, no exception.

**Configuration (done)**: `config/gsr.json` read at startup by `GsrConfigFile` (Gson), rewritten with
the missing fields and the values clamped by `GsrConfig#validate()`; an unreadable file is
kept and the default values used. `/gsr reload` applies the rendering and budget fields live;
levels, pages, splats/page, SSBO binding point and threads at the next startup
(`copyRuntimeFrom` reports it). σ and opacity only apply to regenerated pages.

Next steps, in order of return on investment:
1. ~~**Half-resolution** reflection + depth-guided upsampling~~ (done: 3.04 → 1.24 ms).
2. ~~**Per-page occupancy mask**~~ (done, 2026-10-13). Choice: 64 bits per **brick** of 2³
   cells, stored in the meta, rather than 512 bits per cell. A per-cell mask would only
   trade the `cells[]` read for another read of the same cost, without reducing the number of
   steps. The meta, on the other hand, is already read at every step, and an empty brick is crossed in one step instead of
   2 to 4. CPU test: grazing ray 4 blocks above the ground, 6 steps over 20 blocks instead of ≥ 10.
   **Pitfall fixed**: an empty-brick skip must not cross the distance where the cone footprint
   exceeds 2^L (level L+1 may have content in the skipped area). Without this bound, rough rays
   missed the terrain, which made the water flicker. CPU comparison on 4000 random rays, with and without
   the mask: 0 mismatches (permanent test in `CoreSelfTest`).
   Measured in game (`/gsr bricks off|on`, same spot facing water): reflection **0.67 → 0.51 ms**
   (−24 %), total 1.20 → 1.12 ms.
3. ~~Albedo from the **real textures**~~ (done, 2026-10-13, without biome tint). One color per
   **face group** (sides / top / bottom): `BlockMaterialTable` superimposes the quads of the baked
   model, default tint included, and re-reads the PNGs through the resource manager (no access
   widener): 702 textures in ~100 ms when loading the world. Section summaries average each group
   separately. Splat lighting aligned with vanilla (lightmap, fixed per-face shading, computation in
   sRGB); the old "sun ×2.2" model remains available via `GSR_SHADE_PHYSICAL` for packs.
   **Why**: in game, the GSR reflection of a hill (grass = `MapColor` green on all faces, ×2
   too bright) formed a bright green blob. It did not blend with SSR, hence a green band that
   followed the screen edge, where SSR hands over. Verified through `/gsr debug gsr` captures before/after.
   Per-position biome tint: done (see above).
   Diagnostic tools: `/gsr debug normal|ssr|gsr|raw|hit|refl`, `/gsr bricks on|off`, and
   `gradlew runClient -PgsrAutoshot=<prefix>` (5 automatic captures, one per view, in `run/screenshots`).
   `-PgsrCmds="tp @s x y z;time set 18000|gsr stats"` sends commands 5 s after entering the
   world (and the part after `|`, 13 s before the captures, which then start at 45 s).
   Fixed along the way: SSR, thickness test done **after** the binary refinement (a coarse step
   wrongly rejected visible hills); reconstructed normal, no more off-screen neighbor at the edge.
4. Standalone mode: the depth mask (pass 0) does not distinguish water from stained glass or
   ice; a real material ID would require a mixin on the translucent terrain shader, or leaving it
   to shaderpacks.
5. Optimization: reuse the sections cloned by Sodium (`@Pseudo` mixin on its meshing
   task) to avoid reading the blocks twice.
6. Vulkan port: SSBO → storage buffers, `layout(set, binding)`; the GLSL code already uses no
   uniform outside a block in `gsr_common.glsl`.
