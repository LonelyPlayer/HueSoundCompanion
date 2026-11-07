package app.huebeat.core;

/** Live-adjustable settings shared between the UI and the streaming engine. */
public final class LightSettings {
    public static final int STYLE_BEAT = 0, STYLE_SPECTRUM = 1, STYLE_AMBIENT = 2, STYLE_PARTY = 3, STYLE_SCREEN = 4,
            STYLE_MELODY = 5, STYLE_CHORDS = 6, STYLE_SHUFFLE = 7;
    public static final String[] STYLE_NAMES = {"Beat", "Spectrum", "Ambient", "Party", "Screen", "Melody", "Chords", "Shuffle"};
    /** Order of the style chips on screen (saved values stay the same). */
    public static final int[] STYLE_ORDER = {STYLE_BEAT, STYLE_MELODY, STYLE_CHORDS, STYLE_SPECTRUM, STYLE_AMBIENT, STYLE_PARTY, STYLE_SHUFFLE, STYLE_SCREEN};

    public volatile int style = STYLE_BEAT;
    /** Index into the palette list ({@link Palettes#name(int)}); 0 = Auto (colors from the music's harmony). */
    public volatile int palette = 0;
    /** Take the colors from the phone screen instead of a palette (music styles keep their rhythm). */
    public volatile boolean screenColors = false;
    /** How far beats and loudness move the color along the palette ramp, 0..1. */
    public volatile float colorMove = 0.5f;
    /** How dramatic the effect is, 0..1. */
    public volatile float intensity = 0.65f;
    /** Upper brightness limit, 0..1. */
    public volatile float maxBrightness = 1.0f;
    /** Light delay in milliseconds (to match Bluetooth audio latency). */
    public volatile int delayMs = 0;
    /** Allow full-white strobe flashes in Party style. */
    public volatile boolean strobe = false;
    /** Show build-ups and risers (brighter, whiter, faster flashes, blackout before the drop). */
    public volatile boolean buildups = true;
    /** Lamp strobe: 0 off, 1 Party snares (= strobe), 2 high intensity, 3 build-ups, 4 both. */
    public volatile int strobeMode = 0;
    public static final String[] STROBE_MODES = {"Off", "Party snares", "High intensity", "Build-ups", "High intensity + build-ups"};

    /** Copies every setting from another instance. */
    public void copyFrom(LightSettings o) {
        style = o.style;
        palette = o.palette;
        screenColors = o.screenColors;
        colorMove = o.colorMove;
        intensity = o.intensity;
        maxBrightness = o.maxBrightness;
        delayMs = o.delayMs;
        strobe = o.strobe;
        buildups = o.buildups;
        strobeMode = o.strobeMode;
    }
}
