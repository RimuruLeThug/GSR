package dev.gsr.render;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Cibles écran du mode autonome. Pleine résolution : profondeur opaque. Basse résolution (écran / scale) :
 * reflet brut, indice du pixel représentatif, historique (ping-pong), distance caméra du pixel
 * représentatif (ping-pong).
 */
public final class GsrScreenTargets {
    /** Profondeur opaque R32F. */
    private static final int FULL_BYTES_PER_PIXEL = 4;
    /** reflet RGBA16F 8 + représentant R8UI 1 + 2 × historique RGBA16F 8 + 2 × distance R32F 4. */
    private static final int LOW_BYTES_PER_PIXEL = 8 + 1 + 2 * 8 + 2 * 4;

    public int width = -1, height = -1;
    /** Facteur de réduction des passes reflet + temporel (1 ou 2) et taille correspondante. */
    public int scale = 1, lowWidth, lowHeight;
    public int reflection;
    /** Indice (i + j·scale) du pixel plein écran choisi par la passe reflet, relu par la passe temporelle. */
    public int representative;
    /** Profondeur opaque copiée avant la couche translucide (masque eau/translucide). */
    public int opaqueDepth;
    public final int[] history = new int[2];
    public final int[] distance = new int[2];
    public int current;           // index écrit cette frame
    public boolean historyValid;

    public void ensureSize(int w, int h, int s) {
        if (w == width && h == height && s == scale) return;
        delete();
        width = w; height = h; scale = s;
        lowWidth = (w + s - 1) / s; lowHeight = (h + s - 1) / s;
        opaqueDepth = tex(GL_R32F, GL_NEAREST, w, h);
        reflection = tex(GL_RGBA16F, GL_NEAREST, lowWidth, lowHeight);
        representative = tex(GL_R8UI, GL_NEAREST, lowWidth, lowHeight);
        for (int i = 0; i < 2; i++) {
            history[i] = tex(GL_RGBA16F, GL_LINEAR, lowWidth, lowHeight);   // bilinéaire pour la reprojection
            distance[i] = tex(GL_R32F, GL_LINEAR, lowWidth, lowHeight);
        }
        historyValid = false;
    }

    public long bytes() {
        return width < 0 ? 0 : (long) width * height * FULL_BYTES_PER_PIXEL + (long) lowWidth * lowHeight * LOW_BYTES_PER_PIXEL;
    }

    public void swap() { current ^= 1; historyValid = true; }
    public int prev() { return current ^ 1; }

    private static int tex(int fmt, int filter, int w, int h) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexStorage2D(GL_TEXTURE_2D, 1, fmt, w, h);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return t;
    }

    public void delete() {
        if (width < 0) return;
        glDeleteTextures(new int[]{reflection, representative, opaqueDepth, history[0], history[1], distance[0], distance[1]});
        width = height = -1;
    }
}
