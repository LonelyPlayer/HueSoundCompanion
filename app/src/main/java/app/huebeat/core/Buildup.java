package app.huebeat.core;

/**
 * Build-ups and risers. A build-up is a trend, not a moment: over a few seconds the sound gets
 * brighter (noise riser, opening filter), the hits come faster (snare rolls 4 → 8 → 16), and it gets
 * louder. That trend gives {@link #level()} (0..1). Right before the drop there is often a short
 * gap of near-silence: the lights black out, and the drop's first hit lands as a white impact.
 *
 * The overlay works on top of every style: brighter and whiter as it builds, flashes on every
 * roll hit, strips filling up from the ends like a loading bar.
 */
public final class Buildup {
    private static final int N = 40;           // 4 s of history at 10 samples per second
    private final float[] hist = new float[4 * N];
    private int count = 0, head = 0;
    private float sampleClock = 0f;

    private float onsetRate = 0f, dbInst = -1f, dbRecent = -1f;
    private int lastSnare = Integer.MIN_VALUE, lastBeat = Integer.MIN_VALUE;
    private float build = 0f, flash = 0f, impact = 0f, gapLeft = 0f, gapHold = 0f, cooldown = 0f;
    private boolean gap = false;
    private float score = 0f;

    /** 0 = normal, 1 = the top of a build-up. */
    public float level() {
        return build;
    }

    /** What the lights show: small swells (a single filter sweep, a fill) stay invisible. */
    public float shown() {
        return clamp01((build - 0.25f) / 0.65f);
    }

    /** True during the silent gap right before a drop. */
    public boolean inGap() {
        return gap;
    }

    /** 0..1 white flash right after the gap (the drop's impact). */
    public float impact() {
        return impact;
    }

    public String debug() {
        return String.format(java.util.Locale.US, "score %.2f build %.2f rate %.1f gap %b", score, build, onsetRate, gap);
    }

    public void update(Features f, float dt) {
        if (f == null) return;
        boolean hit = false;
        if (lastSnare != Integer.MIN_VALUE && (f.snareCount != lastSnare || f.beatCount != lastBeat)) hit = true;
        lastSnare = f.snareCount;
        lastBeat = f.beatCount;
        onsetRate += ((hit ? 1f / Math.max(dt, 1e-3f) : 0f) - onsetRate) * (1f - (float) Math.exp(-dt / 0.8f));
        float sh = shown();
        if (hit && sh > 0.05f) flash = Math.max(flash, 0.35f + 0.65f * sh);
        flash = Math.max(0f, flash - dt * (6f + 10f * sh)); // faster rolls → shorter flashes
        impact = Math.max(0f, impact - dt * 3f);
        cooldown = Math.max(0f, cooldown - dt);

        float db = f.silent ? 0f : f.inputMeter * 85f;
        if (dbInst < 0) dbInst = dbRecent = db;
        dbInst += (db - dbInst) * (1f - (float) Math.exp(-dt / 0.05f));
        dbRecent += (db - dbRecent) * (1f - (float) Math.exp(-dt / 1.0f));

        // the gap before the drop: after a real build-up, it suddenly goes (almost) quiet
        if (!gap && build > 0.45f && dbInst < dbRecent - 10f) {
            gap = true;
            gapHold = 0f;
        }
        if (gap) {
            gapHold += dt;
            if (dbInst > dbRecent - 4f || gapHold > 1.5f) {
                gap = false;
                if (gapHold < 1.5f) impact = 1f; // the drop hits
                build = 0f;
                count = 0;
                cooldown = 3f;
            }
            return;
        }

        // history, 10 times a second: brightness, highs, hit rate, loudness
        sampleClock += dt;
        while (sampleClock >= 0.1f) {
            sampleClock -= 0.1f;
            float hi = 0f;
            for (int i = 16; i < Features.SPEC; i++) hi += f.spec[i] / (Features.SPEC - 16);
            hist[4 * head] = f.centroid;
            hist[4 * head + 1] = hi;
            hist[4 * head + 2] = onsetRate;
            hist[4 * head + 3] = db;
            head = (head + 1) % N;
            if (count < N) count++;
            score = trend();
        }
        if (cooldown > 0f) score = 0f;
        // builds climb steadily with the trend and fade when it stops
        float target = clamp01(score);
        float tau = target > build ? 1.2f : 0.8f;
        build += (target - build) * (1f - (float) Math.exp(-dt / tau));
        if (f.silent) build = 0f;
    }

    /** Rising trend over the last 4 s (slopes of a straight-line fit), 0 when nothing rises steadily. */
    private float trend() {
        if (count < N) return 0f;
        float sc = slope(0), sh = slope(1), sr = slope(2), sd = slope(3);
        // per second: centroid +0.02, highs +0.03, hit rate +0.6/s, loudness +0.8 dB each count as "rising"
        float s = 0f;
        s += clamp01(sc / 0.025f) * 0.3f;
        s += clamp01(sh / 0.035f) * 0.25f;
        s += clamp01(sr / 0.7f) * 0.3f;
        s += clamp01(sd / 1.0f) * 0.25f;
        // two or more signs together are needed: one alone (a single filter sweep, a fill) is not a build-up
        int signs = (sc > 0.012f ? 1 : 0) + (sh > 0.018f ? 1 : 0) + (sr > 0.35f ? 1 : 0) + (sd > 0.5f ? 1 : 0);
        if (signs < 2) s *= 0.3f;
        return s;
    }

    private float slope(int k) {
        // least squares over the ring buffer, x in seconds
        float sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < N; i++) {
            int idx = (head + i) % N; // oldest first
            float x = i * 0.1f, y = hist[4 * idx + k];
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        float den = N * sxx - sx * sx;
        return den == 0 ? 0f : (N * sxy - sx * sy) / den;
    }

    // ------------------------------------------------------------------ the look

    /** Lamps: brighter, whiter, flashing on the roll; black in the gap; white impact on the drop. */
    public void apply(float[] rgb, int n) {
        if (gap) {
            java.util.Arrays.fill(rgb, 0, 3 * n, 0f);
            return;
        }
        float b = shown(), w = b * b * 0.6f + flash * 0.4f, floor = b * 0.55f;
        for (int i = 0; i < n; i++) {
            float r = rgb[3 * i], g = rgb[3 * i + 1], bl = rgb[3 * i + 2];
            float m = Math.max(r, Math.max(g, bl));
            // brighter as it builds (toward full), plus the roll flashes on top
            float target = Math.min(1f, Math.max(floor, m + (1f - m) * 0.65f * b) + flash * 0.6f);
            float k = m > 1e-3f ? target / m : 0f;
            r = m > 1e-3f ? r * k : target;
            g = m > 1e-3f ? g * k : target;
            bl = m > 1e-3f ? bl * k : target;
            rgb[3 * i] = mix(r, target, w, impact);
            rgb[3 * i + 1] = mix(g, target, w, impact);
            rgb[3 * i + 2] = mix(bl, target, w, impact);
        }
    }

    /** Strips: the build fills them from both ends toward the middle, plus the same flashes and whites. */
    public void applyStrip(float[] px, int span) {
        if (gap) {
            java.util.Arrays.fill(px, 0, 3 * span, 0f);
            return;
        }
        float b = shown();
        if (b < 0.02f && impact < 0.02f && flash < 0.02f) return;
        float fill = b;
        for (int p = 0; p < span; p++) {
            float t = span == 1 ? 0f : (float) p / (span - 1);
            float fromEdge = 1f - Math.abs(t - 0.5f) * 2f; // 0 at the ends, 1 in the middle
            float inside = clamp01((fill - fromEdge) * 8f);
            float w = (0.25f + 0.5f * b) * inside + flash * 0.5f * inside;
            float lift = inside * (0.35f + 0.5f * b) + flash * 0.4f * inside;
            for (int k = 0; k < 3; k++) {
                float v = px[3 * p + k];
                v = v + (Math.max(v, lift) - v) * 0.8f;
                px[3 * p + k] = mix(v, Math.max(lift, v), w, impact);
            }
        }
    }

    private static float mix(float c, float white, float w, float impact) {
        float v = c + (white - c) * clamp01(w);
        return v + (1f - v) * impact;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
