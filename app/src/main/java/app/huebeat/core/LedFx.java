package app.huebeat.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Ports of the one-dimensional audio-reactive effects of LedFx 2.2.1
 * (https://github.com/LedFx/LedFx, GPL-3.0), translated from Python/NumPy to Java.
 *
 * LedFx feeds effects with its own audio analysis (mel bank, frequency powers, onsets, a BPM
 * oscillator); {@link Ctx} provides the same quantities from this app's {@link Features}.
 * LedFx works with colors 0..255 and renders about 60 times per second; here colors are 0..1
 * and per-frame constants are scaled by the real frame time.
 */
public final class LedFx {
    private LedFx() {
    }

    // ================================================================== effect catalog

    public static final String[] FREQ_RANGES = {"Lows", "Mids", "Highs", "Beat", "Bass"};

    /** One effect: name, LedFx category, optional variant names (LedFx's main option). */
    public static final class Info {
        public final String name, category;
        public final String[] variants;
        final float blur;          // LedFx's default blur for this effect
        final Factory factory;

        Info(String name, String category, String[] variants, float blur, Factory factory) {
            this.name = name;
            this.category = category;
            this.variants = variants;
            this.blur = blur;
            this.factory = factory;
        }
    }

    interface Factory {
        Effect create(int n);
    }

    public static final List<Info> EFFECTS = new ArrayList<>();

    static {
        String[] align = {"Left", "Right", "Center", "Invert"};
        String[] bar = {"Wipe", "Bounce", "In-out"};
        EFFECTS.add(new Info("Bands", "2D-style", align, 0, Bands::new));
        EFFECTS.add(new Info("Bar", "BPM", bar, 0, Bar::new));
        EFFECTS.add(new Info("Blade Power+", "Classic", FREQ_RANGES, 2, BladePower::new));
        EFFECTS.add(new Info("Block Reflections", "Atmospheric", null, 0, BlockReflections::new));
        EFFECTS.add(new Info("Blocks", "2D-style", null, 0, Blocks::new));
        EFFECTS.add(new Info("BPM Strobe", "BPM", new String[]{"1/1", "1/2", "1/4", "1/8", "1/16"}, 0, BpmStrobe::new));
        EFFECTS.add(new Info("Crawler", "Atmospheric", null, 0, Crawler::new));
        EFFECTS.add(new Info("Energy", "Classic", new String[]{"Additive", "Overlap"}, 4, Energy::new));
        EFFECTS.add(new Info("Energy 2", "Atmospheric", null, 0, Energy2::new));
        EFFECTS.add(new Info("Equalizer", "2D-style", align, 0, Equalizer::new));
        EFFECTS.add(new Info("Filter", "Simple", FREQ_RANGES, 0, Filter::new));
        EFFECTS.add(new Info("Fire", "Atmospheric", null, 0, Fire::new));
        EFFECTS.add(new Info("Glitch", "Atmospheric", null, 0, Glitch::new));
        EFFECTS.add(new Info("Hierarchy", "Simple", null, 0, Hierarchy::new));
        EFFECTS.add(new Info("Lava lamp", "Atmospheric", null, 0, LavaLamp::new));
        EFFECTS.add(new Info("Magnitude", "Classic", FREQ_RANGES, 0, Magnitude::new));
        EFFECTS.add(new Info("Marching", "Atmospheric", null, 0, Marching::new));
        EFFECTS.add(new Info("Melt", "Atmospheric", null, 0, Melt::new));
        EFFECTS.add(new Info("Melt and Sparkle", "Atmospheric", null, 0, MeltSparkle::new));
        EFFECTS.add(new Info("Metro", "Diagnostic", null, 0, Metro::new));
        EFFECTS.add(new Info("Multicolor Bar", "BPM", new String[]{"Wipe", "Cascade"}, 0, MultiBar::new));
        EFFECTS.add(new Info("Pitch Spectrum", "Classic", null, 1, PitchSpectrum::new));
        EFFECTS.add(new Info("Power", "Classic", null, 0, Power::new));
        EFFECTS.add(new Info("Rain", "Classic", null, 0, Rain::new));
        EFFECTS.add(new Info("Scan", "Classic", FREQ_RANGES, 3, n -> new Scan(n, false)));
        EFFECTS.add(new Info("Scan and Flare", "Classic", FREQ_RANGES, 3, n -> new Scan(n, true)));
        EFFECTS.add(new Info("Scan Multi", "Classic", null, 3, ScanMulti::new));
        EFFECTS.add(new Info("Scroll", "Classic", null, 3, Scroll::new));
        EFFECTS.add(new Info("Scroll+", "Classic", null, 3, ScrollPlus::new));
        EFFECTS.add(new Info("Spectrum", "Classic", null, 0, Spectrum::new));
        EFFECTS.add(new Info("Spotlight", "Classic", null, 0, Spotlight::new));
        EFFECTS.add(new Info("Strobe", "Classic", null, 0, Strobe::new));
        EFFECTS.add(new Info("VuMeter", "Diagnostic", null, 0, VuMeter::new));
        EFFECTS.add(new Info("Water", "Atmospheric", null, 0, Water::new));
        EFFECTS.add(new Info("Wavelength", "Classic", null, 3, Wavelength::new));
    }

    // ================================================================== context

    /** LedFx's audio and color API on top of this app's analysis. One per strip. */
    public static final class Ctx {
        public final int n;
        Features f = new Features();
        double t;
        float dt = 0.02f;
        /** Frames at 60 fps that passed (LedFx's per-frame constants are tuned for 60 fps). */
        float k60 = 1.2f;
        LightSettings s;
        float speed = 0.5f;
        int variant;
        boolean rainbowAuto;

        boolean beatNow, onsetNow;
        int barCounter;
        private boolean init;
        private int lastBeat, lastSnare;
        private double lastBeatT = -10, periodEst = 0.5;
        final Random rnd = new Random();
        private final float[] mel24 = new float[Features.SPEC];

        Ctx(int n) {
            this.n = n;
        }

        void update(Features feat, double time, float frameDt, LightSettings ls, float spd, int var) {
            if (feat != null) f = feat;
            t = time;
            dt = Math.max(0.001f, Math.min(0.1f, frameDt));
            k60 = dt * 60f;
            s = ls;
            speed = spd;
            variant = var;
            beatNow = false;
            onsetNow = false;
            if (!init) {
                lastBeat = f.beatCount;
                lastSnare = f.snareCount;
                init = true;
            } else {
                beatNow = f.beatCount != lastBeat;
                boolean snare = f.snareCount != lastSnare;
                lastBeat = f.beatCount;
                lastSnare = f.snareCount;
                onsetNow = beatNow || snare;
            }
            if (beatNow) {
                barCounter = (barCounter + 1) % 4;
                double iv = t - lastBeatT;
                if (iv > 0.25 && iv < 2.0) periodEst = iv;
                lastBeatT = t;
            }
            System.arraycopy(f.spec, 0, mel24, 0, Features.SPEC);
        }

        /** Speed factor around LedFx's default (slider 0.5 = default, 0 = ¼, 1 = 4×). */
        float speedFactor() {
            return (float) Math.pow(4.0, (speed - 0.5) * 2.0);
        }

        /** Reactivity factor from the global Intensity (default 0.65 = LedFx default). */
        float react() {
            return (float) Math.pow(3.0, ((s != null ? s.intensity : 0.65f) - 0.65f) * 2.0);
        }

        // ---- audio API (names as in LedFx)

        float lowsPower() {
            return clamp01((f.bands[0] + f.bands[1]) * 0.5f);
        }

        float midsPower() {
            return clamp01((f.bands[2] + f.bands[3]) * 0.5f);
        }

        float highPower() {
            return clamp01((f.bands[4] + f.bands[5]) * 0.5f);
        }

        float power(int range) {
            switch (range) {
                case 1: return midsPower();
                case 2: return highPower();
                case 3: return clamp01(f.bands[0]);
                case 4: return clamp01(f.bands[1]);
                default: return lowsPower();
            }
        }

        float volume() {
            return clamp01(f.level);
        }

        float pitch() {
            return f.pitch;
        }

        /** Mel bank resampled to {@code size} values, 0..1. */
        float[] melbank(int size) {
            float[] o = new float[size];
            for (int i = 0; i < size; i++) {
                float x = size == 1 ? 0 : (float) i / (size - 1) * (Features.SPEC - 1);
                int a = (int) x, b = Math.min(Features.SPEC - 1, a + 1);
                float fr = x - a;
                o[i] = clamp01(mel24[a] * (1 - fr) + mel24[b] * fr);
            }
            return o;
        }

        /** Max of the lows / mids / highs third of the mel bank. */
        float[] thirdsMax() {
            float[] o = new float[3];
            int w = Features.SPEC / 3;
            for (int k = 0; k < 3; k++) for (int i = k * w; i < (k + 1) * w; i++) o[k] = Math.max(o[k], mel24[i]);
            return o;
        }

        float[] thirdsMean() {
            float[] o = new float[3];
            int w = Features.SPEC / 3;
            for (int k = 0; k < 3; k++) {
                for (int i = k * w; i < (k + 1) * w; i++) o[k] += mel24[i];
                o[k] /= w;
            }
            return o;
        }

        double period() {
            return f.bpm > 0 ? 60.0 / f.bpm : periodEst;
        }

        /** 0..1 position within the current beat. */
        float beatOscillator() {
            double p = (t - lastBeatT) / period();
            return (float) Math.max(0, Math.min(0.999, p));
        }

        /** 0..4 position within the current bar of four beats. */
        float barOscillator() {
            return (barCounter + beatOscillator()) % 4f;
        }

        // ---- colors

        /** Gradient color at pos (0..1): the palette, or a rainbow for "Auto" like LedFx's default. */
        void gradient(float pos, float[] out) {
            int pal = s != null ? s.palette : 0;
            if (pal <= 0) {
                Palettes.hsv(rainbowAuto ? pos : pos + f.keyHue, 1f, 1f, out, 0);
            } else {
                Palettes.color(pal, pos, f.keyHue, 1f, out, 0);
            }
        }

        /** LedFx's default lows/mids/highs colors (red/green/blue) with "Auto", else the palette. */
        void third(int which, float[] out) {
            int pal = s != null ? s.palette : 0;
            if (pal <= 0) {
                out[0] = which == 0 ? 1 : 0;
                out[1] = which == 1 ? 1 : 0;
                out[2] = which == 2 ? 1 : 0;
            } else {
                Palettes.color(pal, which / 3f, f.keyHue, 1f, out, 0);
            }
        }

        // ---- HSVEffect time helpers

        /** LedFx HSVEffect.time(): position 0..1 in a cycle of 65.536 s / modifier. */
        float time(double modifier) {
            return time(modifier, t);
        }

        float time(double modifier, double seconds) {
            double period = 65.536 / Math.max(1e-6, modifier);
            return (float) ((seconds % period + period) % period / period);
        }
    }

    /** ExpFilter from LedFx (scalar). */
    static final class Exp {
        final float decay, rise;
        float value = Float.NaN;

        Exp(float decay, float rise) {
            this.decay = decay;
            this.rise = rise;
        }

        float update(float v, float k60) {
            if (Float.isNaN(value)) return value = v;
            float a = v > value ? rise : decay;
            a = 1f - (float) Math.pow(1f - a, k60);
            value = a * v + (1 - a) * value;
            return value;
        }
    }

    // ================================================================== effects

    interface Effect {
        /** Writes rgb (0..1, may exceed 1) into out (3 per pixel). */
        void render(Ctx c, float[] out);
    }

    static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    static float frac(float x) {
        return x - (float) Math.floor(x);
    }

    static float sin01(double x) {
        return (float) (0.5 * Math.sin(x * 2 * Math.PI) + 0.5);
    }

    static float tri01(float x) {
        return 1 - 2 * Math.abs(x - 0.5f);
    }

    /** math.triangle(): periodic triangle wave, 0 at integers, 1 at .5. */
    static float triWave(float x) {
        float f = frac(x);
        return f < 0.5f ? 2 * f : 2 - 2 * f;
    }

    static void set(float[] out, int i, float[] c, float m) {
        out[3 * i] = c[0] * m;
        out[3 * i + 1] = c[1] * m;
        out[3 * i + 2] = c[2] * m;
    }

    static void add(float[] out, int i, float[] c, float m) {
        out[3 * i] += c[0] * m;
        out[3 * i + 1] += c[1] * m;
        out[3 * i + 2] += c[2] * m;
    }

    /** apply_gradient(y): gradient spread over the strip, scaled per pixel. */
    static void applyGradient(Ctx c, float[] y, float[] out) {
        float[] col = new float[3];
        for (int i = 0; i < c.n; i++) {
            c.gradient(c.n == 1 ? 0 : (float) i / (c.n - 1), col);
            set(out, i, col, y[i]);
        }
    }

    /** Gaussian smoothing with mirrored edges, like LedFx's smooth(). Works on one channel. */
    static float[] smooth(float[] x, float sigma) {
        if (sigma <= 0.01f || x.length < 2) return x;
        int r = Math.max(1, Math.round(4f * sigma));
        float[] k = new float[2 * r + 1];
        float sum = 0;
        for (int i = -r; i <= r; i++) {
            k[i + r] = (float) Math.exp(-0.5 * i * i / (sigma * sigma));
            sum += k[i + r];
        }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        int n = x.length;
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            float acc = 0;
            for (int j = -r; j <= r; j++) {
                int p = i + j;
                while (p < 0 || p >= n) p = p < 0 ? -p - 1 : 2 * n - p - 1;
                acc += x[p] * k[j + r];
            }
            y[i] = acc;
        }
        return y;
    }

    /** Blurs an interleaved rgb frame in place. */
    public static void blurRgb(float[] rgb, int n, float sigma) {
        if (sigma <= 0.01f || n < 2) return;
        float[] ch = new float[n];
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < n; i++) ch[i] = rgb[3 * i + k];
            float[] sm = smooth(ch, sigma);
            for (int i = 0; i < n; i++) rgb[3 * i + k] = sm[i];
        }
    }

    // ------------------------------------------------------------------ HSV base

    /** HSVEffect: hue indexes the gradient (with LedFx's "fix hues" remap), s and v shape it. */
    abstract static class Hsv implements Effect {
        final float[] h, s, v;
        private final float[] col = new float[3];

        Hsv(int n) {
            h = new float[n];
            s = new float[n];
            v = new float[n];
        }

        abstract void hsv(Ctx c);

        @Override
        public void render(Ctx c, float[] out) {
            hsv(c);
            for (int i = 0; i < c.n; i++) {
                float hh = frac(h[i]);
                hh = sin01((hh - 0.5f) / 2f); // fix_hue_fast
                c.gradient(frac(hh), col);
                float mx = Math.max(col[0], Math.max(col[1], col[2]));
                float sat = clamp01(s[i]);
                for (int k = 0; k < 3; k++) {
                    float p = col[k] + (mx - col[k]) * (1 - sat);
                    out[3 * i + k] = p * Math.max(0f, v[i]);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Bands / Blocks / Equalizer

    static void alignBand(float[] band, int vol, int align) {
        int w = band.length;
        float[] o = new float[w];
        for (int i = 0; i < vol && i < w; i++) o[i] = 1;
        switch (align) {
            case 1: // right
                for (int i = 0; i < w; i++) band[i] = o[w - 1 - i];
                return;
            case 2: { // center
                int sh = (w - vol) / 2;
                for (int i = 0; i < w; i++) band[(i + sh) % w] = o[i];
                return;
            }
            case 3: { // invert
                int sh = Math.floorMod(-vol / 2, Math.max(1, w));
                for (int i = 0; i < w; i++) band[(i + sh) % w] = o[i];
                return;
            }
            default:
                System.arraycopy(o, 0, band, 0, w);
        }
    }

    /** np.array_split boundaries. */
    static int[] splits(int n, int parts) {
        int[] b = new int[parts + 1];
        int base = n / parts, extra = n % parts, p = 0;
        for (int i = 0; i < parts; i++) {
            b[i] = p;
            p += base + (i < extra ? 1 : 0);
        }
        b[parts] = n;
        return b;
    }

    static final class Bands implements Effect {
        Bands(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] r = c.melbank(c.n);
            int bands = Math.min(6, c.n);
            int[] sp = splits(c.n, bands);
            float[] col = new float[3];
            Arrays.fill(out, 0);
            for (int b = 0; b < bands; b++) {
                int w = sp[b + 1] - sp[b];
                float mx = 0;
                for (int i = sp[b]; i < sp[b + 1]; i++) mx = Math.max(mx, r[i]);
                int vol = (int) (mx * w);
                float[] band = new float[w];
                alignBand(band, vol, c.variant);
                c.gradient((float) b / bands, col);
                for (int i = 0; i < w; i++) if (band[i] > 0) set(out, sp[b] + i, col, 1);
            }
        }
    }

    static final class Blocks implements Effect {
        Blocks(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] r = c.melbank(c.n);
            int blocks = Math.min(4, c.n);
            int[] sp = splits(c.n, blocks);
            float[] col = new float[3];
            for (int b = 0; b < blocks; b++) {
                float mx = 0;
                for (int i = sp[b]; i < sp[b + 1]; i++) mx = Math.max(mx, r[i]);
                c.gradient((float) b / blocks, col);
                for (int i = sp[b]; i < sp[b + 1]; i++) set(out, i, col, r[i] * mx);
            }
        }
    }

    static final class Equalizer implements Effect {
        Equalizer(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] r = c.melbank(c.n);
            int rep = Math.min(6, c.n);
            int[] sp = splits(c.n, rep);
            float[] y = new float[c.n];
            for (int b = 0; b < rep; b++) {
                int w = sp[b + 1] - sp[b];
                float mean = 0;
                for (int i = sp[b]; i < sp[b + 1]; i++) mean += r[i];
                mean /= Math.max(1, w);
                float[] band = new float[w];
                alignBand(band, (int) (mean * w), c.variant);
                System.arraycopy(band, 0, y, sp[b], w);
            }
            applyGradient(c, y, out);
        }
    }

    // ------------------------------------------------------------------ BPM effects

    static float ease(float x, int method) {
        switch (method) {
            case 0: return (float) (0.5 * Math.sin(Math.PI * (x - 0.5)) + 0.5); // ease in-out
            case 1: return x * x;                                              // ease in
            case 2: return -((x - 1) * (x - 1)) + 1;                           // ease out
            default: return x;                                                 // linear
        }
    }

    static final class Bar implements Effect {
        int phase;
        float colorIdx, lastBeat;

        Bar(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float osc = c.beatOscillator();
            if (lastBeat > 0.2f && osc < 0.1f) {
                phase = 1 - phase;
                if (phase == 0) colorIdx = frac(colorIdx + 0.125f);
            }
            lastBeat = osc;
            float x = ease(osc, 2);
            float start, end;
            float barLen = 0.3f;
            switch (c.variant) {
                case 1: // bounce
                    x = x * (1 - barLen);
                    if (phase == 0) {
                        start = x;
                        end = x + barLen;
                    } else {
                        end = 1 - x;
                        start = 1 - (x + barLen);
                    }
                    break;
                case 2: // in-out
                    start = 0;
                    end = phase == 0 ? x : 1 - x;
                    break;
                default: // wipe
                    if (phase == 0) {
                        start = 0;
                        end = x;
                    } else {
                        start = x;
                        end = 1;
                    }
            }
            float[] col = new float[3];
            c.gradient(colorIdx, col);
            Arrays.fill(out, 0);
            for (int i = (int) (c.n * start); i < (int) (c.n * end); i++) if (i >= 0 && i < c.n) set(out, i, col, 1);
        }
    }

    static final class MultiBar implements Effect {
        int phase;
        float colorIdx;

        MultiBar(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            if (c.beatNow) {
                phase = 1 - phase;
                colorIdx = frac(colorIdx + 0.125f);
            }
            float x = c.beatOscillator();
            float[] fg = new float[3], bg = new float[3];
            c.gradient(colorIdx, fg);
            c.gradient(frac(colorIdx + 0.125f), bg);
            float idx;
            if (c.variant == 1) {
                idx = x;
            } else if (phase == 0) {
                idx = x;
            } else {
                idx = 1 - x;
                float[] t = fg;
                fg = bg;
                bg = t;
            }
            int cut = (int) (c.n * idx);
            for (int i = 0; i < c.n; i++) set(out, i, i < cut ? bg : fg, 1);
        }
    }

    static final class BpmStrobe implements Effect {
        BpmStrobe(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            int freq = new int[]{1, 2, 4, 8, 16}[Math.max(0, Math.min(4, c.variant))];
            float o = c.beatOscillator();
            float bar = c.barOscillator();
            float[] col = new float[3];
            c.gradient(bar / 4f, col);
            double inBeat = ((-o % (1.0 / freq)) + (1.0 / freq)) % (1.0 / freq) * freq;
            float b = (float) (Math.pow(inBeat, 1.5) * Math.pow(1 - o, 2));
            for (int i = 0; i < c.n; i++) set(out, i, col, b * b);
        }
    }

    // ------------------------------------------------------------------ Classic

    static final class BladePower extends Hsv {
        BladePower(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float bar = c.power(c.variant) * 0.5f * 2f;
            int barIdx = (int) (bar * c.n);
            float decay = (float) Math.pow(0.7f / 2 + 0.45f, c.k60);
            for (int i = 0; i < c.n; i++) {
                h[i] = c.n == 1 ? 0 : (float) i / (c.n - 1);
                s[i] = 1;
                v[i] *= decay;
                if (i < barIdx) v[i] = 1;
            }
        }
    }

    static final class Energy implements Effect {
        final Exp[] filter;

        Energy(int n) {
            filter = new Exp[3 * n];
            float sens = 0.6f;
            for (int i = 0; i < filter.length; i++) filter[i] = new Exp((sens - 0.1f) * 0.7f, sens);
        }

        @Override
        public void render(Ctx c, float[] out) {
            float mult = 1.6f - 4f / 17f;
            float[] m = c.thirdsMean();
            float[] col = new float[3];
            float[] p = new float[3 * c.n];
            int[] idx = new int[3];
            for (int k = 0; k < 3; k++) idx[k] = Math.min(c.n, (int) (mult * c.n * m[k] * c.react()));
            for (int k = 0; k < 3; k++) {
                c.third(k, col);
                for (int i = 0; i < idx[k]; i++) {
                    if (c.variant == 1) set(p, i, col, 1);
                    else add(p, i, col, 1);
                }
            }
            for (int i = 0; i < p.length; i++) out[i] = filter[i].update(p[i], c.k60);
        }
    }

    static final class Filter implements Effect {
        Filter(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] col = new float[3];
            c.gradient(0, col);
            float x = c.power(c.variant);
            for (int i = 0; i < c.n; i++) set(out, i, col, x);
        }
    }

    static final class Hierarchy implements Effect {
        double lastLow = -10, lastMid = -10;
        int which = -1;
        float power;

        Hierarchy(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            power = c.lowsPower();
            if (power > 0.05f) {
                which = 0;
                lastLow = c.t;
            } else if (c.t - lastLow > 0.1) {
                power = c.midsPower();
                if (power > 0.05f) {
                    which = 1;
                    lastMid = c.t;
                } else if (c.t - lastMid > 0.1) {
                    power = c.highPower();
                    which = 2;
                }
            }
            float[] col = new float[3];
            if (which >= 0) c.third(which, col);
            for (int i = 0; i < c.n; i++) set(out, i, col, power);
        }
    }

    static final class Magnitude implements Effect {
        Magnitude(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float m = c.power(c.variant);
            float[] y = new float[c.n];
            Arrays.fill(y, m);
            applyGradient(c, y, out);
        }
    }

    static final class PitchSpectrum implements Effect {
        float avgMidi = -1;
        final float[] px;

        PitchSpectrum(int n) {
            px = new float[3 * n];
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] mel = c.melbank(c.n);
            float midi = c.pitch();
            float resp = 1f - (float) Math.pow(1 - 0.15f, c.k60);
            if (avgMidi < 0) avgMidi = midi;
            if (midi >= 21) avgMidi = avgMidi * (1 - resp) + midi * resp;
            float[] note = new float[3];
            if (avgMidi >= 21) c.gradient(clamp01((avgMidi - 21) / (108 - 21)), note);
            float fade = (float) Math.pow(1 - 0.15f, c.k60);
            for (int i = 0; i < c.n; i++) {
                for (int k = 0; k < 3; k++) {
                    float v = px[3 * i + k] * (1 - mel[i]) + note[k] * mel[i];
                    px[3 * i + k] = v * fade;
                }
            }
            System.arraycopy(px, 0, out, 0, px.length);
        }
    }

    static final class Power implements Effect {
        final float[] sparks, bass;
        final Exp bassFilter = new Exp(0.1f, 0.8f);

        Power(int n) {
            sparks = new float[3 * n];
            bass = new float[3 * n];
        }

        @Override
        public void render(Ctx c, float[] out) {
            float sd = (float) Math.pow(1 - 0.15f, c.k60), bd = (float) Math.pow(1 - 0.05f, c.k60);
            for (int i = 0; i < sparks.length; i++) {
                sparks[i] *= sd;
                bass[i] *= bd;
            }
            if (c.onsetNow) {
                int count = Math.max(1, c.n / 20);
                for (int j = 0; j < count; j++) {
                    int p = c.rnd.nextInt(c.n);
                    sparks[3 * p] = sparks[3 * p + 1] = sparks[3 * p + 2] = 1f;
                }
            }
            float b = bassFilter.update(c.lowsPower(), c.k60);
            int bi = Math.min(c.n, (int) (b * c.n));
            float[] col = new float[3];
            c.gradient(b, col);
            for (int i = 0; i < bi; i++) set(bass, i, col, 1);
            float[] r = c.melbank(c.n);
            applyGradient(c, r, out);
            for (int i = 0; i < out.length; i++) out[i] += bass[i] + sparks[i];
        }
    }

    /** The "blob" droplet animation from LedFx (30 frames × 29 pixels). */
    static final float[] DROPLET = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0.967f, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.032f, 1, 1, 0.933f, 1, 1, 0.032f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0.003f, 0.09f, 1, 1, 1, 0.9f, 1, 1, 1, 0.09f, 0.003f, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0.001f, 0.011f, 0.119f, 0.725f, 1, 1, 1, 0.867f, 1, 1, 1, 0.725f, 0.119f, 0.011f, 0.001f, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0.003f, 0.021f, 0.126f, 0.51f, 1, 1, 1, 1, 0.833f, 1, 1, 1, 1, 0.51f, 0.126f, 0.021f, 0.003f, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0.001f, 0.006f, 0.03f, 0.122f, 0.381f, 0.897f, 1, 1, 1, 1, 0.8f, 1, 1, 1, 1, 0.897f, 0.381f, 0.122f, 0.03f, 0.006f, 0.001f, 0, 0, 0, 0,
            0, 0, 0, 0.002f, 0.009f, 0.036f, 0.114f, 0.297f, 0.63f, 1, 1, 1, 0.964f, 0.882f, 0.767f, 0.882f, 0.964f, 1, 1, 1, 0.63f, 0.297f, 0.114f, 0.036f, 0.009f, 0.002f, 0, 0, 0,
            0, 0.001f, 0.004f, 0.013f, 0.039f, 0.104f, 0.238f, 0.466f, 0.771f, 1, 1, 1, 0.659f, 0.747f, 0.733f, 0.747f, 0.659f, 1, 1, 1, 0.771f, 0.466f, 0.238f, 0.104f, 0.039f, 0.013f, 0.004f, 0.001f, 0,
            0.002f, 0.005f, 0.016f, 0.041f, 0.095f, 0.196f, 0.358f, 0.575f, 0.803f, 0.96f, 0.958f, 0.761f, 0.466f, 0.659f, 0.7f, 0.659f, 0.466f, 0.761f, 0.958f, 0.96f, 0.803f, 0.575f, 0.358f, 0.196f, 0.095f, 0.041f, 0.016f, 0.005f, 0.002f,
            0.007f, 0.018f, 0.041f, 0.086f, 0.164f, 0.284f, 0.443f, 0.617f, 0.763f, 0.823f, 0.755f, 0.562f, 0.339f, 0.594f, 0.667f, 0.594f, 0.339f, 0.562f, 0.755f, 0.823f, 0.763f, 0.617f, 0.443f, 0.284f, 0.164f, 0.086f, 0.041f, 0.018f, 0.007f,
            0.019f, 0.04f, 0.077f, 0.139f, 0.23f, 0.35f, 0.484f, 0.609f, 0.688f, 0.687f, 0.592f, 0.42f, 0.25f, 0.543f, 0.633f, 0.543f, 0.25f, 0.42f, 0.592f, 0.687f, 0.688f, 0.609f, 0.484f, 0.35f, 0.23f, 0.139f, 0.077f, 0.04f, 0.019f,
            0.038f, 0.07f, 0.12f, 0.19f, 0.282f, 0.387f, 0.491f, 0.57f, 0.601f, 0.566f, 0.465f, 0.318f, 0.185f, 0.5f, 0.6f, 0.5f, 0.185f, 0.318f, 0.465f, 0.566f, 0.601f, 0.57f, 0.491f, 0.387f, 0.282f, 0.19f, 0.12f, 0.07f, 0.038f,
            0.064f, 0.104f, 0.16f, 0.232f, 0.315f, 0.4f, 0.472f, 0.516f, 0.515f, 0.464f, 0.368f, 0.244f, 0.134f, 0.461f, 0.567f, 0.461f, 0.134f, 0.244f, 0.368f, 0.464f, 0.515f, 0.516f, 0.472f, 0.4f, 0.315f, 0.232f, 0.16f, 0.104f, 0.064f,
            0.091f, 0.136f, 0.193f, 0.26f, 0.329f, 0.393f, 0.439f, 0.457f, 0.438f, 0.38f, 0.293f, 0.19f, 0.093f, 0.425f, 0.533f, 0.425f, 0.093f, 0.19f, 0.293f, 0.38f, 0.438f, 0.457f, 0.439f, 0.393f, 0.329f, 0.26f, 0.193f, 0.136f, 0.091f,
            0.117f, 0.163f, 0.217f, 0.274f, 0.329f, 0.374f, 0.4f, 0.399f, 0.37f, 0.312f, 0.234f, 0.149f, 0.073f, 0.39f, 0.5f, 0.39f, 0.073f, 0.149f, 0.234f, 0.312f, 0.37f, 0.399f, 0.4f, 0.374f, 0.329f, 0.274f, 0.217f, 0.163f, 0.117f,
            0.139f, 0.183f, 0.231f, 0.278f, 0.318f, 0.347f, 0.358f, 0.346f, 0.312f, 0.257f, 0.189f, 0.119f, 0.057f, 0.357f, 0.467f, 0.357f, 0.057f, 0.119f, 0.189f, 0.257f, 0.312f, 0.346f, 0.358f, 0.347f, 0.318f, 0.278f, 0.231f, 0.183f, 0.139f,
            0.156f, 0.196f, 0.236f, 0.272f, 0.301f, 0.317f, 0.317f, 0.299f, 0.263f, 0.212f, 0.154f, 0.095f, 0.045f, 0.324f, 0.433f, 0.324f, 0.045f, 0.095f, 0.154f, 0.212f, 0.263f, 0.299f, 0.317f, 0.317f, 0.301f, 0.272f, 0.236f, 0.196f, 0.156f,
            0.167f, 0.201f, 0.234f, 0.261f, 0.279f, 0.286f, 0.279f, 0.257f, 0.222f, 0.176f, 0.126f, 0.077f, 0.037f, 0.292f, 0.4f, 0.292f, 0.037f, 0.077f, 0.126f, 0.176f, 0.222f, 0.257f, 0.279f, 0.286f, 0.279f, 0.261f, 0.234f, 0.201f, 0.167f,
            0.173f, 0.202f, 0.227f, 0.246f, 0.256f, 0.256f, 0.244f, 0.221f, 0.187f, 0.147f, 0.104f, 0.063f, 0.03f, 0.26f, 0.367f, 0.26f, 0.03f, 0.063f, 0.104f, 0.147f, 0.187f, 0.221f, 0.244f, 0.256f, 0.256f, 0.246f, 0.227f, 0.202f, 0.173f,
            0.175f, 0.197f, 0.216f, 0.228f, 0.233f, 0.228f, 0.213f, 0.19f, 0.159f, 0.123f, 0.086f, 0.052f, 0.024f, 0.228f, 0.333f, 0.228f, 0.024f, 0.052f, 0.086f, 0.123f, 0.159f, 0.19f, 0.213f, 0.228f, 0.233f, 0.228f, 0.216f, 0.197f, 0.175f,
            0.172f, 0.19f, 0.203f, 0.21f, 0.21f, 0.202f, 0.186f, 0.163f, 0.135f, 0.104f, 0.072f, 0.043f, 0.02f, 0.196f, 0.3f, 0.196f, 0.02f, 0.043f, 0.072f, 0.104f, 0.135f, 0.163f, 0.186f, 0.202f, 0.21f, 0.21f, 0.203f, 0.19f, 0.172f,
            0.167f, 0.18f, 0.189f, 0.192f, 0.188f, 0.178f, 0.162f, 0.141f, 0.115f, 0.088f, 0.06f, 0.036f, 0.017f, 0.164f, 0.267f, 0.164f, 0.017f, 0.036f, 0.06f, 0.088f, 0.115f, 0.141f, 0.162f, 0.178f, 0.188f, 0.192f, 0.189f, 0.18f, 0.167f,
            0.16f, 0.169f, 0.174f, 0.174f, 0.168f, 0.157f, 0.141f, 0.121f, 0.098f, 0.074f, 0.051f, 0.03f, 0.014f, 0.133f, 0.233f, 0.133f, 0.014f, 0.03f, 0.051f, 0.074f, 0.098f, 0.121f, 0.141f, 0.157f, 0.168f, 0.174f, 0.174f, 0.169f, 0.16f,
            0.151f, 0.157f, 0.159f, 0.157f, 0.15f, 0.139f, 0.123f, 0.105f, 0.084f, 0.063f, 0.043f, 0.025f, 0.012f, 0.101f, 0.2f, 0.101f, 0.012f, 0.025f, 0.043f, 0.063f, 0.084f, 0.105f, 0.123f, 0.139f, 0.15f, 0.157f, 0.159f, 0.157f, 0.151f,
            0.142f, 0.145f, 0.145f, 0.141f, 0.134f, 0.122f, 0.108f, 0.091f, 0.072f, 0.054f, 0.037f, 0.022f, 0.01f, 0.069f, 0.167f, 0.069f, 0.01f, 0.022f, 0.037f, 0.054f, 0.072f, 0.091f, 0.108f, 0.122f, 0.134f, 0.141f, 0.145f, 0.145f, 0.142f,
            0.132f, 0.133f, 0.132f, 0.127f, 0.119f, 0.108f, 0.094f, 0.079f, 0.062f, 0.046f, 0.031f, 0.018f, 0.008f, 0.037f, 0.133f, 0.037f, 0.008f, 0.018f, 0.031f, 0.046f, 0.062f, 0.079f, 0.094f, 0.108f, 0.119f, 0.127f, 0.132f, 0.133f, 0.132f,
            0.122f, 0.122f, 0.119f, 0.114f, 0.105f, 0.095f, 0.082f, 0.068f, 0.054f, 0.04f, 0.027f, 0.016f, 0.007f, 0.006f, 0.1f, 0.006f, 0.007f, 0.016f, 0.027f, 0.04f, 0.054f, 0.068f, 0.082f, 0.095f, 0.105f, 0.114f, 0.119f, 0.122f, 0.122f,
            0.112f, 0.111f, 0.108f, 0.102f, 0.094f, 0.083f, 0.072f, 0.059f, 0.047f, 0.034f, 0.023f, 0.013f, 0.006f, 0.002f, 0.067f, 0.002f, 0.006f, 0.013f, 0.023f, 0.034f, 0.047f, 0.059f, 0.072f, 0.083f, 0.094f, 0.102f, 0.108f, 0.111f, 0.112f,
            0.103f, 0.101f, 0.097f, 0.091f, 0.083f, 0.074f, 0.063f, 0.052f, 0.041f, 0.03f, 0.02f, 0.012f, 0.005f, 0.001f, 0.033f, 0.001f, 0.005f, 0.012f, 0.02f, 0.03f, 0.041f, 0.052f, 0.063f, 0.074f, 0.083f, 0.091f, 0.097f, 0.101f, 0.103f,
            0.094f, 0.092f, 0.087f, 0.081f, 0.074f, 0.065f, 0.055f, 0.045f, 0.035f, 0.026f, 0.017f, 0.01f, 0.005f, 0.001f, 0, 0.001f, 0.005f, 0.01f, 0.017f, 0.026f, 0.035f, 0.045f, 0.055f, 0.065f, 0.074f, 0.081f, 0.087f, 0.092f, 0.094f
    };

    static final class Rain implements Effect {
        final int[] frame;
        final float[][] colors;
        final float[] pulse;
        final Exp[] intensity = {new Exp(0.5f, 0.99f), new Exp(0.5f, 0.99f), new Exp(0.5f, 0.99f)};
        float acc;

        Rain(int n) {
            frame = new int[n];
            colors = new float[n][3];
            pulse = new float[3];
        }

        @Override
        public void render(Ctx c, float[] out) {
            int frames = DROPLET.length / 29;
            acc += c.k60;
            while (acc >= 1f) { // LedFx advances drops once per audio frame
                acc -= 1f;
                for (int i = 0; i < c.n; i++) {
                    if (frame[i] >= frames - 1) {
                        frame[i] = 0;
                        colors[i][0] = colors[i][1] = colors[i][2] = 0;
                    }
                    if (frame[i] > 0) frame[i]++;
                }
            }
            float[] in = c.thirdsMax();
            float[] sens = {0.1f, 0.05f, 0.1f};
            for (int k = 0; k < 3; k++) {
                float filtered = Float.isNaN(intensity[k].value) ? 0 : intensity[k].value;
                if (in[k] - filtered > sens[k] / c.react()) {
                    int p = c.rnd.nextInt(c.n);
                    frame[p] = 1;
                    rainColor(c, k, colors[p]);
                }
                intensity[k].update(in[k], c.k60);
            }
            Arrays.fill(out, 0);
            int half = 29 / 2 - 1; // frame_side_lengths
            for (int i = 0; i < c.n; i++) {
                if (frame[i] <= 0 || frame[i] >= frames) continue;
                int base = frame[i] * 29;
                for (int j = 0; j < 29; j++) {
                    int p = i + j - half;
                    if (p < 0 || p >= c.n) continue;
                    add(out, p, colors[i], DROPLET[base + j]);
                }
            }
        }

        static void rainColor(Ctx c, int k, float[] out) {
            int pal = c.s != null ? c.s.palette : 0;
            if (pal <= 0) {
                // LedFx defaults: lows white, mids red, highs blue
                float[][] d = {{1, 1, 1}, {1, 0, 0}, {0, 0, 1}};
                System.arraycopy(d[k], 0, out, 0, 3);
            } else {
                Palettes.color(pal, k / 3f, 0, 1f, out, 0);
            }
        }
    }

    static final class Scan implements Effect {
        final boolean flare;
        float pos;
        boolean returning;
        final List<float[]> sparkles = new ArrayList<>(); // pos, width, speed, born, dieOff

        Scan(int n, boolean flare) {
            this.flare = flare;
        }

        @Override
        public void render(Ctx c, float[] out) {
            float power = c.power(c.variant) * 2;
            float bar = power * 3f * c.react();
            float[] col = new float[3];
            if (c.s != null && c.s.palette > 0) c.gradient(frac(pos / c.n), col);
            else col[0] = 1; // LedFx default: red
            for (int k = 0; k < 3; k++) col[k] *= Math.min(1f, power);
            float stepPerSec = c.n / 100f * 50f * c.speedFactor();
            int width = Math.max(1, (int) (c.n / 100f * 30));
            pos += (returning ? -1 : 1) * c.dt * stepPerSec * bar;
            if (pos > c.n - width) {
                returning = true;
                pos = c.n - width;
            }
            if (pos < 0) {
                returning = false;
                pos = 0;
            }
            Arrays.fill(out, 0);
            int pp = Math.max(0, Math.min((int) pos, c.n));
            for (int i = pp; i < Math.min(pp + width, c.n); i++) set(out, i, col, 1);
            if (flare) {
                for (int i = sparkles.size() - 1; i >= 0; i--) {
                    float[] sp = sparkles.get(i);
                    float age = (float) (c.t - sp[3]);
                    if (age > sp[4]) {
                        sparkles.remove(i);
                        continue;
                    }
                    float health = 1 - age / sp[4];
                    sp[0] = ((sp[0] + sp[2] * c.dt * health) % c.n + c.n) % c.n;
                    for (int j = 0; j < (int) sp[1]; j++) {
                        int p = ((int) sp[0] + j) % c.n;
                        out[3 * p] += health;
                        out[3 * p + 1] += health;
                        out[3 * p + 2] += health;
                    }
                }
                if (sparkles.size() < 10 && power > 0.6f * 2) {
                    float w = Math.max(1, width * 0.1f);
                    float sPos = returning ? (pp + width) % c.n : ((pp - w) % c.n + c.n) % c.n;
                    sparkles.add(new float[]{sPos, w, returning ? -stepPerSec : stepPerSec, (float) c.t, 1f});
                }
            }
        }
    }

    static final class ScanMulti implements Effect {
        final float[] pos = new float[3];
        final boolean[] returning = new boolean[3];

        ScanMulti(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float stepPerSec = c.n / 100f * 50f * c.speedFactor();
            float step = c.dt * stepPerSec;
            int width = Math.max(1, (int) (c.n / 100f * 30));
            Arrays.fill(out, 0);
            float[] col = new float[3];
            for (int k = 0; k < 3; k++) {
                float power = (k == 0 ? c.lowsPower() : k == 1 ? c.midsPower() : c.highPower()) * 2;
                step = step * power * 3f; // compounds across the three scans, as in LedFx
                pos[k] += returning[k] ? -step : step;
                if (pos[k] > c.n - width) returning[k] = true;
                if (pos[k] < 0) returning[k] = false;
                c.third(k, col);
                int pp = Math.max(0, Math.min((int) pos[k], c.n));
                for (int i = pp; i < Math.min(pp + width, c.n); i++) add(out, i, col, Math.min(1f, power));
            }
        }
    }

    static final class Scroll implements Effect {
        final float[] px;
        float acc;

        Scroll(int n) {
            px = new float[3 * n];
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] in = c.thirdsMax();
            for (int k = 0; k < 3; k++) in[k] = clamp01(in[k] * in[k]);
            acc += 3f * c.k60 * c.speedFactor();
            int speed = Math.min(c.n, (int) acc);
            acc -= speed;
            float decay = (float) Math.pow(0.97f, c.k60);
            if (speed > 0) System.arraycopy(px, 0, px, 3 * speed, 3 * (c.n - speed));
            for (int i = 0; i < px.length; i++) px[i] *= decay;
            float[] col = new float[3];
            for (int i = 0; i < speed; i++) {
                px[3 * i] = px[3 * i + 1] = px[3 * i + 2] = 0;
                for (int k = 0; k < 3; k++) {
                    c.third(k, col);
                    add(px, i, col, in[k]);
                }
            }
            System.arraycopy(px, 0, out, 0, px.length);
        }
    }

    static final class ScrollPlus implements Effect {
        final float[] px;
        float inc;

        ScrollPlus(int n) {
            px = new float[3 * n];
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] in = c.thirdsMax();
            for (int k = 0; k < 3; k++) in[k] = clamp01(in[k] * in[k]);
            inc += c.dt * 0.7f * c.speedFactor() * c.n;
            int shift = Math.min((int) inc, c.n);
            inc -= shift;
            if (shift > 0) System.arraycopy(px, 0, px, 3 * shift, 3 * (c.n - shift));
            float decay = Math.min(1, Math.max(0, 1 - c.dt * 0.5f * 3));
            for (int i = 0; i < px.length; i++) px[i] *= decay;
            float[] col = new float[3];
            for (int i = 0; i < shift; i++) {
                px[3 * i] = px[3 * i + 1] = px[3 * i + 2] = 0;
                for (int k = 0; k < 3; k++) {
                    c.third(k, col);
                    add(px, i, col, in[k]);
                }
            }
            System.arraycopy(px, 0, out, 0, px.length);
        }
    }

    static final class Spectrum implements Effect {
        float[] prev;
        final Exp[] b;

        Spectrum(int n) {
            b = new Exp[n];
            for (int i = 0; i < n; i++) b[i] = new Exp(0.1f, 0.5f);
        }

        @Override
        public void render(Ctx c, float[] out) {
            float[] y = c.melbank(c.n);
            if (prev == null) prev = y.clone();
            float gain = 1000f / 255f; // LedFx: × 1000 in 0..255 color space
            for (int i = 0; i < c.n; i++) {
                out[3 * i] = y[i] * gain;
                out[3 * i + 1] = Math.abs(y[i] - prev[i]) * gain;
                out[3 * i + 2] = b[i].update(y[i], c.k60) * gain;
            }
            prev = y;
        }
    }

    static final class Spotlight implements Effect {
        final List<float[]> spots = new ArrayList<>(); // center, born, anchor
        final Exp activity = new Exp(0.25f, 0.6f);
        float spawnAcc, gradPhase;
        double lastSpawn;

        Spotlight(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float weighted = 0.5f * c.lowsPower() + 0.3f * c.midsPower() + 0.2f * c.highPower();
            float act = activity.update(weighted, c.k60);
            gradPhase = frac(gradPhase + 0.12f * c.dt * c.speedFactor());
            float fade = 0.8f;
            int cap = Math.max(3, Math.min(28, (int) (3 + act * 25)));
            float rate = 2.5f + 14f * act * c.react();
            if (c.onsetNow) rate *= 1.8f;
            spawnAcc += rate * c.dt;
            int spawns = 0;
            while (spawnAcc >= 1f && spawns < 6) {
                spawnAcc -= 1f;
                if (c.t - lastSpawn < 0.02) break;
                if (spots.size() >= cap) spots.remove(0);
                float center = c.rnd.nextInt(Math.max(1, c.n));
                spots.add(new float[]{center, (float) c.t, frac(gradPhase + c.rnd.nextFloat() * 0.3f)});
                lastSpawn = c.t;
                spawns++;
            }
            spawnAcc = Math.min(spawnAcc, 3f);
            Arrays.fill(out, 0);
            int half = Math.max(0, Math.round(c.n * 8f / 100f) / 2);
            float[] ce = new float[3], ed = new float[3];
            for (int k = spots.size() - 1; k >= 0; k--) {
                float[] sp = spots.get(k);
                float age = (float) (c.t - sp[1]);
                if (age > fade) {
                    spots.remove(k);
                    continue;
                }
                float life = (float) Math.pow(1 - age / fade, 1.4);
                c.gradient(sp[2], ce);
                c.gradient(frac(sp[2] + 0.08f), ed);
                for (int o = -half; o <= half; o++) {
                    int p = (int) sp[0] + o;
                    if (p < 0 || p >= c.n) continue;
                    float mix = half == 0 ? 1 : 1 - Math.abs(o) / (float) half;
                    float inten = (float) Math.pow(mix, 1.5) * life;
                    for (int j = 0; j < 3; j++) out[3 * p + j] += (ed[j] * (1 - mix) + ce[j] * mix) * inten;
                }
            }
        }
    }

    static final class Strobe implements Effect {
        final float[] strobe, bass;
        final float[] bassColor = new float[3];
        float colorIdx;
        double lastShift = -10, lastBass = -10;

        Strobe(int n) {
            strobe = new float[3 * n];
            bass = new float[3 * n];
        }

        @Override
        public void render(Ctx c, float[] out) {
            if (c.t - lastShift > 1.0) {
                colorIdx = frac(colorIdx + 0.0625f);
                c.gradient(colorIdx, bassColor);
                lastShift = c.t;
            }
            if (c.beatNow && c.t - lastBass > 0.2) {
                for (int i = 0; i < c.n; i++) set(bass, i, bassColor, 1);
                lastBass = c.t;
            }
            if (c.onsetNow) {
                int w = Math.min(10, c.n);
                int p = c.n - w == 0 ? 0 : c.rnd.nextInt(c.n - w);
                for (int i = p; i < p + w; i++) strobe[3 * i] = strobe[3 * i + 1] = strobe[3 * i + 2] = 1f;
            }
            float d = (float) Math.pow(0.5f, c.k60);
            for (int i = 0; i < out.length; i++) {
                out[i] = bass[i] + strobe[i];
                strobe[i] *= d;
                bass[i] *= d;
            }
        }
    }

    static final class VuMeter implements Effect {
        final Exp peak = new Exp(0.1f, 0.99f), minPeak = new Exp(0.1f, 0.99f);

        VuMeter(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            float vol = c.volume();
            float vp = peak.update(vol, c.k60), vmp = minPeak.update(1 - vol, c.k60);
            int n = c.n;
            int v = (int) (n * vol), vmin = Math.min((int) (n * 0.2f), n), vmax = Math.min((int) (n * 0.8f), n);
            float[] blue = {0, 0, 1}, green = {0, 1, 0}, red = {1, 0, 0}, white = {1, 1, 1};
            Arrays.fill(out, 0);
            for (int i = 0; i < Math.min(vmin, v); i++) set(out, i, blue, 1);
            for (int i = vmin; i < Math.min(v, vmax); i++) set(out, i, green, 1);
            for (int i = vmax; i < v; i++) set(out, i, red, 1);
            int pw = Math.max(1, (int) (n / 100f));
            int ps = Math.min((int) (n * vp), n);
            for (int i = ps; i < Math.min(ps + pw, n); i++) set(out, i, white, 1);
            int ms = Math.max(0, Math.min((int) (n * (1 - vmp)), n));
            for (int i = Math.max(0, ms - pw); i < ms; i++) set(out, i, white, 1);
        }
    }

    static final class Wavelength implements Effect {
        Wavelength(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            applyGradient(c, c.melbank(c.n), out);
        }
    }

    static final class Metro implements Effect {
        Metro(int n) {
        }

        @Override
        public void render(Ctx c, float[] out) {
            double period = 1.0;
            double cycle = c.t % period;
            Arrays.fill(out, 0);
            if (cycle > period * 0.3) return;
            int step = (int) (c.t / period) % 4;
            float[] w = {1, 1, 1};
            if (step == 0) {
                for (int i = 0; i < c.n; i++) set(out, i, w, 1);
                return;
            }
            int div = 1 << (step - 1);
            int chunk = c.n / div;
            for (int b = 0; b < div; b++) {
                int st = b * chunk, en = st + chunk / 2;
                for (int i = st; i < en - 1 && i < c.n; i++) set(out, i, w, 1);
            }
        }
    }

    // ------------------------------------------------------------------ Atmospheric (HSV)

    static final class BlockReflections extends Hsv {
        final Exp lows = new Exp(0.05f, 0.05f);

        BlockReflections(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float sp = 0.5f * c.speedFactor(), re = 0.5f * c.react();
            float t2 = (float) (c.time(sp) * Math.PI * Math.PI + 0.8f * re * lp);
            float t1 = c.time(sp);
            float t3 = c.time(5 * sp) + re * lp;
            float t4 = (float) (c.time(2 * sp) * Math.PI * Math.PI);
            float m = 0.3f + tri01(t1) * 0.2f;
            float cc = tri01(t3) * 10 + 4 * sin01(t4);
            for (int i = 0; i < c.n; i++) {
                float hh = (i - c.n / 2f) / c.n * cc;
                hh = hh % m; // np.mod keeps the sign of m
                if (hh < 0) hh += m;
                hh += sin01(t2);
                float vv = frac(Math.abs(hh) + Math.abs(m) + t1);
                h[i] = hh;
                s[i] = 1;
                v[i] = vv * vv;
            }
        }
    }

    static final class Crawler extends Hsv {
        final Exp lows = new Exp(0.1f, 0.1f);
        double timestep;

        Crawler(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float sp = 0.5f * c.speedFactor(), re = 0.25f * c.react();
            timestep += c.dt + lp * re * sp * c.k60; // LedFx adds lows × react × speed × 1 s per frame
            float t1 = c.time(sp * 20, timestep);
            float t3 = c.time(sp * 30 + lp * re);
            for (int i = 0; i < c.n; i++) {
                float hh = (i + t3 * c.n) / c.n * 2.5f;
                hh = hh % 0.25f;
                hh += c.n == 1 ? 0 : (float) i / (c.n - 1);
                hh += sin01(t1);
                h[i] = hh;
                s[i] = 1;
                float vv = sin01(hh);
                v[i] = vv * vv;
            }
        }
    }

    static final class Energy2 extends Hsv {
        final Exp lows = new Exp(0.1f, 0.1f);

        Energy2(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float re = 0.2f * c.react();
            float t1 = c.time(0.1f * c.speedFactor());
            float shift = 2f * sin01(t1 + re * lp);
            for (int i = 0; i < c.n; i++) {
                float vv = tri01(frac((c.n == 1 ? 0 : (float) i / (c.n - 1)) + shift));
                vv = vv * vv;
                v[i] = vv;
                s[i] = vv < 0.9f - (re + 0.3f) * lp ? 1 : 0;
                h[i] = lp + t1;
            }
        }
    }

    static final class Fire extends Hsv {
        final float[] px;
        final float[] sparks, sparkX;

        Fire(int n) {
            super(n);
            px = new float[n];
            sparks = new float[8];
            sparkX = new float[8];
        }

        final Exp lows = new Exp(0.05f, 0.99f);

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float cooling = 0.75f + lp * 0.25f;
            float speed = (0.04f + lp * 0.01f) * c.speedFactor();
            float delta = c.dt * 1000f * speed;
            float cool = (float) Math.pow(cooling, c.k60);
            int n = c.n;
            for (int i = 0; i < n; i++) px[i] *= cool;
            if (n > 5) {
                for (int i = n - 1; i >= 5; i--) px[i] = (px[i - 1] + px[i - 2] + px[i - 3] * 2 + px[i - 4] * 3) / 7f;
            }
            for (int k = 0; k < sparks.length; k++) {
                if (sparks[k] <= 0) {
                    sparks[k] = 0.5f + 0.5f * c.rnd.nextFloat();
                    sparkX[k] = 5 * c.rnd.nextFloat();
                }
                float step = sparks[k] * sparks[k] * delta * (n / 100f);
                sparkX[k] += step;
                if (sparkX[k] >= n || c.rnd.nextFloat() < 0.05f) {
                    sparks[k] = 0;
                    sparkX[k] = 0;
                    continue;
                }
                int js = (int) (sparkX[k] - step), je = (int) sparkX[k];
                for (int j = js; j < je; j++) {
                    int p = Math.max(0, Math.min(n - 1, j));
                    px[p] += clamp01(1 - sparks[k] * 0.4f) * 0.5f;
                }
            }
            for (int i = 0; i < n; i++) {
                h[i] = clamp01(px[i] * px[i]) * 0.1f + 0.15f;
                s[i] = 1 - (px[i] - 1) * 2;
                v[i] = px[i] * 2;
            }
        }
    }

    static final class Glitch extends Hsv {
        double timestep;

        Glitch(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float lp = c.lowsPower();
            float sp = 0.5f * c.speedFactor(), re = 0.2f * c.react();
            timestep += c.dt + lp * re / sp * c.k60;
            float t1 = (float) (c.time(sp * 0.5, timestep) * Math.PI * 2);
            float t2 = c.time(sp * 0.5, timestep);
            float t3 = c.time(sp * 2.5, timestep);
            float t4 = (float) (c.time(sp * 1.0, timestep) * Math.PI * 2);
            float m = 0.3f + tri01(t2) * 0.2f;
            float cc = tri01(t3) * 10 + 4 * (float) Math.sin(t4);
            for (int i = 0; i < c.n; i++) {
                float hh = (i - c.n / 2f) / c.n * cc;
                hh %= m;
                if (hh < 0) hh += m;
                hh += (float) Math.sin(t1);
                h[i] = hh;
                s[i] = 1; // saturation_threshold = 1
                v[i] = 1;
            }
        }
    }

    static final class LavaLamp extends Hsv {
        final Exp lows;

        LavaLamp(int n) {
            super(n);
            lows = new Exp(0.05f, 0.3f);
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60) * c.react();
            float sp = 7f * c.speedFactor();
            float t1 = c.time(sp * Math.max(1, 1 + lp * 0.004));
            float t2 = c.time(sp * 2 * Math.max(1, 1 + lp * 0.007));
            float contrast = 1 - 0.6f;
            for (int i = 0; i < c.n; i++) {
                float il = c.n == 1 ? 0 : (float) i / (c.n - 1);
                float w1 = sin01(t1 + il);
                float w2 = sin01(t2 - il);
                float w3 = sin01(frac(il + w1 + w2));
                h[i] = t1 + il;
                float a = (w1 + 0.1f) * (w2 + lp * 0.7f) * (w3 + lp * 0.9f);
                h[i] += a * 0.1f;
                float vv = a + contrast;
                s[i] = 1;
                v[i] = vv * vv;
            }
        }
    }

    static final class Marching extends Hsv {
        final Exp lows = new Exp(0.05f, 0.2f);

        Marching(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float sp = 0.1f * c.speedFactor();
            float t1 = c.time(sp * 20);
            float t2 = c.time(sp) + 0.2f * c.react() * lp * 20;
            for (int i = 0; i < c.n; i++) {
                float w2 = c.n == 1 ? 0 : (float) i / (c.n - 1);
                float w1 = sin01(t1 + w2);
                float hh = sin01(sin01(w1 - w2));
                float w = sin01(t2 + 2 - w2 * 10);
                h[i] = hh;
                s[i] = 1;
                v[i] = w1 - w;
            }
        }
    }

    static final class Melt extends Hsv {
        final Exp lows = new Exp(0.1f, 0.1f);
        double timestep;

        Melt(int n) {
            super(n);
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float sp = 0.5f * c.speedFactor();
            timestep += c.dt + lp * 0.5f * c.react() / sp * c.k60;
            float t1 = c.time(sp * 5, timestep), t2 = c.time(sp * 6.5, timestep);
            for (int i = 0; i < c.n; i++) {
                float c1 = 1 - (c.n == 1 ? 0 : (float) i / (c.n - 1));
                float vv = sin01(c1);
                vv = sin01(vv + t1);
                vv = sin01(vv + t1);
                h[i] = c1 + t2;
                s[i] = 1;
                v[i] = vv * vv;
            }
        }
    }

    static final class MeltSparkle extends Hsv {
        final Exp lows = new Exp(0.1f, 0.1f), mids = new Exp(0.1f, 0.1f);
        final float[] strobe;
        double timestep, lastStrobe = -10;
        float direction = 1;

        MeltSparkle(int n) {
            super(n);
            strobe = new float[n];
        }

        @Override
        void hsv(Ctx c) {
            float lp = lows.update(c.lowsPower(), c.k60);
            float mp = mids.update(c.midsPower(), c.k60);
            float cutoff = 0.75f / 10f;
            if (lp > cutoff && c.rnd.nextInt(Math.max(1, (int) (200 / c.k60))) == 0) direction *= -1;
            float[] th = c.thirdsMax();
            boolean fire = c.onsetNow && c.t - lastStrobe > 1 - 0.75 && th[2] * th[2] > cutoff;
            float sp = 0.5f * c.speedFactor(), re = 0.5f * c.react();
            timestep += c.dt * direction + lp * re * sp * 0.5 * c.k60 * direction;
            float t1 = c.time(sp * 20, timestep);
            float bass = lp * re * 0.5f;
            float width = (float) Math.pow(1 - 0.5f, 2);
            float pw = 30 * width - mp * width;
            if (fire) {
                lastStrobe = c.t;
                int sw = Math.max(1, Math.min((int) (Math.pow(0.3f, 3) * c.n), c.n - 1));
                int pos = c.rnd.nextInt(Math.max(1, c.n - sw));
                for (int i = pos; i < pos + sw; i++) strobe[i] = 1;
            }
            for (int i = 0; i < c.n; i++) {
                float hh = sin01(1 - (c.n == 1 ? 0 : (float) i / (c.n - 1)));
                float vv = hh;
                hh = triWave(triWave(hh + bass * sp * 5));
                vv = sin01(vv);
                vv = sin01(vv + t1);
                vv = triWave(vv + (1 - t1));
                vv = triWave(vv + bass);
                vv = (float) Math.pow(Math.max(0, vv), pw) * 0.4f;
                h[i] = hh;
                s[i] = 1 - strobe[i];
                v[i] = Math.min(1, vv + strobe[i]);
            }
            float d = (float) Math.pow(1 - 0.25f, c.k60);
            for (int i = 0; i < c.n; i++) strobe[i] *= d;
            float[] sm = smooth(strobe, 3.5f);
            System.arraycopy(sm, 0, strobe, 0, c.n);
        }
    }

    static final class Water extends Hsv {
        final float[][] buf;
        int cur;
        final float[][] mids = {{0.25f, 1f}, {0.75f, -1f}};
        final float[][] highs = {{0.125f, 1.5f}, {0.375f, -2.5f}, {0.625f, 2.5f}, {0.875f, -1.5f}};
        float acc;

        Water(int n) {
            super(n);
            buf = new float[2][n];
        }

        private void drop(int p, float height) {
            for (int k = 0; k < 2; k++) {
                buf[k][p] = height;
                buf[k][p - 1] = height;
                buf[k][p + 1] = height;
            }
        }

        @Override
        void hsv(Ctx c) {
            int n = c.n;
            if (n < 5) {
                Arrays.fill(v, 0);
                return;
            }
            acc += c.k60;
            while (acc >= 1f) {
                acc -= 1f;
                // ripple step
                cur = 1 - cur;
                int src = cur == 0 ? 1 : 0, dst = cur;
                for (int i = 1; i < n - 1; i++) {
                    buf[dst][i] = (buf[src][i - 1] + buf[src][i + 1] + buf[src][i] * 2) / 2 - buf[dst][i];
                }
                float[] sm = smooth(buf[dst], 1f);
                for (int i = 0; i < n; i++) buf[dst][i] = sm[i] - sm[i] / 64f;
                // drops (LedFx adds them on every audio frame)
                float[] in = c.thirdsMax();
                for (int k = 0; k < 3; k++) in[k] = clamp01(in[k] * in[k]) * c.react();
                drop(1, in[0] * 8);
                drop(n / 2, in[0] * 8);
                drop(n - 2, in[0] * 8);
                float sp = c.speedFactor();
                for (float[] e : mids) {
                    drop(1 + (int) (e[0] * (n - 2)) - (e[0] >= 1 ? 1 : 0), in[1] * 6);
                    e[0] = ((e[0] + 0.0002f * e[1] * sp) % 1f + 1f) % 1f;
                }
                for (float[] e : highs) {
                    drop(1 + (int) (e[0] * (n - 2)) - (e[0] >= 1 ? 1 : 0), in[2] * 3);
                    e[0] = ((e[0] + 0.0002f * e[1] * sp) % 1f + 1f) % 1f;
                }
            }
            float shift = 0.12f;
            for (int i = 0; i < n; i++) {
                float b = buf[cur][i];
                h[i] = triWave(b);
                float vv = clamp01((b + shift) / (1 + shift));
                v[i] = vv;
                s[i] = clamp01(-(vv + shift) + 2f);
            }
        }
    }
}
