package app.huebeat.core;

import java.util.Random;

/**
 * LED-strip effects in the spirit of LedFx, rendered at full LED resolution, plus a strobe
 * overlay with its own color that flashes on top of whatever the effect shows.
 *
 * Positions run from the start of the strip (0) to its end (1); for mirrored strips the
 * start is the center. One instance per strip (effects keep state: particles, scrolling, heat).
 */
public final class StripEffect {
    public static final int LIGHTS = 0, EQUALIZER = 1, SPECTRUM = 2, SCROLL = 3, ENERGY = 4,
            FIREWORKS = 5, RAIN = 6, FIRE = 7;
    /** This app's own effects (ids 0..7); LedFx ports follow from {@link #LEDFX_BASE}. */
    private static final String[] OWN = {"Global look", "Equalizer Peaks", "Spectrum Glow", "Flow", "Energy Bars",
            "Fireworks", "Drops", "Campfire"};
    public static final int LEDFX_BASE = 8;
    /** All effect names by id. */
    public static final String[] NAMES;
    /** Category of every effect, for grouping in the picker. */
    public static final String[] CATEGORIES;

    static {
        int n = OWN.length + LedFx.EFFECTS.size();
        NAMES = new String[n];
        CATEGORIES = new String[n];
        for (int i = 0; i < OWN.length; i++) {
            NAMES[i] = OWN[i];
            CATEGORIES[i] = "HueSoundCompanion";
        }
        for (int i = 0; i < LedFx.EFFECTS.size(); i++) {
            NAMES[LEDFX_BASE + i] = LedFx.EFFECTS.get(i).name;
            CATEGORIES[LEDFX_BASE + i] = "LedFx · " + LedFx.EFFECTS.get(i).category;
        }
    }

    /** Variant names for an effect (LedFx's main option), or null. */
    public static String[] variants(int effect) {
        int k = effect - LEDFX_BASE;
        return k >= 0 && k < LedFx.EFFECTS.size() ? LedFx.EFFECTS.get(k).variants : null;
    }

    public static final int STROBE_OFF = 0, STROBE_KICK = 1, STROBE_SNARE = 2, STROBE_BAR = 3, STROBE_BIG = 4,
            STROBE_HIGH = 5, STROBE_BUILD = 6, STROBE_HIGH_BUILD = 7;
    public static final String[] STROBE_TRIGGERS = {"Off", "Kick", "Snare / hi-hat", "Every 4th beat", "Big hits",
            "High intensity", "Build-ups", "High intensity + build-ups"};
    private final Cues.Timer cueTimer = new Cues.Timer();
    public static final int COVER_FULL = 0, COVER_BLOCKS = 1, COVER_SPARKLES = 2;
    public static final String[] STROBE_COVERS = {"Whole strip", "Blocks", "Sparkles"};

    // settings (may be changed live)
    public volatile int effect = LIGHTS;
    /** Variant of LedFx effects (e.g. Bar: wipe/bounce/in-out). */
    public volatile int variant = 0;
    /** Extra blur, 0 (none) .. 1 (strong). */
    public volatile float blur = 0f;
    /** How fast things move, 0..1. */
    public volatile float speed = 0.5f;
    public volatile int strobeTrigger = STROBE_OFF;
    public volatile int strobeColor = 0xFFFFFF;
    /** Flash length, 0 (very short) .. 1 (long). */
    public volatile float strobeLength = 0.3f;
    public volatile int strobeCover = COVER_FULL;
    /** Strobe strength, 0..1. */
    public volatile float strobeAmount = 1f;

    private final int n;
    private final float[] out;
    private final Random rnd = new Random();
    private float[] screen;          // optional colors along the strip (rgb per segment)
    private int screenN;

    private boolean init = false;
    private int lastBeat, lastSnare;
    private float shift;             // palette movement, pushed by beats
    private float beatFlash;

    // equalizer
    private final float[] peak = new float[Features.SPEC];
    // scroll
    private final float[] scroll;
    private float scrollAcc;
    // particles: fireworks & rain
    private static final int MAXP = 240;
    private final float[] pPos = new float[MAXP], pVel = new float[MAXP], pLife = new float[MAXP], pMax = new float[MAXP];
    private final float[] pR = new float[MAXP], pG = new float[MAXP], pB = new float[MAXP], pTail = new float[MAXP];
    private int pCount = 0;
    // fire
    private final float[] heat;
    // strobe
    private float flash;
    private final float[] mask;
    private final float[] tmp = new float[3];

    private LedFx.Ctx fx;
    private LedFx.Effect fxEffect;
    private int fxEffectId = -1;
    private double clock;

    public StripEffect(int logicalPixels) {
        n = Math.max(1, logicalPixels);
        out = new float[3 * n];
        scroll = new float[3 * n];
        heat = new float[n];
        mask = new float[n];
        java.util.Arrays.fill(mask, 1f);
    }

    public int pixels() {
        return n;
    }

    /** Colors along the strip from the screen / TV camera (rgb per segment), or null. */
    public void setScreenColors(float[] seg, int count) {
        screen = seg;
        screenN = count;
    }

    // ------------------------------------------------------------------ rendering

    /**
     * Renders one frame of the selected effect (not used for {@link #LIGHTS}).
     * @return rgb per logical pixel, 0..1 (live array)
     */
    public float[] render(Features f, float dt, LightSettings s) {
        if (f == null) f = new Features();
        boolean beat = false, snare = false;
        if (!init) {
            lastBeat = f.beatCount;
            lastSnare = f.snareCount;
            init = true;
        } else {
            beat = f.beatCount != lastBeat;
            snare = f.snareCount != lastSnare;
            lastBeat = f.beatCount;
            lastSnare = f.snareCount;
        }
        if (beat) {
            shift += 0.02f + 0.12f * s.colorMove;
            beatFlash = 1f;
        }
        beatFlash *= (float) Math.exp(-dt / 0.12f);
        float inten = s.intensity;
        java.util.Arrays.fill(out, 0f);
        clock += dt;
        int id = effect;
        if (id >= LEDFX_BASE && id - LEDFX_BASE < LedFx.EFFECTS.size()) {
            LedFx.Info info = LedFx.EFFECTS.get(id - LEDFX_BASE);
            if (fx == null) fx = new LedFx.Ctx(n);
            if (fxEffectId != id) {
                fxEffect = info.factory.create(n);
                fxEffectId = id;
            }
            fx.rainbowAuto = "Fire".equals(info.name);
            fx.update(f, clock, dt, s, speed, variant);
            fxEffect.render(fx, out);
            LedFx.blurRgb(out, n, info.blur);
            float maxB = s.maxBrightness;
            for (int i = 0; i < out.length; i++) out[i] = Math.max(0f, Math.min(1f, out[i])) * maxB;
            return out;
        }
        switch (effect) {
            case EQUALIZER: equalizer(f, dt, s, inten); break;
            case SPECTRUM: spectrum(f, s, inten); break;
            case SCROLL: scroll(f, dt, s, beat, inten); break;
            case ENERGY: energy(f, s, inten); break;
            case FIREWORKS: fireworks(f, dt, s, beat, snare, inten); break;
            case RAIN: rain(f, dt, s, beat, snare, inten); break;
            case FIRE: fire(f, dt, s, beat, inten); break;
            default: break;
        }
        float maxB = s.maxBrightness;
        for (int i = 0; i < out.length; i++) out[i] = Math.min(1f, out[i]) * maxB;
        return out;
    }

    /**
     * Adds the strobe overlay to a logical-pixel frame (from any effect, including LIGHTS).
     * Needs the same Features the effect saw, to catch the trigger.
     */
    public void strobe(float[] px, Features f, float dt, boolean beat, boolean snare) {
        int trig = strobeTrigger;
        if (trig != STROBE_OFF && f != null && !f.silent) {
            boolean fire;
            switch (trig) {
                case STROBE_SNARE: fire = snare; break;
                case STROBE_BAR: fire = beat && f.beatCount % 4 == 0; break;
                case STROBE_BIG: fire = beat && f.beatStrength > 0.7f && f.level > 0.55f; break;
                case STROBE_HIGH: fire = Cues.fire(Cues.HIGH_INTENSITY, beat, snare, dt, cueTimer); break;
                case STROBE_BUILD: fire = Cues.fire(Cues.BUILDUPS, beat, snare, dt, cueTimer); break;
                case STROBE_HIGH_BUILD: fire = Cues.fire(Cues.BOTH, beat, snare, dt, cueTimer); break;
                default: fire = beat; break;
            }
            if (fire) {
                flash = 1f;
                makeMask();
            }
        }
        float tau = 0.015f + 0.22f * strobeLength * strobeLength;
        flash *= (float) Math.exp(-dt / tau);
        if (flash < 0.01f) return;
        float r = ((strobeColor >> 16) & 0xFF) / 255f, g = ((strobeColor >> 8) & 0xFF) / 255f, b = (strobeColor & 0xFF) / 255f;
        float a0 = flash * strobeAmount;
        for (int i = 0; i < n; i++) {
            float a = a0 * mask[i];
            if (a <= 0f) continue;
            px[3 * i] += (r - px[3 * i]) * a;
            px[3 * i + 1] += (g - px[3 * i + 1]) * a;
            px[3 * i + 2] += (b - px[3 * i + 2]) * a;
        }
    }

    private void makeMask() {
        switch (strobeCover) {
            case COVER_BLOCKS: {
                java.util.Arrays.fill(mask, 0f);
                int blocks = 1 + rnd.nextInt(2);
                int w = Math.max(1, n / 5);
                for (int k = 0; k < blocks; k++) {
                    int st = rnd.nextInt(Math.max(1, n - w + 1));
                    for (int i = st; i < Math.min(n, st + w); i++) mask[i] = 1f;
                }
                break;
            }
            case COVER_SPARKLES:
                for (int i = 0; i < n; i++) mask[i] = rnd.nextFloat() < 0.18f ? 1f : 0f;
                break;
            default:
                java.util.Arrays.fill(mask, 1f);
        }
    }

    // ------------------------------------------------------------------ colors

    /** Color at a position along the strip (palette ramp, or the screen if screen colors are on). */
    private void color(LightSettings s, float pos, float keyHue, float[] c) {
        float[] sc = screen;
        if ((s.screenColors || s.style == LightSettings.STYLE_SCREEN) && sc != null && screenN > 0) {
            float p = Math.max(0f, Math.min(1f, pos - (float) Math.floor(pos))) * (screenN - 1);
            int a = (int) p, b = Math.min(screenN - 1, a + 1);
            float t = p - a;
            float m = 0f;
            for (int k = 0; k < 3; k++) {
                c[k] = sc[3 * a + k] * (1 - t) + sc[3 * b + k] * t;
                m = Math.max(m, c[k]);
            }
            if (m > 0.04f) for (int k = 0; k < 3; k++) c[k] /= m;
            else c[0] = c[1] = c[2] = 0.6f;
            return;
        }
        Palettes.color(s.palette, pos, keyHue, 1f, c, 0);
    }

    private void add(int i, float[] c, float v) {
        if (i < 0 || i >= n || v <= 0f) return;
        out[3 * i] += c[0] * v;
        out[3 * i + 1] += c[1] * v;
        out[3 * i + 2] += c[2] * v;
    }

    private static float spec(Features f, float x) {
        // x in 0..SPEC-1, linear interpolation
        x = Math.max(0f, Math.min(Features.SPEC - 1, x));
        int a = (int) x, b = Math.min(Features.SPEC - 1, a + 1);
        float t = x - a;
        return f.spec[a] * (1 - t) + f.spec[b] * t;
    }

    // ------------------------------------------------------------------ effects

    private void equalizer(Features f, float dt, LightSettings s, float inten) {
        int bars = Math.max(4, Math.min(Features.SPEC, n / 6));
        float w = (float) n / bars;
        float fall = 0.4f + 1.6f * speed; // peak dots fall this many "bar heights" per second
        float gamma = 0.8f + 1.2f * inten;
        for (int b = 0; b < bars; b++) {
            float x0 = (float) b / bars * Features.SPEC, x1 = (float) (b + 1) / bars * Features.SPEC;
            float v = 0f;
            int cnt = 0;
            for (float x = x0; x < x1; x += 0.5f) {
                v = Math.max(v, spec(f, x));
                cnt++;
            }
            v = (float) Math.pow(Math.min(1f, v), gamma);
            int pi = Math.min(Features.SPEC - 1, b * Features.SPEC / bars);
            peak[pi] = Math.max(v, peak[pi] - fall * dt);
            color(s, (float) b / bars * 0.85f + shift, f.keyHue, tmp);
            int start = Math.round(b * w), end = Math.round((b + 1) * w);
            int gap = end - start >= 4 ? 1 : 0;
            int len = end - start - gap;
            int lit = Math.round(v * len);
            for (int i = 0; i < lit; i++) add(start + i, tmp, 0.35f + 0.65f * (float) (i + 1) / Math.max(1, len));
            int pk = start + Math.min(len - 1, Math.round(peak[pi] * len));
            if (peak[pi] > v + 0.05f && len > 2) {
                // peak dot: a lighter tint of the bar's color
                float[] dot = {0.5f + 0.5f * tmp[0], 0.5f + 0.5f * tmp[1], 0.5f + 0.5f * tmp[2]};
                add(pk, dot, 0.6f);
            }
        }
    }

    private void spectrum(Features f, LightSettings s, float inten) {
        float gamma = 0.7f + 1.5f * inten;
        for (int i = 0; i < n; i++) {
            float x = n == 1 ? 0f : (float) i / (n - 1);
            float v = (float) Math.pow(spec(f, x * (Features.SPEC - 1)), gamma);
            color(s, x * 0.85f + shift, f.keyHue, tmp);
            add(i, tmp, 0.03f + v + 0.25f * beatFlash * (1 - x) * inten);
        }
    }

    private void scroll(Features f, float dt, LightSettings s, boolean beat, float inten) {
        float bass = (f.bands[0] + f.bands[1]) / 2, mid = (f.bands[2] + f.bands[3]) / 2, high = (f.bands[4] + f.bands[5]) / 2;
        float sum = bass + mid + high + 1e-4f;
        float pos = (0f * bass + 0.33f * mid + 0.66f * high) / sum + shift;
        color(s, pos, f.keyHue, tmp);
        float v = (float) Math.pow(Math.max(bass, Math.max(mid, high)), 0.8f + inten) * (0.6f + 0.4f * f.level);
        if (beat) v = 1f;
        scrollAcc += (15f + 160f * speed) * dt * Math.max(1f, n / 60f);
        int steps = (int) scrollAcc;
        scrollAcc -= steps;
        float fade = (float) Math.exp(-dt / (0.4f + 2.5f * (1 - inten)));
        for (int i = 0; i < scroll.length; i++) scroll[i] *= fade;
        if (steps > 0) {
            steps = Math.min(steps, n);
            System.arraycopy(scroll, 0, scroll, 3 * steps, 3 * (n - steps));
            for (int i = 0; i < steps; i++) {
                scroll[3 * i] = tmp[0] * v;
                scroll[3 * i + 1] = tmp[1] * v;
                scroll[3 * i + 2] = tmp[2] * v;
            }
        }
        System.arraycopy(scroll, 0, out, 0, out.length);
    }

    private void energy(Features f, LightSettings s, float inten) {
        float[] vals = {(f.bands[0] + f.bands[1]) / 2, (f.bands[2] + f.bands[3]) / 2, (f.bands[4] + f.bands[5]) / 2};
        float gamma = 0.7f + 1.2f * inten;
        for (int k = 0; k < 3; k++) {
            color(s, k / 3f + shift, f.keyHue, tmp);
            float len = (float) Math.pow(vals[k], gamma) * n * (1f + 0.3f * beatFlash);
            int full = (int) len;
            for (int i = 0; i < Math.min(n, full); i++) add(i, tmp, 0.75f);
            add(full, tmp, 0.75f * (len - full));
        }
    }

    private void spawn(float pos, float vel, float life, float[] c, float tail) {
        if (pCount >= MAXP) return;
        int i = pCount++;
        pPos[i] = pos;
        pVel[i] = vel;
        pLife[i] = life;
        pMax[i] = life;
        pR[i] = c[0];
        pG[i] = c[1];
        pB[i] = c[2];
        pTail[i] = tail;
    }

    private void stepParticles(float dt, float damping) {
        int k = 0;
        for (int i = 0; i < pCount; i++) {
            pLife[i] -= dt;
            if (pLife[i] <= 0) continue;
            pPos[i] += pVel[i] * dt;
            pVel[i] *= (float) Math.exp(-dt * damping);
            if (pPos[i] < -2 || pPos[i] > n + 2) continue;
            pPos[k] = pPos[i]; pVel[k] = pVel[i]; pLife[k] = pLife[i]; pMax[k] = pMax[i];
            pR[k] = pR[i]; pG[k] = pG[i]; pB[k] = pB[i]; pTail[k] = pTail[i];
            k++;
        }
        pCount = k;
    }

    private void drawParticles() {
        for (int i = 0; i < pCount; i++) {
            float life = pLife[i] / pMax[i];
            float v = (float) Math.pow(life, 1.4);
            tmp[0] = pR[i];
            tmp[1] = pG[i];
            tmp[2] = pB[i];
            int p = (int) Math.floor(pPos[i]);
            float fr = pPos[i] - p;
            add(p, tmp, v * (1 - fr));
            add(p + 1, tmp, v * fr);
            // tail behind the movement direction
            int dir = pVel[i] >= 0 ? -1 : 1;
            for (int t = 1; t <= pTail[i]; t++) add(p + dir * t, tmp, v * (1f - t / (pTail[i] + 1f)) * 0.6f);
        }
    }

    private void fireworks(Features f, float dt, LightSettings s, boolean beat, boolean snare, float inten) {
        float scale = Math.max(1f, n / 60f);
        if (beat) {
            float center = n * (0.1f + 0.8f * rnd.nextFloat());
            color(s, rnd.nextFloat(), f.keyHue, tmp);
            int sparks = 8 + Math.round(16 * inten);
            for (int k = 0; k < sparks; k++) {
                float vel = (rnd.nextFloat() * 2 - 1) * (10f + 50f * speed) * scale;
                spawn(center, vel, 0.5f + 0.8f * rnd.nextFloat(), tmp, 0);
            }
            float[] white = {1f, 0.95f, 0.85f};
            spawn(center, 0f, 0.15f, white, 0);
        }
        if (snare && inten > 0.2f) {
            float[] white = {1f, 1f, 1f};
            for (int k = 0; k < 3; k++) spawn(rnd.nextFloat() * n, 0f, 0.12f + 0.1f * rnd.nextFloat(), white, 0);
        }
        stepParticles(dt, 2.2f);
        drawParticles();
    }

    private void rain(Features f, float dt, LightSettings s, boolean beat, boolean snare, float inten) {
        float scale = Math.max(1f, n / 60f);
        float v = (25f + 120f * speed) * scale;
        if (beat) {
            color(s, rnd.nextFloat() * 0.3f + shift, f.keyHue, tmp);
            spawn(0f, v * 1.2f, 3f, tmp, 4 + Math.round(4 * scale));
        }
        if (snare) {
            float[] white = {0.8f, 0.85f, 1f};
            spawn(0f, v * (0.8f + 0.4f * rnd.nextFloat()), 3f, white, 1 + Math.round(scale));
        }
        // a drizzle from the high frequencies
        float high = (f.bands[4] + f.bands[5]) / 2;
        if (rnd.nextFloat() < high * dt * 8f * (0.5f + inten)) {
            color(s, 0.5f + shift, f.keyHue, tmp);
            spawn(0f, v * 0.7f, 3f, new float[]{tmp[0] * 0.5f, tmp[1] * 0.5f, tmp[2] * 0.5f}, Math.round(scale));
        }
        stepParticles(dt, 0f);
        drawParticles();
    }

    private void fire(Features f, float dt, LightSettings s, boolean beat, float inten) {
        float steps = dt * 60f; // tuned for 60 updates per second
        float cooling = (0.035f + 0.08f * (1 - f.level)) * (60f / Math.max(30, n)) * steps;
        for (int i = 0; i < n; i++) heat[i] = Math.max(0f, heat[i] - rnd.nextFloat() * cooling);
        // heat rises toward the end of the strip
        for (int i = n - 1; i >= 2; i--) heat[i] = (heat[i - 1] + heat[i - 2] * 2) / 3f;
        float sparkChance = (0.2f + 0.8f * f.level) * (0.5f + speed) * steps;
        int sparkZone = Math.max(2, n / 8);
        while (sparkChance > 0) {
            if (rnd.nextFloat() < Math.min(1f, sparkChance)) {
                int y = rnd.nextInt(sparkZone);
                heat[y] = Math.min(1f, heat[y] + 0.4f + 0.4f * rnd.nextFloat());
            }
            sparkChance -= 1f;
        }
        if (beat) for (int y = 0; y < sparkZone * 2 && y < n; y++) heat[y] = Math.min(1f, heat[y] + 0.5f + 0.4f * inten);
        boolean classic = s.palette == 0 && !(s.screenColors || s.style == LightSettings.STYLE_SCREEN);
        for (int i = 0; i < n; i++) {
            float h = heat[i];
            if (classic) {
                // black → red → orange/yellow → white
                tmp[0] = Math.min(1f, h * 3f);
                tmp[1] = Math.max(0f, Math.min(1f, h * 3f - 1f));
                tmp[2] = Math.max(0f, Math.min(1f, h * 3f - 2f));
                add(i, tmp, 1f);
            } else {
                color(s, h * 0.5f + shift, f.keyHue, tmp);
                add(i, tmp, (float) Math.pow(h, 1.3));
            }
        }
    }

    // ------------------------------------------------------------------ triggers for LIGHTS

    private int sLastBeat, sLastSnare;
    private boolean sInit;

    /** Strobe for frames the effect didn't render itself (LIGHTS): tracks triggers separately. */
    public void strobeOnly(float[] px, Features f, float dt) {
        boolean beat = false, snare = false;
        if (f != null) {
            if (!sInit) {
                sLastBeat = f.beatCount;
                sLastSnare = f.snareCount;
                sInit = true;
            } else {
                beat = f.beatCount != sLastBeat;
                snare = f.snareCount != sLastSnare;
                sLastBeat = f.beatCount;
                sLastSnare = f.snareCount;
            }
        }
        strobe(px, f, dt, beat, snare);
    }
}
