package app.huebeat.core;

import java.util.Random;

/**
 * Animations without sound, for the "Ambient" mode: static colors, palettes, the non-reactive
 * effects of LedFx (Single Color, Gradient, Fade, Rainbow, Random Flash, Pixels) and own ones
 * (candles, fireplace, aurora, storm, …).
 *
 * Works for any number of outputs placed along a line (0..1): a few Hue lamps or every LED of a
 * strip. One instance per output group; it keeps its own state (flames, flashes, stars).
 */
public final class Ambient {
    public static final int STATIC = 0, PALETTE = 1, SINGLE = 2, GRADIENT = 3, FADE = 4, RAINBOW = 5,
            RANDOM_FLASH = 6, PIXELS = 7, CANDLES = 8, FIREPLACE = 9, BREATHING = 10, AURORA = 11, OCEAN = 12,
            STORM = 13, STARS = 14, COLOR_LOOP = 15, LAVA = 16, PARTY = 17;
    public static final String[] NAMES = {"Colors per light", "Palette", "Single Color", "Gradient", "Fade", "Rainbow",
            "Random Flash", "Pixels", "Candles", "Fireplace", "Breathing", "Aurora", "Ocean", "Thunderstorm",
            "Starry night", "Color loop", "Lava", "Party"};
    public static final String[] CATEGORIES = {"Colors", "Colors", "LedFx", "LedFx", "LedFx", "LedFx",
            "LedFx", "LedFx", "Cozy", "Cozy", "Calm", "Calm", "Calm", "Nature", "Nature", "Lively", "Lively", "Lively"};

    /** Whether an animation uses the color (main or own color). */
    public static boolean usesColor(int a) {
        return a == STATIC || a == SINGLE || a == RANDOM_FLASH || a == PIXELS || a == BREATHING;
    }

    /** Whether an animation uses the palette (Breathing: the color, or a palette when one is picked). */
    public static boolean usesPalette(int a) {
        return a != STATIC && a != SINGLE && a != RANDOM_FLASH && a != PIXELS && a != RAINBOW && a != STORM;
    }

    /** Settings (may change live). */
    public static final class Settings {
        public volatile int animation = PALETTE;
        public volatile int palette = 1;
        /** For Single Color, Breathing and strips in "Static colors". */
        public volatile int color = 0xFF8A2A;
        /** 0..1, 0.5 = normal. */
        public volatile float speed = 0.5f;
        /** 0..1. */
        public volatile float brightness = 1f;

        public void copyFrom(Settings o) {
            animation = o.animation;
            palette = o.palette;
            color = o.color;
            speed = o.speed;
            brightness = o.brightness;
        }
    }

    private final int n;
    private final boolean strip;
    private final float[] pos;
    private final float[] out;
    private final Random rnd = new Random();
    private final float[] seed;
    private final float[] tmp = new float[3];
    private double t;
    /** The lamps' animation: outputs set to "same as the lights" run on its clock, so they stay in step. */
    public static volatile Ambient master;
    /** When set, this animation takes its time and fade/rainbow position from {@code follow}. */
    public volatile Ambient follow;
    // state
    private final float[] flash, star, partyFrom, partyTo;
    private float partyMix = 1f, partyTimer;
    private double hitTime = -10;
    private int hitStart, hitLen;
    private float stormFlash, stormNext = 3f;
    private int stormStrikes;
    private float fadeIdx;
    private boolean fadeFwd = true;
    private float rainbowHue;
    private int pixel;
    private float pixelTimer;
    /** This animation's share of the room's lights (an own look of one lamp among ten: 0.1). Random
     *  flashes come that much less often, so a light with its own look doesn't flash more than the rest. */
    public volatile float share = 1f;
    /** The devices behind the points (the room's scene); null for a single strip. */
    private Devices devices;
    private int[] devOrder;
    private boolean[] stormHit;

    /**
     * Chance of a new random flash this frame. A flash lasts 0.5 s, and no new one starts meanwhile;
     * an animation with a smaller {@code share} waits correspondingly longer, so each light ends up
     * flashing as often as the lights of the shared room animation.
     */
    private float flashChance(float dt, float sp) {
        double rate = -Math.log(1 - 0.1 * 5); // LedFx, per unit of animation time (t runs at the speed)
        float sh = Math.max(0.01f, Math.min(1f, share));
        if (sh < 1f) rate = 1.0 / Math.max(1e-3, (1.0 / rate + 0.5) / sh - 0.5);
        return (float) (1 - Math.exp(-rate * dt * sp));
    }

    /** Random picks and moving pixels then go device by device, so every light gets its turn. */
    public void setDevices(Devices d) {
        if (d == null || d.of.length != n || strip) return;
        devices = d;
        Integer[] o = new Integer[d.count()];
        for (int k = 0; k < o.length; k++) o[k] = k;
        java.util.Arrays.sort(o, (a, b) -> Float.compare(pos[d.start[a]], pos[d.start[b]])); // along the flow
        devOrder = new int[o.length];
        for (int k = 0; k < o.length; k++) devOrder[k] = o[k];
        stormHit = new boolean[o.length];
    }

    /** @param positions position of every output along the line, 0..1 */
    public Ambient(float[] positions, boolean strip) {
        n = positions.length;
        this.strip = strip;
        pos = positions.clone();
        out = new float[3 * n];
        seed = new float[n];
        for (int i = 0; i < n; i++) seed[i] = rnd.nextFloat() * 1000f;
        flash = new float[n];
        star = new float[n];
        partyFrom = new float[3 * n];
        partyTo = new float[3 * n];
    }

    /** Evenly spread positions for {@code n} outputs. */
    public static float[] line(int n) {
        float[] p = new float[n];
        for (int i = 0; i < n; i++) p[i] = n == 1 ? 0.5f : (float) i / (n - 1);
        return p;
    }

    /**
     * @param colors per-output colors (0xRRGGBB) for "Static colors" on lamps, or null
     * @return rgb per output, 0..1 (live array)
     */
    public float[] render(float dt, Settings s, int[] colors) {
        dt = Math.max(0f, Math.min(0.1f, dt));
        float sp = (float) Math.pow(4.0, (s.speed - 0.5) * 2.0);
        Ambient lead = follow;
        boolean synced = lead != null && lead != this;
        if (synced) {
            t = lead.t;
            fadeIdx = lead.fadeIdx;
            fadeFwd = lead.fadeFwd;
            rainbowHue = lead.rainbowHue;
        } else {
            t += dt * sp;
        }
        float tt = (float) t;
        int pal = s.palette;
        switch (s.animation) {
            case STATIC:
                for (int i = 0; i < n; i++) {
                    int c = colors != null && i < colors.length ? colors[i] : s.color;
                    rgb(i, c, 1f);
                }
                break;
            case PALETTE:
                for (int i = 0; i < n; i++) {
                    palette(pal, pos[i], tmp);
                    put(i, tmp, 1f);
                }
                break;
            case SINGLE:
                for (int i = 0; i < n; i++) rgb(i, s.color, 1f);
                break;
            case GRADIENT:
                for (int i = 0; i < n; i++) {
                    palette(pal, pos[i] + tt * 0.02f, tmp);
                    put(i, tmp, 1f);
                }
                break;
            case FADE: {
                // LedFx Fade: whole output walks back and forth through the gradient
                if (!synced) fadeIdx += 0.0075f * dt * sp * 2f;
                if (fadeIdx > 1) {
                    fadeIdx = 0;
                    fadeFwd = !fadeFwd;
                }
                palette(pal, fadeFwd ? fadeIdx : 1 - fadeIdx, tmp);
                for (int i = 0; i < n; i++) put(i, tmp, 1f);
                break;
            }
            case RAINBOW: {
                // LedFx Rainbow: hue steps 0.01 ten times per second at speed 1
                if (!synced) rainbowHue += 0.1f * dt * sp;
                for (int i = 0; i < n; i++) {
                    Palettes.hsv(rainbowHue + pos[i], 0.95f, 1f, tmp, 0);
                    put(i, tmp, 1f);
                }
                break;
            }
            case RANDOM_FLASH: {
                // LedFx Random Flash: 10% of the outputs flash, then fade over 0.5 s
                java.util.Arrays.fill(out, 0f);
                double since = t - hitTime;
                if (since < 0.5) {
                    float f = (float) (1 - since / 0.5);
                    for (int i = hitStart; i < hitStart + hitLen && i < n; i++) rgb(i, s.color, f);
                } else if (rnd.nextFloat() < flashChance(dt, sp)) {
                    if (devices != null && devices.count() > 0) {
                        // one light at random; a strip flashes a piece of itself
                        int d = rnd.nextInt(devices.count()), len = devices.len[d];
                        hitLen = len <= 8 ? len : Math.max(1, Math.round(len * 0.1f));
                        hitStart = devices.start[d] + rnd.nextInt(len - hitLen + 1);
                    } else {
                        hitLen = Math.max(1, Math.round(n * 0.1f));
                        hitStart = rnd.nextInt(Math.max(1, n - hitLen + 1));
                    }
                    hitTime = t;
                }
                return scale(s.brightness);
            }
            case PIXELS: {
                if (devices != null && devices.count() > 0) {
                    // one light after the other along the flow; on a strip the pixel runs along it
                    pixelTimer += dt * sp;
                    if (pixelTimer >= 1f) {
                        pixelTimer -= (float) Math.floor(pixelTimer);
                        pixel = (pixel + 1) % devOrder.length;
                    }
                    int d = devOrder[Math.min(pixel, devOrder.length - 1)], len = devices.len[d];
                    java.util.Arrays.fill(out, 0f);
                    rgb(devices.start[d] + Math.min(len - 1, (int) (pixelTimer * len)), s.color, 1f);
                    if (len <= 8) for (int i = 0; i < len; i++) rgb(devices.start[d] + i, s.color, 1f);
                    return scale(s.brightness);
                }
                pixelTimer += dt * sp;
                if (pixelTimer >= (strip ? 0.05f : 1f)) {
                    pixelTimer = 0;
                    pixel = (pixel + 1) % n;
                }
                java.util.Arrays.fill(out, 0f);
                rgb(pixel, s.color, 1f);
                return scale(s.brightness);
            }
            case CANDLES:
                for (int i = 0; i < n; i++) {
                    int g = strip ? (int) (pos[i] * Math.max(1, n / 8f)) : i; // strips: one candle per ~8 LEDs
                    float sd = strip ? g * 17.3f : seed[i];
                    float flick = 0.62f + 0.22f * noise(tt * 2.2f + sd) + 0.14f * noise(tt * 9f + sd * 1.7f);
                    float gust = noise(tt * 0.35f + sd * 0.3f);
                    if (gust > 0.82f) flick *= 0.55f + 0.45f * noise(tt * 18f + sd); // a draft makes it dance
                    if (pal <= 0) {
                        // ~1900 K candle light, slightly redder when dimmer
                        tmp[0] = 1f;
                        tmp[1] = 0.42f + 0.16f * flick;
                        tmp[2] = 0.06f + 0.05f * flick;
                    } else {
                        palette(pal, pos[i], tmp);
                    }
                    put(i, tmp, flick * (strip ? 0.85f + 0.15f * noise(tt * 3f + i * 0.37f) : 1f));
                }
                break;
            case FIREPLACE:
                for (int i = 0; i < n; i++) {
                    float x = pos[i] * (strip ? 6f : 2f);
                    float heat = 0.45f + 0.35f * noise(tt * 1.3f + x + seed[i] * (strip ? 0 : 1))
                            + 0.25f * noise(tt * 4.5f + x * 2.3f + seed[i] * 0.1f);
                    // flares: per lamp, or per ~10-LED patch on strips (so it isn't speckled)
                    int g = strip ? Math.min(n - 1, (int) (pos[i] * Math.max(1, n / 10f))) : i;
                    if (!strip || i == 0 || g != (int) (pos[i - 1] * Math.max(1, n / 10f))) {
                        if (rnd.nextFloat() < dt * 0.4f) flash[g] = 0.6f + 0.4f * rnd.nextFloat();
                        flash[g] *= (float) Math.exp(-dt * 3f);
                    }
                    heat = Math.min(1.3f, heat + flash[g] * 0.4f);
                    if (pal <= 0) {
                        // deep red → orange → amber
                        float h = 0.005f + 0.075f * Math.min(1f, heat - 0.2f);
                        Palettes.hsv(h, 1f, 1f, tmp, 0);
                    } else {
                        palette(pal, heat * 0.4f, tmp);
                    }
                    put(i, tmp, Math.min(1f, 0.35f + 0.6f * heat));
                }
                break;
            case BREATHING: {
                float b = (float) Math.pow(Math.sin(Math.PI * (tt / 5f % 1f)), 2);
                for (int i = 0; i < n; i++) {
                    if (pal <= 0) rgb(i, s.color, 0.12f + 0.88f * b);
                    else {
                        palette(pal, pos[i], tmp);
                        put(i, tmp, 0.12f + 0.88f * b);
                    }
                }
                break;
            }
            case AURORA:
                for (int i = 0; i < n; i++) {
                    float x = pos[i];
                    float c = x * 0.6f + 0.25f * noise(tt * 0.12f + x * 2f) + tt * 0.01f;
                    if (pal <= 0) {
                        int[] aur = {0x00FF87, 0x00D1B2, 0x2A6CFF, 0x8A2BE2, 0x00FF87};
                        ramp(aur, c, tmp);
                    } else {
                        palette(pal, c, tmp);
                    }
                    float v = 0.25f + 0.75f * (float) Math.pow(noise(tt * 0.25f + x * 3f + 10f), 1.6);
                    put(i, tmp, v);
                }
                break;
            case OCEAN:
                for (int i = 0; i < n; i++) {
                    float x = pos[i];
                    float wave = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * (x * 1.5 - tt * 0.08)));
                    float c = 0.3f * wave + 0.2f * noise(tt * 0.2f + x * 4f);
                    if (pal <= 0) {
                        int[] sea = {0x003A8C, 0x0077BE, 0x00B4D8, 0x48CAE4, 0x003A8C};
                        ramp(sea, c, tmp);
                    } else {
                        palette(pal, c, tmp);
                    }
                    put(i, tmp, 0.35f + 0.55f * wave + 0.1f * noise(tt * 1.5f + x * 9f));
                }
                break;
            case STORM: {
                stormNext -= dt;
                if (stormNext <= 0 && stormStrikes == 0) {
                    stormStrikes = 1 + rnd.nextInt(3);
                    stormNext = 0;
                    hitStart = rnd.nextInt(n);
                    hitLen = strip ? Math.max(1, n / 3) : Math.max(1, n / 2 + rnd.nextInt(n / 2 + 1));
                    if (devices != null && devices.count() > 0) {
                        // about half the lights get the full strike, at least one
                        boolean any = false;
                        for (int k = 0; k < stormHit.length; k++) any |= (stormHit[k] = rnd.nextBoolean());
                        if (!any) stormHit[rnd.nextInt(stormHit.length)] = true;
                    }
                }
                if (stormStrikes > 0 && stormNext <= 0) {
                    stormFlash = 0.7f + 0.3f * rnd.nextFloat();
                    stormStrikes--;
                    stormNext = stormStrikes > 0 ? 0.06f + rnd.nextFloat() * 0.18f : 2.5f + rnd.nextFloat() * 8f;
                }
                stormFlash *= (float) Math.exp(-dt * 14f);
                for (int i = 0; i < n; i++) {
                    float base = 0.06f + 0.04f * noise(tt * 0.3f + pos[i] * 3f);
                    out[3 * i] = 0.05f * base * 6;
                    out[3 * i + 1] = 0.08f * base * 6;
                    out[3 * i + 2] = 0.35f * base * 6;
                    int d = Math.abs(i - hitStart);
                    if (d < hitLen || !strip) {
                        float f = stormFlash * (strip ? 1f - (float) d / hitLen
                                : devices != null ? (stormHit[devices.of[i]] ? 1f : 0.5f) : (d <= hitLen ? 1f : 0.5f));
                        out[3 * i] += 0.85f * f;
                        out[3 * i + 1] += 0.9f * f;
                        out[3 * i + 2] += 1f * f;
                    }
                }
                return scale(s.brightness);
            }
            case STARS:
                for (int i = 0; i < n; i++) {
                    if (star[i] <= 0 && rnd.nextFloat() < dt * (strip ? 0.12f : 0.3f)) star[i] = 1f;
                    float tw = 0f;
                    if (star[i] > 0) {
                        star[i] -= dt * 0.7f;
                        tw = (float) Math.sin(Math.PI * Math.max(0, star[i]));
                    }
                    float[] night = {0.02f, 0.03f, 0.12f};
                    if (pal <= 0) {
                        out[3 * i] = night[0] + tw * 1f;
                        out[3 * i + 1] = night[1] + tw * 0.95f;
                        out[3 * i + 2] = night[2] + tw * 0.85f;
                    } else {
                        palette(pal, pos[i], tmp);
                        out[3 * i] = night[0] + tw * tmp[0];
                        out[3 * i + 1] = night[1] + tw * tmp[1];
                        out[3 * i + 2] = night[2] + tw * tmp[2];
                    }
                }
                return scale(s.brightness);
            case COLOR_LOOP:
                for (int i = 0; i < n; i++) {
                    if (pal <= 0) Palettes.hsv(tt * 0.03f, 1f, 1f, tmp, 0);
                    else palette(pal, tt * 0.03f, tmp);
                    put(i, tmp, 1f);
                }
                break;
            case LAVA:
                for (int i = 0; i < n; i++) {
                    // LedFx's Lava lamp without sound
                    float il = pos[i];
                    float t1 = tt * 7f / 65.536f, t2 = tt * 14f / 65.536f;
                    float w1 = sin01(t1 + il), w2 = sin01(t2 - il), w3 = sin01(il + w1 + w2);
                    float a = (w1 + 0.1f) * w2 * w3;
                    float h = t1 + il + a * 0.1f;
                    float v = (a + 0.4f) * (a + 0.4f);
                    if (pal <= 0) Palettes.hsv(h, 1f, 1f, tmp, 0);
                    else palette(pal, h, tmp);
                    put(i, tmp, Math.min(1f, v));
                }
                break;
            case PARTY: {
                partyTimer -= dt;
                if (partyTimer <= 0) {
                    partyTimer = 1.6f;
                    System.arraycopy(out, 0, partyFrom, 0, out.length);
                    float base = rnd.nextFloat();
                    for (int i = 0; i < n; i++) {
                        float p = strip ? base + pos[i] * 0.5f : base + 0.618034f * i;
                        if (pal <= 0) Palettes.hsv(p, 1f, 1f, partyTo, 3 * i);
                        else Palettes.color(pal, p, 0, 1f, partyTo, 3 * i);
                    }
                    partyMix = 0f;
                }
                partyMix = Math.min(1f, partyMix + dt / 0.35f);
                float m = partyMix * partyMix * (3 - 2 * partyMix);
                for (int i = 0; i < out.length; i++) out[i] = partyFrom[i] + (partyTo[i] - partyFrom[i]) * m;
                return scale(s.brightness);
            }
            default:
                java.util.Arrays.fill(out, 0f);
        }
        return scale(s.brightness);
    }

    private float[] scale(float b) {
        for (int i = 0; i < out.length; i++) out[i] = Math.max(0f, Math.min(1f, out[i])) * b;
        return out;
    }

    private void put(int i, float[] c, float v) {
        out[3 * i] = c[0] * v;
        out[3 * i + 1] = c[1] * v;
        out[3 * i + 2] = c[2] * v;
    }

    private void rgb(int i, int c, float v) {
        out[3 * i] = ((c >> 16) & 0xFF) / 255f * v;
        out[3 * i + 1] = ((c >> 8) & 0xFF) / 255f * v;
        out[3 * i + 2] = (c & 0xFF) / 255f * v;
    }

    private static void palette(int pal, float p, float[] c) {
        if (pal <= 0) Palettes.hsv(p, 1f, 1f, c, 0);
        else Palettes.color(pal, p, 0f, 1f, c, 0);
    }

    private static void ramp(int[] cols, float p, float[] c) {
        p = p - (float) Math.floor(p);
        float f = p * (cols.length - 1);
        int a = (int) f, b = Math.min(cols.length - 1, a + 1);
        float t = f - a;
        t = t * t * (3 - 2 * t);
        for (int k = 0; k < 3; k++) {
            int sh = 16 - 8 * k;
            c[k] = (((cols[a] >> sh) & 0xFF) * (1 - t) + ((cols[b] >> sh) & 0xFF) * t) / 255f;
        }
    }

    private static float sin01(double x) {
        return (float) (0.5 * Math.sin(x * 2 * Math.PI) + 0.5);
    }

    /** Smooth 1D value noise, 0..1. */
    static float noise(float x) {
        int i = (int) Math.floor(x);
        float f = x - i;
        float a = hash(i), b = hash(i + 1);
        float u = f * f * (3 - 2 * f);
        return a + (b - a) * u;
    }

    private static float hash(int i) {
        int h = i * 374761393 + 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }
}
