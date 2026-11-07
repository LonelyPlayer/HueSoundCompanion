package app.huebeat.core;

/**
 * "Just lights", static colors: every device with its own color paints the room around it. Devices
 * that follow the global look take the color at their place: a blend of the colors nearby (closer
 * ones count more), or the main color when nobody has an own color.
 */
public final class LampColors {
    private LampColors() {
    }

    private static volatile float[] xs, ys;
    private static volatile int[] colors;

    public static void set(float[] x, float[] y, int[] c) {
        int n = Math.min(x.length, Math.min(y.length, c.length));
        float[] ax = new float[n], ay = new float[n];
        int[] ac = new int[n];
        System.arraycopy(x, 0, ax, 0, n);
        System.arraycopy(y, 0, ay, 0, n);
        System.arraycopy(c, 0, ac, 0, n);
        colors = ac;
        ys = ay;
        xs = ax;
    }

    public static void clear() {
        xs = null;
        ys = null;
        colors = null;
    }

    /** The color at room place (x, y), or {@code fallback} when no device has an own color. */
    public static int at(float x, float y, int fallback) {
        float[] px = xs, py = ys;
        int[] pc = colors;
        if (px == null || py == null || pc == null || pc.length == 0) return fallback;
        float r = 0, g = 0, b = 0, wsum = 0;
        for (int i = 0; i < pc.length; i++) {
            float dx = x - px[i], dy = y - py[i];
            float d2 = dx * dx + dy * dy;
            if (d2 < 1e-6f) return pc[i];
            float w = 1f / (d2 * d2); // inverse distance, sharp: the nearest color dominates
            r += w * ((pc[i] >> 16) & 0xFF);
            g += w * ((pc[i] >> 8) & 0xFF);
            b += w * (pc[i] & 0xFF);
            wsum += w;
        }
        return (Math.round(r / wsum) << 16) | (Math.round(g / wsum) << 8) | Math.round(b / wsum);
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
