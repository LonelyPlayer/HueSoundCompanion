package app.huebeat.core;

import java.util.Arrays;

/**
 * Finds a TV in low-resolution camera frames and keeps tracking it while the phone moves.
 *
 * Detection: a TV is the region that is bright and keeps changing (video), while the room around
 * it is mostly static. Each frame we estimate the global camera motion (block matching), compensate
 * for it, and accumulate "change" and "brightness" scores. Every second the best-scoring connected
 * region becomes the TV; its four corners come from the extreme points along the diagonals, which
 * also handles a TV seen at an angle.
 *
 * Tracking: between detections the quad is moved by the estimated camera motion.
 */
public final class TvDetector {
    private final int w, h, n;
    private float[] prevLuma;
    private float[] change;          // motion-compensated temporal change (EMA)
    private float[] shiftTmp;
    private final float[] score;
    private final int[] label;
    private final int[] queue;

    private float[] quad;            // TL, TR, BR, BL as x,y pairs (pixels), or null
    private boolean manual = false;
    private int frames = 0;
    private int candidateHits = 0;
    private float[] candidate;
    /** 0..1 how sure the last detection was. */
    public float confidence = 0f;
    /** Last estimated camera motion in pixels (for UI / debugging). */
    public int lastDx, lastDy;
    private float accX, accY;

    public TvDetector(int w, int h) {
        this.w = w;
        this.h = h;
        n = w * h;
        change = new float[n];
        shiftTmp = new float[n];
        score = new float[n];
        label = new int[n];
        queue = new int[n];
    }

    public synchronized float[] quad() {
        return quad == null ? null : quad.clone();
    }

    public synchronized boolean isManual() {
        return manual;
    }

    /** Fixes the corners by hand (pixels, TL TR BR BL). Tracking still follows camera motion. */
    public synchronized void setManualQuad(float[] q) {
        quad = q.clone();
        manual = true;
        confidence = 1f;
    }

    /** Back to automatic detection. */
    public synchronized void autoDetect() {
        manual = false;
        candidateHits = 0;
        frames = 0;
        Arrays.fill(change, 0f);
    }

    /**
     * @param luma brightness per pixel, 0..1 (length w*h)
     */
    public synchronized void process(float[] luma) {
        frames++;
        if (prevLuma == null) {
            prevLuma = luma.clone();
            return;
        }
        // 1. global camera motion between the previous and this frame
        float[] d = estimateMotion(prevLuma, luma);
        if (quad != null) {
            for (int i = 0; i < 4; i++) {
                quad[2 * i] += d[0];
                quad[2 * i + 1] += d[1];
            }
        }
        // the change map moves in whole pixels; keep the remainder for the next frame
        accX += d[0];
        accY += d[1];
        int dx = Math.round(accX), dy = Math.round(accY);
        accX -= dx;
        accY -= dy;
        lastDx = dx;
        lastDy = dy;
        // 2. keep the change map registered with the scene, then add this frame's change
        if (dx != 0 || dy != 0) {
            Arrays.fill(shiftTmp, 0f);
            for (int y = 0; y < h; y++) {
                int sy = y - dy;
                if (sy < 0 || sy >= h) continue;
                for (int x = 0; x < w; x++) {
                    int sx = x - dx;
                    if (sx < 0 || sx >= w) continue;
                    shiftTmp[y * w + x] = change[sy * w + sx];
                }
            }
            float[] t = change;
            change = shiftTmp;
            shiftTmp = t;
        }
        boolean bigMove = Math.abs(dx) + Math.abs(dy) > 6;
        for (int y = 0; y < h; y++) {
            int sy = y - dy;
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                int sx = x - dx;
                float diff = 0f;
                if (sy >= 0 && sy < h && sx >= 0 && sx < w && !bigMove) {
                    diff = Math.abs(luma[i] - prevLuma[sy * w + sx]);
                    if (diff < 0.02f) diff = 0f; // sensor noise
                }
                change[i] = change[i] * 0.94f + diff * 0.06f * 10f;
            }
        }
        System.arraycopy(luma, 0, prevLuma, 0, n);

        // 3. periodically look for the TV
        if (!manual && (frames % 10 == 0 || quad == null) && frames > 6) detect(luma);
    }

    private void detect(float[] luma) {
        // brightness relative to the frame: TVs glow
        float[] sorted = luma.clone();
        Arrays.sort(sorted);
        float median = sorted[n / 2];
        float maxScore = 0f;
        for (int i = 0; i < n; i++) {
            float bright = Math.max(0f, luma[i] - median);
            float s = Math.min(1f, change[i]) * 0.6f + bright * 1.2f;
            score[i] = s;
            if (s > maxScore) maxScore = s;
        }
        if (maxScore < 0.08f) return;
        float thr = maxScore * 0.35f;

        // largest connected bright/changing region
        Arrays.fill(label, 0);
        int best = 0, bestArea = 0;
        float bestQuality = 0f;
        int[] bestBox = null;
        int next = 1;
        for (int start = 0; start < n; start++) {
            if (label[start] != 0 || score[start] < thr) continue;
            int head = 0, tail = 0, area = 0;
            int minX = w, minY = h, maxX = 0, maxY = 0;
            queue[tail++] = start;
            label[start] = next;
            while (head < tail) {
                int p = queue[head++];
                area++;
                int px = p % w, py = p / w;
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
                if (px > 0) tail = visit(p - 1, thr, next, tail);
                if (px < w - 1) tail = visit(p + 1, thr, next, tail);
                if (py > 0) tail = visit(p - w, thr, next, tail);
                if (py < h - 1) tail = visit(p + w, thr, next, tail);
            }
            int boxArea = (maxX - minX + 1) * (maxY - minY + 1);
            float fill = (float) area / boxArea;                       // rectangles fill their box
            float aspect = (float) (maxX - minX + 1) / (maxY - minY + 1);
            float aspectOk = aspect > 0.9f && aspect < 3.2f ? 1f : 0.4f; // TVs are wide
            float quality = area * fill * aspectOk;
            if (quality > bestQuality && area > n * 0.015f) {
                bestQuality = quality;
                best = next;
                bestArea = area;
                bestBox = new int[]{minX, minY, maxX, maxY};
            }
            next++;
        }
        if (best == 0) return;

        // corners from extreme points along the diagonals
        float tlS = Float.MAX_VALUE, brS = -Float.MAX_VALUE, trS = -Float.MAX_VALUE, blS = Float.MAX_VALUE;
        float[] q = new float[8];
        for (int i = 0; i < n; i++) {
            if (label[i] != best) continue;
            float x = i % w, y = i / w;
            if (x + y < tlS) { tlS = x + y; q[0] = x; q[1] = y; }
            if (x - y > trS) { trS = x - y; q[2] = x; q[3] = y; }
            if (x + y > brS) { brS = x + y; q[4] = x; q[5] = y; }
            if (x - y < blS) { blS = x - y; q[6] = x; q[7] = y; }
        }
        int boxArea = (bestBox[2] - bestBox[0] + 1) * (bestBox[3] - bestBox[1] + 1);
        confidence = Math.min(1f, (float) bestArea / boxArea * 1.1f);

        if (quad == null) {
            quad = q;
            candidateHits = 0;
            return;
        }
        float dist = 0f;
        for (int i = 0; i < 8; i++) dist = Math.max(dist, Math.abs(q[i] - quad[i]));
        if (dist < w * 0.08f) {
            for (int i = 0; i < 8; i++) quad[i] += (q[i] - quad[i]) * 0.35f; // refine gently
            candidateHits = 0;
        } else {
            // a different place: only jump after it was found there three times in a row
            if (candidate != null) {
                float cd = 0f;
                for (int i = 0; i < 8; i++) cd = Math.max(cd, Math.abs(q[i] - candidate[i]));
                candidateHits = cd < w * 0.08f ? candidateHits + 1 : 1;
            } else {
                candidateHits = 1;
            }
            candidate = q;
            if (candidateHits >= 3) {
                quad = q;
                candidateHits = 0;
            }
        }
    }

    private int visit(int p, float thr, int lab, int tail) {
        if (label[p] == 0 && score[p] >= thr) {
            label[p] = lab;
            queue[tail++] = p;
        }
        return tail;
    }

    /** Translation (sub-pixel) that best maps prev onto cur (coarse-to-fine block matching). */
    private float[] estimateMotion(float[] prev, float[] cur) {
        int cw = w / 4, ch = h / 4;
        float[] cp = downsample(prev, cw, ch), cc = downsample(cur, cw, ch);
        int bx = 0, by = 0;
        float bestErr = Float.MAX_VALUE;
        int r = 5;
        for (int sy = -r; sy <= r; sy++) {
            for (int sx = -r; sx <= r; sx++) {
                float err = sad(cp, cc, cw, ch, sx, sy, 1);
                if (err < bestErr) { bestErr = err; bx = sx; by = sy; }
            }
        }
        int fx = bx * 4, fy = by * 4;
        bestErr = Float.MAX_VALUE;
        int rx = fx, ry = fy;
        for (int sy = fy - 3; sy <= fy + 3; sy++) {
            for (int sx = fx - 3; sx <= fx + 3; sx++) {
                float err = sad(prev, cur, w, h, sx, sy, 2);
                if (err < bestErr) { bestErr = err; rx = sx; ry = sy; }
            }
        }
        // ignore sub-noise "motion"
        float still = sad(prev, cur, w, h, 0, 0, 2);
        if (still <= bestErr * 1.02f) return new float[]{0f, 0f};
        // refine to sub-pixel with a parabola through the neighbouring errors
        float fxs = rx + parabola(sad(prev, cur, w, h, rx - 1, ry, 2), bestErr, sad(prev, cur, w, h, rx + 1, ry, 2));
        float fys = ry + parabola(sad(prev, cur, w, h, rx, ry - 1, 2), bestErr, sad(prev, cur, w, h, rx, ry + 1, 2));
        return new float[]{fxs, fys};
    }

    private static float parabola(float em, float e0, float ep) {
        float den = em - 2 * e0 + ep;
        if (!(den > 1e-9f) || em == Float.MAX_VALUE || ep == Float.MAX_VALUE) return 0f;
        float off = 0.5f * (em - ep) / den;
        return Math.max(-0.5f, Math.min(0.5f, off));
    }

    private static float[] downsample(float[] a, int cw, int ch) {
        // (w = cw*4) average 4x4 blocks
        int w = cw * 4;
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

    /** Mean absolute difference between prev shifted by (sx,sy) and cur, over the overlap. */
    private static float sad(float[] prev, float[] cur, int w, int h, int sx, int sy, int step) {
        float s = 0f;
        int cnt = 0;
        int x0 = Math.max(0, sx), x1 = Math.min(w, w + sx), y0 = Math.max(0, sy), y1 = Math.min(h, h + sy);
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                s += Math.abs(cur[y * w + x] - prev[(y - sy) * w + (x - sx)]);
                cnt++;
            }
        }
        if (cnt < (w * h) / (step * step * 4)) return Float.MAX_VALUE; // too little overlap
        return s / cnt;
    }

    /**
     * Samples the TV picture as a gw×gh grid of colors (rgb 0..1 per cell) from an RGB frame
     * (0xRRGGBB per pixel). Cells are taken slightly inside the corners to skip the bezel.
     *
     * @return false if no TV is known yet
     */
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
                if (cnt > 0) {
                    out[o] = (float) (r / cnt);
                    out[o + 1] = (float) (g / cnt);
                    out[o + 2] = (float) (b / cnt);
                } else {
                    out[o] = out[o + 1] = out[o + 2] = 0f;
                }
            }
        }
        return true;
    }
}
