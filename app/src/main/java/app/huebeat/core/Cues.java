package app.huebeat.core;

/**
 * Shared musical cues for the strobes of every output: how intense the music is right now
 * (the Shuffle vibe estimate) and how far a build-up has got. Set by the engine every frame.
 *
 * Strobe modes everywhere (lamps, strips, DMX) use {@link #fire}: "High intensity" flashes on the
 * beats and snares while the energy is high; "Build-ups" flashes on every roll hit and, near the
 * top of the build, keeps strobing faster and faster.
 */
public final class Cues {
    private Cues() {
    }

    /** 0..1: how intense the music is (loudness against the song's peak, drums, tempo). */
    public static volatile float energy;
    /** 0..1: how far a build-up has got (0 outside build-ups). */
    public static volatile float build;
    /** The near-silent gap right before a drop, and the drop's impact right after it (0..1). */
    public static volatile boolean gap;
    public static volatile float impact;

    public static final float HIGH = 0.72f;

    /** Strobe on high intensity, on build-ups, or both. */
    public static final int HIGH_INTENSITY = 1, BUILDUPS = 2, BOTH = 3;

    /** Per-output timer state for the continuous strobe at the top of a build. */
    public static final class Timer {
        float t;
    }

    /** True when a strobe in {@code mode} should flash this frame. */
    public static boolean fire(int mode, boolean beat, boolean snare, float dt, Timer timer) {
        boolean high = (mode & HIGH_INTENSITY) != 0 && energy > HIGH && (beat || snare);
        boolean b = false;
        if ((mode & BUILDUPS) != 0 && build > 0.3f) {
            if (beat || snare) b = true;
            if (build > 0.75f && timer != null) {
                // the last bars: a steady strobe that speeds up toward the drop (6 → 16 flashes/s)
                float rate = 6f + 10f * (build - 0.75f) / 0.25f;
                timer.t += dt * rate;
                if (timer.t >= 1f) {
                    timer.t -= 1f;
                    b = true;
                }
            }
        }
        return high || b;
    }
}
