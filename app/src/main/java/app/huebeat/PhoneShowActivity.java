package app.huebeat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import app.huebeat.core.Cues;
import app.huebeat.core.Features;
import app.huebeat.core.Palettes;

/**
 * The phone plays along: a full-screen show that moves with the music (glow, a ring of the
 * spectrum, rings on the beats, sparks on the snares) and the flashlight flashing on the beats.
 * Tap the screen for the controls.
 */
public class PhoneShowActivity extends Activity {
    private static final String PREF_TORCH = "show_torch";

    private ShowView view;
    private LinearLayout controls;
    private TextView torchBtn;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final Runnable hideControls = () -> {
        if (controls != null) controls.setVisibility(View.GONE);
    };

    // flashlight
    private CameraManager cameras;
    private String torchId;
    private int torchMax = 1;
    private HandlerThread torchThread;
    private Handler torch;
    private boolean torchWanted;
    private volatile boolean torchOn;
    private long torchUntil, torchNextOk;
    private int torchBeat = Integer.MIN_VALUE, torchSnare = Integer.MIN_VALUE;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SyncState.phoneShow = true;
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = 1f; // the screen is a light now
        w.setAttributes(lp);
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new ShowView(this);
        root.addView(view, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        view.setOnClickListener(v -> showControls());

        controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER);
        int p = Ui.dp(this, 16);
        controls.setPadding(p, p, p, Ui.dp(this, 40));
        torchBtn = Ui.chip(this, "");
        torchBtn.setOnClickListener(v -> {
            torchWanted = !torchWanted;
            getSharedPreferences("huebeat_ui", MODE_PRIVATE).edit().putBoolean(PREF_TORCH, torchWanted).apply();
            if (!torchWanted) setTorch(false);
            updateTorchBtn();
            showControls();
        });
        controls.addView(torchBtn, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 10), 0));
        TextView close = Ui.chip(this, "Close");
        close.setOnClickListener(v -> finish());
        controls.addView(close);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
        clp.gravity = Gravity.BOTTOM;
        root.addView(controls, clp);
        setContentView(root);
        hideBars();

        torchWanted = getSharedPreferences("huebeat_ui", MODE_PRIVATE).getBoolean(PREF_TORCH, true);
        findTorch();
        updateTorchBtn();
        showControls();
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(android.view.WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void showControls() {
        controls.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideControls);
        main.postDelayed(hideControls, 3500);
    }

    private void updateTorchBtn() {
        torchBtn.setText(torchId == null ? "No flashlight" : torchWanted ? "Flashlight: on" : "Flashlight: off");
        Ui.setChipSelected(torchBtn, torchId != null && torchWanted);
    }

    @Override
    protected void onPause() {
        super.onPause();
        setTorch(false);
        // leaving the show ends it (the lights keep going if there are any)
        if (isFinishing()) stopShow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
    }

    @Override
    protected void onDestroy() {
        stopShow();
        if (torchThread != null) torchThread.quitSafely();
        super.onDestroy();
    }

    private boolean stopped;

    private void stopShow() {
        if (stopped) return;
        stopped = true;
        setTorch(false);
        SyncState.phoneShow = false;
        // started only for the phone: nothing else to light, so stop listening too
        if (!Prefs.get(this).hasOutputs() && SyncState.isActive()) SyncService.stop(this);
    }

    // ------------------------------------------------------------------ flashlight

    private void findTorch() {
        try {
            cameras = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics c = cameras.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (flash != null && flash && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    torchId = id;
                    if (Build.VERSION.SDK_INT >= 33) {
                        Integer max = c.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
                        if (max != null) torchMax = max;
                    }
                    break;
                }
            }
        } catch (Exception e) {
            torchId = null;
        }
        if (torchId != null) {
            torchThread = new HandlerThread("torch");
            torchThread.start();
            torch = new Handler(torchThread.getLooper());
        }
    }

    /** Switches the flashlight (on its own thread: the camera service can take a few ms). */
    private void setTorch(boolean on) {
        setTorch(on, 1f);
    }

    private void setTorch(boolean on, float strength) {
        if (torch == null || on == torchOn) return;
        torchOn = on;
        final int level = Math.max(1, Math.min(torchMax, Math.round(torchMax * (0.35f + 0.65f * strength))));
        torch.post(() -> {
            try {
                if (on && torchMax > 1 && Build.VERSION.SDK_INT >= 33) cameras.turnOnTorchWithStrengthLevel(torchId, level);
                else cameras.setTorchMode(torchId, on);
            } catch (Exception ignored) {
                // camera busy (e.g. the TV camera is running): no flashlight then
            }
        });
    }

    /** Called every frame: a short flash on each beat, on snares too when the music is hot. */
    private void torchFrame(Features f, long now) {
        if (!torchWanted || torchId == null || f == null || f.silent) {
            torchBeat = f != null ? f.beatCount : torchBeat;
            setTorch(false);
            return;
        }
        boolean beat = torchBeat != Integer.MIN_VALUE && f.beatCount != torchBeat;
        boolean snare = torchSnare != Integer.MIN_VALUE && f.snareCount != torchSnare;
        torchBeat = f.beatCount;
        torchSnare = f.snareCount;
        boolean hot = Cues.build > 0.45f || Cues.energy > 0.75f;
        if (Cues.gap) beat = snare = false; // the hush before a drop stays dark
        float strength = 0f;
        if (beat && f.beatStrength > 0.2f) strength = f.beatStrength;
        else if (snare && hot) strength = 0.5f;
        if (strength > 0f && now >= torchNextOk) {
            long len = 55 + (long) (45 * strength);
            torchUntil = now + len;
            torchNextOk = now + len + 50; // the LED needs a short dark moment to read as a flash
            setTorch(true, Math.max(strength, Cues.impact));
        }
        if (torchOn && now >= torchUntil) setTorch(false);
    }

    // ------------------------------------------------------------------ the screen show

    private static final class Ring {
        float r, alpha, width;
        final float[] rgb = new float[3];
    }

    private static final class Spark {
        float x, y, life, size;
        final float[] rgb = new float[3];
    }

    private final class ShowView extends View {
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG), bar = new Paint(Paint.ANTI_ALIAS_FLAG),
                ring = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), flash = new Paint();
        private final List<Ring> rings = new ArrayList<>();
        private final List<Spark> sparks = new ArrayList<>();
        private final Random rnd = new Random();
        private final float[] c0 = new float[3], c1 = new float[3], tmp = new float[3];
        private final float[] spec = new float[Features.SPEC];
        private long last;
        private double t;
        private float angle, level, bass, white;
        private int beat = Integer.MIN_VALUE, snare = Integer.MIN_VALUE;

        ShowView(Context c) {
            super(c);
            bar.setStrokeCap(Paint.Cap.ROUND);
            ring.setStyle(Paint.Style.STROKE);
        }

        private int argb(float a, float[] rgb) {
            return Color.argb(clamp255(a), clamp255(rgb[0]), clamp255(rgb[1]), clamp255(rgb[2]));
        }

        @Override
        protected void onDraw(Canvas c) {
            long now = System.nanoTime();
            float dt = last == 0 ? 0.016f : Math.min(0.1f, (now - last) / 1e9f);
            last = now;
            t += dt;
            Features f = SyncState.features;
            torchFrame(f, now / 1_000_000L);
            boolean music = f != null && !f.silent;
            int pal = SyncState.palette;
            float key = f != null ? f.keyHue : 0f;

            // follow the music smoothly
            float lv = music ? f.level : 0f, bs = music ? Math.max(f.bands[0], f.bands[1]) : 0f;
            level += (lv - level) * Math.min(1f, dt * 12f);
            bass += (bs - bass) * Math.min(1f, dt * 18f);
            for (int i = 0; i < spec.length; i++) {
                float v = music ? f.spec[i] : 0f;
                spec[i] += (v - spec[i]) * Math.min(1f, dt * (v > spec[i] ? 30f : 8f));
            }
            float speed = 0.05f + 0.25f * level + 0.9f * Cues.build;
            angle += dt * speed * 60f;
            float pos = (float) (t * 0.02) + (f != null ? f.beatCount * 0.04f : 0f);
            Palettes.color(pal, pos, key, 1f, c0, 0);
            Palettes.color(pal, pos + 0.5f, key, 1f, c1, 0);
            if (f == null) {
                // "Just lights": the room's colors, slowly breathing
                c0[0] = SyncState.r;
                c0[1] = SyncState.g;
                c0[2] = SyncState.b;
                if (c0[0] + c0[1] + c0[2] < 0.05f) Palettes.color(pal, pos, 0f, 1f, c0, 0);
                level = 0.35f + 0.15f * (float) Math.sin(t * 0.8);
            }

            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f, m = Math.min(w, h);
            c.drawColor(Color.BLACK);

            // glow
            float gr = m * (0.45f + 0.5f * level + 0.25f * bass);
            glow.setShader(new RadialGradient(cx, cy, gr, new int[]{argb(0.35f + 0.55f * level, c0), argb(0.12f + 0.2f * level, c1), 0},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, gr, glow);

            // beats: rings; snares: sparks
            if (f != null) {
                if (beat != Integer.MIN_VALUE && f.beatCount != beat && !Cues.gap) {
                    Ring r = new Ring();
                    r.r = m * 0.16f;
                    r.alpha = 0.5f + 0.5f * f.beatStrength;
                    r.width = m * (0.01f + 0.025f * f.beatStrength);
                    Palettes.color(pal, pos + rnd.nextFloat() * 0.3f, key, 1f, r.rgb, 0);
                    rings.add(r);
                    if (f.beatStrength > 0.85f || Cues.impact > 0.6f) white = Math.max(white, 0.35f * Math.max(f.beatStrength, Cues.impact));
                }
                if (snare != Integer.MIN_VALUE && f.snareCount != snare && !Cues.gap) {
                    int n = 6 + (int) (10 * (level + Cues.build));
                    for (int i = 0; i < n; i++) {
                        Spark s = new Spark();
                        s.x = rnd.nextFloat() * w;
                        s.y = rnd.nextFloat() * h;
                        s.life = 1f;
                        s.size = m * (0.004f + 0.01f * rnd.nextFloat());
                        Palettes.color(pal, pos + rnd.nextFloat(), key, 0.5f, s.rgb, 0);
                        sparks.add(s);
                    }
                }
                beat = f.beatCount;
                snare = f.snareCount;
            }
            for (int i = rings.size() - 1; i >= 0; i--) {
                Ring r = rings.get(i);
                r.r += dt * m * (0.9f + 0.6f * level);
                r.alpha -= dt * 1.1f;
                if (r.alpha <= 0f || r.r > m * 1.2f) {
                    rings.remove(i);
                    continue;
                }
                ring.setStrokeWidth(r.width);
                ring.setColor(argb(r.alpha, r.rgb));
                c.drawCircle(cx, cy, r.r, ring);
            }

            // the spectrum as a ring of bars (mirrored, so it's symmetric)
            float r0 = m * (0.17f + 0.04f * bass);
            int bars = 2 * Features.SPEC;
            bar.setStrokeWidth(m * 0.012f);
            for (int i = 0; i < bars; i++) {
                int k = i < Features.SPEC ? i : bars - 1 - i;
                float v = spec[k];
                float a = (float) Math.toRadians(angle + 360f * i / bars - 90f);
                float len = m * (0.012f + 0.22f * v * v);
                float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                Palettes.color(pal, pos + (float) k / Features.SPEC * 0.6f, key, 1f, tmp, 0);
                bar.setColor(argb(0.45f + 0.55f * v, tmp));
                c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len), bar);
            }
            // the core pulses with the bass
            dot.setColor(argb(0.85f, c1));
            c.drawCircle(cx, cy, m * (0.05f + 0.08f * bass), dot);

            for (int i = sparks.size() - 1; i >= 0; i--) {
                Spark s = sparks.get(i);
                s.life -= dt * 2.2f;
                if (s.life <= 0f) {
                    sparks.remove(i);
                    continue;
                }
                dot.setColor(argb(s.life, s.rgb));
                c.drawCircle(s.x, s.y, s.size * (0.6f + 0.4f * s.life), dot);
            }

            // build-up: a white haze that grows; impact: a flash
            float haze = Cues.gap ? 0f : 0.25f * Cues.build;
            white = Math.max(white * (float) Math.exp(-dt / 0.08f), haze);
            if (white > 0.01f) {
                flash.setColor(Color.argb(clamp255(white), 255, 255, 255));
                c.drawRect(0, 0, w, h, flash);
            }
            postInvalidateOnAnimation();
        }
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255f)));
    }
}
