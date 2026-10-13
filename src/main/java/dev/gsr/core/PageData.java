package dev.gsr.core;

import java.nio.ByteBuffer;

/**
 * Résultat CPU prêt à téléverser : splats triés par cellule (std430, little-endian)
 * + table des cellules (offset | count << 16) + compteur.
 */
public record PageData(PageKey key, long version, int splatCount, ByteBuffer splats, int[] cells) {
    public static PageData empty(PageKey key, long version) {
        return new PageData(key, version, 0, ByteBuffer.allocateDirect(0), new int[GsrLayout.CELLS_PER_PAGE]);
    }
}
