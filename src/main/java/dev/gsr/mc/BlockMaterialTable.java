package dev.gsr.mc;

import dev.gsr.core.GsrLayout;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

/**
 * Table précalculée BlockState -> (genre, albédo, émission), indexée par l'ID brut de l'état.
 * Construite une fois sur le thread principal (registres gelés), puis lue sans verrou par les workers.
 *
 * Albédo : MapColor (couleurs de carte vanilla, déjà proches de l'albédo moyen des textures).
 * Amélioration possible : moyenne réelle des sprites de l'atlas, calculée au rechargement des ressources.
 */
public final class BlockMaterialTable {
    private final byte[] kind;
    private final int[] albedo;
    private final byte[] emission;

    private BlockMaterialTable(int n) {
        kind = new byte[n]; albedo = new int[n]; emission = new byte[n];
    }

    public int kind(int rawId) { return kind[rawId]; }
    public int albedo(int rawId) { return albedo[rawId]; }
    public int emission(int rawId) { return emission[rawId]; }

    public static BlockMaterialTable build() {
        BlockMaterialTable t = new BlockMaterialTable(Block.STATE_IDS.size());
        for (BlockState s : Block.STATE_IDS) {
            int id = Block.getRawIdFromState(s);
            int k, a = 255;
            int rgb = mapColor(s);
            FluidState f = s.getFluidState();
            if (s.isAir()) {
                k = GsrLayout.KIND_AIR;
            } else if (!f.isEmpty() && f.isIn(FluidTags.WATER) && !s.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                k = GsrLayout.KIND_TRANSLUCENT; rgb = 0x3F76E4; a = 150;
            } else if (!f.isEmpty() && f.isIn(FluidTags.LAVA)) {
                k = GsrLayout.KIND_OPAQUE; rgb = 0xD45A12;
            } else if (s.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN) || s.isIn(BlockTags.LEAVES)) {
                k = GsrLayout.KIND_OPAQUE;
            } else if (s.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)) {
                k = GsrLayout.KIND_TRANSLUCENT; a = 90;           // verre, glace, slime...
            } else {
                k = solidEnough(s) ? GsrLayout.KIND_OPAQUE : GsrLayout.KIND_AIR; // dalles, escaliers / fleurs, torches
            }
            t.kind[id] = (byte) k;
            t.albedo[id] = (a << 24) | (rgb & 0xFFFFFF);
            t.emission[id] = (byte) Math.min(15, s.getLuminance());
        }
        return t;
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
