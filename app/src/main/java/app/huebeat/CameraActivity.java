package app.huebeat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.huebeat.core.LightSettings;
import app.huebeat.core.TvDetector;
import app.huebeat.core.TvTracker;

/**
 * TV camera mode: point the phone at the TV. The app finds the screen in the camera image,
 * follows it when the phone moves, and sends its colors to the lights. Nothing is recorded.
 */
public class CameraActivity extends Activity implements SyncState.Listener {
    private static final int AW = 320, AH = 240; // analysis resolution
    private static final int REQ_CAMERA = 1, REQ_PROJECTION = 2;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder request;
    private ImageReader reader;
    private int sensorOrientation = 90;
    private boolean exposureLocked = false;

    private final TvTracker detector = new TvTracker(AW, AH);
    /** Fallback when the ML model isn't available: brightness/motion heuristic at half size. */
    private final TvDetector heuristic = new TvDetector(AW / 2, AH / 2);
    private final float[] luma = new float[AW * AH];
    private final float[] lumaHalf = new float[(AW / 2) * (AH / 2)];
    private final int[] rgb = new int[AW * AH];
    private volatile TvMl ml;
    private HandlerThread mlThread;
    private Handler mlHandler;
    private volatile boolean mlBusy = false;
    private long lastMl = 0;

    private PreviewView preview;
    private TextView status, syncButton, ambiButton;
    private long lastTouch = 0;
    private boolean dimmed = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // camera colors drive the lights: make sure a screen-color look is selected
        if (!prefs.light.screenColors && prefs.light.style != LightSettings.STYLE_SCREEN) {
            prefs.light.screenColors = true;
            prefs.saveLight();
        }
        buildUi();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        preview = new PreviewView(this);
        root.addView(preview, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        LinearLayout top = Ui.row(this);
        int p = Ui.dp(this, 12);
        top.setPadding(p, p, p, p);
        TextView back = Ui.text(this, "‹  TV camera", 18, Ui.TEXT, true);
        back.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        Ui.clickable(back, 0x99000000, Ui.dp(this, 16));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        tlp.gravity = Gravity.TOP | Gravity.START;
        root.addView(top, tlp);

        LinearLayout bottom = Ui.column(this);
        bottom.setPadding(p, p, p, p);
        status = Ui.text(this, "", 14, Ui.TEXT, false);
        status.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        status.setBackground(Ui.rounded(0x99000000, Ui.dp(this, 12)));
        bottom.addView(status, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
        LinearLayout buttons = Ui.row(this);
        syncButton = Ui.button(this, "Start sync", true);
        syncButton.setOnClickListener(v -> toggleSync());
        buttons.addView(syncButton, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        TextView auto = Ui.button(this, "Auto-detect", false);
        auto.setOnClickListener(v -> {
            detector.autoDetect();
            heuristic.autoDetect();
            relockExposure();
            updateStatus();
        });
        buttons.addView(auto, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        TextView cal = Ui.button(this, "Calibrate room light", false);
        cal.setOnClickListener(v -> calibrate());
        buttons.addView(cal, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        ambiButton = Ui.button(this, "", false);
        ambiButton.setOnClickListener(v -> toggleLook());
        buttons.addView(ambiButton, Ui.lp(Ui.WRAP, Ui.WRAP));
        bottom.addView(buttons);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        blp.gravity = Gravity.BOTTOM | Gravity.START;
        root.addView(bottom, blp);

        Ui.setupWindow(this, root);
        updateStatus();
    }

    private void toggleLook() {
        if (prefs.light.style == LightSettings.STYLE_SCREEN) {
            prefs.light.style = LightSettings.STYLE_BEAT;
            prefs.light.screenColors = true;
        } else {
            prefs.light.style = LightSettings.STYLE_SCREEN;
        }
        prefs.saveLight();
        updateStatus();
    }

    private void calibrate() {
        if (!SyncState.isActive()) {
            status.setText("Start the sync first, then calibrate.");
            return;
        }
        if (detector.quad() == null) {
            status.setText("Point the camera at the TV first.");
            return;
        }
        CameraFeed.calibrationStatus = "Calibrating… keep the TV paused on a still image and the phone still (8 s).";
        CameraFeed.calibrateRequested = true;
        updateStatus();
    }

    private void updateStatus() {
        if (isFinishing()) return;
        boolean found = detector.quad() != null && !detector.isLost();
        String s;
        if (!found) s = "Looking for the TV… point the camera at it. Drag the corners if needed.";
        else if (detector.isManual()) s = "Using your corners. The camera follows when the phone moves.";
        else s = ml != null ? "TV found and tracked." : "TV found and tracked (simple detection: the ML model isn't available).";
        String cal = CameraFeed.calibrationStatus;
        if (cal != null && !cal.isEmpty()) s += "\n" + cal;
        else if (!CameraFeed.colors.isCalibrated() && found) s += "\nTip: pause on a still image and tap \"Calibrate room light\".";
        if (SyncState.status == SyncState.Status.ERROR) s += "\n" + SyncState.message;
        status.setText(s);
        boolean active = SyncState.isActive();
        syncButton.setText(active ? "Stop sync" : "Start sync");
        ambiButton.setText(prefs.light.style == LightSettings.STYLE_SCREEN ? "Look: Ambilight" : "Look: Ambilight + beat");
    }

    @Override
    public void onSyncStateChanged() {
        updateStatus();
    }

    // ------------------------------------------------------------------ dimming

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        lastTouch = System.currentTimeMillis();
        if (dimmed) setDim(false);
        return super.dispatchTouchEvent(ev);
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // the phone screen lights up the room too: dim it while nobody touches it
            if (!dimmed && System.currentTimeMillis() - lastTouch > 15_000 && SyncState.isActive()) setDim(true);
            updateStatus();
            main.postDelayed(this, 1000);
        }
    };

    private void setDim(boolean dim) {
        dimmed = dim;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = dim ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        SyncState.add(this);
        lastTouch = System.currentTimeMillis();
        main.post(ticker);
        startMl();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    @Override
    protected void onPause() {
        SyncState.remove(this);
        main.removeCallbacks(ticker);
        if (dimmed) setDim(false);
        stopCamera();
        stopMl();
        CameraFeed.clear();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
            else status.setText("TV camera mode needs the camera permission.");
        } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSync();
        }
    }

    // ------------------------------------------------------------------ sync start/stop

    private void toggleSync() {
        if (SyncState.isActive()) {
            SyncService.stop(this);
            SyncState.set(SyncState.Status.OFF, "");
        } else {
            startSync();
        }
    }

    private void startSync() {
        if (!prefs.isConfigured()) {
            status.setText("Connect your bridge on the main screen first.");
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 3);
            return;
        }
        if (prefs.mode() == Prefs.MODE_APP && prefs.appPackage() == null) {
            status.setText("Choose the app to follow on the main screen first.");
            return;
        }
        if (prefs.mode() == Prefs.MODE_MIC) {
            launch(0, null);
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent consent = Build.VERSION.SDK_INT >= 34
                    ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : mpm.createScreenCaptureIntent();
            startActivityForResult(consent, REQ_PROJECTION);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) launch(resultCode, data);
    }

    private void launch(int resultCode, Intent data) {
        SyncState.set(SyncState.Status.STARTING, "Starting…");
        Intent i = new Intent(this, SyncService.class)
                .setAction(SyncService.ACTION_START)
                .putExtra(SyncService.EXTRA_MODE, prefs.mode());
        if (data != null) {
            i.putExtra(SyncService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(SyncService.EXTRA_RESULT_DATA, data);
            i.putExtra(SyncService.EXTRA_SCREEN, true);
        }
        try {
            startForegroundService(i);
        } catch (Exception e) {
            SyncState.set(SyncState.Status.ERROR, "Could not start: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ ML detection

    private void startMl() {
        mlThread = new HandlerThread("huebeat-ml");
        mlThread.start();
        mlHandler = new Handler(mlThread.getLooper());
        mlHandler.post(() -> {
            TvMl m = new TvMl();
            if (m.init(getApplicationContext())) ml = m;
        });
    }

    private void stopMl() {
        Handler h = mlHandler;
        if (h != null) {
            h.post(() -> {
                if (ml != null) ml.close();
                ml = null;
            });
        }
        if (mlThread != null) mlThread.quitSafely();
        mlThread = null;
        mlHandler = null;
    }

    /** On the camera thread: hand a copy of this frame to the detector thread a few times per second. */
    private void maybeDetect() {
        Handler h = mlHandler, cam = camHandler;
        if (ml == null || h == null || cam == null || mlBusy) return;
        long now = System.currentTimeMillis();
        if (now - lastMl < 150) return;
        lastMl = now;
        mlBusy = true;
        final long frameNo = detector.frameNumber();
        final float[] l = luma.clone();
        final Bitmap bmp = Bitmap.createBitmap(AW, AH, Bitmap.Config.ARGB_8888);
        int[] px = new int[AW * AH];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | rgb[i];
        bmp.setPixels(px, 0, AW, 0, 0, AW, AH);
        h.post(() -> {
            float[] box = ml != null ? ml.detect(bmp) : null;
            mlBusy = false;
            if (box != null) {
                Handler c = camHandler;
                if (c != null) c.post(() -> detector.onBox(box[0], box[1], box[2], box[3], box[4], frameNo, l));
            }
        });
    }

    // ------------------------------------------------------------------ camera

    @SuppressLint("MissingPermission")
    private void startCamera() {
        if (device != null) return;
        camThread = new HandlerThread("huebeat-camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String id = null;
            CameraCharacteristics chars = null;
            for (String c : cm.getCameraIdList()) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(c);
                Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    id = c;
                    chars = cc;
                    break;
                }
            }
            if (id == null) {
                status.setText("No back camera found.");
                return;
            }
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img != null) onFrame(img);
                } catch (Throwable ignored) {
                } finally {
                    if (img != null) img.close();
                }
            }, camHandler);

            final CameraCharacteristics fchars = chars;
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice cam) {
                    device = cam;
                    createSession(fchars);
                }

                @Override
                public void onDisconnected(CameraDevice cam) {
                    cam.close();
                    device = null;
                }

                @Override
                public void onError(CameraDevice cam, int error) {
                    cam.close();
                    device = null;
                    main.post(() -> status.setText("Camera error " + error + ". Close other camera apps and try again."));
                }
            }, camHandler);
        } catch (Exception e) {
            status.setText("Camera unavailable: " + e.getMessage());
        }
    }

    private static Size pickSize(Size[] sizes) {
        Size best = null;
        for (Size s : sizes) {
            boolean fourThree = Math.abs((float) s.getWidth() / s.getHeight() - 4f / 3f) < 0.05f;
            if (!fourThree || s.getWidth() < 320 || s.getHeight() < 240) continue;
            if (best == null || s.getWidth() < best.getWidth()) best = s;
        }
        if (best == null) {
            for (Size s : sizes) {
                if (s.getWidth() < 320 || s.getHeight() < 240) continue;
                if (best == null || s.getWidth() * s.getHeight() < best.getWidth() * best.getHeight()) best = s;
            }
        }
        return best != null ? best : sizes[0];
    }

    @SuppressWarnings("deprecation")
    private void createSession(CameraCharacteristics chars) {
        try {
            request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(reader.getSurface());
            // run a bit darker than "correct" so a bright TV doesn't clip to white
            Range<Integer> range = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (range != null && step != null && step.floatValue() > 0) {
                int idx = Math.max(range.getLower(), Math.round(-1.3f / step.floatValue()));
                request.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx);
            }
            device.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    setLocks(false);
                    relockExposure();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    main.post(() -> status.setText("Camera could not be configured."));
                }
            }, camHandler);
        } catch (Exception e) {
            main.post(() -> status.setText("Camera unavailable: " + e.getMessage()));
        }
    }

    /** Let the camera adjust exposure and white balance for a moment, then freeze them. */
    private void relockExposure() {
        if (camHandler == null) return;
        camHandler.post(() -> setLocks(false));
        camHandler.postDelayed(() -> setLocks(true), 2500);
    }

    private void setLocks(boolean lock) {
        if (session == null || request == null) return;
        try {
            request.set(CaptureRequest.CONTROL_AE_LOCK, lock);
            request.set(CaptureRequest.CONTROL_AWB_LOCK, lock);
            session.setRepeatingRequest(request.build(), null, camHandler);
            exposureLocked = lock;
        } catch (Exception ignored) {
        }
    }

    private void stopCamera() {
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        try {
            if (device != null) device.close();
        } catch (Exception ignored) {
        }
        device = null;
        try {
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        reader = null;
        if (camThread != null) camThread.quitSafely();
        camThread = null;
        camHandler = null;
    }

    /** Runs on the camera thread for every frame. */
    private void onFrame(Image img) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
        int uvRow = pl[1].getRowStride(), uvPix = pl[1].getPixelStride();
        int iw = img.getWidth(), ih = img.getHeight();
        boolean flip = sensorOrientation == 270; // landscape UI: 90 is upright, 270 is upside down
        for (int ay = 0; ay < AH; ay++) {
            int sy = ay * ih / AH;
            for (int ax = 0; ax < AW; ax++) {
                int sx = ax * iw / AW;
                int Y = yb.get(sy * yRow + sx * yPix) & 0xFF;
                int uvo = (sy / 2) * uvRow + (sx / 2) * uvPix;
                int U = (ub.get(uvo) & 0xFF) - 128, V = (vb.get(uvo) & 0xFF) - 128;
                int r = clamp255(Math.round(Y + 1.402f * V));
                int g = clamp255(Math.round(Y - 0.344f * U - 0.714f * V));
                int b = clamp255(Math.round(Y + 1.772f * U));
                int o = flip ? (AH - 1 - ay) * AW + (AW - 1 - ax) : ay * AW + ax;
                rgb[o] = (r << 16) | (g << 8) | b;
                luma[o] = Y / 255f;
            }
        }
        detector.process(luma);
        if (ml != null) {
            maybeDetect();
        } else {
            // no ML model: the brightness/motion heuristic suggests where the TV is
            int hw = AW / 2, hh = AH / 2;
            for (int y = 0; y < hh; y++)
                for (int x = 0; x < hw; x++)
                    lumaHalf[y * hw + x] = (luma[2 * y * AW + 2 * x] + luma[2 * y * AW + 2 * x + 1]
                            + luma[(2 * y + 1) * AW + 2 * x] + luma[(2 * y + 1) * AW + 2 * x + 1]) / 4f;
            heuristic.process(lumaHalf);
            float[] hq = heuristic.quad();
            if (hq != null && detector.frameNumber() % 10 == 0) {
                for (int i = 0; i < 8; i++) hq[i] *= 2f;
                detector.onQuad(hq, heuristic.confidence * 0.5f, detector.frameNumber());
            }
        }
        float[] grid = new float[CameraFeed.GW * CameraFeed.GH * 3];
        if (detector.sampleGrid(rgb, CameraFeed.GW, CameraFeed.GH, grid)) CameraFeed.publish(grid, System.nanoTime());
        else CameraFeed.clear();
        preview.post(rgb, detector.isLost() ? null : detector.quad(), detector.isManual());
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    // ------------------------------------------------------------------ preview + corner editing

    private final class PreviewView extends View {
        private final Bitmap bmp = Bitmap.createBitmap(AW, AH, Bitmap.Config.ARGB_8888);
        private final int[] px = new int[AW * AH];
        private float[] quad;
        private boolean manual;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint img = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dst = new RectF();
        private final Path path = new Path();
        private int dragging = -1;

        PreviewView(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(Ui.dp(c, 3));
            line.setColor(Ui.ACCENT);
            dot.setColor(Ui.ACCENT);
        }

        /** Called from the camera thread. */
        void post(int[] frame, float[] q, boolean m) {
            synchronized (px) {
                for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | frame[i];
                if (dragging < 0) {
                    quad = q;
                    manual = m;
                }
            }
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float vw = getWidth(), vh = getHeight();
            float scale = Math.min(vw / AW, vh / AH);
            float dw = AW * scale, dh = AH * scale;
            dst.set((vw - dw) / 2, (vh - dh) / 2, (vw + dw) / 2, (vh + dh) / 2);
            float[] q;
            synchronized (px) {
                bmp.setPixels(px, 0, AW, 0, 0, AW, AH);
                q = quad;
            }
            c.drawBitmap(bmp, null, dst, img);
            if (q == null) return;
            path.reset();
            for (int i = 0; i < 4; i++) {
                float x = dst.left + q[2 * i] * scale, y = dst.top + q[2 * i + 1] * scale;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            path.close();
            line.setColor(manual ? Color.WHITE : Ui.ACCENT);
            c.drawPath(path, line);
            for (int i = 0; i < 4; i++) {
                c.drawCircle(dst.left + q[2 * i] * scale, dst.top + q[2 * i + 1] * scale, Ui.dp(getContext(), 9), dot);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float scale = Math.min((float) getWidth() / AW, (float) getHeight() / AH);
            float ax = (e.getX() - dst.left) / scale, ay = (e.getY() - dst.top) / scale;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    float[] q = quad;
                    if (q == null) {
                        // no TV yet: start a default box around the touch point
                        q = new float[]{ax - 60, ay - 36, ax + 60, ay - 36, ax + 60, ay + 36, ax - 60, ay + 36};
                        quad = q;
                    }
                    float best = Ui.dp(getContext(), 40) / scale;
                    dragging = -1;
                    for (int i = 0; i < 4; i++) {
                        float d = (float) Math.hypot(q[2 * i] - ax, q[2 * i + 1] - ay);
                        if (d < best) {
                            best = d;
                            dragging = i;
                        }
                    }
                    return dragging >= 0;
                }
                case MotionEvent.ACTION_MOVE:
                    if (dragging >= 0 && quad != null) {
                        quad[2 * dragging] = Math.max(0, Math.min(AW - 1, ax));
                        quad[2 * dragging + 1] = Math.max(0, Math.min(AH - 1, ay));
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (dragging >= 0 && quad != null) {
                        detector.setManualQuad(quad);
                        manual = true;
                    }
                    dragging = -1;
                    updateStatus();
                    return true;
                default:
                    return false;
            }
        }
    }
}
