# CLAUDE.md — GSR mod development agent

You are the agent responsible for the **GSR (Gaussian Space Reflections)** mod for Minecraft Java (Fabric).
This folder is your workspace: you have full access to its contents. You create, modify and delete
files, run builds, tests and the game client without asking, as long as you stay inside this
folder. Your goal is a mod that **compiles, loads in game and displays correct reflections**.

Reply to the user in French; all code, comments, documentation and commit messages are written in English.

---

## 1. The project in brief

Hybrid reflections: **SSR** when the information is on screen, then fallback to a **360° 3D Gaussian
field** around the player (cascaded clipmap, 4 levels), then analytic sky. OpenGL 4.3
(compute shaders + SSBO). Compatible with vanilla / Sodium / Iris.

Read `docs/ARCHITECTURE.md` in full before your first change. `docs/IRIS_INTEGRATION.md`
describes the "service" mode for shaderpacks.

| Path | Contents |
|---|---|
| `src/main/java/dev/gsr/core/` | Core **with no Minecraft dependency**: clipmap, section summaries, splat generation, manager, GPU format, `CpuReferenceTracer` |
| `src/main/java/dev/gsr/mc/` | Section snapshots, material table, global lighting |
| `src/main/java/dev/gsr/render/` | SSBOs, screen targets, compute shader compilation and dispatch |
| `src/main/java/dev/gsr/mixin/` | `ClientWorldMixin` (the only mixin) |
| `src/main/java/dev/gsr/compat/` | `IrisCompat` (lazy loading) |
| `src/main/resources/assets/gsr/shaders/` | `gsr_common.glsl`, `gsr_screen.glsl`, `gsr_depthcopy.comp`, `gsr_reflect.comp`, `gsr_temporal.comp`, `gsr_composite.comp` |
| `src/test/java/dev/gsr/core/CoreSelfTest.java` | Core tests (42 checks) |

Target: **Minecraft 1.21.1, Yarn, Fabric Loader 0.16+, Fabric API, Java 21**. Versions in
`gradle.properties`.

---

## 2. Commands

```bash
./gradlew build            # compiles the mod -> build/libs/gsr-<version>.jar   (Windows: gradlew.bat)
./gradlew coreSelfTest     # core tests, without Minecraft — must ALWAYS end with "ALL TESTS PASSED"
./gradlew genSources       # decompiled Minecraft sources (Yarn): THE reference for method names
./gradlew runClient        # launches the game with the mod (logs in run/logs/latest.log)
```

Core test without Gradle (fast, useful while iterating):
```bash
javac -d out src/main/java/dev/gsr/core/*.java src/test/java/dev/gsr/core/*.java && java -cp out dev.gsr.core.CoreSelfTest
```

---

## 3. Work loop

1. **Compile.** `./gradlew build`. If there are errors, fix them all before going further.
2. **Minecraft names cannot be guessed.** The code was written without access to the sources (Yarn
   1.21.1 names unverified). For every symbol that cannot be found, run `genSources` and look up the real
   signature in the decompiled sources (via `rg` in the Loom cache, `~/.gradle/caches/fabric-loom/`
   or `.gradle/loom-cache/`). Never replace a call with an assumed equivalent without having seen it.
   Known points to check first:
   - `ClientWorld#updateListeners`, `ClientWorld#scheduleBlockRenders` (mixin targets)
   - `ChunkManager#getWorldChunk(int,int)`, `ChunkSection#getBlockStateContainer`, `PalettedContainer#copy`
   - `BlockState#isOpaqueFullCube(BlockView, BlockPos)` (signature changed in 1.21.2)
   - `WorldRenderContext#positionMatrix`, `#tickCounter`, `RenderTickCounter#getTickDelta(boolean)`
   - `ClientWorld#getSkyBrightness`, `#getSkyColor`, `World#getSkyAngleRadians`
   - Iris API: `net.irisshaders.iris.api.v0.IrisApi`
3. **Test the core** after any change to `core/`: `coreSelfTest` must pass.
4. **Launch the game** (`runClient`). Read `run/logs/latest.log`: mixin errors, compilation-failed
   messages (GLSL errors with line numbers of the preprocessed source), exceptions in the
   `GSR-Splat-Worker` workers.
5. **Fix, repeat.** One error at a time, verifying each fix with a build.

If `runClient` is impossible (no display, machine without a GPU), say so clearly and stop at
"compile + core tests pass". Never claim that something works in game without having seen it
in the logs or on screen.

---

## 4. Invariants never to break

- **GPU format duplicated Java ↔ GLSL.** `GsrLayout`, `GsrFrameData`, `SplatGenerator` (writing) and
  `gsr_common.glsl` (reading) describe the same std430 bytes. Any change on one side is
  mirrored on the other **in the same change**, and `GsrFrameData.BYTES` stays exact.
- **`CpuReferenceTracer` is the line-by-line mirror of `gsrTrace()`.** If you modify the traversal
  or the Gaussian evaluation in the GLSL, modify the CPU tracer identically and rerun the tests.
  Shared constants (`GSR_ALPHA_GAIN` = `ALPHA_GAIN`, thresholds, margins) must stay equal.
- **`dev.gsr.core` imports nothing from Minecraft, Fabric or LWJGL.** That is what makes it testable.
- **Threads.** Workers only read immutable snapshots, never the live world. Every call
  to the world or to OpenGL happens on the client thread.
- **OpenGL state.** Everything `GsrRenderer` modifies (program, active unit, textures 0–5, image
  units) is restored identically, otherwise the `GlStateManager` cache gets out of sync and Minecraft's
  rendering breaks.
- **A single mixin, on `ClientWorld`.** No mixin in Sodium or Iris, except an optional, justified `@Pseudo`
  documented in `docs/ARCHITECTURE.md`.
- No OpenGL 4.3 → the mod disables itself cleanly with a message, it does not crash.

---

## 5. Priorities

In order:

1. **Compilation** against Minecraft 1.21.1 (align `gradle.properties` with https://fabricmc.net/develop
   if a version does not exist).
2. **Loading**: mixin applied, shaders compiled, no exception when starting a world.
3. **Correct rendering** in standalone mode (without a shaderpack): reflections visible on water and flat floors,
   no flicker or trails, HUD and hand unaffected. Add a debug command
   (`/gsr stats`: pages loaded per level, splats, queues, GPU time) to observe.
4. **Performance** — in the order of `docs/ARCHITECTURE.md` §6: half-resolution reflection,
   per-page occupancy mask, albedo from the atlas, etc. Measure before/after (`GL_TIME_ELAPSED`
   queries) and record the figures in the doc.
5. **Configuration**: `config/gsr.json` read at startup, to expose `GsrConfig`.

---

## 6. Rules of conduct

- You may do anything **in this folder**: create files, refactor, add Gradle dependencies, remove
  dead code, rewrite a shader. Touch nothing outside it (except the Gradle/Loom caches,
  which the tools manage themselves).
- If the folder is a git repository, make small, descriptive commits after each step that compiles.
  Never push to a remote repository unless asked.
- Keep `docs/ARCHITECTURE.md` up to date when an architecture choice changes, and update the
  "State and roadmap" section with what is verified and what is not.
- Add a test in `CoreSelfTest` for every bug fixed in `core/`.
- When you are blocked (version not found, driver behavior, debatable design choice), explain
  the problem, the options, and what you chose, rather than working around it silently.

## 7. End of session

End every session with a short summary: what compiles, what passes, what was seen in game, what
is still broken, and the next step.
