package app.huebeat.core;

/**
 * The global look over the whole room: one renderer (music) and one animation (just lights) over
 * every point of every device. Everything that follows the global look takes its colors from here,
 * so Hue lamps, LED strips and DMX lights move together, in space and in time.
 */
public final class Scene {
    public final float[] x, y, z, u;
    public final int n;
    private final Renderer renderer;
    private final Ambient ambient;
    private final int[] staticColors;
    private float[] last;

    public Scene(float[] x, float[] y, float[] z, int flow) {
        this.x = x;
        this.y = y;
        this.z = z;
        n = x.length;
        u = Room.u(flow, x, y);
        renderer = new Renderer(n == 0 ? new float[]{0f} : Room.signed(u));
        ambient = new Ambient(n == 0 ? new float[]{0.5f} : u, false);
        staticColors = new int[Math.max(1, n)];
        last = new float[3 * Math.max(1, n)];
    }

    /** Music frame: rgb (0..1) per point. */
    public float[] render(Features f, double t, float dt, LightSettings s) {
        last = renderer.render(f, t, dt, s);
        return last;
    }

    /** Just-lights frame; static colors come from the room's color field. */
    public float[] renderAmbient(float dt, Ambient.Settings s) {
        for (int i = 0; i < n; i++) staticColors[i] = LampColors.at(x[i], y[i], s.color);
        Ambient.master = ambient;
        last = ambient.render(dt, s, staticColors);
        return last;
    }

    /** Screen/camera colors per point (null = none). */
    public void setScreenColors(float[] rgb) {
        renderer.setScreenColors(rgb);
    }

    public float[] meanColor() {
        float[] m = new float[3];
        float[] l = last;
        int k = l.length / 3;
        for (int i = 0; i < k; i++) for (int c = 0; c < 3; c++) m[c] += l[3 * i + c] / k;
        return m;
    }

    /** The animation clock everything "same as global" follows. */
    public Ambient clock() {
        return ambient;
    }
}
