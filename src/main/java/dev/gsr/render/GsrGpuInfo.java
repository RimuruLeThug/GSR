package dev.gsr.render;

import org.lwjgl.opengl.ATIMeminfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.NVXGPUMemoryInfo;

import static org.lwjgl.opengl.GL11.GL_NO_ERROR;
import static org.lwjgl.opengl.GL11.GL_RENDERER;
import static org.lwjgl.opengl.GL11.GL_VENDOR;
import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glGetString;

/**
 * GPU identification for the first-launch preset (client thread, GL context current).
 *
 * Video memory: {@code GL_NVX_gpu_memory_info} gives the dedicated VRAM (NVIDIA). {@code GL_ATI_meminfo} only gives
 * the FREE texture memory (AMD): at startup it is close to the total, and an underestimate is the safe side.
 * Neither is exposed by Intel or Mesa: -1 ("unknown"), the classifier then relies on the renderer string.
 *
 * @param vramSource "NVX" (dedicated), "ATI" (free texture memory) or "none"
 */
public record GsrGpuInfo(String vendor, String renderer, long vramMiB, String vramSource) {
    public static GsrGpuInfo detect() {
        String vendor = String.valueOf(glGetString(GL_VENDOR));
        String renderer = String.valueOf(glGetString(GL_RENDERER));
        long vram = -1;
        String src = "none";
        GLCapabilities caps = GL.getCapabilities();
        int[] kib = new int[4];
        if (caps.GL_NVX_gpu_memory_info) {
            glGetIntegerv(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX, kib);
            if (glGetError() == GL_NO_ERROR && kib[0] > 0) { vram = kib[0] / 1024L; src = "NVX"; }
        } else if (caps.GL_ATI_meminfo) {
            glGetIntegerv(ATIMeminfo.GL_TEXTURE_FREE_MEMORY_ATI, kib);   // [0] = total free in the pool, KiB
            if (glGetError() == GL_NO_ERROR && kib[0] > 0) { vram = kib[0] / 1024L; src = "ATI"; }
        }
        for (int i = 0; i < 8 && glGetError() != GL_NO_ERROR; i++) { /* leave no error behind for Minecraft */ }
        return new GsrGpuInfo(vendor, renderer, vram, src);
    }
}
