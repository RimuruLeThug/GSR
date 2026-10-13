# GSR — Gaussian Space Reflections

> Des reflets d'eau qui montrent aussi ce qui est derrière vous, hors de l'écran : un mod Fabric pour Minecraft Java 1.21.1.

## Ce que fait le mod

Dans Minecraft normal, l'eau reflète surtout le ciel. Les techniques de reflets « en espace écran » (SSR) ne peuvent refléter que ce qui est déjà visible à l'écran. Dès qu'un arbre, une colline ou une berge est hors champ ou caché, le reflet disparaît.

GSR combine trois sources, de la plus précise à la moins précise :

1. **SSR (reflets en espace écran)** : quand l'information est à l'écran, c'est le reflet le plus net.
2. **Champ de Gaussiennes 3D à 360°** : sinon, le mod interroge une représentation 3D simplifiée du monde autour du joueur. Chaque face de bloc exposée devient une petite « tache » gaussienne (un *surfel*). Ces taches sont rangées en 4 niveaux de détail emboîtés (une *clipmap* : voxels de 1, 2, 4 et 8 blocs, jusqu'à environ 512 blocs de distance horizontale). Le reflet peut donc montrer le relief, les arbres et les berges hors écran.
3. **Ciel analytique** : en dernier recours, le ciel est calculé (soleil, heure, pluie, dimension).

Le tout tourne sur le GPU avec des **compute shaders OpenGL 4.3**, sans ray tracing matériel. Le champ de Gaussiennes est construit en arrière-plan par des threads CPU à partir de copies des sections du monde, puis envoyé au GPU. Le cycle jour/nuit ne déclenche aucune reconstruction.

Par défaut, l'eau calme se comporte comme un miroir (rugosité autonome 0). Le calcul des reflets se fait en demi-résolution, puis il est remis à l'échelle en s'appuyant sur la profondeur.

## Compatibilité

**Vérifié**

| Élément | Détail |
|---|---|
| Minecraft | 1.21.1 (mappings Yarn 1.21.1+build.3) |
| Fabric Loader | 0.16.5 (0.16+) |
| Fabric API | 0.115.0+1.21.1 |
| Java | 21 |
| Vanilla | Reflets corrects, main et HUD intacts |
| Sodium | 0.6.13 : reflets corrects |
| Iris | 1.8.8 sans shaderpack : mode autonome, reflets corrects |
| Iris 1.8.8 + shaderpack | Mode « service » : GSR met sa clipmap à disposition du pack, qui appelle `gsrTrace()`. Vérifié avec un pack de test minimal (`examples/gsr-testpack`) |
| Divers | Nuit, pluie, Nether (changement de dimension propre) et mode graphique « Fabuleux » |

Tout cela a été testé sur Windows 11, avec une RX 7800 XT et un pilote AMD.

**Non vérifié**

- Les pilotes **NVIDIA** et **Intel**.
- Les shaderpacks publics. Complementary Reimagined et Unbound r5.9.3 ont été testés avec Iris 1.8.8 : ils se chargent normalement et GSR passe en mode service, mais ils **n'appellent pas `gsrTrace()`**. **Avec ces packs, GSR n'apporte donc aucun changement visible** : leurs reflets restent les leurs. L'intégration relève de leurs auteurs. Voir [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md).
- D'autres tailles de fenêtre que 2560×1369, pour les réglages de seuils.

macOS n'est **pas supporté**, car il n'offre pas OpenGL 4.3.

## Installation

1. Installez [Fabric Loader](https://fabricmc.net/use/) pour Minecraft 1.21.1.
2. Placez **Fabric API** dans le dossier `mods/`.
3. Placez le jar de GSR (`gsr-<version>.jar`, onglet *Releases*) dans `mods/`. Sodium et Iris sont facultatifs.
4. Lancez le jeu. Le fichier `config/gsr.json` est créé au premier lancement.

**OpenGL 4.3 est requis.** Si votre machine ne le fournit pas, le mod se désactive proprement avec un message dans le log, sans planter. La détection accepte aussi les pilotes qui annoncent un contexte 3.2 mais exposent les extensions ARB nécessaires (cas des pilotes AMD).

## Commandes en jeu et configuration

Toutes les commandes sont côté client.

| Commande | Effet |
|---|---|
| `/gsr stats` | Pages et splats par niveau, files d'attente, temps GPU par passe, VRAM, teinte du biome |
| `/gsr debug normal\|ssr\|gsr\|raw\|hit\|refl` | Vues de diagnostic : normales, SSR seul, GSR seul, GSR sans Fresnel, impacts, reflet hybride final |
| `/gsr scale 1\|2` | Résolution du calcul des reflets : 1 = pleine, 2 = demi-résolution (défaut) |
| `/gsr bricks on\|off` | Active ou coupe le masque d'occupation par brique (diagnostic) |
| `/gsr reload` | Relit `config/gsr.json` |

**`config/gsr.json`** est créé avec toutes les valeurs par défaut. Les champs manquants sont réécrits et les valeurs sont bornées. Un fichier illisible est conservé tel quel et les valeurs par défaut sont utilisées. `/gsr reload` applique à chaud les champs de rendu et de budget. Le nombre de niveaux, les pages, le nombre de splats par page, le point de liaison des SSBO et les threads demandent un redémarrage.

## Performances mesurées

Mesures de `/gsr stats` à 2560×1369, sur une RX 7800 XT, en plaine face à un lac, de jour :

| Étape | Total GPU |
|---|---|
| Avant optimisation (pleine résolution) | ≈ 2,95 ms |
| Demi-résolution (défaut) | ≈ 1,24 ms |
| Après masque de briques et passe temporelle allégée | **≈ 0,72 ms** |

- Les cibles écran passent de 120 Mio (pleine résolution) à environ 40 Mio (demi-résolution).
- Le champ de Gaussiennes occupe environ 64 Mio de VRAM par défaut (1024 pages).

Ces chiffres sont ceux d'une seule machine. Ils ne disent rien des autres GPU.

## Pour les développeurs

```bash
./gradlew build          # produit build/libs/gsr-<version>.jar
./gradlew coreSelfTest   # tests du cœur, sans Minecraft (42 vérifications)
./gradlew runClient      # lance le jeu avec le mod
./gradlew runClient -PwithSodium   # ou -PwithIris, pour tester la compatibilité
./gradlew gsrTestPack    # assemble le pack Iris de test dans run/shaderpacks/GSR-TestPack
```

Le cœur peut aussi se tester sans Gradle :

```bash
javac -d out src/main/java/dev/gsr/core/*.java src/test/java/dev/gsr/core/*.java && java -cp out dev.gsr.core.CoreSelfTest
```

**Architecture en bref**

- `dev.gsr.core` : clipmap en cascade (4 niveaux), résumés de sections multi-résolution, génération des surfels gaussiens, gestionnaire de pages, format GPU et `CpuReferenceTracer`. Ce paquet **n'importe rien de Minecraft**, ce qui le rend testable seul.
- `CpuReferenceTracer` est le miroir ligne à ligne de `gsrTrace()` (GLSL). Toute modification de l'un se répercute sur l'autre.
- `dev.gsr.mc` : snapshots de sections, table des matériaux (albédo issu des textures réelles, teinte de biome), éclairage.
- `dev.gsr.render` : SSBO, cibles écran, compilation et dispatch des compute shaders.
- Un **seul mixin**, sur `ClientWorld`. Aucun mixin dans Sodium ni Iris.
- Les shaders sont dans `src/main/resources/assets/gsr/shaders/`. Ils comprennent `gsr_common.glsl` (partagé avec les shaderpacks) et les passes copie de profondeur, reflet, temporelle et composition.
- Les threads de calcul ne lisent que des snapshots immuables, jamais le monde vivant.

**Documentation**

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) : architecture complète, choix de conception, mesures et état de vérification.
- [`docs/IRIS_INTEGRATION.md`](docs/IRIS_INTEGRATION.md) : utiliser GSR depuis un shaderpack Iris (`gsrTrace()`, points de liaison SSBO 10 à 13).
- `examples/gsr-testpack` : pack Iris minimal qui reflète l'eau uniquement par `gsrTrace()`.

## Auteurs

Tout le code de ce mod a été écrit par Claude (Anthropic), un agent IA, sous la direction et avec la relecture de Rimuru, qui a testé en jeu et validé chaque étape.

Licence : à définir.
