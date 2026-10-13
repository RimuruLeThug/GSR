package dev.gsr.mc;

import dev.gsr.core.GsrLayout;
import dev.gsr.core.SectionSnapshotSource;
import dev.gsr.core.VoxelSource;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.color.world.BiomeColors;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Capture of a section + its 6 face neighbors, on the main thread.
 *
 * We COPY the PalettedContainers and ChunkNibbleArrays (a few KiB): the workers then read
 * frozen data, never touching the live world or its locks. This is the same principle
 * as vanilla's ChunkRendererRegion / Sodium's cloned sections.
 *
 * Biome tint: if the section contains tinted blocks (grass, foliage, water), the biome color
 * (already smoothed by vanilla's "biome blend") is read on a 4×4 grid of columns, at the center of
 * each 4×4-block square (the biome resolution), at mid-height of the section, then
 * bilinearly interpolated per column. 16 reads per type instead of 256.
 */
public final class McSnapshotSource implements SectionSnapshotSource {
    private final ClientWorld world;
    private BlockMaterialTable materials; // read and replaced on the client thread only (capture)
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();

    public McSnapshotSource(ClientWorld world, BlockMaterialTable materials) {
        this.world = world;
        this.materials = materials;
    }

    /** Resource pack changed: subsequent captures use the new table. */
    public void setMaterials(BlockMaterialTable materials) { this.materials = materials; }

    // order: center, then GsrLayout.DIRS (-X +X -Y +Y -Z +Z)
    private static final int[][] OFFS = {{0, 0, 0}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final ColorResolver[] RESOLVERS = {null, BiomeColors.GRASS_COLOR, BiomeColors.FOLIAGE_COLOR, BiomeColors.WATER_COLOR};

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
        return new Snapshot(materials, states, sky, blk, belowWorld, hasSky, sampleTints(center, sx, sy, sz));
    }

    /** Tints per type (index TINT_*), 16×16 columns 0xRRGGBB, or null for a type absent from the section. */
    private int[][] sampleTints(ChunkSection center, int sx, int sy, int sz) {
        int[] mask = new int[1];
        center.getBlockStateContainer().hasAny(st -> {
            mask[0] |= 1 << materials.tintType(Block.getRawIdFromState(st));
            return false;   // walks the whole palette
        });
        int[][] out = new int[4][];
        int[] grid = new int[16];
        for (int type = 1; type < 4; type++) {
            if ((mask[0] & (1 << type)) == 0) continue;
            for (int j = 0; j < 4; j++)
                for (int i = 0; i < 4; i++) {
                    cursor.set(sx * 16 + i * 4 + 2, sy * 16 + 8, sz * 16 + j * 4 + 2);
                    grid[i + 4 * j] = world.getColor(cursor, RESOLVERS[type]);
                }
            int[] col = new int[256];
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    col[x + 16 * z] = bilinear(grid, x, z);
            out[type] = col;
        }
        return out;
    }

    /** Interpolation between sample centers (x = 2, 6, 10, 14), clamped at the edges. */
    private static int bilinear(int[] grid, int x, int z) {
        float fx = Math.max(0f, Math.min(3f, (x - 2f) / 4f)), fz = Math.max(0f, Math.min(3f, (z - 2f) / 4f));
        int x0 = Math.min(2, (int) fx), z0 = Math.min(2, (int) fz);
        float tx = fx - x0, tz = fz - z0;
        int c00 = grid[x0 + 4 * z0], c10 = grid[x0 + 1 + 4 * z0], c01 = grid[x0 + 4 * (z0 + 1)], c11 = grid[x0 + 1 + 4 * (z0 + 1)];
        int out = 0;
        for (int sh = 0; sh <= 16; sh += 8) {
            float a = (c00 >> sh) & 0xFF, b = (c10 >> sh) & 0xFF, c = (c01 >> sh) & 0xFF, d = (c11 >> sh) & 0xFF;
            float v = (a + (b - a) * tx) * (1 - tz) + (c + (d - c) * tx) * tz;
            out |= Math.round(v) << sh;
        }
        return out;
    }

    /** Immutable snapshot, readable from any thread. */
    private record Snapshot(BlockMaterialTable mat, PalettedContainer<BlockState>[] states,
                            ChunkNibbleArray[] sky, ChunkNibbleArray[] blk, boolean[] belowWorld,
                            boolean hasSky, int[][] tints) implements VoxelSource {

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
            if (belowWorld[i]) return GsrLayout.KIND_OPAQUE;   // no faces below bedrock
            int id = rawId(i, x, y, z);
            return id < 0 ? GsrLayout.KIND_AIR : mat.kind(id);
        }
        @Override public int albedo(int x, int y, int z) { return albedo(x, y, z, GsrLayout.FACE_SIDE); }
        @Override public int albedo(int x, int y, int z, int faceGroup) {
            int id = rawId(which(x, y, z), x, y, z);
            if (id < 0) return 0;
            int[] t = tints[mat.tintType(id)];
            if (t == null) return mat.albedo(id, faceGroup);   // untinted, or neighbor of a type absent from the section
            int cx = Math.max(0, Math.min(15, x)), cz = Math.max(0, Math.min(15, z));
            return mat.albedo(id, faceGroup, t[cx + 16 * cz]);
        }
        @Override public int emission(int x, int y, int z) {
            int id = rawId(which(x, y, z), x, y, z);
            return id < 0 ? 0 : mat.emission(id);
        }
        @Override public boolean unshaded(int x, int y, int z) {
            int id = rawId(which(x, y, z), x, y, z);
            return id >= 0 && mat.unshaded(id);
        }
        @Override public int light(int x, int y, int z) {
            int i = which(x, y, z);
            int s = !hasSky ? 0 : sky[i] == null ? 15 : sky[i].get(x & 15, y & 15, z & 15);
            int b = blk[i] == null ? 0 : blk[i].get(x & 15, y & 15, z & 15);
            return (s << 4) | b;
        }
    }
}
