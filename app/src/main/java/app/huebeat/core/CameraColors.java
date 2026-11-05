package app.huebeat.core;

/**
 * Turns the camera's view of the TV (a grid of cell colors) into one color per light, and
 * removes the room light: the Hue lights themselves shine onto the screen and reflect in it, which
 * would otherwise feed back into their own colors.
 *
 * The room-light model comes from a short calibration (see {@link Calibrator}): with the TV
 * showing a still image, the lights go black, white, red, green and blue. For every grid cell
 * that gives a 3×3 matrix "light color → color added to this cell", plus the delay between
 * sending a color and the camera seeing it.
 */
public final class CameraColors {
    public final int gw, gh;
    private volatile float[] model;     // 9 floats per cell, column c = effect of light primary c
    private volatile int latencyMs = 150;
    private float agcPeak = 0.3f;

    public CameraColors(int gw, int gh) {
        this.gw = gw;
        this.gh = gh;
    }

    public boolean isCalibrated() {
        return model != null;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public void setCalibration(float[] model, int latencyMs) {
        this.model = model;
        this.latencyMs = latencyMs;
    }

    public void clearCalibration() {
        model = null;
    }

    /**
     * @param grid   rgb per cell from the camera, 0..1
     * @param light  average rgb the lights were showing {@link #latencyMs()} ago (0..1), or null
     * @param u      per light: horizontal position 0 (left) .. 1 (right)
     * @param v      per light: vertical position 0 (top) .. 1 (bottom)
     * @param out    rgb per light, 0..1
     */
    public void compute(float[] grid, float[] light, float[] u, float[] v, float[] out) {
        int cells = gw * gh;
        float[] g = new float[3 * cells];
        float[] m = model;
        for (int c = 0; c < cells; c++) {
            for (int k = 0; k < 3; k++) {
                float x = grid[3 * c + k];
                if (m != null && light != null) {
                    int o = 9 * c;
                    // row k of the cell's matrix times the light color
                    x -= m[o + k] * light[0] + m[o + 3 + k] * light[1] + m[o + 6 + k] * light[2];
                }
                g[3 * c + k] = Math.max(0f, x);
            }
        }
        // automatic gain: the camera runs underexposed so bright scenes don't clip
        float[] maxes = new float[cells];
        for (int c = 0; c < cells; c++) maxes[c] = Math.max(g[3 * c], Math.max(g[3 * c + 1], g[3 * c + 2]));
        java.util.Arrays.sort(maxes);
        float p95 = maxes[(int) (cells * 0.95f)];
        agcPeak = Math.max(p95, Math.max(agcPeak * 0.997f, 0.12f));
        float gain = 0.95f / agcPeak;

        for (int i = 0; i < u.length; i++) {
            int x0 = clampI((int) Math.floor((u[i] - 0.2f) * gw), gw), x1 = clampI((int) Math.ceil((u[i] + 0.2f) * gw) - 1, gw);
            int y0 = clampI((int) Math.floor((v[i] - 0.25f) * gh), gh), y1 = clampI((int) Math.ceil((v[i] + 0.25f) * gh) - 1, gh);
            double r = 0, gg = 0, b = 0, ws = 0;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int c = y * gw + x;
                    float pr = g[3 * c], pg = g[3 * c + 1], pb = g[3 * c + 2];
                    float mx = Math.max(pr, Math.max(pg, pb)), mn = Math.min(pr, Math.min(pg, pb));
                    float w = 0.15f + (mx - mn) * 2f * gain + mx * 0.5f * gain;
                    r += pr * w;
                    gg += pg * w;
                    b += pb * w;
                    ws += w;
                }
            }
            out[3 * i] = ws > 0 ? Math.min(1f, (float) (r / ws) * gain) : 0f;
            out[3 * i + 1] = ws > 0 ? Math.min(1f, (float) (gg / ws) * gain) : 0f;
            out[3 * i + 2] = ws > 0 ? Math.min(1f, (float) (b / ws) * gain) : 0f;
        }
    }

    private static int clampI(int v, int size) {
        return v < 0 ? 0 : (v >= size ? size - 1 : v);
    }

    /**
     * Drives the lights through black → white → red → green → blue while the TV shows a still
     * image, and measures how each light color shows up on the screen (and how late).
     */
    public static final class Calibrator {
        public static final int PHASE_MS = 1600;
        private static final float[][] PHASES = {{0, 0, 0}, {1, 1, 1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        private final int cells;
        private final long startNanos;
        private final double[][] sums;      // per phase: rgb sums per cell (settled half only)
        private final int[] counts;
        // latency: luminance of the TV over time during the white phase
        private final float[] whiteT = new float[200], whiteL = new float[200];
        private int whiteN = 0;
        private float blackLum = 0f;
        public String result;
        public float[] model;
        public int latencyMs;

        public Calibrator(int gw, int gh, long nowNanos) {
            cells = gw * gh;
            startNanos = nowNanos;
            sums = new double[PHASES.length][3 * cells];
            counts = new int[PHASES.length];
        }

        private int phase(long now) {
            return (int) ((now - startNanos) / 1_000_000L / PHASE_MS);
        }

        /** Color all lights should show now, or null when the calibration is finished. */
        public float[] lightColor(long now) {
            int p = phase(now);
            return p < PHASES.length ? PHASES[p] : null;
        }

        public boolean finished(long now) {
            return phase(now) >= PHASES.length;
        }

        public void addFrame(long now, float[] grid) {
            int p = phase(now);
            if (p >= PHASES.length) return;
            float inPhaseMs = ((now - startNanos) / 1_000_000L) - p * PHASE_MS;
            if (p == 1 && whiteN < whiteT.length) {
                float lum = 0f;
                for (int c = 0; c < cells; c++) lum += grid[3 * c] + grid[3 * c + 1] + grid[3 * c + 2];
                whiteT[whiteN] = inPhaseMs;
                whiteL[whiteN++] = lum / (3 * cells);
            }
            if (inPhaseMs < PHASE_MS * 0.5f) return; // let lights and camera settle
            for (int i = 0; i < 3 * cells; i++) sums[p][i] += grid[i];
            counts[p]++;
        }

        /** Computes the model; returns false if the measurement was unusable. */
        public boolean finish() {
            for (int c : counts) {
                if (c == 0) {
                    result = "Calibration failed: the camera lost the TV.";
                    return false;
                }
            }
            float[][] avg = new float[PHASES.length][3 * cells];
            for (int p = 0; p < PHASES.length; p++)
                for (int i = 0; i < 3 * cells; i++) avg[p][i] = (float) (sums[p][i] / counts[p]);
            model = new float[9 * cells];
            double totalEffect = 0;
            for (int c = 0; c < cells; c++) {
                for (int primary = 0; primary < 3; primary++) {
                    for (int k = 0; k < 3; k++) {
                        float eff = Math.max(0f, avg[2 + primary][3 * c + k] - avg[0][3 * c + k]);
                        model[9 * c + 3 * primary + k] = eff;
                        totalEffect += eff;
                    }
                }
            }
            // latency: first moment the TV got halfway from its black level to its white level
            float black = 0f, white = 0f;
            for (int i = 0; i < 3 * cells; i++) {
                black += avg[0][i];
                white += avg[1][i];
            }
            black /= 3 * cells;
            white /= 3 * cells;
            blackLum = black;
            latencyMs = 150;
            if (white - black > 0.01f) {
                float half = black + 0.5f * (white - black);
                for (int i = 0; i < whiteN; i++) {
                    if (whiteL[i] >= half) {
                        latencyMs = Math.max(30, Math.min(900, Math.round(whiteT[i])));
                        break;
                    }
                }
            }
            float influence = (float) (totalEffect / (9 * cells));
            int pct = Math.round(Math.min(1f, (white - black) / Math.max(0.05f, white)) * 100);
            result = influence < 0.002f
                    ? "Calibrated: the lights barely reach the screen, nothing to correct."
                    : "Calibrated: the lights add about " + pct + "% to the screen's brightness, delay "
                    + latencyMs + " ms. This is now removed from the screen colors.";
            return true;
        }
    }
}
