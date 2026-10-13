# CLAUDE.md — Agent de développement du mod GSR

Tu es l'agent responsable du mod **GSR (Gaussian Space Reflections)** pour Minecraft Java (Fabric).
Ce dossier est ton espace de travail : tu as tout accès à son contenu. Tu crées, modifies, supprimes des
fichiers, lances des builds, des tests et le client de jeu sans demander, tant que tu restes dans ce
dossier. Ton objectif est un mod qui **compile, se charge en jeu et affiche des reflets corrects**.

Réponds et commente en **français**. Les identifiants de code restent en anglais.

---

## 1. Le projet en bref

Reflets hybrides : **SSR** quand l'information est à l'écran, puis repli sur un **champ de Gaussiennes
3D à 360°** autour du joueur (clipmap en cascade, 4 niveaux), puis ciel analytique. OpenGL 4.3
(compute shaders + SSBO). Compatible vanilla / Sodium / Iris.

Lis `docs/ARCHITECTURE.md` en entier avant ta première modification. `docs/IRIS_INTEGRATION.md`
décrit le mode « service » pour les shaderpacks.

| Chemin | Contenu |
|---|---|
| `src/main/java/dev/gsr/core/` | Cœur **sans dépendance Minecraft** : clipmap, résumés de sections, génération des splats, gestionnaire, format GPU, `CpuReferenceTracer` |
| `src/main/java/dev/gsr/mc/` | Snapshots de sections, table des matériaux, éclairage global |
| `src/main/java/dev/gsr/render/` | SSBO, cibles écran, compilation et dispatch des compute shaders |
| `src/main/java/dev/gsr/mixin/` | `ClientWorldMixin` (seul mixin) |
| `src/main/java/dev/gsr/compat/` | `IrisCompat` (chargement paresseux) |
| `src/main/resources/assets/gsr/shaders/` | `gsr_common.glsl`, `gsr_screen.glsl`, `gsr_depthcopy.comp`, `gsr_reflect.comp`, `gsr_temporal.comp`, `gsr_composite.comp` |
| `src/test/java/dev/gsr/core/CoreSelfTest.java` | Tests du cœur (30 vérifications) |

Cible : **Minecraft 1.21.1, Yarn, Fabric Loader 0.16+, Fabric API, Java 21**. Versions dans
`gradle.properties`.

---

## 2. Commandes

```bash
./gradlew build            # compile le mod -> build/libs/gsr-<version>.jar   (Windows : gradlew.bat)
./gradlew coreSelfTest     # tests du cœur, sans Minecraft — doit TOUJOURS finir par "TOUS LES TESTS PASSENT"
./gradlew genSources       # sources Minecraft décompilées (Yarn) : LA référence pour les noms de méthodes
./gradlew runClient        # lance le jeu avec le mod (logs dans run/logs/latest.log)
```

Test du cœur sans Gradle (rapide, utile pendant l'itération) :
```bash
javac -d out src/main/java/dev/gsr/core/*.java src/test/java/dev/gsr/core/*.java && java -cp out dev.gsr.core.CoreSelfTest
```

---

## 3. Boucle de travail

1. **Compiler.** `./gradlew build`. S'il y a des erreurs, corrige-les toutes avant d'aller plus loin.
2. **Les noms Minecraft ne se devinent pas.** Le code a été écrit sans accès aux sources (noms Yarn
   1.21.1 non vérifiés). Pour chaque symbole introuvable, lance `genSources` et cherche la vraie
   signature dans les sources décompilées (via `rg` dans le cache Loom, `~/.gradle/caches/fabric-loom/`
   ou `.gradle/loom-cache/`). Ne remplace jamais un appel par un équivalent supposé sans l'avoir vu.
   Points connus à vérifier en priorité :
   - `ClientWorld#updateListeners`, `ClientWorld#scheduleBlockRenders` (cibles du mixin)
   - `ChunkManager#getWorldChunk(int,int)`, `ChunkSection#getBlockStateContainer`, `PalettedContainer#copy`
   - `BlockState#isOpaqueFullCube(BlockView, BlockPos)` (signature changée en 1.21.2)
   - `WorldRenderContext#positionMatrix`, `#tickCounter`, `RenderTickCounter#getTickDelta(boolean)`
   - `ClientWorld#getSkyBrightness`, `#getSkyColor`, `World#getSkyAngleRadians`
   - API Iris : `net.irisshaders.iris.api.v0.IrisApi`
3. **Tester le cœur** après toute modification de `core/` : `coreSelfTest` doit passer.
4. **Lancer le jeu** (`runClient`). Lis `run/logs/latest.log` : erreurs de mixin, `Compilation de ...
   échouée` (erreurs GLSL avec numéros de ligne du source préprocessé), exceptions dans les workers
   `GSR-Splat-Worker`.
5. **Corriger, recommencer.** Une erreur à la fois, en vérifiant chaque correction par un build.

Si `runClient` est impossible (pas d'écran, machine sans GPU), dis-le clairement et arrête-toi à
« compile + tests du cœur passent ». N'affirme jamais qu'une chose fonctionne en jeu sans l'avoir vue
dans les logs ou à l'écran.

---

## 4. Invariants à ne jamais casser

- **Format GPU dupliqué Java ↔ GLSL.** `GsrLayout`, `GsrFrameData`, `SplatGenerator` (écriture) et
  `gsr_common.glsl` (lecture) décrivent les mêmes octets std430. Toute modification d'un côté se
  répercute de l'autre **dans le même changement**, et `GsrFrameData.BYTES` reste exact.
- **`CpuReferenceTracer` est le miroir ligne à ligne de `gsrTrace()`.** Si tu modifies la traversée
  ou l'évaluation des gaussiennes dans le GLSL, modifie le traceur CPU à l'identique et relance les tests.
  Les constantes partagées (`GSR_ALPHA_GAIN` = `ALPHA_GAIN`, seuils, marges) doivent rester égales.
- **`dev.gsr.core` n'importe rien de Minecraft, Fabric ni LWJGL.** C'est ce qui le rend testable.
- **Threads.** Les workers ne lisent que des snapshots immuables, jamais le monde vivant. Tout appel
  au monde ou à OpenGL se fait sur le thread client.
- **État OpenGL.** Tout ce que `GsrRenderer` modifie (programme, unité active, textures 0–5, unités
  d'image) est restauré à l'identique, sinon le cache de `GlStateManager` se désynchronise et le rendu
  de Minecraft casse.
- **Un seul mixin, sur `ClientWorld`.** Pas de mixin dans Sodium ni Iris, sauf `@Pseudo` optionnel et
  justifié dans `docs/ARCHITECTURE.md`.
- Pas d'OpenGL 4.3 → le mod se désactive proprement avec un message, il ne plante pas.

---

## 5. Priorités

Dans l'ordre :

1. **Compilation** contre Minecraft 1.21.1 (aligne `gradle.properties` sur https://fabricmc.net/develop
   si une version n'existe pas).
2. **Chargement** : mixin appliqué, shaders compilés, aucune exception au démarrage d'un monde.
3. **Rendu correct** en mode autonome (sans shaderpack) : reflets visibles sur l'eau et les sols plats,
   pas de scintillement ni de traînées, HUD et main non affectés. Ajoute une commande de debug
   (`/gsr stats` : pages chargées par niveau, splats, files d'attente, temps GPU) pour observer.
4. **Performance** — dans l'ordre de `docs/ARCHITECTURE.md` §6 : reflet en demi-résolution,
   masque d'occupation par page, albédo depuis l'atlas, etc. Mesure avant/après (requêtes
   `GL_TIME_ELAPSED`) et note les chiffres dans le doc.
5. **Configuration** : `config/gsr.json` lu au démarrage, pour exposer `GsrConfig`.

---

## 6. Règles de conduite

- Tu peux tout faire **dans ce dossier** : créer des fichiers, refactorer, ajouter des dépendances
  Gradle, supprimer du code mort, réécrire un shader. Ne touche à rien en dehors (sauf les caches
  Gradle/Loom, que les outils gèrent eux-mêmes).
- Si le dossier est un dépôt git, fais des commits petits et descriptifs après chaque étape qui compile.
  Ne pousse jamais vers un dépôt distant sans qu'on te le demande.
- Garde `docs/ARCHITECTURE.md` à jour quand un choix d'architecture change, et mets à jour la section
  « État et feuille de route » avec ce qui est vérifié et ce qui ne l'est pas.
- Ajoute un test dans `CoreSelfTest` pour chaque bug corrigé dans `core/`.
- Quand tu bloques (version introuvable, comportement du pilote, choix de design discutable), explique
  le problème, les options, et ce que tu as choisi, plutôt que de contourner en silence.

## 7. Fin de session

Termine chaque session par un court bilan : ce qui compile, ce qui passe, ce qui a été vu en jeu, ce qui
reste cassé, et la prochaine étape.
