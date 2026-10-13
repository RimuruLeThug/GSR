# GSR — Gaussian Space Reflections : architecture

Mod Fabric (Minecraft 1.21.1, Yarn) ajoutant des reflets hybrides : **SSR** quand l'information est
à l'écran, **champ de Gaussiennes 3D à 360°** autour du joueur quand elle ne l'est pas, ciel analytique
en dernier recours. Aucun ray tracing matériel : uniquement OpenGL 4.3 (compute + SSBO).

## 1. Vue d'ensemble

```
 MONDE (ClientWorld)                CPU (workers)                         GPU
 ──────────────────                 ─────────────                         ───
 bloc modifié ─┐                                                    SSBO frame   (208 o)
 lumière ──────┼─► file de capture ─► snapshot  ──► SectionSummary ─┐  SSBO meta   (32 o / page)
 chunk chargé ─┘   (priorité         (copie         (mips 2/4/8 b)  │  SSBO cells  (512 / page)
                    distance)        Paletted-                      │  SSBO splats (slab / page)
                                     Container)  ┌─ si page L0 ─────┤        │
                                                 ▼                  ▼        │
                                         VoxelGrid 18³  ◄── résumés (L1..L3) │
                                                 │                           │
                                         SplatGenerator ──► PageData ──► upload (≤ N/frame)
                                         (faces exposées → surfels gaussiens,
                                          tri par cellule, budget par page)

 FRAME :  START ─ bind SSBO ─┬─ [mode autonome] LAST : reflect.comp ─► temporal.comp ─► composite.comp
                             └─ [Iris actif]   le shaderpack appelle gsrTrace() dans sa passe de reflets
```

| Paquet | Rôle | Dépend de MC |
|---|---|---|
| `dev.gsr.core` | clipmap, résumés, génération des splats, gestionnaire, format GPU, traceur CPU de référence | **non** (testé seul) |
| `dev.gsr.mc` | snapshots de sections, table des matériaux, éclairage global | oui |
| `dev.gsr.render` | SSBO, cibles écran, compilation et dispatch des compute shaders | oui (LWJGL) |
| `dev.gsr.mixin` | 1 mixin sur `ClientWorld` | oui |
| `assets/gsr/shaders` | `gsr_common.glsl` (traversée GSR), `gsr_screen.glsl`, 3 passes compute | — |

### Points d'intégration Fabric — et pourquoi ceux-là

* **`ClientWorldMixin`** (`updateListeners`, `scheduleBlockRenders`) : seul mixin. On n'intercepte
  **pas** le `ChunkBuilder` : Sodium le remplace entièrement (et Iris s'appuie sur Sodium). `ClientWorld`
  n'est touché par aucun des deux → une seule base de code pour vanilla / Sodium / Iris.
* **Fabric API** : `ClientChunkEvents` (chargement/déchargement), `ClientTickEvents` (planification),
  `WorldRenderEvents.START/LAST/END` (GPU). Ces événements sont émis aussi sous Sodium.
* **Iris** : quand un shaderpack est actif, le mode autonome se coupe et GSR devient un *service* :
  ses 4 SSBO restent liés à des points de liaison fixes (10–13 par défaut) pendant toute la frame. Le
  pack inclut `gsr_common.glsl` et appelle `gsrTrace()` dans sa propre passe de reflets, avec son vrai
  G-buffer (normales, rugosité labPBR). Aucun mixin dans Iris → pas de fragilité de version.
  Voir `IRIS_INTEGRATION.md`.

## 2. Génération dynamique des splats

**Choix : threads CPU asynchrones, pas de compute shader.** Les données de blocs vivent côté CPU ; les
envoyer au GPU pour les convertir coûterait plus en bande passante (4096 états × 7 sections) que
d'envoyer le résultat (quelques centaines de splats de 32 o). Le GPU est réservé au rendu.

1. **Snapshot (thread principal, ~µs)** : copie des `PalettedContainer` + `ChunkNibbleArray` de la
   section et de ses 6 voisines de face. Les workers ne touchent jamais au monde vivant.
2. **`SectionSummary` (worker)** : en une passe sur 4096 blocs, mips 1/2/3 (voxels de 2/4/8 blocs) :
   genre majoritaire (seuil 50 %), albédo moyen *des blocs de surface*, lumière moyenne de l'air,
   émission max. ≈ 4 Kio/section ; les sections d'air partagent un singleton.
3. **`VoxelGrid` 18³** : grille unique pour tous les niveaux. L0 vient du snapshot ; L1–L3 sont
   assemblés **uniquement depuis les résumés** (une page L3 couvre 512 sections : relire 2 M de blocs
   serait prohibitif).
4. **`SplatGenerator`** : une gaussienne par **face exposée** (voisin = air, ou opaque contre
   translucide). L'intérieur des montagnes ne coûte rien. Chaque splat est un *surfel* gaussien oblate :
   `Σ = σt²(I − nnᵀ) + σn²nnᵀ` avec σt = 0,55 voxel, σn = 0,1 voxel.
   Au-delà du budget (2048/page), on garde en priorité les faces exposées au ciel.
   Tri par cellule (2³ voxels) par counting sort → table `offset | count<<16`.
5. **Anti-rebond** : les pages grossières sont reconstruites après 300 ms (L1), 0,9 s (L2), 2,7 s (L3),
   et seulement si l'empreinte du résumé a changé (un changement de lumière invisible à 8 blocs de
   résolution ne coûte rien).

Format d'un splat (std430, 32 o) :

| champ | contenu |
|---|---|
| `posSigmaT.xyz` | position **relative à l'origine de la page** (pas de perte de précision à ±30 M blocs) |
| `posSigmaT.w` | σ tangentiel |
| `data.x` | albédo sRGB + opacité `0xAARRGGBB` |
| `data.y` | normale octaédrique 8+8 · lumière ciel/bloc 4+4 · émission 4 · niveau 2 |
| `data.z` | σ normal (bits float) |
| `data.w` | réservé (ID matériau, rugosité réfléchie…) |

Les splats ne stockent **pas** de radiance : seulement albédo + niveaux de lumière. L'éclairage
(soleil, heure, pluie, dimension) est appliqué au moment de l'échantillonnage → le cycle jour/nuit ne
déclenche aucune reconstruction.

## 3. Mémoire : la clipmap gaussienne en cascade

| Niveau | Voxel | Page | Fenêtre 8×4×8 pages | Usage |
|---|---|---|---|---|
| L0 | 1 bloc | 16 blocs (= 1 section) | ±64 h / ±32 v | reflets nets proches (miroirs, eau calme) |
| L1 | 2 blocs | 32 | ±128 / ±64 | |
| L2 | 4 blocs | 64 | ±256 / ±128 | |
| L3 | 8 blocs | 128 | ±512 / ±256 | relief lointain, reflets rugueux |

* **Stockage toroïdal** : la page `p` occupe le slot `floorMod(p, dims)`. Quand le joueur avance, seules
  les pages qui entrent dans la fenêtre sont reconstruites ; rien n'est copié ni décalé en VRAM.
* **Slot = adresse** : 1 page ↔ 1 slab fixe → aucun allocateur, aucune fragmentation. La méta
  `GsrPageMeta { ivec4(pageXYZ, count); uvec4 mask }` permet au shader de rejeter un slot périmé ;
  `mask.xy` est le **masque de briques** : 1 bit par brique de 2×2×2 cellules (4³ voxels), 64 par page.
* **Budget par défaut** : 1024 slots × 64 Kio = **64 Mio** de splats + 2 Mio de cellules + 32 Kio de
  méta. Réglable (`pagesX/Y/Z`, `levels`, `maxSplatsPerPage`).
* Cibles écran du mode autonome : profondeur opaque en pleine résolution (4 o/pixel) + reflet,
  historique et distance en basse résolution (32 o/texel). En demi-résolution (défaut) : 12 o/pixel,
  ≈ 40 Mio en 2560×1369 (calculé ; 120 Mio mesurés en pleine résolution).

### Demi-résolution (`standaloneResolutionScale`, `/gsr scale 1|2`)
Les passes 1 et 2 travaillent sur une grille ÷`scale`. Chaque texel choisit un **pixel représentatif**
dans son bloc `scale²` : le premier, dans un ordre fixe, qui porte un reflet (`gsrRepresentative`).
Un bloc à cheval sur un bord d'eau garde donc un échantillon d'eau, et le choix est déterministe :
les deux passes retrouvent le même pixel sans cible intermédiaire. La passe temporelle lit
l'historique décalé de (centre du texel − pixel représentatif). Sans ce décalage, chaque frame
rééchantillonnerait l'historique d'¼ de texel, ce qui flouterait l'image en continu même caméra immobile.
La composition (pleine résolution) agrège les 4 texels voisins avec une **pondération bilinéaire ×
similarité de distance caméra** (tolérance 2 % + 5 cm). Cette distance est celle de la cible
`distance` de la passe temporelle. Avec `scale = 1`, on retrouve exactement l'ancien comportement.

## 4. Pipeline de rendu

### Passe 0 — `gsr_depthcopy.comp` : masque matériau du mode autonome (sans mixin)
Sans G-buffer, le mode autonome doit deviner *où* refléter. Un simple masque « normale vers le haut »
transformait herbe et cimes d'arbres en miroirs (voile laiteux aux angles rasants, vu en jeu). À
`WorldRenderEvents.BEFORE_DEBUG_RENDER` — vérifié dans le bytecode de `WorldRenderer#render` 1.21.1 :
le rendu debug précède la couche translucide — on copie la profondeur **opaque** (R32F). Aux passes
suivantes, un pixel dont la profondeur finale est plus proche que l'opaque (marge 0,02 + 0,2 % de la
distance) porte une surface translucide (eau, glace, verre) → reflet à pleine force ; les sols opaques
orientés vers le haut reçoivent `standaloneFloorStrength` (0 par défaut). En mode « Fabuleux », l'eau
est dans le framebuffer translucide : les passes lisent alors sa profondeur. Si la copie n'a pas eu lieu
pendant la frame (redimensionnement), le reflet est sauté plutôt que faux.

### Passe 1 — `gsr_reflect.comp` : SSR → GSR → ciel
1. Normale (reconstruite depuis la profondeur en mode autonome ; G-buffer sous Iris), rugosité, masque.
2. Direction : `reflect()` si rugosité < 0,03, sinon microfacette **GGX VNDF** (Heitz 2018) tirée avec
   IGN + séquence R2 décalée par frame.
3. **SSR** : marche en espace écran à z interpolé en perspective + raffinement binaire. Confiance = 0 si
   le rayon sort de l'écran **ou passe derrière un objet** (épaisseur dépassée), fondu près des bords.
4. **Repli GSR** si confiance < 1 : `gsrTrace()` = cône-marche dans la clipmap. À chaque pas :
   * niveau = le plus fin dont le voxel (2^L) ≥ empreinte du cône **et** dont la fenêtre contient le
     point (`coneTan` = f(rugosité) : un reflet flou utilise d'office des splats plus gros → moins de pas) ;
   * page absente/vide → saut d'une page entière ; brique vide (bit nul dans la méta, déjà lue) →
     saut de 4³ voxels sans lire `cells[]` ; sinon évaluation des splats de la cellule ;
   * **intégrale analytique de la gaussienne le long du rayon** (pas de rastérisation, pas de tri) :
     `t* = −dᵀΣ⁻¹o / dᵀΣ⁻¹d`, `m² = oᵀΣ⁻¹o − (dᵀΣ⁻¹o)²/dᵀΣ⁻¹d`, `α = op·min(1, 1,6·e^(−m²/2))`
     (noyau « à sommet plat » : un mur pavé de surfels est réellement opaque) ;
   * composition avant→arrière exacte entre cellules (DDA), moyenne pondérée α dans une cellule ;
   * surfels à sens unique (pas d'auto-intersection, pas de fuite à travers une face).
5. `mix(GSR + T·ciel, SSR, confiance)` ; sortie rgb + distance d'impact.

> Pourquoi un cône-marche et pas un « buffer GSR projeté » ? Une projection des splats depuis la caméra
> ne contient, par construction, que ce que la caméra voit — exactement la limite du SSR. Pour les rayons
> hors écran il faut interroger la représentation 3D dans une direction arbitraire. La grille de cellules
> par page rend cette requête bornée (~80 cellules dans le pire cas pour un rayon miroir de 512 blocs).

### Passe 2 — `gsr_temporal.comp` : accumulation
* **Reprojection hybride** selon la rugosité : surface (rugueux) vs **image virtuelle** `P + V·d_impact`
  (lisse) — le reflet d'un miroir suit la parallaxe de l'objet réfléchi.
* Rejet de désocclusion via la distance caméra de la frame précédente (cible R32F ping-pong).
* Clipping YCoCg du voisinage 3×3 : γ = 1 (lisse, serré) → 2,5 (rugueux, large).
* Historique : 12 frames max (lisse : pas de traînées) → 48 (rugueux : élimine le bruit GGX).

**Limite honnête** : l'accumulation supprime le bruit et le crénelage, mais elle ne crée pas de détail
au-delà de la résolution des splats (1 bloc en L0). Les détails plus fins qu'un bloc viennent du SSR ;
pour aller plus loin, il faut subdiviser les faces L0 (2×2 surfels colorés depuis l'atlas).

### Passe 3 — `gsr_composite.comp`
Fresnel via approximation analytique de la BRDF d'environnement (Karis), F0 = 0,02 ; mélange en
linéaire, écriture `rgba8` dans le framebuffer principal **avant** la main et le HUD.

## 5. Threads, synchronisation, robustesse

* Thread client : événements, captures (budget 48/tick, plus proches d'abord), planification, uploads
  (budget 32 pages/frame), rendu.
* Workers (`cores/4`, priorité basse) : résumés + génération. Données immuables en entrée, `PageData`
  en sortie via file sans verrou.
* **Versionnage par page** : un résultat est jeté si une génération plus récente a été lancée ou si la
  page est sortie de la fenêtre entre-temps.
* Ordre d'upload : splats → cellules → méta (la méta valide la page en dernier).
* État GL : programme, unité active et textures 0–5 relus puis restaurés à l'identique (cohérence du
  cache `GlStateManager`). Les SSBO et unités d'image ne sont pas utilisés par Minecraft.
* Pas d'OpenGL 4.3 (macOS) → GSR se désactive proprement. **Détection** : Minecraft demande un contexte
  3.2 Core et les pilotes AMD annoncent alors exactement « 3.2 » (vu en jeu : `3.2.0 Core Profile
  Context` sur RX 7800 XT, GLSL 4.60), donc `GLCapabilities.OpenGL43` est faux. On accepte aussi
  `ARB_compute_shader + ARB_shader_storage_buffer_object + ARB_shader_image_load_store +
  ARB_texture_storage` avec GLSL ≥ 4.30 (LWJGL partage les pointeurs de fonctions core/ARB).

## 6. État et feuille de route

**Vérifié** : tout `dev.gsr.core` compile et passe 33 vérifications (`CoreSelfTest`, dont les stats, la file de captures et le masque de briques) : format std430, clé de
section signée, budget et tri par cellule, résumés, et — via `CpuReferenceTracer`, miroir ligne à ligne
de `gsrTrace()` sur les mêmes octets — impacts au sol (L0), sur un pilier hors fenêtre L0 (trouvé en
L1), dans l'eau translucide, en cône rugueux, après destruction de blocs et après recentrage toroïdal.

**Vérifié le 2026-10-13** (Windows 11, RX 7800 XT, pilote AMD 26.8.1, vanilla sans Sodium/Iris) :
* compilation contre Minecraft 1.21.1 / Yarn build.3 / Fabric API 0.115.0 (versions de
  `gradle.properties` inchangées) ; cibles du mixin (`updateListeners`, `scheduleBlockRenders`) vues
  dans le jar remappé avec les bonnes signatures ;
* chargement : mod initialisé, entrée dans un monde solo sans exception, les 4 compute shaders
  compilent et se lient sur le pilote AMD ;
* rendu (captures d'écran, caméra immobile) : l'eau reflète le relief et le soleil, l'herbe et les
  arbres ne sont plus réfléchissants, main et HUD intacts.

* reflet du soleil validé par l'utilisateur : disque `pow(sd, 3000) × 20`, compression logarithmique
  au-delà d'une luminance de 8 (`gsr_composite.comp`) ;
* stabilité en mouvement : aucun scintillement constaté par l'utilisateur (en marchant et en tournant
  la caméra au bord de l'eau) ;
* `/gsr stats` (commande client) : pages valides / non vides / splats par niveau, files, temps GPU
  par passe (requêtes `GL_TIME_ELAPSED` en anneau de 4 frames, jamais bloquantes), VRAM.

**Mesures de référence** (avant toute optimisation ; 2560×1369, RX 7800 XT, plaine + lac, jour) :

| Passe | Immobile face à l'eau | Après 10 s de course |
|---|---|---|
| copie profondeur | 0,02 ms | 0,02 ms |
| reflet (SSR + GSR) | 2,48 ms | 1,93 ms |
| temporel | 0,32 ms | 0,44 ms |
| composition | 0,13 ms | 0,08 ms |
| **total** | **2,95 ms** | **2,47 ms** |

Splats chargés : ≈ 60–80 k (L0), 120–160 k (L1), 100–115 k (L2), 15–18 k (L3) ; VRAM 66 Mio de SSBO
+ 120 Mio de cibles écran (36 o/pixel). Téléversements à l'arrêt : 0 page/frame.

**Bug corrigé grâce à ces stats** : la file de captures restait à ~100 000 entrées. La couverture L3
(±512 blocs) dépasse la distance de rendu ; chaque section non chargée consommait une capture du
budget, si bien que la file ne se vidait jamais. Désormais seule une vraie copie consomme le budget
(tentatives plafonnées à 64 × budget par tick). Test de non-régression dans `CoreSelfTest`.

**Demi-résolution (2026-10-13), vérifiée en jeu** : pas de liseré aux bords de l'eau ni autour des
silhouettes (constaté par l'utilisateur). Mesures `/gsr stats` au même endroit, face à l'eau
(2560×1369, RX 7800 XT) :

| Passe | `/gsr scale 1` | `/gsr scale 2` (défaut) |
|---|---|---|
| copie profondeur | 0,02 ms | 0,02 ms |
| reflet (SSR + GSR) | 2,49 ms | **0,81 ms** |
| temporel | 0,41 ms | 0,33 ms |
| composition | 0,13 ms | 0,08 ms |
| **total** | **3,04 ms** | **1,24 ms** (−59 %) |
| cibles écran (VRAM) | 120,3 Mio | 40,1 Mio |

Le temporel ne gagne presque rien : la recherche du pixel représentatif (jusqu'à 4 reconstructions de
normale par texel) compense la réduction du nombre de pixels. Si besoin, on peut écrire l'indice du
représentant dans la passe reflet et le relire, au lieu de le recalculer.

**Vérifié le 2026-10-13 (2e session, captures automatiques `-PgsrCmds`, fenêtre 854×480)** :
scintillement mesuré par `tools/FlickerDiff` (part des pixels d'eau dont l'amplitude dépasse 16/255,
caméra figée) :

| Configuration | Résultat | Mod coupé | Mod actif |
|---|---|---|---|
| Nuit (`time set 18000`) | eau légèrement assombrie, colline reflétée, pas de surbrillance | — | — |
| Pluie | reflet du ciel gris, gouttes dessinées par-dessus | — | — |
| Nether | changement de dimension propre, lave opaque non réfléchissante, 0,03 ms GPU | — | — |
| Sodium 0.6.13 | reflets corrects, main et HUD intacts | 1,52 % | 0,40 % |
| Iris 1.8.8 sans shaderpack | mode autonome, reflets corrects | 0,84 % | 0,37 % |
| Iris 1.8.8 + Complementary Reimagined r5.9.3 | mode service (passes autonomes coupées), aucune exception | — | — |
| Mode « Fabuleux » | reflets corrects (profondeur lue dans le framebuffer translucide) | 1,89 % | 0,64 % |

Tests de compatibilité : `./gradlew runClient -PwithSodium` ou `-PwithIris` (Sodium + Iris + leurs
bibliothèques jcpp / glsl-transformer / antlr, non chargées depuis le jar d'Iris en dev). Sodium 0.6.13
exige Loom ≥ 1.8.9 : le projet est passé en Loom 1.8. Les erreurs `[MIXIN] Cannot resolve class
org/lwjgl/glfw/GLFW` au démarrage viennent de la pré-vérification de Sodium en dev, sans conséquence.
Le mode service n'est vérifié qu'en « ne plante pas » : aucun pack public n'appelle encore `gsrTrace()`.

**Non vérifié** : pilotes NVIDIA/Intel, mode service avec un pack qui appelle réellement `gsrTrace()`.

**Problèmes résolus (vérifiés par l'utilisateur le 2026-10-13, commit `c30a21b`)** : plus de
scintillement de l'eau en marchant et en tournant la caméra, plus de nuage vert au ras de l'eau. Les
corrections de couleur et du SSR (`2265201`) suffisaient. **Reste, mineur** : une légère nuance marron
dans les reflets de l'eau au bord de l'écran, probablement au raccord SSR → GSR (terre des faces latérales
dans le GSR). Pas prioritaire ; la teinte de biome peut la réduire.

Historique du diagnostic :
* **Scintillement de l'eau** signalé par l'utilisateur, absent avant le masque de briques et la
  demi-résolution. Encore présent après la borne de LOD du masque (`7ecff59`). **Pas revérifié depuis
  les corrections de couleur et du SSR** (`2265201`) : une alternance SSR/GSR de couleurs très
  différentes d'une frame à l'autre était une cause probable. Diagnostic prévu :
  `/gsr bricks off`, puis `/gsr scale 1`, pour isoler la cause. Une mesure objective est possible :
  plusieurs captures caméra immobile, puis différence des pixels de l'eau.
* **Mesure objective du 2026-10-13 (caméra figée, commit suivant `7058515`)** : rafales de 8 captures
  à un tick d'intervalle (`-PgsrAutoshot`, puis `java tools/FlickerDiff.java ...`), zone d'eau, part des
  pixels dont l'amplitude temporelle dépasse 16/255 : mod coupé 1,49 % (animation de l'eau vanilla),
  défaut 0,30 %, sans briques 0,32 %, pleine résolution 1,04 %, GSR seul 0,25 %. **Caméra immobile, le
  mod ne scintille pas** (il lisse même l'animation). Reste à vérifier en mouvement, où la reprojection
  temporelle intervient : la mesure automatique ne le couvre pas.
* **« Nuage » de pixels verts au-dessus de l'eau** quand la caméra est presque au ras de l'eau (capture
  utilisateur 17:35). Probablement le même amas vert GSR, non revérifié.
* Le gain de 0,81 → 0,13 ms vu avant la borne de LOD était faux (rayons qui rataient). La bonne
  mesure est 0,51 ms.

**Teinte de biome (faite, 2026-10-13, vérifiée en jeu)** :
* La composition des quads est **affine en la teinte** : par groupe de faces, couleur = A + B ⊙ teinte
  / 255 (A = partie non teintée, B = texture des quads teintés). `BlockMaterialTable` stocke A, B et un
  type de teinte (aucune, herbe, feuillage, eau). Le type se déduit en comparant
  `BlockColors.getColor(state, null, null, i)` à `GrassColors/FoliageColors.getDefaultColor()`. Les
  teintes constantes (bouleau, épicéa, redstone) sont figées dans A. L'eau (et les blocs immergés) :
  A = 0, B = blanc. 9241 états teintés sur 26684 (surtout des variantes `waterlogged`).
* `McSnapshotSource` lit `ClientWorld#getColor(pos, BiomeColors.GRASS/FOLIAGE/WATER_COLOR)` (déjà lissé
  par le « biome blend ») au centre de chaque carré 4×4 de la section, à mi-hauteur, soit 16 lectures
  par type, et seulement pour les types présents dans la palette de la section. Interpolation
  bilinéaire par colonne ; les voisins de bordure prennent la colonne la plus proche. Le cœur n'a
  pas changé : le snapshot renvoie directement l'albédo teinté, et les résumés L1–L3 en héritent.
* Vérification : `/gsr stats` affiche la teinte sous le joueur et l'albédo du dessus d'un bloc
  d'herbe. Au marécage (775, 2041) : herbe #677039, eau #617B64 → dessus d'herbe #3B4121 (plaine
  #476D3E) ; la vue `/gsr debug gsr` y montre un reflet olive sombre.
* Limites : canne à sucre (teinte `-1` sans monde) classée non teintée ; une seule hauteur par
  section (biomes 3D des grottes approchés) ; la table n'est pas reconstruite après un changement de
  pack de ressources (reconstruite au prochain lancement).

**Configuration (faite)** : `config/gsr.json` lu au démarrage par `GsrConfigFile` (Gson), réécrit avec
les champs manquants et les valeurs bornées par `GsrConfig#validate()` ; un fichier illisible est
conservé et les valeurs par défaut utilisées. `/gsr reload` applique à chaud les champs de rendu et de
budget ; niveaux, pages, splats/page, point de liaison SSBO et threads au prochain démarrage
(`copyRuntimeFrom` le signale). σ et opacité ne s'appliquent qu'aux pages régénérées.

Prochaines étapes, par ordre de rentabilité :
1. ~~Reflet en **demi-résolution** + upsampling guidé par la profondeur~~ (fait : 3,04 → 1,24 ms).
2. ~~**Masque d'occupation par page**~~ (fait, 2026-10-13). Choix : 64 bits par **brique** de 2³
   cellules, rangés dans la méta, plutôt que 512 bits par cellule. Un masque par cellule ne ferait
   qu'échanger la lecture de `cells[]` contre une autre lecture de même coût, sans réduire le nombre de
   pas. La méta, elle, est déjà lue à chaque pas, et une brique vide se franchit en un pas au lieu de
   2 à 4. Test CPU : rayon rasant à 4 blocs du sol, 6 pas sur 20 blocs au lieu de ≥ 10.
   **Piège corrigé** : un saut de brique vide ne doit pas franchir la distance où l'empreinte du cône
   dépasse 2^L (le niveau L+1 peut avoir du contenu dans la zone sautée). Sans cette borne, des rayons
   rugueux rataient le relief, ce qui faisait scintiller l'eau. Comparaison CPU sur 4000 rayons aléatoires, avec et sans
   masque : 0 écart (test permanent dans `CoreSelfTest`).
   Mesuré en jeu (`/gsr bricks off|on`, même endroit face à l'eau) : reflet **0,67 → 0,51 ms**
   (−24 %), total 1,20 → 1,12 ms.
3. ~~Albédo depuis les **textures réelles**~~ (fait, 2026-10-13, sans teinte de biome). Une couleur par
   **groupe de faces** (côtés / dessus / dessous) : `BlockMaterialTable` superpose les quads du modèle
   cuit, teinte par défaut incluse, et relit les PNG via le gestionnaire de ressources (aucun access
   widener) : 702 textures en ~100 ms au chargement du monde. Les résumés de section moyennent chaque groupe
   séparément. Éclairage des splats aligné sur vanilla (lightmap, ombrage fixe par face, calcul en
   sRGB) ; l'ancien modèle « soleil ×2,2 » reste disponible via `GSR_SHADE_PHYSICAL` pour les packs.
   **Pourquoi** : en jeu, le reflet GSR d'une colline (herbe = vert `MapColor` sur toutes les faces, ×2
   trop clair) formait un amas vert vif. Il ne se raccordait pas au SSR, d'où une bande verte qui
   suivait le bord de l'écran, là où le SSR passe la main. Vérifié par captures `/gsr debug gsr` avant/après.
   Teinte de biome par position : faite (voir plus haut).
   Outils de diagnostic : `/gsr debug normal|ssr|gsr`, `/gsr bricks on|off`, et
   `gradlew runClient -PgsrAutoshot=<préfixe>` (3 captures automatiques, une par vue, dans `run/screenshots`).
   `-PgsrCmds="tp @s x y z;time set 18000|gsr stats"` envoie des commandes 5 s après l'entrée dans le
   monde (et la partie après `|`, 13 s avant les captures, qui commencent alors à 45 s).
   Corrigés au passage : SSR, test d'épaisseur fait **après** le raffinement binaire (un pas grossier
   rejetait à tort des collines visibles) ; normale reconstruite, plus de voisin hors écran au bord.
4. Mode autonome : le masque par profondeur (passe 0) ne distingue pas l'eau du verre teinté ni de la
   glace ; un vrai ID matériau demanderait un mixin sur le shader de terrain translucide, ou de le
   laisser aux shaderpacks.
5. Optimisation : réutiliser les sections clonées par Sodium (`@Pseudo` mixin sur sa tâche de
   meshing) pour éviter la double lecture des blocs.
6. Portage Vulkan : SSBO → storage buffers, `layout(set, binding)` ; le code GLSL n'utilise déjà aucune
   uniforme hors bloc dans `gsr_common.glsl`.
