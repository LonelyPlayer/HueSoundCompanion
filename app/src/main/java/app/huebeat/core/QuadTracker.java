package app.huebeat.core;

import java.util.Random;

/**
 * Follows the TV outline while the phone moves, rotates or zooms.
 *
 * It tracks distinctive points around the TV (bezel corners, furniture, wall edges; not the
 * picture itself, which changes all the time) from frame to frame, fits an affine motion to
 * them with RANSAC (so a few wrong matches don't matter) and moves the four corners with it.
 */
public final class QuadTracker {
    private static final int MAX_POINTS = 70, R = 4; // patch radius → 9×9 patches
    private final int w, h;
    private float[] quad;
    private float[] px = new float[MAX_POINTS], py = new float[MAX_POINTS];
    private int count = 0;
    private float[] prev;
    private final Random rnd = new Random(7);
    /** Points that agreed with the last motion estimate. */
    public int inliers = 0;
    /** True if the last frame could be followed. */
    public boolean ok = false;
    public String why = "";

    public QuadTracker(int w, int h) {
        this.w = w;
        this.h = h;
    }

    public synchronized float[] quad() {
        return quad == null ? null : quad.clone();
    }

    /** Replaces the outline (from a detection or the user) and picks new points to follow. */
    public synchronized void setQuad(float[] q, float[] luma) {
        quad = q.clone();
        prev = luma.clone();
        seed(luma);
        ok = true;
    }

    /** Nudges the outline toward a fresh detection without re-seeding the points. */
    public synchronized void blendQuad(float[] q, float amount) {
        if (quad == null) {
            quad = q.clone();
            return;
        }
        for (int i = 0; i < 8; i++) quad[i] += (q[i] - quad[i]) * amount;
    }

    public synchronized void reset() {
        quad = null;
        count = 0;
        ok = false;
    }

    /** @return true if the outline was moved along with the camera */
    public synchronized boolean track(float[] luma) {
        if (quad == null || prev == null) {
            prev = luma.clone();
            return false;
        }
        // coarse global shift first, so fast moves still land inside each point's search window
        int[] g = coarseShift(prev, luma);
        float[] nx = new float[count], ny = new float[count];
        boolean[] found = new boolean[count];
        int matched = 0;
        for (int i = 0; i < count; i++) {
            float[] m = matchPoint(prev, luma, px[i], py[i], px[i] + g[0], py[i] + g[1]);
            if (m != null) {
                nx[i] = m[0];
                ny[i] = m[1];
                found[i] = true;
                matched++;
            }
        }
        prev = luma.clone();
        if (matched < 6) {
            why = "matched " + matched + "/" + count;
            shiftOnly(g);
            inliers = 0;
            return false;
        }
        // RANSAC affine fit
        float[] best = null;
        int bestIn = 0;
        int[] idx = new int[matched];
        int k = 0;
        for (int i = 0; i < count; i++) if (found[i]) idx[k++] = i;
        for (int it = 0; it < 80; it++) {
            int a = idx[rnd.nextInt(matched)], b = idx[rnd.nextInt(matched)], c = idx[rnd.nextInt(matched)];
            if (a == b || b == c || a == c) continue;
            float[] A = affineFrom3(px[a], py[a], nx[a], ny[a], px[b], py[b], nx[b], ny[b], px[c], py[c], nx[c], ny[c]);
            if (A == null) continue;
            int in = 0;
            for (int j : idx) if (err(A, px[j], py[j], nx[j], ny[j]) < 1.5f) in++;
            if (in > bestIn) {
                bestIn = in;
                best = A;
            }
        }
        if (best == null || bestIn < Math.max(5, matched * 0.35f)) {
            why = "ransac " + bestIn + "/" + matched;
            shiftOnly(g);
            inliers = bestIn;
            return false;
        }
        // least-squares refit on the inliers
        float[] refined = fitAffine(best, idx, nx, ny);
        if (refined != null) best = refined;
        // sanity: no wild scale/shear jumps between two frames
        float sx = (float) Math.hypot(best[0], best[3]), sy = (float) Math.hypot(best[1], best[4]);
        if (sx < 0.8f || sx > 1.25f || sy < 0.8f || sy > 1.25f) {
            why = "scale " + sx + "," + sy;
            shiftOnly(g);
            return false;
        }
        for (int i = 0; i < 4; i++) {
            float x = quad[2 * i], y = quad[2 * i + 1];
            quad[2 * i] = best[0] * x + best[1] * y + best[2];
            quad[2 * i + 1] = best[3] * x + best[4] * y + best[5];
        }
        // keep the points that agree, then top up with fresh ones
        int keep = 0;
        for (int i = 0; i < count; i++) {
            if (found[i] && err(best, px[i], py[i], nx[i], ny[i]) < 1.5f && inside(nx[i], ny[i])) {
                px[keep] = nx[i];
                py[keep] = ny[i];
                keep++;
            }
        }
        count = keep;
        inliers = bestIn;
        ok = true;
        if (count < MAX_POINTS / 2) seed(luma);
        return true;
    }

    /** Point tracking failed: at least move everything by the coarse camera shift, then re-seed. */
    private void shiftOnly(int[] g) {
        ok = false;
        for (int i = 0; i < 4; i++) {
            quad[2 * i] += g[0];
            quad[2 * i + 1] += g[1];
        }
        count = 0;
        seed(prev);
    }

    private boolean inside(float x, float y) {
        return x >= R + 6 && y >= R + 6 && x < w - R - 6 && y < h - R - 6;
    }

    /** Picks well-textured points in a ring around the TV: the bezel and the room, not the picture. */
    private void seed(float[] luma) {
        float cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) {
            cx += quad[2 * i] / 4;
            cy += quad[2 * i + 1] / 4;
        }
        int cell = 8;
        int added = 0;
        float[] candS = new float[(w / cell) * (h / cell)];
        int[] candX = new int[candS.length], candY = new int[candS.length];
        int nc = 0;
        for (int gy = 0; gy + cell <= h; gy += cell) {
            for (int gx = 0; gx + cell <= w; gx += cell) {
                float bestS = 0f;
                int bx = -1, by = -1;
                for (int y = gy; y < gy + cell; y++) {
                    for (int x = gx; x < gx + cell; x++) {
                        if (!inside(x, y)) continue;
                        float rel = ringPosition(x, y, cx, cy);
                        if (rel < 0.8f || rel > 1.6f) continue; // just inside the edge … well outside
                        float s = cornerness(luma, x, y);
                        if (s > bestS) {
                            bestS = s;
                            bx = x;
                            by = y;
                        }
                    }
                }
                if (bx >= 0 && bestS > 1e-4f) {
                    candS[nc] = bestS;
                    candX[nc] = bx;
                    candY[nc] = by;
                    nc++;
                }
            }
        }
        // strongest first, skipping places we already follow
        while (count < MAX_POINTS && added < MAX_POINTS) {
            int bi = -1;
            float bs = 0f;
            for (int i = 0; i < nc; i++) if (candS[i] > bs) { bs = candS[i]; bi = i; }
            if (bi < 0) break;
            candS[bi] = 0f;
            boolean near = false;
            for (int i = 0; i < count; i++) {
                if (Math.abs(px[i] - candX[bi]) < 5 && Math.abs(py[i] - candY[bi]) < 5) { near = true; break; }
            }
            if (near) continue;
            px[count] = candX[bi];
            py[count] = candY[bi];
            count++;
            added++;
        }
    }

    /** How far (x,y) is from the quad center, relative to the quad's extent in that direction (1 = on the edge). */
    private float ringPosition(float x, float y, float cx, float cy) {
        float dx = x - cx, dy = y - cy;
        float halfW = (Math.abs(quad[2] - quad[0]) + Math.abs(quad[4] - quad[6])) / 4f + 1f;
        float halfH = (Math.abs(quad[7] - quad[1]) + Math.abs(quad[5] - quad[3])) / 4f + 1f;
        return Math.max(Math.abs(dx) / halfW, Math.abs(dy) / halfH);
    }

    /** Smaller eigenvalue of the structure tensor over a 5×5 window (Shi–Tomasi). */
    private float cornerness(float[] l, int x, int y) {
        float a = 0, b = 0, c = 0;
        for (int yy = y - 2; yy <= y + 2; yy++) {
            for (int xx = x - 2; xx <= x + 2; xx++) {
                float gx = l[yy * w + xx + 1] - l[yy * w + xx - 1];
                float gy = l[(yy + 1) * w + xx] - l[(yy - 1) * w + xx];
                a += gx * gx;
                b += gx * gy;
                c += gy * gy;
            }
        }
        float t = (a + c) / 2f;
        return t - (float) Math.sqrt((a - c) * (a - c) / 4f + b * b);
    }

    /** Finds the point's 9×9 patch in the new frame near (gx,gy); sub-pixel; null if no good match. */
    private float[] matchPoint(float[] a, float[] b, float x, float y, float gx, float gy) {
        int ix = Math.round(x), iy = Math.round(y);
        if (!inside(ix, iy)) return null;
        int cx = Math.round(gx), cy = Math.round(gy);
        int sr = 5;
        float best = Float.MAX_VALUE, second = Float.MAX_VALUE;
        int bx = 0, by = 0;
        float[] errs = new float[(2 * sr + 1) * (2 * sr + 1)];
        for (int dy = -sr; dy <= sr; dy++) {
            for (int dx = -sr; dx <= sr; dx++) {
                int tx = cx + dx, ty = cy + dy;
                float e;
                if (!inside(tx, ty)) e = Float.MAX_VALUE;
                else {
                    e = 0f;
                    for (int py = -R; py <= R; py++) {
                        int ra = (iy + py) * w, rb = (ty + py) * w;
                        for (int pxx = -R; pxx <= R; pxx++) e += Math.abs(a[ra + ix + pxx] - b[rb + tx + pxx]);
                    }
                }
                errs[(dy + sr) * (2 * sr + 1) + dx + sr] = e;
                if (e < best) {
                    second = best;
                    best = e;
                    bx = dx;
                    by = dy;
                } else if (e < second) second = e;
            }
        }
        if (best == Float.MAX_VALUE) return null;
        float perPixel = best / ((2 * R + 1) * (2 * R + 1));
        if (perPixel > 0.06f) return null; // patch changed too much (TV picture, blur)
        float fx = cx + bx, fy = cy + by;
        int stride = 2 * sr + 1, ci = (by + sr) * stride + bx + sr;
        if (bx > -sr && bx < sr) fx += parabola(errs[ci - 1], errs[ci], errs[ci + 1]);
        if (by > -sr && by < sr) fy += parabola(errs[ci - stride], errs[ci], errs[ci + stride]);
        return new float[]{fx, fy};
    }

    private static float parabola(float em, float e0, float ep) {
        float den = em - 2 * e0 + ep;
        if (!(den > 1e-9f) || em == Float.MAX_VALUE || ep == Float.MAX_VALUE) return 0f;
        return Math.max(-0.5f, Math.min(0.5f, 0.5f * (em - ep) / den));
    }

    /** Global translation at quarter resolution, ±5 cells (±20 px). */
    private int[] coarseShift(float[] a, float[] b) {
        int cw = w / 4, ch = h / 4;
        float[] ca = down(a, cw, ch), cb = down(b, cw, ch);
        float best = Float.MAX_VALUE;
        int bx = 0, by = 0;
        for (int sy = -5; sy <= 5; sy++) {
            for (int sx = -5; sx <= 5; sx++) {
                float s = 0f;
                int n = 0;
                for (int y = Math.max(0, sy); y < Math.min(ch, ch + sy); y++) {
                    for (int x = Math.max(0, sx); x < Math.min(cw, cw + sx); x++) {
                        s += Math.abs(cb[y * cw + x] - ca[(y - sy) * cw + (x - sx)]);
                        n++;
                    }
                }
                if (n < cw * ch / 3) continue;
                s /= n;
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
                float s = 0;
                for (int yy = 0; yy < 4; yy++)
                    for (int xx = 0; xx < 4; xx++) s += a[(y * 4 + yy) * w + x * 4 + xx];
                o[y * cw + x] = s / 16f;
            }
        return o;
    }

    private static float err(float[] A, float x, float y, float tx, float ty) {
        float ex = A[0] * x + A[1] * y + A[2] - tx, ey = A[3] * x + A[4] * y + A[5] - ty;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    private static float[] affineFrom3(float x1, float y1, float u1, float v1, float x2, float y2, float u2, float v2,
                                       float x3, float y3, float u3, float v3) {
        float det = x1 * (y2 - y3) - y1 * (x2 - x3) + (x2 * y3 - x3 * y2);
        if (Math.abs(det) < 1e-3f) return null;
        float[] r = solve3(x1, y1, x2, y2, x3, y3, u1, u2, u3, det);
        float[] s = solve3(x1, y1, x2, y2, x3, y3, v1, v2, v3, det);
        return new float[]{r[0], r[1], r[2], s[0], s[1], s[2]};
    }

    /** Solves a*x + b*y + c = t for three points (Cramer). */
    private static float[] solve3(float x1, float y1, float x2, float y2, float x3, float y3, float t1, float t2, float t3, float det) {
        float a = (t1 * (y2 - y3) - y1 * (t2 - t3) + (t2 * y3 - t3 * y2)) / det;
        float b = (x1 * (t2 - t3) - t1 * (x2 - x3) + (x2 * t3 - x3 * t2)) / det;
        float c = (x1 * (y2 * t3 - y3 * t2) - y1 * (x2 * t3 - x3 * t2) + t1 * (x2 * y3 - x3 * y2)) / det;
        return new float[]{a, b, c};
    }

    private float[] fitAffine(float[] A, int[] idx, float[] nx, float[] ny) {
        double sxx = 0, sxy = 0, sx = 0, syy = 0, sy = 0, n = 0;
        double ux = 0, uy = 0, u = 0, vx = 0, vy = 0, v = 0;
        for (int j : idx) {
            if (err(A, px[j], py[j], nx[j], ny[j]) >= 1.5f) continue;
            double x = px[j], y = py[j];
            sxx += x * x; sxy += x * y; sx += x; syy += y * y; sy += y; n++;
            ux += nx[j] * x; uy += nx[j] * y; u += nx[j];
            vx += ny[j] * x; vy += ny[j] * y; v += ny[j];
        }
        if (n < 3) return null;
        double[][] M = {{sxx, sxy, sx}, {sxy, syy, sy}, {sx, sy, n}};
        double[] r = solveSym(M, new double[]{ux, uy, u}), s = solveSym(M, new double[]{vx, vy, v});
        if (r == null || s == null) return null;
        return new float[]{(float) r[0], (float) r[1], (float) r[2], (float) s[0], (float) s[1], (float) s[2]};
    }

    private static double[] solveSym(double[][] m, double[] b) {
        double det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (Math.abs(det) < 1e-9) return null;
        double[] r = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] t = {m[0].clone(), m[1].clone(), m[2].clone()};
            for (int i = 0; i < 3; i++) t[i][c] = b[i];
            r[c] = (t[0][0] * (t[1][1] * t[2][2] - t[1][2] * t[2][1]) - t[0][1] * (t[1][0] * t[2][2] - t[1][2] * t[2][0])
                    + t[0][2] * (t[1][0] * t[2][1] - t[1][1] * t[2][0])) / det;
        }
        return r;
    }
}
