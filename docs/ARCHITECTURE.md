# GSR — Gaussian Space Reflections : architecture

Mod Fabric (Minecraft 1.21.1, Yarn) ajoutant des reflets hybrides : **SSR** quand l'information est
à l'écran, **champ de Gaussiennes 3D à 360°** autour du joueur quand elle ne l'est pas, ciel analytique
en dernier recours. Aucun ray tracing matériel : uniquement OpenGL 4.3 (compute + SSBO).

## 1. Vue d'ensemble

```
 MONDE (ClientWorld)                CPU (workers)                         GPU
 ──────────────────                 ─────────────                         ───
 bloc modifié ─┐                                                    SSBO frame   (208 o)
 lumière ──────┼─► file de capture ─► snapshot  ──► SectionSummary ─┐  SSBO meta   (ivec4 / page)
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
  `ivec4(pageXYZ, count)` permet au shader de rejeter un slot périmé.
* **Budget par défaut** : 1024 slots × 64 Kio = **64 Mio** de splats + 2 Mio de cellules + 16 Kio de
  méta. Réglable (`pagesX/Y/Z`, `levels`, `maxSplatsPerPage`).
* Cibles écran du mode autonome : 36 o/pixel (≈ 120 Mio en 2560×1369, mesuré) — le passage du reflet en demi-
  résolution est la première optimisation prévue (voir §6).

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
   * page absente/vide → saut d'une page entière ; sinon évaluation des splats de la cellule ;
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

**Vérifié** : tout `dev.gsr.core` compile et passe 27 vérifications (`CoreSelfTest`, dont les stats et la file de captures) : format std430, clé de
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

**Non vérifié** : mode « Fabuleux », Sodium, Iris,
pilotes NVIDIA/Intel, nuit/pluie/Nether, coût GPU (pas encore de requêtes `GL_TIME_ELAPSED`).

Prochaines étapes, par ordre de rentabilité :
1. Reflet en **demi-résolution** + upsampling guidé par la profondeur (÷4 sur les passes 1–2).
2. **Masque d'occupation par page** (512 bits) pour sauter les cellules vides sans lire `cells[]`.
3. Albédo depuis la **moyenne réelle des sprites** de l'atlas (au lieu de `MapColor`), teinte de biome.
4. Mode autonome : le masque par profondeur (passe 0) ne distingue pas l'eau du verre teinté ni de la
   glace ; un vrai ID matériau demanderait un mixin sur le shader de terrain translucide, ou de le
   laisser aux shaderpacks.
5. Optimisation : réutiliser les sections clonées par Sodium (`@Pseudo` mixin sur sa tâche de
   meshing) pour éviter la double lecture des blocs.
6. Portage Vulkan : SSBO → storage buffers, `layout(set, binding)` ; le code GLSL n'utilise déjà aucune
   uniforme hors bloc dans `gsr_common.glsl`.
