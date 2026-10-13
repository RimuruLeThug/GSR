package dev.gsr.core;

import java.nio.ByteBuffer;

/**
 * CPU result ready to upload: splats sorted by cell (std430, little-endian)
 * + cell table (offset | count << 16) + counter + mask of non-empty bricks.
 */
public record PageData(PageKey key, long version, int splatCount, ByteBuffer splats, int[] cells, long brickMask) {
    public static PageData empty(PageKey key, long version) {
        return new PageData(key, version, 0, ByteBuffer.allocateDirect(0), new int[GsrLayout.CELLS_PER_PAGE], 0L);
    }
}
