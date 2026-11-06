package app.huebeat.core;

import java.util.Arrays;

/**
 * Keeps the TV's four corners locked in the camera image while the phone moves.
 *
 * - Detections (an ML bounding box, or a quad from the fallback heuristic) arrive a few times per
 *   second, possibly late. The box is turned into precise corners by fitting straight lines to
 *   the TV's edges, then moved forward by the camera motion that happened since that frame.
 * - Between detections, distinctive points around the TV (bezel corners, furniture, wall texture,
 *   but not the changing picture) are followed frame to frame; an affine fit of their motion
 *   moves the corners, so sliding, rotating and zooming are all followed.
 *
 * Coordinates are pixels of the luma frame (w×h). Not thread-safe: call from one thread.
 */
public final class TvTracker {
    private static final int MAX_FEATURES = 40;
    private static final int PATCH = 4;          // patch radius (9×9)
    private static final int HISTORY = 64;

    private final int w, h;
    private float[] prev;
    private float[] quad;                          // TL TR BR BL
    private boolean manual = false;
    private boolean lost = true;
    private int failStreak = 0;

    private final float[] fx = new float[MAX_FEATURES], fy = new float[MAX_FEATURES];
    private int nf = 0;
    private int framesSinceSelect = 0;
    private float[] keyframe;                      // luma of the frame the points were picked in
    private float[] kfQuad;                        // quad in the keyframe
    private double[] kfA = {1, 0, 0, 0, 1, 0};     // keyframe -> current frame
    private boolean needReselect = false;

    private long frame = 0;
    // cumulative affine transform per frame (reference -> frame): a b c d e f
    private final double[][] cum = new double[HISTORY][];
    private final long[] cumFrame = new long[HISTORY];
    private double[] cur = {1, 0, 0, 0, 1, 0};

    private int candidateHits = 0;
    private float[] candidate;

    /** Last frame's number of tracked points (for debugging / UI). */
    public int trackedPoints = 0;

    public TvTracker(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public synchronized float[] quad() {
        return quad == null ? null : quad.clone();
    }

    public synchronized boolean isLost() {
        return lost;
    }

    public synchronized boolean isManual() {
        return manual;
    }

    public synchronized long frameNumber() {
        return frame;
    }

    public synchronized void setManualQuad(float[] q) {
        quad = q.clone();
        manual = true;
        lost = false;
        if (prev != null) selectFeatures(prev);
    }

    public synchronized void autoDetect() {
        manual = false;
        candidateHits = 0;
    }

    // ------------------------------------------------------------------ per frame

    /** @param luma brightness 0..1, w*h */
    public synchronized void process(float[] luma) {
        frame++;
        if (prev != null && quad != null && nf >= 6) {
            track(prev, luma);
        } else if (prev != null && quad != null) {
            failStreak++;
        }
        if (failStreak > 8) lost = true;
        if (prev == null) prev = new float[w * h];
        System.arraycopy(luma, 0, prev, 0, w * h);
        int slot = (int) (frame % HISTORY);
        cum[slot] = cur.clone();
        cumFrame[slot] = frame;
        framesSinceSelect++;
        if (quad != null && !lost && (nf < 15 || needReselect)) selectFeatures(luma);
    }

    /**
     * Matches the keyframe's points in the new frame. Comparing against the keyframe (not the
     * previous frame) avoids slowly accumulating drift; the keyframe is renewed regularly.
     */
    private void track(float[] a, float[] b) {
        int[] g = coarseShift(a, b);
        // where each keyframe point should be now: last known transform plus this frame's shift
        double[] pred = kfA.clone();
        pred[2] += g[0];
        pred[5] += g[1];
        float[] mx = new float[nf], my = new float[nf];
        boolean[] ok = new boolean[nf];
        int good = 0;
        for (int i = 0; i < nf; i++) {
            int kx = Math.round(fx[i]), ky = Math.round(fy[i]);
            int px = (int) Math.round(pred[0] * fx[i] + pred[1] * fy[i] + pred[2]);
            int py = (int) Math.round(pred[3] * fx[i] + pred[4] * fy[i] + pred[5]);
            float best = Float.MAX_VALUE;
            int bx = 0, by = 0;
            int r = 5;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int qx = px + dx, qy = py + dy;
                    if (qx < PATCH || qy < PATCH || qx >= w - PATCH || qy >= h - PATCH) continue;
                    float s = ssd(keyframe, kx, ky, b, qx, qy);
                    if (s < best) {
                        best = s;
                        bx = qx;
                        by = qy;
                    }
                }
            }
            if (best == Float.MAX_VALUE) continue;
            mx[i] = bx + parab(ssdSafe(keyframe, kx, ky, b, bx - 1, by), best, ssdSafe(keyframe, kx, ky, b, bx + 1, by));
            my[i] = by + parab(ssdSafe(keyframe, kx, ky, b, bx, by - 1), best, ssdSafe(keyframe, kx, ky, b, bx, by + 1));
            ok[i] = best < 0.6f;
            if (ok[i]) good++;
        }
        double[] A = fitAffine(ok, mx, my);
        double[] before = kfA;
        if (A == null) {
            failStreak++;
            kfA = pred;
        } else {
            failStreak = 0;
            kfA = A;
            int inl = 0;
            for (boolean o : ok) if (o) inl++;
            good = inl;
        }
        trackedPoints = good;
        // quad = transform(keyframe quad); frame-to-frame increment for latency compensation
        for (int i = 0; i < 4; i++) {
            double x = kfQuad[2 * i], y = kfQuad[2 * i + 1];
            quad[2 * i] = (float) (kfA[0] * x + kfA[1] * y + kfA[2]);
            quad[2 * i + 1] = (float) (kfA[3] * x + kfA[4] * y + kfA[5]);
        }
        compose(mul(kfA, inv(before)));
        if (good < 12 || framesSinceSelect > 30) needReselect = true;
    }

    private static double[] mul(double[] A, double[] B) {
        return new double[]{
                A[0] * B[0] + A[1] * B[3], A[0] * B[1] + A[1] * B[4], A[0] * B[2] + A[1] * B[5] + A[2],
                A[3] * B[0] + A[4] * B[3], A[3] * B[1] + A[4] * B[4], A[3] * B[2] + A[4] * B[5] + A[5]};
    }

    private static double[] inv(double[] o) {
        double det = o[0] * o[4] - o[1] * o[3];
        if (Math.abs(det) < 1e-12) return new double[]{1, 0, 0, 0, 1, 0};
        double ia = o[4] / det, ib = -o[1] / det, id = -o[3] / det, ie = o[0] / det;
        return new double[]{ia, ib, -(ia * o[2] + ib * o[5]), id, ie, -(id * o[2] + ie * o[5])};
    }

    private double[] fitAffine(boolean[] ok, float[] mx, float[] my) {
        boolean[] use = ok.clone();
        double[] A = null;
        for (int round = 0; round < 3; round++) {
            A = solveAffine(use, mx, my);
            if (A == null) return null;
            // residuals → drop outliers
            float[] res = new float[nf];
            int cnt = 0;
            for (int i = 0; i < nf; i++) {
                if (!use[i]) continue;
                double ex = A[0] * fx[i] + A[1] * fy[i] + A[2] - mx[i];
                double ey = A[3] * fx[i] + A[4] * fy[i] + A[5] - my[i];
                res[cnt++] = (float) Math.hypot(ex, ey);
            }
            float[] sorted = Arrays.copyOf(res, cnt);
            Arrays.sort(sorted);
            float lim = Math.max(1.5f, sorted[cnt / 2] * 2.5f);
            int j = 0;
            boolean changed = false;
            for (int i = 0; i < nf; i++) {
                if (!use[i]) continue;
                if (res[j++] > lim) {
                    use[i] = false;
                    ok[i] = false;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        A = solveAffine(use, mx, my);
        if (A == null) return null;
        // sanity: no wild zoom/shear in one frame
        double sx = Math.hypot(A[0], A[3]), sy = Math.hypot(A[1], A[4]);
        if (sx < 0.6 || sx > 1.6 || sy < 0.6 || sy > 1.6) return null;
        return A;
    }

    private double[] solveAffine(boolean[] use, float[] mx, float[] my) {
        double sxx = 0, sxy = 0, syy = 0, sx = 0, sy = 0, n = 0;
        double bx0 = 0, bx1 = 0, bx2 = 0, by0 = 0, by1 = 0, by2 = 0;
        for (int i = 0; i < nf; i++) {
            if (!use[i]) continue;
            double x = fx[i], y = fy[i];
            sxx += x * x;
            sxy += x * y;
            syy += y * y;
            sx += x;
            sy += y;
            n++;
            bx0 += x * mx[i];
            bx1 += y * mx[i];
            bx2 += mx[i];
            by0 += x * my[i];
            by1 += y * my[i];
            by2 += my[i];
        }
        if (n < 6) return null;
        double[][] M = {{sxx, sxy, sx}, {sxy, syy, sy}, {sx, sy, n}};
        double[] r1 = solve3(M, new double[]{bx0, bx1, bx2});
        double[] r2 = solve3(M, new double[]{by0, by1, by2});
        if (r1 == null || r2 == null) return null;
        return new double[]{r1[0], r1[1], r1[2], r2[0], r2[1], r2[2]};
    }

    private static double[] solve3(double[][] m, double[] b) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (Math.abs(det) < 1e-9) return null;
        double[] r = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] t = {m[0].clone(), m[1].clone(), m[2].clone()};
            for (int k = 0; k < 3; k++) t[k][c] = b[k];
            r[c] = (t[0][0] * (t[1][1] * t[2][2] - t[1][2] * t[2][1])
                    - t[0][1] * (t[1][0] * t[2][2] - t[1][2] * t[2][0])
                    + t[0][2] * (t[1][0] * t[2][1] - t[1][1] * t[2][0])) / det;
        }
        return r;
    }

    /** After the quad was corrected, express it in keyframe coordinates so tracking keeps the fix. */
    private void syncKeyframeQuad() {
        if (kfQuad == null || quad == null) return;
        double[] I = inv(kfA);
        for (int i = 0; i < 4; i++) {
            double x = quad[2 * i], y = quad[2 * i + 1];
            kfQuad[2 * i] = (float) (I[0] * x + I[1] * y + I[2]);
            kfQuad[2 * i + 1] = (float) (I[3] * x + I[4] * y + I[5]);
        }
    }

    private void applyToQuad(double[] A) {
        if (quad == null) return;
        for (int i = 0; i < 4; i++) {
            double x = quad[2 * i], y = quad[2 * i + 1];
            quad[2 * i] = (float) (A[0] * x + A[1] * y + A[2]);
            quad[2 * i + 1] = (float) (A[3] * x + A[4] * y + A[5]);
        }
    }

    private void shiftQuad(float dx, float dy) {
        if (quad == null) return;
        for (int i = 0; i < 4; i++) {
            quad[2 * i] += dx;
            quad[2 * i + 1] += dy;
        }
    }

    /** cur = A ∘ cur */
    private void compose(double[] A) {
        double[] c = cur;
        cur = new double[]{
                A[0] * c[0] + A[1] * c[3], A[0] * c[1] + A[1] * c[4], A[0] * c[2] + A[1] * c[5] + A[2],
                A[3] * c[0] + A[4] * c[3], A[3] * c[1] + A[4] * c[4], A[3] * c[2] + A[4] * c[5] + A[5]};
    }

    /** Transform that maps positions in frame {@code f} to the current frame, or null if too old. */
    private double[] sinceFrame(long f) {
        int slot = (int) (f % HISTORY);
        if (f <= 0 || cumFrame[slot] != f || cum[slot] == null) return null;
        double[] o = cum[slot];
        // inverse of o (2×3 affine)
        double det = o[0] * o[4] - o[1] * o[3];
        if (Math.abs(det) < 1e-12) return null;
        double ia = o[4] / det, ib = -o[1] / det, id = -o[3] / det, ie = o[0] / det;
        double ic = -(ia * o[2] + ib * o[5]), iff = -(id * o[2] + ie * o[5]);
        double[] c = cur;
        return new double[]{
                c[0] * ia + c[1] * id, c[0] * ib + c[1] * ie, c[0] * ic + c[1] * iff + c[2],
                c[3] * ia + c[4] * id, c[3] * ib + c[4] * ie, c[3] * ic + c[4] * iff + c[5]};
    }

    // ------------------------------------------------------------------ detections

    /**
     * An ML bounding box of the TV found in frame {@code frameNo} (whose luma is {@code luma}).
     * The corners are fitted to the TV's edges in that frame, then moved to the current frame.
     */
    public synchronized void onBox(float x0, float y0, float x1, float y1, float score, long frameNo, float[] luma) {
        if (manual) return;
        float[] prior = new float[]{x0, y0, x1, y0, x1, y1, x0, y1};
        // If we already track the TV and the box agrees, refine around our (possibly tilted)
        // outline as it was in that frame; the box itself is always axis-aligned.
        double[] T = frameNo == frame ? new double[]{1, 0, 0, 0, 1, 0} : sinceFrame(frameNo);
        if (quad != null && !lost && T != null) {
            double[] I = inv(T);
            float[] back = new float[8];
            float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                double x = quad[2 * i], y = quad[2 * i + 1];
                back[2 * i] = (float) (I[0] * x + I[1] * y + I[2]);
                back[2 * i + 1] = (float) (I[3] * x + I[4] * y + I[5]);
                bx0 = Math.min(bx0, back[2 * i]);
                bx1 = Math.max(bx1, back[2 * i]);
                by0 = Math.min(by0, back[2 * i + 1]);
                by1 = Math.max(by1, back[2 * i + 1]);
            }
            if (iou(x0, y0, x1, y1, bx0, by0, bx1, by1) > 0.5f) prior = back;
        }
        float[] q = fitCorners(luma, prior);
        accept(q, score, frameNo);
    }

    private static float iou(float a0, float b0, float a1, float b1, float c0, float d0, float c1, float d1) {
        float ix = Math.max(0, Math.min(a1, c1) - Math.max(a0, c0)), iy = Math.max(0, Math.min(b1, d1) - Math.max(b0, d0));
        float inter = ix * iy, uni = (a1 - a0) * (b1 - b0) + (c1 - c0) * (d1 - d0) - inter;
        return uni > 0 ? inter / uni : 0f;
    }

    /** A detected quad (e.g. from the brightness/motion heuristic) found in frame {@code frameNo}. */
    public synchronized void onQuad(float[] q, float score, long frameNo) {
        if (manual) return;
        accept(q.clone(), score, frameNo);
    }

    private void accept(float[] q, float score, long frameNo) {
        double[] T = frameNo == frame ? new double[]{1, 0, 0, 0, 1, 0} : sinceFrame(frameNo);
        if (T == null) return; // too old
        for (int i = 0; i < 4; i++) {
            double x = q[2 * i], y = q[2 * i + 1];
            q[2 * i] = (float) (T[0] * x + T[1] * y + T[2]);
            q[2 * i + 1] = (float) (T[3] * x + T[4] * y + T[5]);
        }
        if (quad == null || lost) {
            quad = q;
            lost = false;
            failStreak = 0;
            candidateHits = 0;
            if (prev != null) selectFeatures(prev);
            return;
        }
        float dist = 0f;
        for (int i = 0; i < 8; i++) dist = Math.max(dist, Math.abs(q[i] - quad[i]));
        if (dist < w * 0.1f) {
            float k = 0.25f + 0.35f * Math.min(1f, score);
            for (int i = 0; i < 8; i++) quad[i] += (q[i] - quad[i]) * k;
            candidateHits = 0;
            syncKeyframeQuad();
        } else {
            if (candidate != null) {
                float cd = 0f;
                for (int i = 0; i < 8; i++) cd = Math.max(cd, Math.abs(q[i] - candidate[i]));
                candidateHits = cd < w * 0.1f ? candidateHits + 1 : 1;
            } else {
                candidateHits = 1;
            }
            candidate = q;
            if (candidateHits >= 2 || score > 0.7f) {
                quad = q;
                candidateHits = 0;
                if (prev != null) selectFeatures(prev);
            }
        }
    }

    /**
     * Fits straight lines to the four TV edges near a prior outline (TL TR BR BL) and intersects
     * them. Each edge is searched along its normal for the strongest brightness step.
     */
    float[] fitCorners(float[] L, float[] prior) {
        float[][] lines = new float[4][];
        for (int s = 0; s < 4; s++) {
            int a = s, b = (s + 1) % 4;
            lines[s] = edgeLine(L, prior[2 * a], prior[2 * a + 1], prior[2 * b], prior[2 * b + 1], prior);
        }
        float[] q = new float[8];
        float diag = (float) Math.hypot(prior[4] - prior[0], prior[5] - prior[1]);
        for (int i = 0; i < 4; i++) {
            // corner i lies between side i-1 (ending at i) and side i (starting at i)
            float[] l1 = lines[(i + 3) % 4], l2 = lines[i];
            float[] c = (l1 != null && l2 != null) ? intersect(l1, l2) : null;
            if (c == null || Math.hypot(c[0] - prior[2 * i], c[1] - prior[2 * i + 1]) > diag * 0.15f) {
                c = new float[]{prior[2 * i], prior[2 * i + 1]};
            }
            q[2 * i] = c[0];
            q[2 * i + 1] = c[1];
        }
        return q;
    }

    /** Line through the strongest edge near segment p→q, as {px, py, dx, dy} (point + direction). */
    private float[] edgeLine(float[] L, float px, float py, float qx, float qy, float[] prior) {
        float dx = qx - px, dy = qy - py;
        float len = (float) Math.hypot(dx, dy);
        if (len < 8) return null;
        dx /= len;
        dy /= len;
        float nx = -dy, ny = dx; // normal
        // search ±10% of the other side's length, at least 4 px
        float other = (float) Math.hypot(prior[4] - prior[2], prior[5] - prior[3]) + (float) Math.hypot(prior[6] - prior[0], prior[7] - prior[1]);
        int win = Math.max(4, Math.round(other / 2 * 0.1f));
        float[] xs = new float[80], ys = new float[80];
        int n = 0;
        for (int k = 0; k < 80; k++) {
            float t = 0.1f + 0.8f * k / 79f;
            float cx = px + dx * len * t, cy = py + dy * len * t;
            float best = 0.05f;
            int bestO = Integer.MIN_VALUE;
            for (int o = -win; o <= win; o++) {
                float ax = cx + nx * (o - 1), ay = cy + ny * (o - 1), bx = cx + nx * (o + 1), by = cy + ny * (o + 1);
                int iax = Math.round(ax), iay = Math.round(ay), ibx = Math.round(bx), iby = Math.round(by);
                if (iax < 0 || iay < 0 || ibx < 0 || iby < 0 || iax >= w || ibx >= w || iay >= h || iby >= h) continue;
                float gr = Math.abs(L[iby * w + ibx] - L[iay * w + iax]);
                if (gr > best) {
                    best = gr;
                    bestO = o;
                }
            }
            if (bestO != Integer.MIN_VALUE) {
                xs[n] = cx + nx * bestO;
                ys[n] = cy + ny * bestO;
                n++;
            }
        }
        if (n < 10) return null;
        boolean[] use = new boolean[n];
        java.util.Arrays.fill(use, true);
        float[] line = null;
        for (int round = 0; round < 3; round++) {
            // total least squares line through the used points
            double mx = 0, my = 0, c = 0;
            for (int i = 0; i < n; i++) if (use[i]) { mx += xs[i]; my += ys[i]; c++; }
            if (c < 8) return null;
            mx /= c;
            my /= c;
            double sxx = 0, syy = 0, sxy = 0;
            for (int i = 0; i < n; i++) {
                if (!use[i]) continue;
                double ux = xs[i] - mx, uy = ys[i] - my;
                sxx += ux * ux;
                syy += uy * uy;
                sxy += ux * uy;
            }
            double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy);
            float lx = (float) Math.cos(ang), ly = (float) Math.sin(ang);
            line = new float[]{(float) mx, (float) my, lx, ly};
            for (int i = 0; i < n; i++) {
                float dist = Math.abs((xs[i] - line[0]) * -ly + (ys[i] - line[1]) * lx);
                use[i] = dist <= 1.5f;
            }
        }
        // the fitted edge must run roughly along the prior side
        if (Math.abs(line[2] * dx + line[3] * dy) < 0.95f) return null;
        return line;
    }

    private static float[] intersect(float[] a, float[] b) {
        float den = a[2] * b[3] - a[3] * b[2];
        if (Math.abs(den) < 1e-6f) return null;
        float t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / den;
        return new float[]{a[0] + a[2] * t, a[1] + a[3] * t};
    }

    // ------------------------------------------------------------------ features

    private void selectFeatures(float[] L) {
        framesSinceSelect = 0;
        needReselect = false;
        if (keyframe == null) keyframe = new float[w * h];
        System.arraycopy(L, 0, keyframe, 0, w * h);
        kfA = new double[]{1, 0, 0, 0, 1, 0};
        kfQuad = quad == null ? null : quad.clone();
        if (quad == null) {
            nf = 0;
            return;
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, quad[2 * i]);
            maxX = Math.max(maxX, quad[2 * i]);
            minY = Math.min(minY, quad[2 * i + 1]);
            maxY = Math.max(maxY, quad[2 * i + 1]);
            cx += quad[2 * i] / 4;
            cy += quad[2 * i + 1] / 4;
        }
        float qw = maxX - minX, qh = maxY - minY;
        int x0 = Math.max(PATCH + 3, Math.round(minX - qw * 0.45f)), x1 = Math.min(w - PATCH - 4, Math.round(maxX + qw * 0.45f));
        int y0 = Math.max(PATCH + 3, Math.round(minY - qh * 0.45f)), y1 = Math.min(h - PATCH - 4, Math.round(maxY + qh * 0.45f));
        // inner screen area (changing picture) is excluded
        float[] inner = new float[8];
        for (int i = 0; i < 4; i++) {
            inner[2 * i] = cx + (quad[2 * i] - cx) * 0.88f;
            inner[2 * i + 1] = cy + (quad[2 * i + 1] - cy) * 0.88f;
        }
        int cap = 600;
        float[] sc = new float[cap];
        int[] px = new int[cap], py = new int[cap];
        int cnt = 0;
        for (int y = y0; y <= y1; y += 3) {
            for (int x = x0; x <= x1; x += 3) {
                if (inQuad(inner, x, y)) continue;
                float sxx = 0, syy = 0, sxy = 0;
                for (int yy = -2; yy <= 2; yy++) {
                    for (int xx = -2; xx <= 2; xx++) {
                        int i = (y + yy) * w + x + xx;
                        float gx = L[i + 1] - L[i - 1], gy = L[i + w] - L[i - w];
                        sxx += gx * gx;
                        syy += gy * gy;
                        sxy += gx * gy;
                    }
                }
                float tr = (sxx + syy) / 2, d = (float) Math.sqrt((sxx - syy) * (sxx - syy) / 4 + sxy * sxy);
                float minEig = tr - d;
                if (minEig < 0.002f) continue;
                if (cnt < cap) {
                    sc[cnt] = minEig;
                    px[cnt] = x;
                    py[cnt] = y;
                    cnt++;
                } else {
                    int worst = 0;
                    for (int i = 1; i < cap; i++) if (sc[i] < sc[worst]) worst = i;
                    if (minEig > sc[worst]) {
                        sc[worst] = minEig;
                        px[worst] = x;
                        py[worst] = y;
                    }
                }
            }
        }
        // strongest first, keep them apart
        Integer[] order = new Integer[cnt];
        for (int i = 0; i < cnt; i++) order[i] = i;
        final float[] fsc = sc;
        Arrays.sort(order, (p1, p2) -> Float.compare(fsc[p2], fsc[p1]));
        nf = 0;
        float minDist = Math.max(6f, Math.min(qw, qh) * 0.12f);
        for (int idx = 0; idx < cnt && nf < MAX_FEATURES; idx++) {
            int i = order[idx];
            boolean far = true;
            for (int j = 0; j < nf; j++) {
                if (Math.abs(fx[j] - px[i]) < minDist && Math.abs(fy[j] - py[i]) < minDist) {
                    far = false;
                    break;
                }
            }
            if (!far) continue;
            fx[nf] = px[i];
            fy[nf] = py[i];
            nf++;
        }
    }

    private static boolean inQuad(float[] q, float x, float y) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            float ax = q[2 * i], ay = q[2 * i + 1], bx = q[(2 * i + 2) % 8], by = q[(2 * i + 3) % 8];
            float cr = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
            if (cr > 0) pos = true;
            if (cr < 0) neg = true;
        }
        return !(pos && neg);
    }

    // ------------------------------------------------------------------ matching helpers

    private float ssd(float[] a, int ax, int ay, float[] b, int bx, int by) {
        float s = 0f, ma = 0f, mb = 0f;
        // compare mean-removed patches, so brightness changes (auto exposure, lights) don't matter
        for (int y = -PATCH; y <= PATCH; y++) {
            int ia = (ay + y) * w + ax, ib = (by + y) * w + bx;
            for (int x = -PATCH; x <= PATCH; x++) {
                ma += a[ia + x];
                mb += b[ib + x];
            }
        }
        int cnt = (2 * PATCH + 1) * (2 * PATCH + 1);
        ma /= cnt;
        mb /= cnt;
        float va = 0f;
        for (int y = -PATCH; y <= PATCH; y++) {
            int ia = (ay + y) * w + ax, ib = (by + y) * w + bx;
            for (int x = -PATCH; x <= PATCH; x++) {
                float da = a[ia + x] - ma, db = b[ib + x] - mb;
                s += (da - db) * (da - db);
                va += da * da;
            }
        }
        return s / Math.max(va, 1e-4f); // normalised: 0 = identical, ~2 = unrelated
    }

    private float ssdSafe(float[] a, int ax, int ay, float[] b, int bx, int by) {
        if (bx < PATCH || by < PATCH || bx >= w - PATCH || by >= h - PATCH) return Float.MAX_VALUE;
        return ssd(a, ax, ay, b, bx, by);
    }

    private static float parab(float em, float e0, float ep) {
        if (em == Float.MAX_VALUE || ep == Float.MAX_VALUE) return 0f;
        float den = em - 2 * e0 + ep;
        if (!(den > 1e-9f)) return 0f;
        return Math.max(-0.5f, Math.min(0.5f, 0.5f * (em - ep) / den));
    }

    /** Global shift between two frames from a 4×-downsampled search (±24 px). */
    private int[] coarseShift(float[] a, float[] b) {
        int cw = w / 4, ch = h / 4;
        float[] da = down(a, cw, ch), db = down(b, cw, ch);
        float best = Float.MAX_VALUE;
        int bx = 0, by = 0;
        for (int sy = -6; sy <= 6; sy++) {
            for (int sx = -6; sx <= 6; sx++) {
                float s = 0f;
                int c = 0;
                for (int y = Math.max(0, sy); y < Math.min(ch, ch + sy); y++) {
                    for (int x = Math.max(0, sx); x < Math.min(cw, cw + sx); x++) {
                        s += Math.abs(db[y * cw + x] - da[(y - sy) * cw + x - sx]);
                        c++;
                    }
                }
                if (c < cw * ch / 3) continue;
                s /= c;
                if (s < best) {
                    best = s;
                    bx = sx;
                    by = sy;
                }
            }
        }
        return new int[]{bx * 4, by * 4};
    }

    private float[] down(float[] a, int cw, int ch) {
        float[] o = new float[cw * ch];
        for (int y = 0; y < ch; y++)
            for (int x = 0; x < cw; x++) {
                float s = 0f;
                for (int yy = 0; yy < 4; yy++)
                    for (int xx = 0; xx < 4; xx++) s += a[(y * 4 + yy) * w + x * 4 + xx];
                o[y * cw + x] = s / 16f;
            }
        return o;
    }

    /** Samples the TV picture as a gw×gh grid (rgb 0..1) from an RGB frame (0xRRGGBB, w×h). */
    public boolean sampleGrid(int[] rgb, int gw, int gh, float[] out) {
        float[] q = quad();
        if (q == null) return false;
        float inset = 0.06f;
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                double r = 0, g = 0, b = 0;
                int cnt = 0;
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 3; sx++) {
                        float u = inset + (1 - 2 * inset) * (gx + (sx + 0.5f) / 3f) / gw;
                        float v = inset + (1 - 2 * inset) * (gy + (sy + 0.5f) / 3f) / gh;
                        float x = (1 - u) * (1 - v) * q[0] + u * (1 - v) * q[2] + u * v * q[4] + (1 - u) * v * q[6];
                        float y = (1 - u) * (1 - v) * q[1] + u * (1 - v) * q[3] + u * v * q[5] + (1 - u) * v * q[7];
                        int ix = Math.round(x), iy = Math.round(y);
                        if (ix < 0 || iy < 0 || ix >= w || iy >= h) continue;
                        int c = rgb[iy * w + ix];
                        r += ((c >> 16) & 0xFF) / 255.0;
                        g += ((c >> 8) & 0xFF) / 255.0;
                        b += (c & 0xFF) / 255.0;
                        cnt++;
                    }
                }
                int o = 3 * (gy * gw + gx);
                out[o] = cnt > 0 ? (float) (r / cnt) : 0f;
                out[o + 1] = cnt > 0 ? (float) (g / cnt) : 0f;
                out[o + 2] = cnt > 0 ? (float) (b / cnt) : 0f;
            }
        }
        return true;
    }
}
