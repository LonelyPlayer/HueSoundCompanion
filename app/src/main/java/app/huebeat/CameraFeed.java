package app.huebeat;

import app.huebeat.core.CameraColors;

/** Hands the TV colors seen by the camera (CameraActivity) to the sync engine (SyncService). */
final class CameraFeed {
    static final int GW = 16, GH = 9;

    /** Latest TV picture as a GW×GH grid of rgb cells, or null while no TV is tracked. */
    static volatile float[] grid;
    static volatile long gridNanos;
    /** Set by the camera screen, picked up by the engine. */
    static volatile boolean calibrateRequested;
    /** Calibration progress / result for the camera screen. */
    static volatile String calibrationStatus = "";
    /** Room-light model and color extraction; lives as long as the app process. */
    static final CameraColors colors = new CameraColors(GW, GH);

    private CameraFeed() {
    }

    static void publish(float[] g, long nanos) {
        grid = g;
        gridNanos = nanos;
    }

    static void clear() {
        grid = null;
    }

    /** True while the camera screen delivers TV colors. */
    static boolean fresh(long now) {
        return grid != null && now - gridNanos < 600_000_000L;
    }
}
