import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;

/**
 * Mesure des « vagues erratiques » sur une rafale caméra EN MOUVEMENT régulier (-PgsrMove).
 * Pour un mouvement lent et uniforme, chaque frame ≈ moyenne de ses deux voisines ; l'écart
 * |f(k) − (f(k−1) + f(k+1)) / 2| mesure ce qui saute d'une frame à l'autre au lieu de glisser.
 * Usage : java tools/JerkDiff.java <yMin 0..1> frame_0.png frame_1.png ...   (yMin : haut de la zone d'eau)
 * Affiche l'écart moyen (luminance 0..255) et la part de pixels au-dessus de 8 et 24.
 */
public class JerkDiff {
    public static void main(String[] args) throws Exception {
        float yMin = Float.parseFloat(args[0]);
        String[] files = Arrays.stream(args).skip(1).filter(a -> !a.endsWith("_heat.png")).toArray(String[]::new);
        Arrays.sort(files, (a, b) -> Integer.compare(index(a), index(b)));
        float[][] f = new float[files.length][];
        int w = 0, h = 0;
        for (int i = 0; i < files.length; i++) {
            BufferedImage im = ImageIO.read(new File(files[i]));
            w = im.getWidth(); h = im.getHeight();
            f[i] = new float[w * h];
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int c = im.getRGB(x, y);
                f[i][x + w * y] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
            }
        }
        double sum = 0; long n = 0, over8 = 0, over24 = 0;
        int y0 = (int) (yMin * h);
        for (int k = 1; k + 1 < f.length; k++)
            for (int y = y0; y < h; y++) for (int x = 0; x < w; x++) {
                int i = x + w * y;
                float j = Math.abs(f[k][i] - 0.5f * (f[k - 1][i] + f[k + 1][i]));
                sum += j; n++;
                if (j > 8) over8++;
                if (j > 24) over24++;
            }
        // Carte de chaleur : gris = première frame, rouge = à-coup maximal sur la rafale.
        float[] mx = new float[w * h];
        for (int k = 1; k + 1 < f.length; k++)
            for (int i = 0; i < w * h; i++) mx[i] = Math.max(mx[i], Math.abs(f[k][i] - 0.5f * (f[k - 1][i] + f[k + 1][i])));
        BufferedImage heat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            int g = (int) (f[0][i] * 0.5f), r = Math.min(255, g + (int) (mx[i] * 6f));
            heat.setRGB(i % w, i / w, (r << 16) | (g << 8) | g);
        }
        ImageIO.write(heat, "png", new File(files[0].replace(".png", "_jerk_heat.png")));
        System.out.printf("%-40s à-coup moyen %.2f   > 8 : %.2f %%   > 24 : %.3f %%%n",
                new File(files[0]).getName(), sum / n, 100.0 * over8 / n, 100.0 * over24 / n);
    }

    private static int index(String f) {
        String s = f.replaceAll(".*_(\\d+)\\.png$", "$1");
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
