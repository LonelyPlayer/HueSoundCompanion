package app.huebeat.core;

/**
 * Turns a rough TV box (from the AI detector) into the four real corners: inside a band around
 * each side of the box it looks for the strongest straight brightness edge (screen/bezel against
 * the wall), fits a line to it and intersects the four lines. Works for TVs seen at an angle.
 */
public final class QuadRefiner {
    private QuadRefiner() {
    }

    /**
     * @param box x0,y0,x1,y1 in pixels
     * @return TL,TR,BR,BL corners, or null if the edges aren't clear (then use the box itself)
     */
    public static float[] refine(float[] luma, int w, int h, float[] box) {
        float x0 = box[0], y0 = box[1], x1 = box[2], y1 = box[3];
        float bw = x1 - x0, bh = y1 - y0;
        if (bw < 12 || bh < 8) return null;
        float bandX = Math.max(3, bw * 0.15f), bandY = Math.max(3, bh * 0.15f);
        // each line: y = a*x + b (top/bottom) or x = a*y + b (left/right)
        float[] top = fit(luma, w, h, true, x0 + bw * 0.15f, x1 - bw * 0.15f, y0 - bandY, y0 + bandY);
        float[] bottom = fit(luma, w, h, true, x0 + bw * 0.15f, x1 - bw * 0.15f, y1 - bandY, y1 + bandY);
        float[] left = fit(luma, w, h, false, y0 + bh * 0.15f, y1 - bh * 0.15f, x0 - bandX, x0 + bandX);
        float[] right = fit(luma, w, h, false, y0 + bh * 0.15f, y1 - bh * 0.15f, x1 - bandX, x1 + bandX);
        if (top == null || bottom == null || left == null || right == null) return null;
        float[] q = new float[8];
        if (!intersect(top, left, q, 0) || !intersect(top, right, q, 2)
                || !intersect(bottom, right, q, 4) || !intersect(bottom, left, q, 6)) return null;
        // plausibility: corners near the box, quad not collapsed
        for (int i = 0; i < 4; i++) {
            if (Math.abs(q[2 * i] - (i == 0 || i == 3 ? x0 : x1)) > bw * 0.3f) return null;
            if (Math.abs(q[2 * i + 1] - (i < 2 ? y0 : y1)) > bh * 0.3f) return null;
        }
        float area = 0.5f * Math.abs((q[0] * q[3] - q[2] * q[1]) + (q[2] * q[5] - q[4] * q[3])
                + (q[4] * q[7] - q[6] * q[5]) + (q[6] * q[1] - q[0] * q[7]));
        if (area < bw * bh * 0.5f) return null;
        return q;
    }

    /**
     * Along the main axis from s0 to s1, find in each column (or row) the position in [t0,t1]
     * with the strongest perpendicular edge, then fit a line with outlier rejection.
     */
    private static float[] fit(float[] l, int w, int h, boolean horizontalLine, float s0, float s1, float t0, float t1) {
        int n = 0;
        float[] ss = new float[256], ts = new float[256], ws = new float[256];
        int is0 = Math.max(1, Math.round(s0)), is1 = Math.min((horizontalLine ? w : h) - 2, Math.round(s1));
        int it0 = Math.max(1, Math.round(t0)), it1 = Math.min((horizontalLine ? h : w) - 2, Math.round(t1));
        if (it1 - it0 < 2) return null;
        for (int s = is0; s <= is1 && n < 256; s++) {
            float best = 0f;
            int bt = -1;
            for (int t = it0; t <= it1; t++) {
                float g = horizontalLine
                        ? Math.abs(l[(t + 1) * w + s] - l[(t - 1) * w + s])
                        : Math.abs(l[s * w + t + 1] - l[s * w + t - 1]);
                if (g > best) {
                    best = g;
                    bt = t;
                }
            }
            if (bt >= 0 && best > 0.04f) {
                ss[n] = s;
                ts[n] = bt;
                ws[n] = best;
                n++;
            }
        }
        if (n < Math.max(5, (is1 - is0) * 0.3f)) return null;
        // iterative least squares, dropping points far from the line
        boolean[] use = new boolean[n];
        java.util.Arrays.fill(use, true);
        float a = 0f, b = 0f;
        for (int iter = 0; iter < 4; iter++) {
            double sw = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                if (!use[i]) continue;
                double wgt = ws[i];
                sw += wgt;
                sx += wgt * ss[i];
                sy += wgt * ts[i];
                sxx += wgt * ss[i] * ss[i];
                sxy += wgt * ss[i] * ts[i];
            }
            double den = sw * sxx - sx * sx;
            if (sw <= 0 || Math.abs(den) < 1e-9) return null;
            a = (float) ((sw * sxy - sx * sy) / den);
            b = (float) ((sy - a * sx) / sw);
            int kept = 0;
            for (int i = 0; i < n; i++) {
                use[i] = Math.abs(a * ss[i] + b - ts[i]) < 2.0f;
                if (use[i]) kept++;
            }
            if (kept < 5) return null;
        }
        if (Math.abs(a) > 0.6f) return null; // a TV edge isn't that steep
        return new float[]{a, b};
    }

    /** Intersects y = a1*x + b1 (horizontal-ish) with x = a2*y + b2 (vertical-ish). */
    private static boolean intersect(float[] hl, float[] vl, float[] out, int o) {
        float den = 1f - vl[0] * hl[0];
        if (Math.abs(den) < 1e-4f) return false;
        float x = (vl[0] * hl[1] + vl[1]) / den;
        float y = hl[0] * x + hl[1];
        out[o] = x;
        out[o + 1] = y;
        return true;
    }
}
