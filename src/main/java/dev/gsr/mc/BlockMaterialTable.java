package dev.gsr.mc;

import dev.gsr.core.GsrLayout;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.Sprite;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;
import net.minecraft.world.biome.FoliageColors;
import net.minecraft.world.biome.GrassColors;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Table précalculée BlockState -> (genre, albédo par groupe de faces, émission), indexée par l'ID brut.
 * Construite une fois sur le thread principal (modèles cuits, registres gelés), puis lue sans verrou
 * par les workers.
 *
 * Albédo : moyenne réelle des textures du modèle, face par face (dessus / dessous / côtés), teinte par
 * défaut incluse (herbe, feuillage). Un bloc d'herbe est donc vert dessus et terre sur les côtés, comme
 * à l'écran : sans cela, le reflet GSR d'une colline était entièrement vert et ne se raccordait pas au
 * SSR (bande verte au bord de l'écran). Repli sur MapColor si le modèle n'a pas de quads (fluides...).
 *
 * Teinte de biome : la superposition des quads est affine en la teinte, donc chaque groupe de faces
 * s'écrit couleur = A + B ⊙ teinte / 255 (A = partie non teintée, B = texture des quads teintés). La
 * table stocke A, B et le type de teinte (herbe, feuillage, eau) ; le snapshot fournit la teinte du
 * biome à la position du bloc. Les teintes constantes (bouleau, épicéa, redstone) sont figées dans A.
 */
public final class BlockMaterialTable {
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3;
    private static final int G = GsrLayout.FACE_GROUPS;
    private final byte[] kind;
    private final int[] albedo;   // [id * G + groupe] : A, 0xAARRGGBB
    private final int[] tinted;   // [id * G + groupe] : B, 0xRRGGBB
    private final byte[] tintType;
    private final byte[] emission;

    private BlockMaterialTable(int n) {
        kind = new byte[n]; albedo = new int[n * G]; tinted = new int[n * G]; tintType = new byte[n]; emission = new byte[n];
    }

    public int kind(int rawId) { return kind[rawId]; }
    public int tintType(int rawId) { return tintType[rawId]; }
    /** Albédo avec la teinte par défaut (plaine). */
    public int albedo(int rawId, int faceGroup) { return albedo(rawId, faceGroup, DEFAULT_TINTS[tintType[rawId]]); }
    /** Albédo pour une teinte de biome 0xRRGGBB (ignorée si le bloc n'est pas teinté). */
    public int albedo(int rawId, int faceGroup, int tint) {
        int i = rawId * G + faceGroup, a = albedo[i];
        if (tintType[rawId] == TINT_NONE) return a;
        int b = tinted[i];
        int r = Math.min(255, ((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) * ((tint >> 16) & 0xFF) + 127) / 255);
        int g = Math.min(255, ((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) * ((tint >> 8) & 0xFF) + 127) / 255);
        int bl = Math.min(255, (a & 0xFF) + ((b & 0xFF) * (tint & 0xFF) + 127) / 255);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }
    public int emission(int rawId) { return emission[rawId]; }

    /** Teintes par défaut par type (index = TINT_*), celles de la plaine. */
    private static final int[] DEFAULT_TINTS = new int[4];
    static final int WATER_DEFAULT = 0x3F76E4;

    public static BlockMaterialTable build() {
        long t0 = System.nanoTime();
        MinecraftClient client = MinecraftClient.getInstance();
        SpriteAverages sprites = new SpriteAverages(client.getResourceManager());
        BlockRenderManager models = client.getBlockRenderManager();
        BlockColors tints = client.getBlockColors();
        Random rnd = Random.create();
        DEFAULT_TINTS[TINT_NONE] = 0xFFFFFF;
        DEFAULT_TINTS[TINT_GRASS] = GrassColors.getDefaultColor() & 0xFFFFFF;
        DEFAULT_TINTS[TINT_FOLIAGE] = FoliageColors.getDefaultColor() & 0xFFFFFF;
        DEFAULT_TINTS[TINT_WATER] = WATER_DEFAULT;

        BlockMaterialTable t = new BlockMaterialTable(Block.STATE_IDS.size());
        int fromModels = 0, tintedStates = 0;
        int[] type = new int[1];
        for (BlockState s : Block.STATE_IDS) {
            int id = Block.getRawIdFromState(s);
            int k, a = 255;
            int rgb = mapColor(s);
            int[] faces = null;
            type[0] = TINT_NONE;
            FluidState f = s.getFluidState();
            if (s.isAir()) {
                k = GsrLayout.KIND_AIR;
            } else if (!f.isEmpty() && f.isIn(FluidTags.WATER) && !s.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                // Eau (et blocs immergés) : couleur entièrement donnée par la teinte du biome.
                k = GsrLayout.KIND_TRANSLUCENT; rgb = 0; a = 150;
                type[0] = TINT_WATER;
                faces = new int[2 * G];
                for (int g = 0; g < G; g++) faces[G + g] = 0xFFFFFF;
            } else if (!f.isEmpty() && f.isIn(FluidTags.LAVA)) {
                k = GsrLayout.KIND_OPAQUE; rgb = 0xD45A12;
            } else {
                if (s.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) || s.isIn(BlockTags.LEAVES)) {
                    k = GsrLayout.KIND_OPAQUE;
                } else if (s.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                    k = GsrLayout.KIND_TRANSLUCENT; a = 90;           // verre, glace, slime...
                } else {
                    k = solidEnough(s) ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; // dalles, escaliers / fleurs, torches
                }
                if (k != GsrLayout.KIND_AIR) {
                    faces = modelFaceColors(s, models, tints, sprites, rnd, type);
                    if (faces != null) fromModels++;
                }
            }
            if (type[0] != TINT_NONE) tintedStates++;
            t.kind[id] = (byte) k;
            t.tintType[id] = (byte) type[0];
            for (int g = 0; g < G; g++) {
                t.albedo[id * G + g] = (a << 24) | ((faces != null ? faces[g] : rgb) & 0xFFFFFF);
                t.tinted[id * G + g] = faces != null ? faces[G + g] : 0;
            }
            t.emission[id] = (byte) Math.min(15, s.getLuminance());
        }
        dev.gsr.GsrClient.LOG.info("GSR : table des matériaux, {} états dont {} colorés depuis les textures, {} teintés par le biome ({} textures lues) en {} ms",
                Block.STATE_IDS.size(), fromModels, tintedStates, sprites.loaded(), (System.nanoTime() - t0) / 1_000_000);
        return t;
    }

    /** Type de teinte d'une couleur de quad renvoyée sans monde : la valeur par défaut identifie le biome-dépendant. */
    private static int classifyTint(int tint) {
        int c = tint & 0xFFFFFF;
        if (c == DEFAULT_TINTS[TINT_GRASS]) return TINT_GRASS;
        if (c == DEFAULT_TINTS[TINT_FOLIAGE]) return TINT_FOLIAGE;
        return TINT_NONE;
    }

    /**
     * Couleur moyenne visible de chaque groupe de faces (côtés, dessus, dessous) sous forme affine :
     * [0, G) = A (0xRRGGBB, partie non teintée), [G, 2G) = B (texture des quads teintés par le biome),
     * ou null si le modèle n'a aucun quad exploitable. Les quads d'une face se superposent dans l'ordre
     * de dessin : une surcouche partielle (herbe sur le côté d'un bloc d'herbe) se mélange selon sa
     * couverture. {@code typeOut[0]} reçoit le type de teinte du bloc.
     */
    private static int[] modelFaceColors(BlockState s, BlockRenderManager models, BlockColors tints, SpriteAverages sprites, Random rnd, int[] typeOut) {
        BakedModel model;
        List<BakedQuad> loose;
        try {
            model = models.getModel(s);
            rnd.setSeed(42L);
            loose = model.getQuads(s, null, rnd);
        } catch (Throwable e) {
            return null;
        }
        float[][] dir = new float[6][];   // ordre Direction.values() : DOWN, UP, NORTH, SOUTH, WEST, EAST
        for (Direction d : Direction.values()) {
            List<BakedQuad> quads = new ArrayList<>();
            try {
                rnd.setSeed(42L);
                quads.addAll(model.getQuads(s, d, rnd));
            } catch (Throwable e) {
                continue;
            }
            for (BakedQuad q : loose) if (q.getFace() == d) quads.add(q);
            float[] c = new float[6];   // A rgb, B rgb
            boolean any = false;
            for (BakedQuad q : quads) {
                float[] avg = sprites.average(q.getSprite());   // r, g, b (0..255), couverture 0..1
                if (avg == null || avg[3] <= 0f) continue;
                float[] qc = {avg[0], avg[1], avg[2], 0, 0, 0};
                if (q.hasColor()) {
                    int tint = tint(tints, s, q.getColorIndex());
                    int type = tint == -1 ? TINT_NONE : classifyTint(tint);
                    if (type != TINT_NONE && (typeOut[0] == TINT_NONE || typeOut[0] == type)) {
                        typeOut[0] = type;   // dépend du biome : part B
                        qc = new float[]{0, 0, 0, avg[0], avg[1], avg[2]};
                    } else if (tint != -1) {   // teinte constante : figée dans A
                        qc[0] *= ((tint >> 16) & 0xFF) / 255f; qc[1] *= ((tint >> 8) & 0xFF) / 255f; qc[2] *= (tint & 0xFF) / 255f;
                    }
                }
                float w = any ? avg[3] : 1f;   // le premier quad pose la base, les suivants se superposent
                for (int i = 0; i < 6; i++) c[i] += (qc[i] - c[i]) * w;
                any = true;
            }
            if (any) dir[d.ordinal()] = c;
        }
        float[] up = dir[Direction.UP.ordinal()], down = dir[Direction.DOWN.ordinal()];
        float[] side = null;
        int n = 0;
        for (Direction d : Direction.Type.HORIZONTAL) {
            float[] c = dir[d.ordinal()];
            if (c == null) continue;
            if (side == null) side = new float[6];
            for (int i = 0; i < 6; i++) side[i] += c[i];
            n++;
        }
        if (side != null) for (int i = 0; i < 6; i++) side[i] /= n;
        float[] any = side != null ? side : up != null ? up : down;
        if (any == null) return null;
        int[] out = new int[2 * G];
        float[][] groups = new float[G][];
        groups[GsrLayout.FACE_SIDE] = side != null ? side : any;
        groups[GsrLayout.FACE_UP] = up != null ? up : any;
        groups[GsrLayout.FACE_DOWN] = down != null ? down : any;
        for (int g = 0; g < G; g++) {
            out[g] = pack(groups[g][0], groups[g][1], groups[g][2]);
            out[G + g] = pack(groups[g][3], groups[g][4], groups[g][5]);
        }
        return out;
    }

    private static int tint(BlockColors tints, BlockState s, int index) {
        try {
            return tints.getColor(s, null, null, index);   // sans monde : teinte par défaut (plaine)
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int pack(float cr, float cg, float cb) {
        int r = Math.min(255, Math.round(cr)), g = Math.min(255, Math.round(cg)), b = Math.min(255, Math.round(cb));
        return (r << 16) | (g << 8) | b;
    }

    /** Moyenne des pixels non transparents de la première image de chaque texture, en cache. */
    private static final class SpriteAverages {
        private final ResourceManager resources;
        private final Map<Identifier, float[]> cache = new HashMap<>();
        SpriteAverages(ResourceManager resources) { this.resources = resources; }

        int loaded() { return cache.size(); }

        float[] average(Sprite sprite) {
            Identifier id = sprite.getContents().getId();
            return cache.computeIfAbsent(id, k -> load(k, sprite.getContents().getWidth(), sprite.getContents().getHeight()));
        }

        private float[] load(Identifier id, int w, int h) {
            Identifier file = id.withPath("textures/" + id.getPath() + ".png");
            Optional<Resource> res = resources.getResource(file);
            if (res.isEmpty()) return null;
            try (InputStream in = res.get().getInputStream();
                 NativeImage img = NativeImage.read(NativeImage.Format.RGBA, in)) {
                // Texture animée : bande verticale d'images, on ne lit que la première (w x h).
                int fw = Math.min(w, img.getWidth()), fh = Math.min(h, img.getHeight());
                double r = 0, g = 0, b = 0;
                int n = 0;
                for (int y = 0; y < fh; y++)
                    for (int x = 0; x < fw; x++) {
                        int c = img.getColor(x, y);   // 0xAABBGGRR
                        if ((c >>> 24) < 128) continue;
                        r += c & 0xFF; g += (c >> 8) & 0xFF; b += (c >> 16) & 0xFF;
                        n++;
                    }
                if (n == 0) return new float[]{0, 0, 0, 0};
                return new float[]{(float) (r / n), (float) (g / n), (float) (b / n), n / (float) (fw * fh)};
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static int mapColor(BlockState s) {
        try {
            MapColor c = s.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            return (c == null || c == MapColor.CLEAR) ? 0xC8C8C8 : c.color;
        } catch (Throwable t) {
            return 0x808080;
        }
    }

    private static boolean solidEnough(BlockState s) {
        try {
            VoxelShape shape = s.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            if (shape.isEmpty()) return false;
            Box b = shape.getBoundingBox();
            return (b.maxX - b.minX) * (b.maxY - b.minY) * (b.maxZ - b.minZ) >= 0.5;
        } catch (Throwable t) {
            return false;
        }
    }
}
