package dev.gsr.mc;

import dev.gsr.core.GsrLayout;
import dev.gsr.core.SectionSnapshotSource;
import dev.gsr.core.VoxelSource;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Capture d'une section + ses 6 voisines de face, sur le thread principal.
 *
 * On COPIE les PalettedContainer et les ChunkNibbleArray (quelques Kio) : les workers lisent ensuite
 * des données figées, sans jamais toucher au monde vivant ni à ses verrous. C'est le même principe
 * que les ChunkRendererRegion vanilla / les sections clonées de Sodium.
 */
public final class McSnapshotSource implements SectionSnapshotSource {
    private final ClientWorld world;
    private final BlockMaterialTable materials;

    public McSnapshotSource(ClientWorld world, BlockMaterialTable materials) {
        this.world = world;
        this.materials = materials;
    }

    // ordre : centre, puis GsrLayout.DIRS (-X +X -Y +Y -Z +Z)
    private static final int[][] OFFS = {{0, 0, 0}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    @Override
    public VoxelSource capture(int sx, int sy, int sz) {
        int idx = world.sectionCoordToIndex(sy);
        if (idx < 0 || idx >= world.countVerticalSections()) return null;
        WorldChunk chunk = world.getChunkManager().getWorldChunk(sx, sz);
        if (chunk == null) return null;
        ChunkSection center = chunk.getSectionArray()[idx];
        if (center == null || center.isEmpty()) return VoxelSource.EMPTY;

        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] states = new PalettedContainer[7];
        ChunkNibbleArray[] sky = new ChunkNibbleArray[7], blk = new ChunkNibbleArray[7];
        boolean[] belowWorld = new boolean[7];
        boolean hasSky = world.getDimension().hasSkyLight();

        for (int i = 0; i < 7; i++) {
            int x = sx + OFFS[i][0], y = sy + OFFS[i][1], z = sz + OFFS[i][2];
            int yi = world.sectionCoordToIndex(y);
            if (yi < 0) { belowWorld[i] = true; continue; }
            if (yi >= world.countVerticalSections()) continue;
            WorldChunk c = (x == sx && z == sz) ? chunk : world.getChunkManager().getWorldChunk(x, z);
            if (c == null) continue;
            ChunkSection s = c.getSectionArray()[yi];
            if (s != null && !s.isEmpty()) states[i] = s.getBlockStateContainer().copy();
            ChunkSectionPos p = ChunkSectionPos.from(x, y, z);
            if (hasSky) {
                ChunkNibbleArray a = world.getLightingProvider().get(LightType.SKY).getLightSection(p);
                if (a != null) sky[i] = a.copy();
            }
            ChunkNibbleArray b = world.getLightingProvider().get(LightType.BLOCK).getLightSection(p);
            if (b != null) blk[i] = b.copy();
        }
        return new Snapshot(materials, states, sky, blk, belowWorld, hasSky);
    }

    /** Snapshot immuable, lisible depuis n'importe quel thread. */
    private record Snapshot(BlockMaterialTable mat, PalettedContainer<BlockState>[] states,
                            ChunkNibbleArray[] sky, ChunkNibbleArray[] blk, boolean[] belowWorld,
                            boolean hasSky) implements VoxelSource {

        private static int which(int x, int y, int z) {
            if (x < 0) return 1; if (x > 15) return 2;
            if (y < 0) return 3; if (y > 15) return 4;
            if (z < 0) return 5; if (z > 15) return 6;
            return 0;
        }

        private int rawId(int i, int x, int y, int z) {
            PalettedContainer<BlockState> c = states[i];
            return c == null ? -1 : Block.getRawIdFromState(c.get(x & 15, y & 15, z & 15));
        }

        @Override public int kind(int x, int y, int z) {
            int i = which(x, y, z);
            if (belowWorld[i]) return GsrLayout.KIND_OPAQUE;   // pas de faces sous la bedrock
            int id = rawId(i, x, y, z);
            return id < 0 ? GsrLayout.KIND_AIR : mat.kind(id);
        }
        @Override public int albedo(int x, int y, int z) { return albedo(x, y, z, GsrLayout.FACE_SIDE); }
        @Override public int albedo(int x, int y, int z, int faceGroup) {
            int id = rawId(which(x, y, z), x, y, z);
            return id < 0 ? 0 : mat.albedo(id, faceGroup);
        }
        @Override public int emission(int x, int y, int z) {
            int id = rawId(which(x, y, z), x, y, z);
            return id < 0 ? 0 : mat.emission(id);
        }
        @Override public int light(int x, int y, int z) {
            int i = which(x, y, z);
            int s = !hasSky ? 0 : sky[i] == null ? 15 : sky[i].get(x & 15, y & 15, z & 15);
            int b = blk[i] == null ? 0 : blk[i].get(x & 15, y & 15, z & 15);
            return (s << 4) | b;
        }
    }
}
