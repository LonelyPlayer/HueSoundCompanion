package app.huebeat.core;

/**
 * "Just lights", Colors per light: every device with its own color paints the room around it. Devices
 * that follow the global look take the color at their place: the own colors nearby blended with the
 * main color (closer ones count more), so the main color shows wherever no own color is close.
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

    /** Distance at which a device's own color and the main color count the same. */
    private static final float REACH = 0.5f;

    /**
     * The color at room place (x, y): the own colors nearby, and the main color ({@code fallback})
     * everywhere else, so changing the main color always shows.
     */
    public static int at(float x, float y, int fallback) {
        float[] px = xs, py = ys;
        int[] pc = colors;
        if (px == null || py == null || pc == null || pc.length == 0) return fallback;
        // the main color is everywhere, the own colors win close to their devices
        float w0 = 1f / (REACH * REACH * REACH * REACH);
        float r = w0 * ((fallback >> 16) & 0xFF), g = w0 * ((fallback >> 8) & 0xFF), b = w0 * (fallback & 0xFF), wsum = w0;
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
