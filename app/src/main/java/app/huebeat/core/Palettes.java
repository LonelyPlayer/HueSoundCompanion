package app.huebeat.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Color palettes. Index 0 is "Auto" (colors from the music's harmony), then the built-in
 * palettes, then the user's own palettes.
 */
public final class Palettes {
    private static final String[] BUILT_IN_NAMES = {"Auto", "Sunset", "Ocean", "Forest", "Neon", "Fire", "Pastel"};

    // RGB 0xRRGGBB, cycled through smoothly
    private static final int[][] BUILT_IN = {
            null,
            {0xFF5E3A, 0xFF2A6D, 0xB0306A, 0xFFB03A},          // Sunset
            {0x0077FF, 0x00C2D1, 0x2E3BFF, 0x00FFB3},          // Ocean
            {0x2EB82E, 0x9BE22E, 0x0F7A4A, 0xD4E157},          // Forest
            {0xFF00E6, 0x00F0FF, 0x7A00FF, 0x39FF14},          // Neon
            {0xFF2200, 0xFF7A00, 0xFFC300, 0xB30000},          // Fire
            {0xFFB3C7, 0xB3D9FF, 0xD9B3FF, 0xB3FFD9, 0xFFE5B3} // Pastel
    };

    public static final int BUILT_IN_COUNT = BUILT_IN.length;

    /** A user-made palette. */
    public static final class Custom {
        public final String name;
        public final int[] colors;

        public Custom(String name, int[] colors) {
            this.name = name;
            this.colors = colors;
        }
    }

    private static volatile List<Custom> custom = new ArrayList<>();

    private Palettes() {
    }

    public static void setCustom(List<Custom> list) {
        custom = new ArrayList<>(list);
    }

    public static int count() {
        return BUILT_IN_COUNT + custom.size();
    }

    public static String name(int i) {
        if (i < BUILT_IN_COUNT) return BUILT_IN_NAMES[Math.max(0, i)];
        List<Custom> c = custom;
        int k = i - BUILT_IN_COUNT;
        return k < c.size() ? c.get(k).name : BUILT_IN_NAMES[0];
    }

    public static boolean isCustom(int i) {
        return i >= BUILT_IN_COUNT && i < count();
    }

    private static int[] colorsOf(int i) {
        if (i <= 0) return null;
        if (i < BUILT_IN_COUNT) return BUILT_IN[i];
        List<Custom> c = custom;
        int k = i - BUILT_IN_COUNT;
        return k < c.size() ? c.get(k).colors : null;
    }

    /**
     * Writes a color into out[o..o+2] (0..1).
     *
     * @param palette    palette index
     * @param position   cyclic position in the palette (any float, wraps at 1)
     * @param keyHue     harmony hue from the analyzer (used by Auto)
     * @param saturation 0..1
     */
    public static void color(int palette, float position, float keyHue, float saturation, float[] out, int o) {
        int[] c = colorsOf(palette);
        if (c == null || c.length == 0) {
            float h = keyHue + position * 0.45f; // Auto: harmony color plus a related spread
            hsv(h, saturation, 1f, out, o);
            return;
        }
        float p = position - (float) Math.floor(position);
        float f = p * c.length;
        int i = (int) f;
        float t = f - i;
        t = t * t * (3 - 2 * t); // smoothstep between neighbours
        int a = c[i % c.length], b = c[(i + 1) % c.length];
        for (int k = 0; k < 3; k++) {
            int shift = 16 - 8 * k;
            float ca = ((a >> shift) & 0xFF) / 255f, cb = ((b >> shift) & 0xFF) / 255f;
            float v = ca + (cb - ca) * t;
            out[o + k] = v + (1f - v) * (1f - saturation); // desaturate toward white when requested
        }
    }

    public static void hsv(float h, float s, float v, float[] out, int o) {
        h = h - (float) Math.floor(h);
        float hh = h * 6f;
        int i = (int) hh;
        float f = hh - i;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i % 6) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        out[o] = r;
        out[o + 1] = g;
        out[o + 2] = b;
    }
}
