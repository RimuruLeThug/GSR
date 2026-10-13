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
- Public shaderpacks. Complementary Reimagined and Unbound r5.9.3 were tested with Iris 1.8.8: they load normally and GSR switches to service mode, but they **do not call `gsrTrace()`**. **With these packs, GSR therefore brings no visible change**: their reflections remain their own. Integration is up to their authors. See [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md).
- Window sizes other than 2560×1369, for threshold tuning.

macOS is **not supported**, because it does not offer OpenGL 4.3.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.21.1.
2. Put **Fabric API** in the `mods/` folder.
3. Put the GSR jar (`gsr-<version>.jar`, *Releases* tab) in `mods/`. Sodium and Iris are optional.
4. Launch the game. The `config/gsr.json` file is created on first launch.

**OpenGL 4.3 is required.** If your machine does not provide it, the mod disables itself cleanly with a message in the log, without crashing. Detection also accepts drivers that report a 3.2 context but expose the required ARB extensions (the case with AMD drivers).

## In-game commands and configuration

All commands are client-side.

| Command | Effect |
|---|---|
| `/gsr stats` | Pages and splats per level, queues, GPU time per pass, VRAM, biome tint |
| `/gsr debug normal\|ssr\|gsr\|raw\|hit\|refl` | Diagnostic views: normals, SSR only, GSR only, GSR without Fresnel, hits, final hybrid reflection |
| `/gsr scale 1\|2` | Reflection computation resolution: 1 = full, 2 = half resolution (default) |
| `/gsr bricks on\|off` | Enables or disables the per-brick occupancy mask (diagnostic) |
| `/gsr reload` | Re-reads `config/gsr.json` |

**`config/gsr.json`** is created with all default values. Missing fields are rewritten and values are clamped. An unreadable file is kept as is and the default values are used. `/gsr reload` applies the rendering and budget fields live. The number of levels, the pages, the number of splats per page, the SSBO binding point and the threads require a restart.

## Measured performance

`/gsr stats` measurements at 2560×1369, on an RX 7800 XT, in a plain facing a lake, during the day:

| Step | GPU total |
|---|---|
| Before optimization (full resolution) | ≈ 2.95 ms |
| Half resolution (default) | ≈ 1.24 ms |
| After brick mask and lightened temporal pass | **≈ 0.72 ms** |

- Screen targets go from 120 MiB (full resolution) to about 40 MiB (half resolution).
- The Gaussian field takes about 64 MiB of VRAM by default (1024 pages).

These figures come from a single machine. They say nothing about other GPUs.

## For developers

```bash
./gradlew build          # produces build/libs/gsr-<version>.jar
./gradlew coreSelfTest   # core tests, without Minecraft (42 checks)
./gradlew runClient      # launches the game with the mod
./gradlew runClient -PwithSodium   # or -PwithIris, to test compatibility
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

All the code of this mod was written by Claude (Anthropic), an AI agent, under the direction and with the review of Rimuru, who tested it in game and validated each step. The original idea for GSR came from Garloulou.

## License

GSR is released under the [MIT License](LICENSE): you may use, modify and redistribute the code freely, including in other projects, as long as you keep the copyright notice — i.e. credit the GSR project (https://github.com/RimuruLeThug/GSR).
