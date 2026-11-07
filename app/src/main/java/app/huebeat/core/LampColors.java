package app.huebeat.core;

/**
 * The lamps' own colors in "Just lights" (static colors), by their place in the room, so strips
 * and DMX lights set to "Same as lights" can take them over: a strip gets a smooth blend from the
 * leftmost to the rightmost lamp, a DMX light the color at its own place.
 */
public final class LampColors {
    private LampColors() {
    }

    /** Lamp positions (-1 left .. 1 right, ascending) and their colors; null when there are no lamps. */
    private static volatile float[] xs;
    private static volatile int[] colors;

    public static void set(float[] x, int[] c) {
        int n = Math.min(x.length, c.length);
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(x[a], x[b]));
        float[] sx = new float[n];
        int[] sc = new int[n];
        for (int i = 0; i < n; i++) {
            sx[i] = x[idx[i]];
            sc[i] = c[idx[i]];
        }
        colors = sc;
        xs = sx;
    }

    public static void clear() {
        xs = null;
        colors = null;
    }

    /** The lamp color at room position {@code x} (blended between neighbours), or {@code fallback}. */
    public static int at(float x, int fallback) {
        float[] px = xs;
        int[] pc = colors;
        if (px == null || pc == null || px.length == 0) return fallback;
        if (x <= px[0]) return pc[0];
        if (x >= px[px.length - 1]) return pc[pc.length - 1];
        for (int i = 0; i + 1 < px.length; i++) {
            if (x <= px[i + 1]) {
                float span = px[i + 1] - px[i];
                float t = span > 1e-6f ? (x - px[i]) / span : 0f;
                return blend(pc[i], pc[i + 1], t);
            }
        }
        return pc[pc.length - 1];
    }

    static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
