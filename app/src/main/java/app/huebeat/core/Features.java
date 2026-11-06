package app.huebeat.core;

/** Immutable snapshot of the music analysis at one moment. */
public final class Features {
    public static final int BANDS = 6; // sub, bass, low-mid, mid, high-mid, high

    public long timeNanos;
    /** Overall loudness, auto-gained, 0..1. */
    public float level;
    /** Per-band energy, auto-gained, 0..1. */
    public final float[] bands = new float[BANDS];
    /** Finer spectrum for LED strip effects: 24 log-spaced bands from 40 Hz to 14 kHz, auto-gained, 0..1. */
    public static final int SPEC = 24;
    public final float[] spec = new float[SPEC];
    /** Increments on every detected beat (kick / bass onset). */
    public int beatCount;
    /** Strength of the most recent beat, 0..1. */
    public float beatStrength;
    /** Increments on every snare / hi-hat onset. */
    public int snareCount;
    /** Estimated tempo, or 0 if unknown. */
    public float bpm;
    /** Brightness of the sound (spectral centroid), 0..1. */
    public float centroid;
    /** Color of the current harmony: dominant pitch placed on the circle of fifths, 0..1. */
    public float keyHue;
    /** How clearly tonal the music is right now, 0..1. */
    public float tonality;
    /** Dominant pitch as a MIDI note number (e.g. 69 = A4), or 0 when no clear note. */
    public float pitch;
    /** True when there is (practically) no audio. */
    public boolean silent = true;
    /** Raw input level for the UI meter, 0..1. */
    public float inputMeter;

    public Features copy() {
        Features f = new Features();
        f.timeNanos = timeNanos;
        f.level = level;
        System.arraycopy(bands, 0, f.bands, 0, BANDS);
        System.arraycopy(spec, 0, f.spec, 0, SPEC);
        f.beatCount = beatCount;
        f.beatStrength = beatStrength;
        f.snareCount = snareCount;
        f.bpm = bpm;
        f.centroid = centroid;
        f.keyHue = keyHue;
        f.tonality = tonality;
        f.silent = silent;
        f.pitch = pitch;
        f.inputMeter = inputMeter;
        return f;
    }
}
