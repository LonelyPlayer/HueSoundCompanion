package app.huebeat.core;

/**
 * Places in the room, seen from above: x -1 (left) … 1 (right), y -1 (back) … 1 (front, where the
 * TV/stage is), z -1 (floor) … 1 (ceiling). Effects run through the room in one of four ways; each
 * point becomes a single coordinate 0..1 along that way, which the styles and animations use.
 */
public final class Room {
    private Room() {
    }

    public static final int LEFT_RIGHT = 0, FRONT_BACK = 1, AROUND = 2, FROM_CENTER = 3;

    /** Coordinate 0..1 of a point along the flow. */
    public static float u(int flow, float x, float y) {
        switch (flow) {
            case FRONT_BACK:
                return clamp01((1f - y) / 2f);
            case AROUND: {
                // starts in front (top of the plan) and goes clockwise: front → right → back → left
                double a = 0.25 - Math.atan2(y, x) / (2 * Math.PI);
                return (float) (a - Math.floor(a));
            }
            case FROM_CENTER:
                return clamp01((float) Math.sqrt(x * x + y * y) / 1.4142f);
            default:
                return clamp01((x + 1f) / 2f);
        }
    }

    public static float[] u(int flow, float[] x, float[] y) {
        float[] u = new float[x.length];
        for (int i = 0; i < u.length; i++) u[i] = u(flow, x[i], y[i]);
        return u;
    }

    /** The renderers work on -1..1 along the flow. */
    static float[] signed(float[] u) {
        float[] s = new float[u.length];
        for (int i = 0; i < s.length; i++) s[i] = u[i] * 2f - 1f;
        return s;
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
