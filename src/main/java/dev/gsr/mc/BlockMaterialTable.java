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
 * Pas encore : teinte de biome par position (couleur par défaut de la plaine partout).
 */
public final class BlockMaterialTable {
    private static final int G = GsrLayout.FACE_GROUPS;
    private final byte[] kind;
    private final int[] albedo;   // [id * G + groupe]
    private final byte[] emission;

    private BlockMaterialTable(int n) {
        kind = new byte[n]; albedo = new int[n * G]; emission = new byte[n];
    }

    public int kind(int rawId) { return kind[rawId]; }
    public int albedo(int rawId, int faceGroup) { return albedo[rawId * G + faceGroup]; }
    public int emission(int rawId) { return emission[rawId]; }

    public static BlockMaterialTable build() {
        long t0 = System.nanoTime();
        MinecraftClient client = MinecraftClient.getInstance();
        SpriteAverages sprites = new SpriteAverages(client.getResourceManager());
        BlockRenderManager models = client.getBlockRenderManager();
        BlockColors tints = client.getBlockColors();
        Random rnd = Random.create();

        BlockMaterialTable t = new BlockMaterialTable(Block.STATE_IDS.size());
        int fromModels = 0;
        for (BlockState s : Block.STATE_IDS) {
            int id = Block.getRawIdFromState(s);
            int k, a = 255;
            int rgb = mapColor(s);
            int[] faces = null;
            FluidState f = s.getFluidState();
            if (s.isAir()) {
                k = GsrLayout.KIND_AIR;
            } else if (!f.isEmpty() && f.isIn(FluidTags.WATER) && !s.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                k = GsrLayout.KIND_TRANSLUCENT; rgb = 0x3F76E4; a = 150;
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
                if (k != GsrLayout.KIND_AIR) faces = modelFaceColors(s, models, tints, sprites, rnd);
            }
            if (faces != null) fromModels++;
            t.kind[id] = (byte) k;
            for (int g = 0; g < G; g++)
                t.albedo[id * G + g] = (a << 24) | ((faces != null ? faces[g] : rgb) & 0xFFFFFF);
            t.emission[id] = (byte) Math.min(15, s.getLuminance());
        }
        dev.gsr.GsrClient.LOG.info("GSR : table des matériaux, {} états dont {} colorés depuis les textures ({} textures lues) en {} ms",
                Block.STATE_IDS.size(), fromModels, sprites.loaded(), (System.nanoTime() - t0) / 1_000_000);
        return t;
    }

    /**
     * Couleur moyenne visible de chaque groupe de faces (côtés, dessus, dessous), 0xRRGGBB, ou null si le
     * modèle n'a aucun quad exploitable. Les quads d'une face se superposent dans l'ordre de dessin : une
     * surcouche partielle (herbe sur le côté d'un bloc d'herbe) se mélange selon sa couverture.
     */
    private static int[] modelFaceColors(BlockState s, BlockRenderManager models, BlockColors tints, SpriteAverages sprites, Random rnd) {
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
            float r = 0, g = 0, b = 0;
            boolean any = false;
            for (BakedQuad q : quads) {
                float[] avg = sprites.average(q.getSprite());   // r, g, b (0..255), couverture 0..1
                if (avg == null || avg[3] <= 0f) continue;
                float qr = avg[0], qg = avg[1], qb = avg[2];
                if (q.hasColor()) {
                    int tint = tint(tints, s, q.getColorIndex());
                    if (tint != -1) {
                        qr *= ((tint >> 16) & 0xFF) / 255f; qg *= ((tint >> 8) & 0xFF) / 255f; qb *= (tint & 0xFF) / 255f;
                    }
                }
                float w = any ? avg[3] : 1f;   // le premier quad pose la base, les suivants se superposent
                r += (qr - r) * w; g += (qg - g) * w; b += (qb - b) * w;
                any = true;
            }
            if (any) dir[d.ordinal()] = new float[]{r, g, b};
        }
        float[] up = dir[Direction.UP.ordinal()], down = dir[Direction.DOWN.ordinal()];
        float[] side = null;
        int n = 0;
        for (Direction d : Direction.Type.HORIZONTAL) {
            float[] c = dir[d.ordinal()];
            if (c == null) continue;
            if (side == null) side = new float[3];
            for (int i = 0; i < 3; i++) side[i] += c[i];
            n++;
        }
        if (side != null) for (int i = 0; i < 3; i++) side[i] /= n;
        float[] any = side != null ? side : up != null ? up : down;
        if (any == null) return null;
        int[] out = new int[G];
        out[GsrLayout.FACE_SIDE] = pack(side != null ? side : any);
        out[GsrLayout.FACE_UP] = pack(up != null ? up : any);
        out[GsrLayout.FACE_DOWN] = pack(down != null ? down : any);
        return out;
    }

    private static int tint(BlockColors tints, BlockState s, int index) {
        try {
            return tints.getColor(s, null, null, index);   // sans monde : teinte par défaut (plaine)
        } catch (Throwable e) {
            return -1;
        }
    }

    private static int pack(float[] c) {
        int r = Math.min(255, Math.round(c[0])), g = Math.min(255, Math.round(c[1])), b = Math.min(255, Math.round(c[2]));
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
