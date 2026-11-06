package app.huebeat;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Display;

import java.nio.ByteBuffer;

/**
 * Samples the phone screen at very low resolution (96×96) and computes one color per light,
 * from the screen region that matches the light's position in the entertainment area:
 * left/right from x, top/bottom from the light's height (z).
 *
 * Protected video (Netflix etc.) shows up black here, so it never drives the lights.
 */
final class ScreenSource {
    private static final int SIZE = 96;

    /** Region centers per channel, 0..1 (u = left/right, v = top/bottom); replaced as a pair. */
    private volatile float[][] uv;
    private final Context context;
    private HandlerThread thread;
    private ImageReader reader;
    private VirtualDisplay display;
    private volatile float[] colors;
    private long lastFrame = 0;
    String error;

    ScreenSource(Context context, float[] x, float[] z) {
        this.context = context.getApplicationContext();
        setPositions(x, z);
    }

    /** New channel positions (e.g. an LED strip was added); used from the next frame on. */
    void setPositions(float[] x, float[] z) {
        int n = x.length;
        float[] u = new float[n], v = new float[n];
        for (int i = 0; i < n; i++) {
            u[i] = clamp01((x[i] + 1f) / 2f);
            v[i] = clamp01(1f - (z[i] + 1f) / 2f); // z = 1 is up → top of the screen
        }
        uv = new float[][]{u, v};
        colors = null;
    }

    /** Latest colors (rgb per channel, 0..1), or null before the first frame. */
    float[] colors() {
        return colors;
    }

    boolean start(MediaProjection mp) {
        try {
            thread = new HandlerThread("huebeat-screen");
            thread.start();
            Handler h = new Handler(thread.getLooper());
            reader = ImageReader.newInstance(SIZE, SIZE, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    long now = System.nanoTime();
                    if (now - lastFrame < 40_000_000L) return; // ~25 fps is plenty
                    lastFrame = now;
                    sample(img);
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, h);
            int dpi = context.getResources().getDisplayMetrics().densityDpi;
            display = mp.createVirtualDisplay("huesound-screen", SIZE, SIZE, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
            return true;
        } catch (Throwable t) {
            error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            stop();
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void sample(Image img) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int rowStride = p.getRowStride(), pixStride = p.getPixelStride();

        // The mirrored screen is scaled to fit the square, with black bars on two sides.
        float aspect = 9f / 19.5f;
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            Point pt = new Point();
            d.getRealSize(pt);
            if (pt.x > 0 && pt.y > 0) aspect = (float) pt.x / pt.y;
        } catch (Throwable ignored) {
        }
        int cw = aspect >= 1 ? SIZE : Math.round(SIZE * aspect);
        int ch = aspect >= 1 ? Math.round(SIZE / aspect) : SIZE;
        int cx0 = (SIZE - cw) / 2, cy0 = (SIZE - ch) / 2;

        float[][] pos = uv;
        float[] u = pos[0], v = pos[1];
        int n = u.length;
        float[] out = new float[3 * n];
        for (int i = 0; i < n; i++) {
            // each light looks at a region around its position: 40% of the width, 50% of the height
            int x0 = cx0 + Math.round(clamp01(u[i] - 0.2f) * (cw - 1));
            int x1 = cx0 + Math.round(clamp01(u[i] + 0.2f) * (cw - 1));
            int y0 = cy0 + Math.round(clamp01(v[i] - 0.25f) * (ch - 1));
            int y1 = cy0 + Math.round(clamp01(v[i] + 0.25f) * (ch - 1));
            double r = 0, g = 0, b = 0, wsum = 0;
            for (int y = y0; y <= y1; y += 2) {
                int row = y * rowStride;
                for (int x = x0; x <= x1; x += 2) {
                    int o = row + x * pixStride;
                    if (o + 2 >= buf.limit()) continue;
                    float pr = (buf.get(o) & 0xFF) / 255f, pg = (buf.get(o + 1) & 0xFF) / 255f, pb = (buf.get(o + 2) & 0xFF) / 255f;
                    float max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
                    // vivid, bright pixels count more, like the official sync does
                    float w = 0.15f + (max - min) * 2f + max * 0.5f;
                    r += pr * w;
                    g += pg * w;
                    b += pb * w;
                    wsum += w;
                }
            }
            if (wsum > 0) {
                out[3 * i] = (float) (r / wsum);
                out[3 * i + 1] = (float) (g / wsum);
                out[3 * i + 2] = (float) (b / wsum);
            }
        }
        colors = out;
    }

    void stop() {
        try {
            if (display != null) display.release();
        } catch (Throwable ignored) {
        }
        display = null;
        try {
            if (reader != null) reader.close();
        } catch (Throwable ignored) {
        }
        reader = null;
        if (thread != null) thread.quitSafely();
        thread = null;
        colors = null;
    }

    private static float clamp01(float x) {
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }
}
