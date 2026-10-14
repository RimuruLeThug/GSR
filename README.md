# GSR — Gaussian Space Reflections

> Water reflections that also show what is behind you, off screen: a Fabric mod for Minecraft Java 1.21.1.

## What the mod does

In vanilla Minecraft, water mostly reflects the sky. "Screen-space" reflection (SSR) techniques can only reflect what is already visible on screen. As soon as a tree, a hill or a shoreline is out of view or hidden, the reflection disappears.

GSR combines three sources, from the most accurate to the least accurate:

1. **SSR (screen-space reflections)**: when the information is on screen, this is the sharpest reflection.
2. **360° 3D Gaussian field**: otherwise, the mod queries a simplified 3D representation of the world around the player. Each exposed block face becomes a small Gaussian "blob" (a *surfel*). These blobs are stored in 4 nested levels of detail (a *clipmap*: voxels of 1, 2, 4 and 8 blocks, up to about 512 blocks of horizontal distance). The reflection can therefore show terrain, trees and shorelines that are off screen.
3. **Analytic sky**: as a last resort, the sky is computed (sun, time of day, rain, dimension).

Everything runs on the GPU with **OpenGL 4.3 compute shaders**, with no hardware ray tracing. The Gaussian field is built in the background by CPU threads from copies of the world sections, then uploaded to the GPU. The day/night cycle triggers no rebuild.

By default, calm water behaves like a mirror (standalone roughness 0). The reflections are computed at half resolution, then scaled back up using depth.

## Compatibility

**Verified**

| Item | Detail |
|---|---|
| Minecraft | 1.21.1 (Yarn mappings 1.21.1+build.3) |
| Fabric Loader | 0.16.5 (0.16+) |
| Fabric API | 0.115.0+1.21.1 |
| Java | 21 |
| Vanilla | Correct reflections, hand and HUD untouched |
| Sodium | 0.6.13: correct reflections |
| Iris | 1.8.8 without a shaderpack: standalone mode, correct reflections |
| Iris 1.8.8 + shaderpack | "Service" mode: GSR makes its clipmap available to the pack, which calls `gsrTrace()`. Verified with a minimal test pack (`examples/gsr-testpack`) |
| Misc | Night, rain, Nether (clean dimension change) and the "Fabulous" graphics mode |

All of this was tested on Windows 11, with an RX 7800 XT and an AMD driver.

**Not verified**

- **NVIDIA** and **Intel** drivers.
- The first-launch GPU classifier on Intel, NVIDIA, AMD integrated GPUs and software renderers (covered by unit tests only), a real fresh install classified as Low, the Low preset's performance on a modest GPU, and the "pick Low in the screen, then restart" chain.
- The SSBO limit check on real low-limit drivers or a real out-of-memory (only fault injection was tried), and the shader info-log warning (AMD returns empty logs). Known gaps: after a GPU initialization failure the chunk manager keeps capturing pages (wasted CPU), and a shader failing after buffer allocation leaks GPU resources.
- Public shaderpacks. Complementary Reimagined and Unbound r5.9.3 were tested with Iris 1.8.8: they load normally and GSR switches to service mode, but they **do not call `gsrTrace()`**. **With these packs, GSR therefore brings no visible change**: their reflections remain their own. Integration is up to their authors. See [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md).
- Window sizes other than 2560×1369, for threshold tuning.

macOS is **not supported**, because it does not offer OpenGL 4.3.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1.
2. Put **Fabric API** in the `mods/` folder.
3. Put the GSR jar (`gsr-<version>.jar`, *Releases* tab) in `mods/`. Sodium and Iris are optional.
4. Launch the game. The `config/gsr.json` file is created on first launch. Only in that case, GSR reads the GPU (vendor, renderer, free video memory) and picks a quality preset (see below). An existing `config/gsr.json` is never changed, apart from a `gpuDetectionDone` flag added to it.

**OpenGL 4.3 is required.** If your machine does not provide it, the mod disables itself cleanly with a message in the log, without crashing. GSR also checks the driver's SSBO limits (block size, bindings) and logs them with the planned buffer sizes; if they are too low, or if allocation fails, it disables itself with a message in the log naming the config fields to lower (`maxSplatsPerPage`, `pagesX/Y/Z`, `levels`, `ssboBindingBase`) and the game keeps vanilla rendering. Verified only by fault injection on an RX 7800 XT (see below), not on a real low-limit driver. Detection also accepts drivers that report a 3.2 context but expose the required ARB extensions (the case with AMD drivers).

## Settings, keys and commands

**Settings screen**: *Options > Video Settings > GSR Reflections...* (at the bottom of the list), or the *Configure* button in Mod Menu. With Sodium, which replaces the Video Settings screen, a *GSR Reflections...* button appears in the top-left corner of the Options screen. Changes apply immediately and are saved to `config/gsr.json` when the screen closes.

| Setting | Effect |
|---|---|
| Reflections | On / off |
| Preset | Low, Medium, High, Ultra, or Custom when the current values match none (see below) |
| Resolution | Full, Half (default), Third, Quarter: lower is faster and blurrier |
| Strength | Reflections on water and translucent blocks (100% = physical Fresnel) |
| Opaque Floors | Reflections on upward-facing opaque blocks (0% by default) |
| Roughness | 0% = mirror (default); higher values blur the reflection |
| Trace Distance | Maximum length of an off-screen reflection ray (64–512 blocks) |
| Debug View, Statistics, Brick Mask | Diagnostics (not saved) |

**Presets.** A preset sets the reflection resolution, the splats per page and the uploads per frame:

| Preset | Resolution | Splats per page | Uploads per frame |
|---|---|---|---|
| Low | Quarter | 1024 | 16 |
| Medium | Third | 2048 | 32 |
| High (= previous defaults) | Half | 2048 | 32 |
| Ultra | Full | 2048 | 32 |

On the very first launch (no `config/gsr.json` yet), the preset is chosen from the GPU: Low for Intel (except discrete Arc), AMD integrated GPUs, GeForce MX/GT and cards with under 1 GiB of video memory; Medium under 3 GiB; High otherwise; GSR left disabled for software renderers. Ultra is never selected automatically. The splats per page cannot change while the game runs: picking a preset in the screen shows "restart to finish" and that value applies at the next startup (the other fields apply immediately). Known limitation: after picking a preset in the screen, `/gsr reload` followed by a save can overwrite a hand-edited `maxSplatsPerPage` with the pending preset value.

Verified on 2026-10-14 (Windows 11, RX 7800 XT): a fresh install logs `GSR: GPU "ATI Technologies Inc." / "AMD Radeon RX 7800 XT" (15293 MiB free) -> preset HIGH`; an existing `gsr.json` was kept unchanged except for `gpuDetectionDone`; the Preset row was seen in the settings screen (High, Low with "restart to finish", Medium/Custom/Ultra with F8). With 1024 splats per page at half resolution, the splat SSBO goes from 66.0 to 34.0 MiB and the splats per level from 89k/176k/106k/18k to 88k/163k/93k/17k; GPU time was unchanged on this GPU and no difference was visible at the reference spot.

**Keys** (rebindable in *Options > Controls > Key Binds*, "GSR Reflections" category):

| Key | Effect |
|---|---|
| F6 | Reflections on / off |
| F7 | Next debug view (Shift+F7: previous) |
| F8 | Next resolution (Shift+F8: previous) |
| F9 | Statistics overlay (GPU time per pass, pages, queues) |
| *unbound* | Open the GSR settings screen |

**Commands** (client-side, mainly for scripts and diagnostics):

| Command | Effect |
|---|---|
| `/gsr stats` | Pages and splats per level, queues, GPU time per pass, CPU line (client tick average/max with 3 decimals, worker jobs and ms/job), VRAM, biome tint |
| `/gsr debug normal\|ssr\|gsr\|raw\|hit\|refl\|cost` | Diagnostic views: normal rendering, SSR only, GSR only, GSR without Fresnel, hits, final hybrid reflection, GSR cost (steps / splats per ray) |
| `/gsr scale 1\|2\|3\|4` | Reflection resolution: 1 = full, 2 = half (default), 3 = third, 4 = quarter |
| `/gsr bricks on\|off` | Enables or disables the per-brick occupancy mask (diagnostic) |
| `/gsr reload` | Re-reads `config/gsr.json` |

**`config/gsr.json`** is created with all default values (or the preset chosen for your GPU on first launch). Missing fields are rewritten and values are clamped. An unreadable file is kept as is and the default values are used. `/gsr reload` applies the rendering and budget fields live. The number of levels, the pages, the number of splats per page, the SSBO binding point and the threads require a restart.

## Measured performance

`/gsr stats` measurements at 2560×1369, on an RX 7800 XT, in a plain facing a lake, during the day:

| Step | GPU total |
|---|---|
| Before optimization (full resolution) | ≈ 2.95 ms |
| Half resolution (default) | ≈ 1.24 ms |
| After brick mask and lightened temporal pass | ≈ 0.72 ms |
| After octant skipping and batched SSR reads (2026-10-14) | **≈ 0.65 ms** |

- Screen targets go from 120 MiB (full resolution) to about 40 MiB (half resolution).
- Reflection pass alone (2026-10-14, GPU kept at full clock): 1.45 ms at full resolution, 0.39 ms at half, 0.19 ms at a third, 0.14 ms at a quarter.
- The Gaussian field takes about 64 MiB of VRAM by default (1024 pages), about 32 MiB with the Low preset (1024 splats per page).

These figures come from a single machine. They say nothing about other GPUs.

## For developers

```bash
./gradlew build          # produces build/libs/gsr-<version>.jar
./gradlew coreSelfTest   # core tests, without Minecraft (52 checks)
./gradlew runClient      # launches the game with the mod
./gradlew runClient -PwithSodium   # or -PwithIris, to test compatibility
./gradlew runClient -PgsrFakeMaxSsboBlock=<bytes>   # dev only: fake SSBO block-size limit, to test the clean disable
./gradlew runClient -PgsrFakeSsboOom                # dev only: fake out-of-memory on SSBO allocation
./gradlew gsrTestPack    # assembles the test Iris pack in run/shaderpacks/GSR-TestPack
```

The core can also be tested without Gradle:

```bash
javac -d out src/main/java/dev/gsr/core/*.java src/test/java/dev/gsr/core/*.java && java -cp out dev.gsr.core.CoreSelfTest
```

**Architecture in brief**

- `dev.gsr.core`: cascaded clipmap (4 levels), multi-resolution section summaries, Gaussian surfel generation, page manager, GPU format and `CpuReferenceTracer`. This package **imports nothing from Minecraft**, which makes it testable on its own.
- `CpuReferenceTracer` is the line-by-line mirror of `gsrTrace()` (GLSL). Any change to one must be reflected in the other.
- `dev.gsr.mc`: section snapshots, material table (albedo taken from the real textures, biome tint), lighting.
- `dev.gsr.render`: SSBOs, screen targets, compilation and dispatch of the compute shaders.
- A **single mixin**, on `ClientWorld`. No mixin in Sodium or Iris.
- The shaders are in `src/main/resources/assets/gsr/shaders/`. They include `gsr_common.glsl` (shared with shaderpacks) and the depth copy, reflection, temporal and composite passes.
- The compute threads only read immutable snapshots, never the live world.

**Documentation**

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md): full architecture, design choices, measurements and verification status.
- [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md): using GSR from an Iris shaderpack (`gsrTrace()`, SSBO binding points 10 to 13).
- `examples/gsr-testpack`: minimal Iris pack that reflects water through `gsrTrace()` only.

## Credits

Some changes in this repository are produced and reviewed autonomously by AI agents and are not necessarily tested in game by Rimuru, unless stated otherwise.

All the code of this mod was written by Claude (Anthropic), an AI agent, under the direction and with the review of Rimuru, who tested it in game and validated each step. The original idea for GSR came from Garloulou.

## License

GSR is released under the [MIT License](LICENSE): you may use, modify and redistribute the code freely, including in other projects, as long as you keep the copyright notice — i.e. credit the GSR project (https://github.com/RimuruLeThug/GSR).
