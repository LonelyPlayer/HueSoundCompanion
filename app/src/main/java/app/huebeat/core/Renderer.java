package app.huebeat.core;

import java.util.Arrays;
import java.util.Random;

/** Turns {@link Features} into per-channel colors for the entertainment area. */
public final class Renderer {
    private final int n;
    private final float[] rank;      // 0..1, left to right
    private final boolean[] leftSide;
    private final float[] env, sparkle, posOffset;
    private final float[] target, out;
    private final float[] tmp = new float[3];
    private final Random random = new Random();

    private boolean initialized = false;
    private int lastBeat, lastSnare, beatIndex;
    private float strobe, ambientLevel;
    /** Playhead on the palette ramp: beats move the target, the playhead glides after it. */
    private float rampTarget, rampPos, loudPos;
    private final float[] mean = new float[3];

    /** @param x channel x positions from the entertainment configuration (-1 = left, 1 = right) */
    public Renderer(float[] x) {
        n = x.length;
        rank = new float[n];
        leftSide = new boolean[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(x[a], x[b]));
        for (int r = 0; r < n; r++) rank[order[r]] = n > 1 ? (float) r / (n - 1) : 0.5f;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : x) { min = Math.min(min, v); max = Math.max(max, v); }
        boolean spread = max - min > 0.2f;
        for (int i = 0; i < n; i++) leftSide[i] = spread ? x[i] < (min + max) / 2 : rank[i] < 0.5f;
        env = new float[n];
        sparkle = new float[n];
        posOffset = new float[n];
        target = new float[3 * n];
        out = new float[3 * n];
        for (int i = 0; i < n; i++) posOffset[i] = rank[i] * 0.3f;
    }

    private volatile float[] screen;

    /** Latest per-channel screen colors (rgb 0..1), or null when the screen isn't captured. */
    public void setScreenColors(float[] rgb) {
        screen = rgb != null && rgb.length >= 3 * n ? rgb : null;
    }

    /** Color for channel i at full brightness: from the screen if enabled, else from the palette. */
    private void color(int i, LightSettings s, float pos, float keyHue, float sat) {
        float[] sc = screen;
        if ((s.screenColors || s.style == LightSettings.STYLE_SCREEN) && sc != null) {
            float r = sc[3 * i], g = sc[3 * i + 1], b = sc[3 * i + 2];
            float m = Math.max(r, Math.max(g, b));
            if (m > 0.04f) {
                tmp[0] = r / m;
                tmp[1] = g / m;
                tmp[2] = b / m;
            } else {
                tmp[0] = tmp[1] = tmp[2] = 0.6f; // dark screen: neutral
            }
            return;
        }
        Palettes.color(s.palette, pos, keyHue, sat, tmp, 0);
    }

    /** Pure screen sync (ambilight): follows the screen's colors and brightness, ignores the music. */
    private void screenStyle(float dt, LightSettings s) {
        float[] sc = screen;
        float inten = s.intensity;
        for (int i = 0; i < n; i++) {
            if (sc == null) {
                setTarget(i, new float[]{1f, 0.8f, 0.6f}, 0.05f, 0f);
                continue;
            }
            float r = sc[3 * i], g = sc[3 * i + 1], b = sc[3 * i + 2];
            float m = Math.max(r, Math.max(g, b));
            float v = (float) Math.pow(m, 0.75);          // lift dark scenes a little
            float boost = lerp(1.0f, 1.8f, inten);        // more vivid colors at higher intensity
            float avg = (r + g + b) / 3f;
            float[] c = new float[3];
            float[] src = {r, g, b};
            for (int k = 0; k < 3; k++) {
                float x = avg + (src[k] - avg) * boost;
                c[k] = m > 0.02f ? clamp(x / m, 0f, 1f) : 0f;
            }
            setTarget(i, c, Math.max(0.02f, v), 0f);
        }
        smooth(dt, lerp(0.35f, 0.04f, inten), lerp(0.5f, 0.08f, inten), s);
    }

    /** Average output color (for the UI), 0..1. */
    public float[] meanColor() {
        return mean;
    }

    /**
     * @param f   analysis snapshot (may be null before audio arrives)
     * @param t   time in seconds
     * @param dt  seconds since the previous frame
     * @return rgb triples, 0..1, one per channel (live array, do not keep)
     */
    public float[] render(Features f, double t, float dt, LightSettings s) {
        if (s.style == LightSettings.STYLE_SCREEN) {
            screenStyle(dt, s);
            return out;
        }
        if (f == null) f = new Features();
        float inten = s.intensity;
        if (!initialized) {
            lastBeat = f.beatCount;
            lastSnare = f.snareCount;
            initialized = true;
        }
        boolean beat = f.beatCount != lastBeat;
        boolean snare = f.snareCount != lastSnare;
        lastBeat = f.beatCount;
        lastSnare = f.snareCount;
        float period = f.bpm > 0 ? 60f / f.bpm : 0.5f;
        ambientLevel += (f.level - ambientLevel) * (1f - (float) Math.exp(-dt / 0.6f));

        // Color movement along the palette ramp: each beat pushes the playhead forward and it
        // glides there in time with the tempo; loudness adds an offset so drops get other colors.
        float move = s.colorMove;
        if (beat && s.style != LightSettings.STYLE_PARTY) {
            float step = s.style == LightSettings.STYLE_AMBIENT ? 0.01f + 0.08f * move : 0.02f + 0.28f * move;
            rampTarget += step * (0.7f + 0.3f * f.beatStrength);
        }
        float glide = clamp(period * (s.style == LightSettings.STYLE_AMBIENT ? 1.2f : 0.35f), 0.06f, 1.5f);
        rampPos += (rampTarget - rampPos) * (1f - (float) Math.exp(-dt / glide));
        loudPos += (move * 0.35f * f.level - loudPos) * (1f - (float) Math.exp(-dt / 0.25f));
        if (rampTarget > 1000f) { rampTarget -= 1000f; rampPos -= 1000f; }

        if (f.silent) {
            for (int i = 0; i < n; i++) {
                env[i] = decay(env[i], dt, 0.3f);
                sparkle[i] = 0f;
                color(i, s, posOffset[i], f.keyHue, 0.8f);
                setTarget(i, tmp, 0.05f, 0f);
            }
            smooth(dt, 0.8f, 0.8f, s);
            return out;
        }

        switch (s.style) {
            case LightSettings.STYLE_SPECTRUM: spectrum(f, t, dt, s, beat, inten); break;
            case LightSettings.STYLE_AMBIENT: ambient(f, t, dt, s, beat, inten); break;
            case LightSettings.STYLE_PARTY: party(f, dt, s, beat, snare, inten, period); break;
            default: beatStyle(f, dt, s, beat, snare, inten, period); break;
        }
        return out;
    }

    // Intensity = how hard and how fast the light reacts.
    //   low:  soft swells that fade out slowly over the whole beat, lights never go dark
    //   high: sharp hits that drop off almost instantly to near-black between beats

    private void beatStyle(Features f, float dt, LightSettings s, boolean beat, boolean snare, float inten, float period) {
        if (beat) {
            beatIndex++;
            float strength = 0.6f + 0.4f * f.beatStrength;
            boolean accentLeft = (beatIndex & 1) == 0;
            for (int i = 0; i < n; i++) {
                boolean accent = n == 1 || leftSide[i] == accentLeft;
                env[i] = Math.max(env[i], accent ? strength : strength * lerp(0.85f, 0.25f, inten));
            }
        }
        if (snare && inten > 0.2f && n > 0) {
            int i = random.nextInt(n);
            sparkle[i] = Math.max(sparkle[i], 0.3f + 0.6f * inten);
        }
        float tau = lerp(Math.max(0.25f, period * 0.9f), 0.06f, (float) Math.pow(inten, 0.8));
        float floor = lerp(0.45f, 0.02f, inten) * (0.4f + 0.6f * f.level);
        for (int i = 0; i < n; i++) {
            env[i] = decay(env[i], dt, tau);
            sparkle[i] = decay(sparkle[i], dt, lerp(0.15f, 0.05f, inten));
            color(i, s, rampPos + loudPos + rank[i] * 0.15f, f.keyHue, 0.95f);
            setTarget(i, tmp, floor + (1f - floor) * env[i], sparkle[i] * 0.75f);
        }
        smooth(dt, lerp(0.06f, 0f, inten), lerp(0.12f, 0.02f, inten), s);
    }

    private void spectrum(Features f, double t, float dt, LightSettings s, boolean beat, float inten) {
        if (beat) for (int i = 0; i < n; i++) env[i] = 1f;
        float drift = (float) (t * 0.01);
        float gamma = lerp(0.7f, 2.4f, inten);  // high = only the peaks light up
        float floor = lerp(0.18f, 0.0f, inten);
        for (int i = 0; i < n; i++) {
            float bp = n > 1 ? rank[i] * (Features.BANDS - 1) : 1f;
            int b0 = (int) Math.floor(bp);
            int b1 = Math.min(Features.BANDS - 1, b0 + 1);
            float fr = bp - b0;
            float band = f.bands[b0] * (1 - fr) + f.bands[b1] * fr;
            env[i] = decay(env[i], dt, lerp(0.3f, 0.08f, inten));
            float v = floor + (1f - floor) * (float) Math.pow(band, gamma);
            v += 0.3f * env[i] * (1f - rank[i]) * inten;
            color(i, s, drift + rampPos * 0.5f + loudPos + rank[i] * (0.25f + 0.35f * inten), f.keyHue, 0.95f);
            setTarget(i, tmp, v, 0f);
        }
        smooth(dt, lerp(0.12f, 0f, inten), lerp(0.4f, 0.03f, inten), s);
    }

    private void ambient(Features f, double t, float dt, LightSettings s, boolean beat, float inten) {
        if (beat) {
            beatIndex++;
            for (int i = 0; i < n; i++) env[i] = 1f;
        }
        for (int i = 0; i < n; i++) {
            env[i] = decay(env[i], dt, lerp(1.0f, 0.3f, inten));
            float pos = 0.12f * (float) Math.sin(t * 0.15 + rank[i] * 1.3) + rampPos + loudPos * 0.6f;
            color(i, s, pos + posOffset[i] * 0.5f, f.keyHue, 0.75f + 0.2f * f.tonality);
            float base = lerp(0.5f, 0.25f, inten);
            setTarget(i, tmp, base + lerp(0.3f, 0.5f, inten) * ambientLevel + lerp(0.05f, 0.4f, inten) * env[i], 0f);
        }
        smooth(dt, lerp(1.2f, 0.2f, inten), lerp(1.6f, 0.4f, inten), s);
    }

    private void party(Features f, float dt, LightSettings s, boolean beat, boolean snare, float inten, float period) {
        if (beat) {
            beatIndex++;
            float base = random.nextFloat();
            for (int i = 0; i < n; i++) {
                posOffset[i] = base + 0.618034f * i; // golden-ratio spread: neighbours differ
                env[i] = 1f;
            }
        }
        if (snare) {
            if (s.strobe && inten > 0.5f) {
                strobe = 1f;
            } else if (n > 0) {
                int i = random.nextInt(n);
                sparkle[i] = 0.9f;
            }
        }
        strobe = decay(strobe, dt, 0.045f);
        float tau = lerp(Math.max(0.2f, period * 0.7f), 0.045f, inten);
        float floor = lerp(0.25f, 0.0f, inten);
        for (int i = 0; i < n; i++) {
            env[i] = decay(env[i], dt, tau);
            sparkle[i] = decay(sparkle[i], dt, 0.06f);
            color(i, s, posOffset[i] + loudPos, f.keyHue, 1f);
            float w = Math.max(sparkle[i] * 0.85f, strobe);
            setTarget(i, tmp, floor + (1f - floor) * Math.max(env[i], strobe), w);
        }
        smooth(dt, 0.0f, lerp(0.08f, 0.015f, inten), s);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp(t, 0f, 1f);
    }

    /** color (full brightness) * v, then mixed toward white by w. */
    private void setTarget(int i, float[] c, float v, float w) {
        v = clamp(v, 0f, 1f);
        for (int k = 0; k < 3; k++) {
            float x = c[k] * v;
            target[3 * i + k] = x + (v - x) * clamp(w, 0f, 1f);
        }
    }

    private void smooth(float dt, float attackTau, float releaseTau, LightSettings s) {
        float aUp = attackTau <= 0 ? 1f : 1f - (float) Math.exp(-dt / attackTau);
        float aDown = releaseTau <= 0 ? 1f : 1f - (float) Math.exp(-dt / releaseTau);
        mean[0] = mean[1] = mean[2] = 0f;
        float maxB = s.maxBrightness;
        for (int i = 0; i < 3 * n; i++) {
            float tg = (float) Math.pow(target[i], 1.4) * maxB; // perceptual punch
            float a = tg > out[i] ? aUp : aDown;
            out[i] += (tg - out[i]) * a;
            mean[i % 3] += out[i] / Math.max(1, n);
        }
    }

    private static float decay(float v, float dt, float tau) {
        return v * (float) Math.exp(-dt / tau);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
