package app.huebeat.core;

/** Live-adjustable settings shared between the UI and the streaming engine. */
public final class LightSettings {
    public static final int STYLE_BEAT = 0, STYLE_SPECTRUM = 1, STYLE_AMBIENT = 2, STYLE_PARTY = 3;
    public static final String[] STYLE_NAMES = {"Beat", "Spectrum", "Ambient", "Party"};

    public volatile int style = STYLE_BEAT;
    /** Index into the palette list ({@link Palettes#name(int)}); 0 = Auto (colors from the music's harmony). */
    public volatile int palette = 0;
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
}
