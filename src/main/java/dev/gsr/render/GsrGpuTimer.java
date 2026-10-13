package dev.gsr.render;

import static org.lwjgl.opengl.GL43C.*;

/**
 * GPU timing per section (GL_TIME_ELAPSED queries), never blocking the CPU: each
 * section has a ring of RING queries, and the result of a frame is only read RING-1 frames
 * later, if available. Exponential moving average in milliseconds.
 * GL_TIME_ELAPSED queries cannot be nested: sections must be disjoint.
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

    /** To be called once per frame: advances the ring and collects the ready results. */
    void endFrame() {
        frame++;
        int r = frame % RING;   // the oldest slot, reused this frame
        for (int s = 0; s < avgMs.length; s++) {
            if (!pending[r][s]) continue;
            pending[r][s] = false;
            if (glGetQueryObjecti(queries[r][s], GL_QUERY_RESULT_AVAILABLE) == GL_FALSE) continue; // too late: discard
            double ms = glGetQueryObjecti64(queries[r][s], GL_QUERY_RESULT) / 1e6;
            avgMs[s] = avgMs[s] == 0 ? ms : avgMs[s] * 0.9 + ms * 0.1;
        }
    }

    double avgMs(int section) { return avgMs[section]; }

    void close() {
        for (int[] q : queries) glDeleteQueries(q);
    }
}
