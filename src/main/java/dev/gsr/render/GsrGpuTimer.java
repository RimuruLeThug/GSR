package dev.gsr.render;

import static org.lwjgl.opengl.GL43C.*;

/**
 * Chronométrage GPU par sections (requêtes GL_TIME_ELAPSED), sans jamais bloquer le CPU : chaque
 * section a un anneau de RING requêtes, et le résultat d'une frame n'est lu que RING−1 frames plus
 * tard, s'il est disponible. Moyenne glissante exponentielle en millisecondes.
 * Les requêtes GL_TIME_ELAPSED ne s'imbriquent pas : les sections doivent être disjointes.
 */
final class GsrGpuTimer {
    private static final int RING = 4;
    private final int[][] queries;
    private final boolean[][] pending;
    private final double[] avgMs;
    private int frame;

    GsrGpuTimer(int sections) {
        queries = new int[RING][sections];
        pending = new boolean[RING][sections];
        avgMs = new double[sections];
        for (int r = 0; r < RING; r++) glGenQueries(queries[r]);
    }

    void begin(int section) { glBeginQuery(GL_TIME_ELAPSED, queries[frame % RING][section]); }

    void end(int section) {
        glEndQuery(GL_TIME_ELAPSED);
        pending[frame % RING][section] = true;
    }

    /** À appeler une fois par frame : avance l'anneau et récupère les résultats prêts. */
    void endFrame() {
        frame++;
        int r = frame % RING;   // le plus ancien emplacement, réutilisé à cette frame
        for (int s = 0; s < avgMs.length; s++) {
            if (!pending[r][s]) continue;
            pending[r][s] = false;
            if (glGetQueryObjecti(queries[r][s], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) continue; // trop tard : on jette
            double ms = glGetQueryObjecti64(queries[r][s], GL_QUERY_RESULT) / 1e6;
            avgMs[s] = avgMs[s] == 0 ? ms : avgMs[s] * 0.9 + ms * 0.1;
        }
    }

    double avgMs(int section) { return avgMs[section]; }

    void close() {
        for (int[] q : queries) glDeleteQueries(q);
    }
}
