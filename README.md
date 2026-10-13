# GSR — Gaussian Space Reflections (Fabric 1.21.1)

Reflets hybrides pour Minecraft : SSR à l'écran, champ de Gaussiennes 3D à 360° (clipmap en cascade)
hors écran, ciel analytique en dernier recours. OpenGL 4.3, compatible vanilla / Sodium / Iris.

* Architecture complète : [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
* Intégration shaderpack : [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md)

## Arborescence

```
src/main/java/dev/gsr/
  GsrClient.java                 entrée Fabric, câblage des événements
  core/                          indépendant de Minecraft (testable seul)
    GsrLayout, GsrConfig         format GPU partagé avec le GLSL, paramètres
    GaussianClipmap, PageKey     fenêtres toroïdales par niveau
    VoxelSource, SectionSummary  snapshot de section, résumé multi-résolution
    VoxelGrid, SplatGenerator    voxels -> surfels gaussiens triés par cellule
    GaussianChunkManager         files, workers, versionnage, anti-rebond, uploads
    GsrFrameData, GpuSink        SSBO "frame", interface d'upload
    CpuReferenceTracer           miroir CPU exact de gsrTrace() (tests / debug)
  mc/       McSnapshotSource, BlockMaterialTable, GsrLighting
  render/   GsrGpuResources (SSBO), GsrScreenTargets, GsrShaderLoader, GsrRenderer
  mixin/    ClientWorldMixin
  compat/   IrisCompat
src/main/resources/assets/gsr/shaders/
  gsr_common.glsl     structures, SSBO, traversée de la clipmap gaussienne (partagé avec Iris)
  gsr_screen.glsl     reconstruction, GGX VNDF, bruit
  gsr_reflect.comp    passe 1 : SSR -> GSR -> ciel
  gsr_temporal.comp   passe 2 : reprojection hybride + clipping
  gsr_composite.comp  passe 3 : Fresnel + composition
src/test/java/dev/gsr/core/CoreSelfTest.java
```

## Compiler / tester

```bash
./gradlew build            # nécessite le wrapper Gradle (gradle wrapper) et l'accès à maven.fabricmc.net
./gradlew coreSelfTest     # tests du cœur, sans Minecraft

# ou sans Gradle :
javac -d out src/main/java/dev/gsr/core/*.java src/test/java/dev/gsr/core/*.java
java -cp out dev.gsr.core.CoreSelfTest
```

Les versions de `gradle.properties` (Yarn, Fabric API, Iris) sont à aligner sur
https://fabricmc.net/develop avant la première compilation.

## En jeu

* `config/gsr.json` : créé au premier lancement avec toutes les valeurs par défaut ; `/gsr reload` le
  relit (niveaux, pages, splats par page, liaisons SSBO et threads : au redémarrage).
* `/gsr stats` : pages et splats par niveau, files, temps GPU par passe, VRAM, teinte du biome.
* `/gsr debug normal|ssr|gsr|raw|hit|refl`, `/gsr bricks on|off`, `/gsr scale 1|2` : diagnostic.
* Compatibilité : `./gradlew runClient -PwithSodium` ou `-PwithIris` pour tester avec Sodium / Iris.
