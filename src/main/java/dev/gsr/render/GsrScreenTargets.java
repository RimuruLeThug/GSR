package dev.gsr.render;

import static org.lwjgl.opengl.GL43C.*;

/** Cibles écran du mode autonome : reflet brut, historique (ping-pong), distance caméra (ping-pong). */
public final class GsrScreenTargets {
    public int width = -1, height = -1;
    public int reflection;
    /** Profondeur opaque copiée avant la couche translucide (masque eau/translucide). */
    public int opaqueDepth;
    public final int[] history = new int[2];
    public final int[] distance = new int[2];
    public int current;           // index écrit cette frame
    public boolean historyValid;

    public void ensureSize(int w, int h) {
        if (w == width && h == height) return;
        delete();
        width = w; height = h;
        reflection = tex(GL_RGBA16F, GL_NEAREST);
        opaqueDepth = tex(GL_R32F, GL_NEAREST);
        for (int i = 0; i < 2; i++) {
            history[i] = tex(GL_RGBA16F, GL_LINEAR);   // bilinéaire pour la reprojection
            distance[i] = tex(GL_R32F, GL_LINEAR);
        }
        historyValid = false;
    }

    public void swap() { current ^= 1; historyValid = true; }
    public int prev() { return current ^ 1; }

    private int tex(int fmt, int filter) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        glTexStorage2D(GL_TEXTURE_2D, 1, fmt, width, height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return t;
    }

    public void delete() {
        if (width < 0) return;
        glDeleteTextures(new int[]{reflection, opaqueDepth, history[0], history[1], distance[0], distance[1]});
        width = height = -1;
    }
}
