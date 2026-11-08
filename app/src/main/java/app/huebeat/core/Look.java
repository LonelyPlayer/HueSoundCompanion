package app.huebeat.core;

/**
 * The look of one device or one group over its points: either the global look (colors taken from
 * the {@link Scene}), or its own: an own style, a solid color, its own palette, its own animation
 * and color. A group renders its own look over all members' points together, so effects spread
 * over the group like over a Hue entertainment area.
 */
public final class Look {
    public static final int SPREAD = 0, SAME = 1;

    /** Music: Dmx.FX_* (0 = global), own palette (-1 = global), color for the solid effects. */
    public volatile int effect = Dmx.FX_LIKE, palette = -1, color = 0xFF2A6D;
    /** Just lights: own animation (-1 = global) and color (-1 = global). */
    public volatile int ambientEffect = -1, ambientColor = -1;
    /** Groups: spread over the members' places, or all the same. */
    public volatile int together = SPREAD;

    private final float[] u, px, py;
    private final int n;
    private Renderer own;
    private Ambient ownAmb;
    private final LightSettings ownLs = new LightSettings();
    private final Ambient.Settings ownAs = new Ambient.Settings();
    private final float[] out;
    /** How many of the room's lights this look covers (0..1), for fair random picks. */
    public float share = 1f;
    private int[] cols;
    private float env;

    /** @param u flow coordinate of each point, px/py their room places */
    public Look(float[] u, float[] px, float[] py) {
        this.u = u;
        this.px = px;
        this.py = py;
        n = u.length;
        out = new float[3 * n];
    }

    public boolean globalMusic() {
        return effect == Dmx.FX_LIKE && palette < 0;
    }

    public boolean globalAmbient() {
        return ambientEffect < 0 && palette < 0 && ambientColor < 0;
    }

    /** Music colors per point. {@code scene}/{@code idx}: the global colors and where these points are in them. */
    public float[] music(float[] scene, int[] idx, Features f, double t, float dt, LightSettings lights, boolean beat) {
        int fx = effect;
        if (globalMusic()) return copy(scene, idx);
        if (fx == Dmx.FX_OFF) {
            java.util.Arrays.fill(out, 0f);
            return out;
        }
        if (fx == Dmx.FX_SOLID || fx == Dmx.FX_SOLID_PULSE) {
            float v = 1f;
            if (fx == Dmx.FX_SOLID_PULSE) {
                float inten = lights.intensity, period = f != null && f.bpm > 0 ? 60f / f.bpm : 0.5f;
                if (beat) env = 1f;
                float tau = Math.max(0.06f, (1 - inten) * Math.max(0.25f, period * 0.9f) + inten * 0.06f);
                env *= (float) Math.exp(-dt / tau);
                float floor = 0.45f - 0.43f * inten;
                v = floor + (1f - floor) * env;
            }
            int c = color;
            for (int i = 0; i < n; i++) {
                out[3 * i] = ((c >> 16) & 0xFF) / 255f * v;
                out[3 * i + 1] = ((c >> 8) & 0xFF) / 255f * v;
                out[3 * i + 2] = (c & 0xFF) / 255f * v;
            }
            return out;
        }
        if (own == null) own = new Renderer(together == SAME ? new float[]{0f} : Room.signed(u));
        ownLs.copyFrom(lights);
        ownLs.maxBrightness = 1f;
        int style = Dmx.styleOf(fx);
        if (style >= 0) ownLs.style = style;
        if (palette >= 0) {
            ownLs.palette = Math.min(palette, Palettes.count() - 1);
            ownLs.screenColors = false;
        }
        own.share = share;
        float[] c = own.render(f, t, dt, ownLs);
        spread(c);
        return out;
    }

    /** Just-lights colors per point. */
    public float[] ambient(float[] scene, int[] idx, float dt, Ambient.Settings lights) {
        if (globalAmbient()) return copy(scene, idx);
        if (ownAmb == null) ownAmb = new Ambient(together == SAME ? new float[]{0.5f} : u, false);
        ownAs.copyFrom(lights);
        if (ambientEffect >= 0) ownAs.animation = ambientEffect;
        if (palette >= 0) ownAs.palette = Math.min(palette, Palettes.count() - 1);
        ownAs.color = ambientColor >= 0 ? ambientColor : lights.color;
        ownAmb.follow = ambientEffect < 0 ? Ambient.master : null; // same animation as global: same clock
        int k = together == SAME ? 1 : n;
        if (cols == null || cols.length != k) cols = new int[k];
        for (int i = 0; i < k; i++) cols[i] = ambientColor >= 0 ? ambientColor : LampColors.at(px[i], py[i], lights.color);
        ownAmb.share = share;
        float[] c = ownAmb.render(dt, ownAs, cols);
        spread(c);
        return out;
    }

    private float[] copy(float[] scene, int[] idx) {
        for (int i = 0; i < n; i++) {
            int j = idx[i];
            out[3 * i] = scene[3 * j];
            out[3 * i + 1] = scene[3 * j + 1];
            out[3 * i + 2] = scene[3 * j + 2];
        }
        return out;
    }

    /** One color for all (SAME) or one per point. */
    private void spread(float[] c) {
        if (c.length == 3 * n) {
            System.arraycopy(c, 0, out, 0, 3 * n);
            return;
        }
        for (int i = 0; i < n; i++) {
            out[3 * i] = c[0];
            out[3 * i + 1] = c[1];
            out[3 * i + 2] = c[2];
        }
    }
}
