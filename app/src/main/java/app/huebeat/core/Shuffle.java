package app.huebeat.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * "Shuffle": an automatic light director. It listens to the vibe of the music for a while
 * (energy, tempo, brightness of the sound, major/minor) and picks a matching look: style,
 * palette, intensity, color movement, and an effect for every LED strip. It changes the look at
 * musical moments: after a phrase (32 beats), on a drop, or when the music calms down.
 */
public final class Shuffle {
    /** The settings the renderers use while shuffling (user settings with the picks on top). */
    public final LightSettings out = new LightSettings();

    private final Random rnd = new Random();
    // vibe
    // loudness in dB above the analysis floor (not auto-gained: sections must stay apart)
    private float dbSlow = -1, dbFast = -1, dbPeak = -1;
    private float beatRate = 0f, bright = 0.5f, minor = 0.3f, strength = 0.5f;
    private float energy = 0.5f, pickedEnergy = 0.5f;
    // sound color: 24-band spectrum, the last ~half second against the last few seconds
    private final float[] shapeFast = new float[Features.SPEC], shapeSlow = new float[Features.SPEC];
    private boolean shapeInit = false;
    private float novelty = 0f, dbQuick = -1f, dbInst, dbBase = -1f, bassInst, bassBase;
    private String pending;
    private double pendingAt;
    /** How different the sound color must get to count as a new section (mean change per band). */
    static float NOVELTY = 0.11f;
    static float TAU_FAST = 0.4f, TAU_SLOW = 4f;
    private boolean warmedUp = false;
    private double clock = 0, lastSwitch = -100, quietSince = -1;
    private int lastBeat = Integer.MIN_VALUE, beatsSince = 0, beatAtPending;
    private volatile boolean forced = true;
    // current picks
    private int style = LightSettings.STYLE_BEAT, palette = 0;
    private float intensity = 0.6f, colorMove = 0.5f;
    private final List<int[]> stripPick = new ArrayList<>();   // effect, variant, strobe
    private final List<float[]> stripPickF = new ArrayList<>(); // blur, speed
    private float dmxMove = 0.3f;
    private int dmxStrobe = 0;
    private int generation = 0;
    private String reason = "";

    private static final int[] STYLES_CALM = {LightSettings.STYLE_AMBIENT, LightSettings.STYLE_MELODY, LightSettings.STYLE_CHORDS, LightSettings.STYLE_BEAT};
    private static final float[] W_CALM = {3, 2.5f, 2.5f, 1};
    private static final int[] STYLES_MID = {LightSettings.STYLE_BEAT, LightSettings.STYLE_MELODY, LightSettings.STYLE_CHORDS, LightSettings.STYLE_SPECTRUM};
    private static final float[] W_MID = {3, 2, 2, 2};
    private static final int[] STYLES_HIGH = {LightSettings.STYLE_BEAT, LightSettings.STYLE_PARTY, LightSettings.STYLE_SPECTRUM, LightSettings.STYLE_MELODY};
    private static final float[] W_HIGH = {3, 2.5f, 2, 1};

    private static final String[] FX_CALM = {"Like the lights", "Spectrum Glow", "Flow", "Lava lamp", "Water", "Melt", "Campfire",
            "Fire", "Scroll", "Wavelength", "Filter"};
    private static final String[] FX_MID = {"Equalizer Peaks", "Energy Bars", "Scroll+", "Energy", "Bands", "Equalizer", "Spectrum",
            "Pitch Spectrum", "Melt and Sparkle", "Hierarchy", "Multicolor Bar", "Marching", "Rain", "Drops", "Crawler",
            "Block Reflections", "Magnitude", "VuMeter", "Spotlight", "Energy 2"};
    private static final String[] FX_HIGH = {"Power", "Blade Power+", "Bar", "Scan", "Scan and Flare", "Scan Multi", "Glitch",
            "Strobe", "BPM Strobe", "Fireworks", "Blocks", "Energy", "Metro"};

    /** Ask for a new look right away (the "Shuffle now" button). */
    public void reshuffle() {
        forced = true;
    }

    public float energy() {
        return energy;
    }

    /** Changes every time a new look is picked. */
    public int generation() {
        return generation;
    }

    /** What is playing now, e.g. "Chords · Synthwave". */
    public String describe() {
        return LightSettings.STYLE_NAMES[style] + " · " + Palettes.name(Math.min(palette, Palettes.count() - 1));
    }

    /** For tests: the parts of the energy estimate. */
    public String debug() {
        return String.format(java.util.Locale.US, "nov %.3f inst %.1f base %.1f bass %.2f/%.2f peak %.1f db %.0f/%.0f/%.0f beatRate %.2f strength %.2f bright %.2f", novelty, dbInst, dbBase, bassInst, bassBase, dbPeak, dbFast, dbSlow, dbPeak, beatRate, strength, bright);
    }

    public String reason() {
        return reason;
    }

    public void update(Features f, float dt, LightSettings user) {
        clock += dt;
        if (f != null && !f.silent) {
            float a = 1f - (float) Math.exp(-dt / 6f), af = 1f - (float) Math.exp(-dt / 0.8f);
            float db = f.inputMeter * 85f;
            if (dbSlow < 0) dbSlow = dbFast = dbPeak = db;
            dbSlow += (db - dbSlow) * a;
            dbFast += (db - dbFast) * af;
            // the loudest part so far, forgotten slowly (a new song may be quieter)
            dbPeak = Math.max(dbFast, dbPeak - 0.04f * dt);
            bright += (f.centroid - bright) * a;
            float aq = 1f - (float) Math.exp(-dt / TAU_FAST), as = 1f - (float) Math.exp(-dt / TAU_SLOW);
            if (dbQuick < 0) dbQuick = db;
            dbQuick += (db - dbQuick) * (1f - (float) Math.exp(-dt / 0.3f));
            if (!shapeInit) {
                System.arraycopy(f.spec, 0, shapeFast, 0, Features.SPEC);
                System.arraycopy(f.spec, 0, shapeSlow, 0, Features.SPEC);
                shapeInit = true;
            }
            float nov = 0f;
            for (int i = 0; i < Features.SPEC; i++) {
                shapeFast[i] += (f.spec[i] - shapeFast[i]) * aq;
                nov += Math.abs(shapeFast[i] - shapeSlow[i]);
                shapeSlow[i] += (shapeFast[i] - shapeSlow[i]) * as;
            }
            novelty = nov / Features.SPEC;
            // instant view (~0.1 s) of loudness and bass, against a baseline that resets at every change
            float ai = 1f - (float) Math.exp(-dt / 0.08f), ab = 1f - (float) Math.exp(-dt / 4f);
            float bassNow = 0f;
            for (int i = 0; i < 5; i++) bassNow += f.spec[i] / 5f;
            if (dbBase < 0) {
                dbInst = dbBase = db;
                bassInst = bassBase = bassNow;
            }
            dbInst += (db - dbInst) * ai;
            bassInst += (bassNow - bassInst) * ai;
            dbBase += (dbInst - dbBase) * ab;
            bassBase += (bassInst - bassBase) * ab;
            if (f.chordRoot >= 0) minor += ((f.chordMinor ? 1f : 0f) - minor) * (1f - (float) Math.exp(-dt / 15f));
            boolean beat = lastBeat != Integer.MIN_VALUE && f.beatCount != lastBeat;
            lastBeat = f.beatCount;
            if (beat) {
                beatsSince++;
                strength += (f.beatStrength - strength) * 0.1f;
            }
            beatRate += ((beat ? 1f / Math.max(dt, 1e-3f) : 0f) - beatRate) * (1f - (float) Math.exp(-dt / 4f));
            float tempo = f.bpm > 0 ? clamp01((f.bpm - 80f) / 90f) : 0.3f;
            // weak "beats" (pads, chord changes) count little, real drums a lot
            float beats = clamp01(beatRate / 2.5f) * (float) Math.pow(clamp01((strength - 0.2f) / 0.7f), 1.3);
            float loud = clamp01(1f - (dbPeak - dbSlow) / 14f); // 14 dB under the song's peak = quiet
            energy = clamp01(0.4f * loud + 0.4f * beats + 0.1f * tempo + 0.1f * bright);
            if (clock > 5 && !warmedUp) {
                warmedUp = true;
                forced = true; // first real pick, now that the vibe is known
            }
        }

        // when to change
        double since = clock - lastSwitch;
        String why = null;
        if (forced) why = "new look";
        // a drop, right on its first hit: much louder and the bass is in, near the song's loudest.
        // No waiting: a drop that is noticed seconds later is already over.
        // (only a previous drop holds it back for a moment; a phrase change just before is common)
        else if (warmedUp && since > ("drop".equals(reason) ? 2.5 : 0.8) && pickedEnergy < 0.65f && dbInst > dbBase + 4f
                && bassInst > bassBase + 0.1f && dbInst > dbPeak - 6f) {
            why = "drop";
            pending = null;
        }
        // the opposite, just as quick: the bass drops out and it gets clearly quieter
        else if (warmedUp && since > 1.5 && pickedEnergy > 0.45f && dbInst < dbBase - 6f && bassInst < bassBase - 0.1f) {
            why = "calmer part";
            pending = null;
        }
        // another new section: the sound color changes suddenly → switch on the next beat
        else if (pending == null && warmedUp && since > 1.5 && novelty > NOVELTY) {
            float up = dbQuick - dbBase, bassUp = 0f;
            for (int i = 0; i < 5; i++) bassUp += (shapeFast[i] - shapeSlow[i]) / 5f;
            if (up < -4f || bassUp < -0.08f) pending = "calmer part";
            else if ((up > 2.5f || bassUp > 0.08f) && dbQuick > dbPeak - 5f) pending = "drop";
            else pending = "new section";
            pendingAt = clock;
            beatAtPending = f != null ? f.beatCount : 0;
        } else if (pending != null) {
            if ((f != null && f.beatCount != beatAtPending) || clock - pendingAt > 0.5) {
                why = pending;
                pending = null;
            }
        }
        // slow safety net: clearly quieter than the song's loud parts for a while
        if (why == null && pending == null && warmedUp && since > 4 && pickedEnergy > 0.45f) {
            if (dbFast < dbPeak - 9f) {
                if (quietSince < 0) quietSince = clock;
                if (clock - quietSince > 2.0) why = "calmer part";
            } else {
                quietSince = -1;
            }
        }
        // next phrase, but not while the music is just swelling up (that's usually a drop coming)
        boolean rising = dbFast > dbSlow + 3f;
        if (why == null && pending == null && warmedUp && !rising && (beatsSince >= 32 && since > 12 || since > 40)) why = "next phrase";
        if (why != null) {
            float quick = clamp01(1f - (dbPeak - dbQuick) / 14f);
            pickedEnergy = "drop".equals(why) ? Math.max(energy, 0.8f) : "calmer part".equals(why) ? Math.min(energy, 0.3f)
                    : "new section".equals(why) ? 0.5f * energy + 0.5f * quick : energy;
            pick(pickedEnergy);
            // the new part is the new normal: compare against it from now on
            System.arraycopy(shapeFast, 0, shapeSlow, 0, Features.SPEC);
            dbBase = dbInst;
            bassBase = bassInst;
            forced = false;
            lastSwitch = clock;
            beatsSince = 0;
            quietSince = -1;
            reason = why;
            generation++;
        }

        out.copyFrom(user);
        out.style = style;
        out.palette = Math.min(palette, Palettes.count() - 1);
        out.screenColors = false;
        out.intensity = intensity;
        out.colorMove = colorMove;
        out.strobe = energy > 0.75f;
    }

    private void pick(float e) {
        // style: weighted by energy, not the same twice in a row
        int[] pool = e < 0.38f ? STYLES_CALM : e > 0.68f ? STYLES_HIGH : STYLES_MID;
        float[] w = e < 0.38f ? W_CALM : e > 0.68f ? W_HIGH : W_MID;
        int next = style;
        for (int tries = 0; tries < 6 && next == style; tries++) next = pool[weighted(w)];
        style = next;
        intensity = clamp01(0.22f + 0.72f * e + (rnd.nextFloat() - 0.5f) * 0.12f);
        colorMove = clamp01(0.25f + 0.6f * e + (rnd.nextFloat() - 0.5f) * 0.15f);
        palette = pickPalette(e);
        dmxMove = 0.08f + 0.55f * e;
        dmxStrobe = e > 0.75f ? 1 : 0;
        stripPick.clear();
        stripPickF.clear();
    }

    /**
     * Palette by mood: the colors of each palette are measured (warmth, saturation), so custom
     * palettes join in too. Minor / dark sound → cooler; major / bright → warmer; energy → vivid.
     */
    private int pickPalette(float e) {
        float wantWarm = clamp01(0.55f + 0.5f * (bright - 0.5f) - 0.6f * (minor - 0.35f));
        float wantVivid = clamp01(0.35f + 0.7f * e);
        int best = palette;
        float bestScore = -1e9f;
        for (int i = 0; i < Palettes.count(); i++) {
            int[] c = Palettes.colorsOf(i);
            float warm, vivid;
            if (c == null || c.length == 0) {
                warm = 0.5f; // Auto follows the music's harmony: fits anything a little
                vivid = 0.7f;
            } else {
                warm = 0f;
                vivid = 0f;
                for (int rgb : c) {
                    float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
                    float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                    vivid += mx > 0 ? (mx - mn) / mx : 0f;
                    warm += clamp01(0.5f + 0.5f * (r - b) + 0.15f * (g - b) * (r > g ? 1 : 0));
                }
                warm /= c.length;
                vivid /= c.length;
            }
            float score = -Math.abs(warm - wantWarm) * 1.2f - Math.abs(vivid - wantVivid) + rnd.nextFloat() * 0.35f;
            if (i == palette) score -= 0.25f; // prefer a change
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Puts this look's effect on strip {@code k} (picked once per look, per strip). */
    public void applyStrip(int k, StripOutput so) {
        while (stripPick.size() <= k) {
            float e = pickedEnergy;
            String[] pool = e < 0.38f ? FX_CALM : e > 0.68f ? FX_HIGH : FX_MID;
            int fx = StripEffect.LIGHTS;
            for (int tries = 0; tries < 8; tries++) {
                int id = indexOf(pool[rnd.nextInt(pool.length)]);
                if (id >= 0) {
                    fx = id;
                    break;
                }
            }
            String[] vars = StripEffect.variants(fx);
            int variant = vars != null && vars.length > 1 ? rnd.nextInt(vars.length) : 0;
            float blur = e < 0.38f ? 0.35f + 0.35f * rnd.nextFloat() : e > 0.68f ? 0.1f * rnd.nextFloat() : 0.1f + 0.2f * rnd.nextFloat();
            float speed = clamp01(0.3f + 0.45f * e + (rnd.nextFloat() - 0.5f) * 0.2f);
            int strobe = e > 0.78f && rnd.nextFloat() < 0.5f ? StripEffect.STROBE_BIG : StripEffect.STROBE_OFF;
            stripPick.add(new int[]{fx, variant, strobe});
            stripPickF.add(new float[]{blur, speed});
        }
        int[] p = stripPick.get(k);
        float[] q = stripPickF.get(k);
        so.effect.effect = p[0];
        so.effect.variant = p[1];
        so.effect.strobeTrigger = p[2];
        so.effect.blur = q[0];
        so.effect.speed = q[1];
        so.palette = -1; // follow the picked palette
    }

    public void applyDmx(Dmx.Fixture f) {
        f.move = Dmx.moves(f.roles) ? dmxMove : f.move;
        f.strobeMode = dmxStrobe;
    }

    private static int indexOf(String name) {
        for (int i = 0; i < StripEffect.NAMES.length; i++) if (name.equals(StripEffect.NAMES[i])) return i;
        return -1;
    }

    private int weighted(float[] w) {
        float sum = 0;
        for (float x : w) sum += x;
        float r = rnd.nextFloat() * sum;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) return i;
        }
        return w.length - 1;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
