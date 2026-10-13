import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/**
 * Objective flicker measurement on a burst of still-camera captures (GsrClient#autoShot).
 * Usage: java tools/FlickerDiff.java frame_0.png frame_1.png ...
 * Prints, on luminance 0..255: the mean difference between consecutive frames, the share of pixels whose
 * temporal amplitude (max − min) exceeds 16 and 48, and writes a heat map <frame_0>_heat.png
 * (gray = image, red = temporal amplitude).
 */
public class FlickerDiff {
    public static void main(String[] args) throws Exception {
        // Heat maps from previous measurements match the same glob: skip them.
        String[] files = java.util.Arrays.stream(args).filter(a -> !a.endsWith("_heat.png")).toArray(String[]::new);
        if (files.length < 2) { System.err.println("at least 2 images"); System.exit(1); }
        Arrays.sort(files, (a, b) -> Integer.compare(index(a), index(b)));
        BufferedImage first = ImageIO.read(new File(files[0]));
        int w = first.getWidth(), h = first.getHeight(), n = w * h;
        float[] min = new float[n], max = new float[n], prev = lum(first);
        System.arraycopy(prev, 0, min, 0, n);
        System.arraycopy(prev, 0, max, 0, n);
        double sumDiff = 0;
        for (int f = 1; f < files.length; f++) {
            float[] cur = lum(ImageIO.read(new File(files[f])));
            for (int i = 0; i < n; i++) {
                sumDiff += Math.abs(cur[i] - prev[i]);
                min[i] = Math.min(min[i], cur[i]);
                max[i] = Math.max(max[i], cur[i]);
            }
            prev = cur;
        }
        // "Water" zone: lower screen, excluding hand and hotbar (rows 45–92 %, columns 0–65 %).
        int x1 = (int) (w * 0.65), y0 = (int) (h * 0.45), y1 = (int) (h * 0.92);
        double zoneSum = 0; int zoneN = 0, zone16 = 0;
        int over16 = 0, over48 = 0;
        BufferedImage heat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        float[] base = lum(first);
        for (int i = 0; i < n; i++) {
            float r = max[i] - min[i];
            if (r > 16) over16++;
            if (r > 48) over48++;
            int x = i % w, y = i / w;
            if (x < x1 && y >= y0 && y < y1) { zoneSum += r; zoneN++; if (r > 16) zone16++; }
            int g = (int) (base[i] * 0.5f);
            int red = Math.min(255, g + (int) (r * 4));
            heat.setRGB(i % w, i / w, (red << 16) | (g << 8) | g);
        }
        System.out.printf("%d images %dx%d: mean difference %.3f, amplitude >16: %.2f %%, >48: %.2f %%%n",
                files.length, w, h, sumDiff / ((double) n * (files.length - 1)),
                100.0 * over16 / n, 100.0 * over48 / n);
        System.out.printf("water zone: mean amplitude %.2f, >16: %.2f %%%n", zoneSum / zoneN, 100.0 * zone16 / zoneN);
        File out = new File(files[0].replace(".png", "_heat.png"));
        ImageIO.write(heat, "png", out);
        System.out.println("map: " + out);
    }

    private static int index(String f) {
        String s = f.replaceAll(".*_(\\d+)\\.png$", "$1");
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static float[] lum(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        float[] l = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                l[y * w + x] = 0.2126f * ((p >> 16) & 255) + 0.7152f * ((p >> 8) & 255) + 0.0722f * (p & 255);
            }
        return l;
    }
}
