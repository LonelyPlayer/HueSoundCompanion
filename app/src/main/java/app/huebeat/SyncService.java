package app.huebeat;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import app.huebeat.core.Ambient;
import app.huebeat.core.Analyzer;
import app.huebeat.core.CameraColors;
import app.huebeat.core.DtlsPskClient;
import app.huebeat.core.Features;
import app.huebeat.core.HueStream;
import app.huebeat.core.LightSettings;
import app.huebeat.core.Renderer;
import app.huebeat.core.StripOutput;
import app.huebeat.core.Dmx;

/** Foreground service: captures audio, analyses it and streams colors to the Hue bridge. */
public class SyncService extends Service {
    public static final String ACTION_START = "app.huebeat.START";
    public static final String ACTION_STOP = "app.huebeat.STOP";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    /** The screen is needed (screen colors / Screen style), so capture consent was asked even for the microphone. */
    public static final String EXTRA_SCREEN = "screen";

    private static final String CHANNEL = "sync";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_MS = 20; // 50 packets per second

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    /** Every start gets a new number; an engine whose number is no longer current stops at once. */
    private volatile int generation = 0;
    private Thread engineThread;
    private MediaProjection projection;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    public static void stop(Context c) {
        c.startService(new Intent(c, SyncService.class).setAction(ACTION_STOP));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            shutdown(null);
            return START_NOT_STICKY;
        }
        int mode = intent.getIntExtra(EXTRA_MODE, Prefs.MODE_OUTPUT);
        boolean ambient = mode == Prefs.MODE_AMBIENT;
        boolean wantsProjection = !ambient && (mode != Prefs.MODE_MIC || intent.getBooleanExtra(EXTRA_SCREEN, false));
        int type = ambient ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (wantsProjection) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting light sync…"), type);
        } catch (Exception e) {
            SyncState.set(SyncState.Status.ERROR, "Android refused to start the sync service: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (running) stopEngine();

        MediaProjection mp = null;
        if (wantsProjection) {
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            int code = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            if (data == null) {
                shutdown("Audio capture permission missing. Tap the button again.");
                return START_NOT_STICKY;
            }
            try {
                MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                mp = mpm.getMediaProjection(code, data);
            } catch (Exception e) {
                mp = null;
            }
            if (mp == null) {
                shutdown("Could not start audio capture. Tap the button again.");
                return START_NOT_STICKY;
            }
            mp.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    if (running) main.post(() -> shutdown(null));
                }
            }, main);
        }
        projection = mp;
        acquireLocks();
        running = true;
        final MediaProjection fmp = mp;
        final int gen = ++generation;
        engineThread = new Thread(() -> {
            if (ambient) runAmbient(gen);
            else runEngine(mode, fmp, gen);
        }, "huebeat-engine");
        engineThread.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopEngine();
        releaseLocks();
        if (SyncState.isActive()) SyncState.set(SyncState.Status.OFF, "");
        super.onDestroy();
    }

    private void shutdown(String error) {
        stopEngine();
        releaseLocks();
        if (error != null) SyncState.set(SyncState.Status.ERROR, error);
        else SyncState.set(SyncState.Status.OFF, "");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopEngine() {
        running = false;
        generation++;
        Thread t = engineThread;
        engineThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(8000);
            } catch (InterruptedException ignored) {
            }
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
            }
            projection = null;
        }
    }

    // ------------------------------------------------------------------ engine

    private boolean alive(int gen) {
        return running && gen == generation;
    }

    private void runEngine(int mode, MediaProjection mp, int gen) {
        Prefs prefs = Prefs.get(this);
        String ip = prefs.bridgeIp(), user = prefs.username(), key = prefs.clientKey();
        AudioRecord rec = null;
        Thread audioThread = null;
        VisualizerSource viz = null;
        ScreenSource screen = null;
        String screenError = null;
        DatagramSocket stripSock = null;
        app.huebeat.core.Shuffle shuffle = new app.huebeat.core.Shuffle();
        app.huebeat.core.Buildup buildup = new app.huebeat.core.Buildup();
        app.huebeat.core.Cues.Timer lampTimer = new app.huebeat.core.Cues.Timer();
        float lampFlash = 0f;
        int lampBeat = 0, lampSnare = 0;
        boolean shuffled = false;
        Rig rig = new Rig();
        HueLink link = null;
        try {
            List<HueLamp> hueLamps = Rig.streamedLamps(prefs);
            boolean hue = !hueLamps.isEmpty();
            SyncState.set(SyncState.Status.STARTING, "Starting…");
            rig.update(prefs);
            if (rig.isEmpty() && !hue && !SyncState.phoneShow)
                throw new IOException("Add Hue lamps, an LED strip or a DMX light first.");
            // the bridge connects on its own thread: an unreachable bridge doesn't hold up the rest
            if (hue) link = new HueLink(prefs, hueLamps, SyncState::notifyListeners);
            stripSock = new DatagramSocket();

            rec = createRecorder(mode, mp, prefs);
            final AudioRecord frec = rec;
            final Analyzer analyzer = new Analyzer(SAMPLE_RATE, mode != Prefs.MODE_MIC);
            rec.startRecording();
            audioThread = new Thread(() -> {
                short[] buf = new short[960];
                while (alive(gen)) {
                    int n = frec.read(buf, 0, buf.length);
                    if (n > 0) analyzer.push(buf, n, System.nanoTime());
                    else if (n < 0) break;
                }
            }, "huebeat-audio");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();

            LightSettings ls = prefs.light;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean bt = isBluetoothOutput(am);
            SyncState.bluetooth = bt;
            SyncState.set(SyncState.Status.RUNNING, "");
            updateNotification(prefs);

            long start = System.nanoTime(), last = start, nextFrame = start, lastCheck = start;
            long silentSince = start, captureSilentSince = start, captureLoudSince = 0, vizRetryAt = start;
            boolean useViz = false;
            String vizError = null;
            String blocker = mode == Prefs.MODE_APP ? prefs.appLabel() : "The playing app";
            // TV camera / screen: where each point of the room sits on the screen (across = x, up = z)
            app.huebeat.core.Scene laid = rig.scene;
            float[][] cam = camLayout(rig);
            long[] lightT = new long[128];
            float[] lightC = new float[3 * 128];
            int lightPos = 0;
            CameraColors.Calibrator calibrator = null;
            long lastCalFrame = 0;
            while (alive(gen)) {
                long now = System.nanoTime();
                float dt = Math.min(0.1f, (now - last) / 1e9f);
                last = now;

                // the bridge answered: its lamps join
                HueApi.ChannelMap hueMap = link != null ? link.takeMap() : null;
                if (hueMap != null) rig.setHue(hueLamps, hueMap);
                SyncState.problem = link != null ? link.problem : "";
                // settings changed in the app: taken over without restarting the sync
                rig.update(prefs);
                if (rig.scene != laid) {
                    laid = rig.scene;
                    cam = camLayout(rig);
                    if (screen != null) screen.setPositions(rig.screenX(), rig.screenZ());
                    updateNotification(prefs);
                }
                Features cap = analyzer.latest();
                boolean captureSilent = cap == null || cap.silent;
                if (captureSilent) captureLoudSince = 0;
                else {
                    captureSilentSince = now;
                    if (captureLoudSince == 0) captureLoudSince = now;
                }

                if (now - lastCheck > 1_000_000_000L) {
                    lastCheck = now;
                    boolean b = isBluetoothOutput(am);
                    if (b != SyncState.bluetooth) {
                        SyncState.bluetooth = b;
                        SyncState.notifyListeners();
                    }
                    // Screen colors: start sampling the screen the first time they're wanted.
                    // (A capture session can create its screen mirror only once, so it then stays on.)
                    boolean wantScreen = ls.screenColors || ls.style == LightSettings.STYLE_SCREEN;
                    if (wantScreen && screen == null && mp != null && screenError == null && !CameraFeed.fresh(now)) {
                        ScreenSource sc = new ScreenSource(this, rig.screenX(), rig.screenZ());
                        if (sc.start(mp)) screen = sc;
                        else screenError = sc.error;
                    }
                    // Fallback: nothing arrives although music plays -> the app blocks capture.
                    // Switch on Android's visualizer, which still sees the overall output.
                    if (mode != Prefs.MODE_MIC && viz == null && now >= vizRetryAt
                            && now - captureSilentSince > 2_000_000_000L && mediaPlaying(am)) {
                        VisualizerSource v = new VisualizerSource();
                        if (v.start()) {
                            viz = v;
                            vizError = null;
                        } else {
                            vizError = v.error;
                            vizRetryAt = now + 30_000_000_000L;
                        }
                    }
                    // Direct capture works again for a while: the visualizer is no longer needed.
                    if (viz != null && captureLoudSince != 0 && now - captureLoudSince > 10_000_000_000L) {
                        viz.stop();
                        viz = null;
                    }
                }
                Features vz = viz != null ? viz.analyzer.latest() : null;
                useViz = captureSilent && vz != null && !vz.silent;
                SyncState.visualizer = useViz;
                Analyzer active = useViz ? viz.analyzer : analyzer;
                ls.delayMs = mode == Prefs.MODE_MIC ? 0
                        : (SyncState.bluetooth ? prefs.delayBluetooth() : prefs.delaySpeaker());

                boolean camera = CameraFeed.fresh(now);
                float[] camGrid = CameraFeed.grid;
                if (camera && camGrid != null) {
                    // the lights' own glow on the screen, as it looked latency ms ago
                    long want = now - CameraFeed.colors.latencyMs() * 1_000_000L;
                    float[] past = null;
                    for (int k = 1; k <= lightT.length; k++) {
                        int idx = (lightPos - k + lightT.length) % lightT.length;
                        if (lightT[idx] == 0) break;
                        if (lightT[idx] <= want) {
                            past = new float[]{lightC[3 * idx], lightC[3 * idx + 1], lightC[3 * idx + 2]};
                            break;
                        }
                    }
                    CameraFeed.colors.compute(camGrid, past, cam[0], cam[1], cam[2]);
                    rig.scene.setScreenColors(cam[2]);
                } else {
                    float[] sc = screen != null ? screen.colors() : null;
                    rig.scene.setScreenColors(sc != null && sc.length >= 3 * rig.scene.n ? sc : null);
                }

                // room-light calibration requested from the camera screen
                if (CameraFeed.calibrateRequested) {
                    CameraFeed.calibrateRequested = false;
                    if (camera) {
                        calibrator = new CameraColors.Calibrator(CameraFeed.GW, CameraFeed.GH, now);
                        lastCalFrame = 0;
                    } else {
                        CameraFeed.calibrationStatus = "Calibration needs the TV camera screen open.";
                    }
                }
                boolean calibrating = false;
                float[] calColor = null;
                if (calibrator != null) {
                    float[] lc = calibrator.lightColor(now);
                    if (!camera) {
                        calibrator = null;
                        CameraFeed.calibrationStatus = "Calibration stopped: the camera lost the TV.";
                    } else if (lc == null) {
                        if (calibrator.finish()) CameraFeed.colors.setCalibration(calibrator.model, calibrator.latencyMs);
                        CameraFeed.calibrationStatus = calibrator.result;
                        calibrator = null;
                    } else {
                        long gt = CameraFeed.gridNanos;
                        if (gt != lastCalFrame && camGrid != null) {
                            calibrator.addFrame(gt, camGrid);
                            lastCalFrame = gt;
                        }
                        calibrating = true;
                        calColor = lc;
                    }
                }
                Features f = active.at(now - ls.delayMs * 1_000_000L);
                double tSec = (now - start) / 1e9;
                // Shuffle: the director picks style, palette and effects; everything renders with its picks
                LightSettings use = ls;
                // the vibe and build-up are always tracked: the strobes of every output use them
                if (SyncState.reshuffle) {
                    SyncState.reshuffle = false;
                    shuffle.reshuffle();
                }
                shuffle.update(f, dt, ls);
                if (!calibrating) buildup.update(f, dt);
                app.huebeat.core.Cues.energy = shuffle.energy();
                app.huebeat.core.Cues.gap = !calibrating && buildup.inGap();
                app.huebeat.core.Cues.build = calibrating || buildup.inGap() ? 0f : buildup.shown();
                app.huebeat.core.Cues.impact = calibrating ? 0f : buildup.impact();
                if (ls.style == LightSettings.STYLE_SHUFFLE) {
                    use = shuffle.out;
                    for (int k = 0; k < rig.strips.size(); k++) shuffle.applyStrip(k, rig.strips.get(k));
                    for (Dmx.Fixture fx : rig.dmx.fixtures) shuffle.applyDmx(fx);
                    SyncState.shuffle = shuffle.describe();
                    shuffled = true;
                } else if (shuffled) {
                    // back to the user's own settings
                    shuffled = false;
                    SyncState.shuffle = "";
                    Prefs.stripsVersion++;
                    Prefs.dmxVersion++;
                    rig.update(prefs);
                }
                boolean showBuild = ls.buildups && !calibrating;
                shuffle.building = showBuild ? buildup.level() : 0f;
                rig.dmx.overlay = showBuild ? buildup : null;
                SyncState.features = f;
                SyncState.palette = use.palette;
                rig.music(f, tSec, dt, use, calibrating ? calColor : null);
                float[] rgb = rig.hueColors();
                if (showBuild) {
                    rgb = rgb.clone();
                    buildup.apply(rgb, rgb.length / 3);
                }
                // lamp strobe on high intensity / build-ups (Party snares are done by the renderer)
                int sm = ls.strobeMode;
                if (sm >= 2 && f != null && !f.silent && !calibrating) {
                    boolean lb = f.beatCount != lampBeat, lsn = f.snareCount != lampSnare;
                    int cue = sm == 2 ? app.huebeat.core.Cues.HIGH_INTENSITY : sm == 3 ? app.huebeat.core.Cues.BUILDUPS : app.huebeat.core.Cues.BOTH;
                    if (app.huebeat.core.Cues.fire(cue, lb, lsn, dt, lampTimer)) lampFlash = 1f;
                }
                if (f != null) {
                    lampBeat = f.beatCount;
                    lampSnare = f.snareCount;
                }
                lampFlash *= (float) Math.exp(-dt / 0.05f);
                if (lampFlash > 0.02f) {
                    if (!showBuild) rgb = rgb.clone();
                    for (int i = 0; i < rgb.length; i++) rgb[i] += (1f - rgb[i]) * lampFlash;
                }
                if (calibrating) {
                    float[] cc = new float[3 * rig.dmx.fixtures.size()];
                    for (int i = 0; i < rig.dmx.fixtures.size(); i++) System.arraycopy(calColor, 0, cc, 3 * i, 3);
                    rig.dmx.write(cc, f, dt, false, 0f, 1f);
                } else {
                    rig.dmx.write(rig.dmxColors(), f, dt, false, 0f, use.maxBrightness);
                }
                float[][] stripRgb = new float[rig.strips.size()][];
                for (int k = 0; k < rig.strips.size(); k++) {
                    StripOutput so = rig.strips.get(k);
                    stripRgb[k] = calibrating ? so.fill(calColor) : so.render(f, tSec, dt, use);
                    if (showBuild) {
                        stripRgb[k] = stripRgb[k].clone();
                        buildup.applyStrip(stripRgb[k], stripRgb[k].length / 3);
                    }
                }
                // remember what the lights showed (average), for removing their glow from the camera image
                float[] mean = rig.meanColor();
                lightT[lightPos] = now;
                lightC[3 * lightPos] = mean[0];
                lightC[3 * lightPos + 1] = mean[1];
                lightC[3 * lightPos + 2] = mean[2];
                lightPos = (lightPos + 1) % lightT.length;
                rig.sendLocal(stripSock, stripRgb);
                if (link != null) link.send(rig.channelIds, rgb);

                float[] m = rig.meanColor();
                SyncState.r = m[0];
                SyncState.g = m[1];
                SyncState.b = m[2];
                Features latest = active.latest();
                SyncState.meter = latest != null ? latest.inputMeter : 0f;
                SyncState.bpm = latest != null ? latest.bpm : 0f;
                SyncState.chord = latest != null && latest.chordRoot >= 0
                        ? CHORD_NAMES[latest.chordRoot] + (latest.chordMinor ? "m" : "") : "";
                boolean screenWanted = ls.screenColors || ls.style == LightSettings.STYLE_SCREEN;
                if (camera) {
                    silentSince = now;
                    SyncState.setHint("Colors from the TV camera.");
                } else if (screenWanted && screen == null && (mp == null || screenError != null)) {
                    SyncState.setHint(mp == null ? "Screen colors need screen sharing. Turn sync off and on again."
                            : "Screen colors aren't available: " + screenError);
                } else if (ls.style == LightSettings.STYLE_SCREEN) {
                    silentSince = now;
                    SyncState.setHint("Following the screen. Protected videos (Netflix etc.) show as black.");
                } else if (useViz) {
                    silentSince = now;
                    SyncState.setHint(blocker + " blocks direct capture, so the lights follow Android's music visualizer instead (a bit coarser).");
                } else if (latest == null || latest.silent) {
                    if (now - silentSince > 6_000_000_000L) {
                        String h = silenceHint(mode);
                        if (vizError != null) h += " (Android's visualizer fallback isn't available: " + vizError + ")";
                        SyncState.setHint(h);
                    }
                } else {
                    silentSince = now;
                    SyncState.setHint("");
                }

                nextFrame += FRAME_MS * 1_000_000L;
                long sleep = nextFrame - System.nanoTime();
                if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                else nextFrame = System.nanoTime();
            }
        } catch (InterruptedException ignored) {
            // stopping
        } catch (Exception e) {
            final String msg = friendly(e);
            if (alive(gen)) main.post(() -> shutdown(msg));
        } finally {
            if (gen == generation) running = false;
            rig.blackoutDmx(stripSock);
            if (stripSock != null) stripSock.close();
            if (viz != null) viz.stop();
            if (screen != null) screen.stop();
            SyncState.problem = "";
            if (link != null) {
                Thread.interrupted(); // stopping the stream must not be cut short
                link.close();
            }
            if (audioThread != null) {
                try {
                    audioThread.join(500);
                } catch (InterruptedException ignored) {
                }
            }
            if (rec != null) {
                try {
                    rec.stop();
                } catch (Exception ignored) {
                }
                rec.release();
            }
        }
    }

    private static final String[] CHORD_NAMES = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};

    // ------------------------------------------------------------------ ambient mode

    /** Lights without music: palettes, single colors per lamp and calm animations. */
    private void runAmbient(int gen) {
        Prefs prefs = Prefs.get(this);
        String ip = prefs.bridgeIp(), user = prefs.username(), key = prefs.clientKey();
        DatagramSocket stripSock = null;
        Rig rig = new Rig();
        HueLink link = null;
        boolean finished = false;
        try {
            List<HueLamp> hueLamps = Rig.streamedLamps(prefs);
            boolean hue = !hueLamps.isEmpty();
            SyncState.set(SyncState.Status.STARTING, "Starting…");
            rig.update(prefs);
            if (rig.isEmpty() && !hue && !SyncState.phoneShow) throw new IOException("Add Hue lamps, an LED strip or a DMX light first.");
            stripSock = new DatagramSocket();
            if (hue) link = new HueLink(prefs, hueLamps, SyncState::notifyListeners);
            Ambient.Settings as = prefs.ambient;
            SyncState.meter = 0f;
            SyncState.bpm = 0f;
            SyncState.set(SyncState.Status.RUNNING, "");
            SyncState.setHint("");
            notifyAmbient(prefs);

            long last = System.nanoTime(), nextFrame = last;
            while (alive(gen)) {
                long now = System.nanoTime();
                float dt = Math.min(0.1f, (now - last) / 1e9f);
                last = now;
                HueApi.ChannelMap hueMap = link != null ? link.takeMap() : null;
                if (hueMap != null) rig.setHue(hueLamps, hueMap);
                SyncState.problem = link != null ? link.problem : "";
                rig.update(prefs);
                SyncState.features = null;
                SyncState.palette = as.palette;
                rig.ambient(dt, as);
                rig.dmx.overlay = null;
                rig.dmx.write(rig.dmxColors(), null, dt, true, as.speed, as.brightness);
                float[][] stripRgb = new float[rig.strips.size()][];
                for (int k = 0; k < rig.strips.size(); k++) stripRgb[k] = rig.strips.get(k).renderAmbient(dt, as);
                rig.sendLocal(stripSock, stripRgb);
                if (link != null) link.send(rig.channelIds, rig.hueColors());
                float[] m = rig.meanColor();
                SyncState.r = m[0];
                SyncState.g = m[1];
                SyncState.b = m[2];

                nextFrame += FRAME_MS * 1_000_000L;
                long sleep = nextFrame - System.nanoTime();
                if (sleep > 0) Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                else nextFrame = System.nanoTime();
            }
            finished = true;
        } catch (InterruptedException ignored) {
            finished = true;
        } catch (Exception e) {
            final String msg = friendly(e);
            if (alive(gen)) main.post(() -> shutdown(msg));
        } finally {
            if (gen == generation) running = false;
            Thread.interrupted(); // the network calls below must not be cut short
            SyncState.problem = "";
            if (link != null) link.close();
            if (finished && prefs.keepColors()) keepColors(ip, user, rig);
            else rig.blackoutDmx(stripSock); // DMX nodes hold the last frame: that already keeps the colors
            if (stripSock != null) stripSock.close();
            Ambient.master = null;
        }
    }

    /** Camera layout: where each room point sits in the camera picture, plus the output buffer. */
    private static float[][] camLayout(Rig rig) {
        int n = rig.scene.n;
        float[] u = new float[n], v = new float[n];
        for (int i = 0; i < n; i++) {
            u[i] = Math.max(0f, Math.min(1f, (rig.scene.x[i] + 1f) / 2f));
            v[i] = Math.max(0f, Math.min(1f, 1f - (rig.scene.z[i] + 1f) / 2f));
        }
        return new float[][]{u, v, new float[3 * n]};
    }

    /** Leaves the last colors on the lamps and strips after stopping, through their normal APIs. */
    private static void keepColors(String ip, String user, Rig rig) {
        for (int k = 0; k < rig.lamps.size(); k++) {
            HueLamp h = rig.lamps.get(k);
            if (h.lightId.isEmpty()) continue;
            float[] c = rig.lampColor(k);
            try {
                HueApi.setLightColor(ip, user, h.lightId, c[0], c[1], c[2]);
            } catch (Exception ignored) {
            }
        }
        for (StripOutput so : rig.strips) {
            try {
                WledApi.setColors(so.host(), so.physicalColors());
            } catch (Exception ignored) {
                // not a WLED controller, or unreachable
            }
        }
    }

    private void notifyAmbient(Prefs prefs) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification("Lighting " + targetsText(prefs)));
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createRecorder(int mode, MediaProjection mp, Prefs prefs) throws IOException {
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, 960 * 2 * 4);
        AudioRecord.Builder b = new AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(bufSize);

        if (mode == Prefs.MODE_MIC) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean unprocessed = "true".equals(am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
            b.setAudioSource(unprocessed ? MediaRecorder.AudioSource.UNPROCESSED : MediaRecorder.AudioSource.MIC);
        } else {
            AudioPlaybackCaptureConfiguration.Builder cb = new AudioPlaybackCaptureConfiguration.Builder(mp);
            if (mode == Prefs.MODE_APP) {
                String pkg = prefs.appPackage();
                if (pkg == null) throw new IOException("Choose an app first.");
                try {
                    ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                    cb.addMatchingUid(ai.uid);
                } catch (PackageManager.NameNotFoundException e) {
                    throw new IOException("The chosen app is no longer installed.");
                }
            } else {
                cb.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
            }
            b.setAudioPlaybackCaptureConfig(cb.build());
        }
        AudioRecord rec;
        try {
            rec = b.build();
        } catch (Exception e) {
            throw new IOException("Could not open audio: " + e.getMessage());
        }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
            rec.release();
            throw new IOException("Could not open audio (is another app recording?)");
        }
        return rec;
    }

    private static boolean isBluetoothOutput(AudioManager am) {
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_HEARING_AID) {
                return true;
            }
        }
        return false;
    }

    private static boolean mediaPlaying(AudioManager am) {
        try {
            for (AudioPlaybackConfiguration pc : am.getActivePlaybackConfigurations()) {
                int u = pc.getAudioAttributes().getUsage();
                if (u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME || u == AudioAttributes.USAGE_UNKNOWN) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private String silenceHint(int mode) {
        if (mode == Prefs.MODE_MIC) return "Not hearing anything. Is music playing nearby?";
        // Look at what is playing right now to tell "nothing plays" from "an app blocks capture".
        int media = 0, blocked = 0;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            for (AudioPlaybackConfiguration pc : am.getActivePlaybackConfigurations()) {
                AudioAttributes aa = pc.getAudioAttributes();
                int u = aa.getUsage();
                if (u == AudioAttributes.USAGE_MEDIA || u == AudioAttributes.USAGE_GAME || u == AudioAttributes.USAGE_UNKNOWN) {
                    media++;
                    if (aa.getAllowedCapturePolicy() != AudioAttributes.ALLOW_CAPTURE_BY_ALL) blocked++;
                }
            }
        } catch (Exception ignored) {
        }
        String who = mode == Prefs.MODE_APP ? Prefs.get(this).appLabel() : "the app that's playing";
        if (media == 0) return "Nothing seems to be playing right now. Start some music.";
        if (blocked > 0) return "Music is playing, but " + who + " doesn't allow other apps to capture its sound. Use \"Microphone\" for this app.";
        return "Music is playing, but no sound reaches LightBox. " + who.substring(0, 1).toUpperCase() + who.substring(1)
                + " probably blocks capture. Use \"Microphone\" for this app.";
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                || e instanceof java.net.NoRouteToHostException) {
            return "Bridge not reachable. Is the phone on the same Wi-Fi? (" + m + ")";
        }
        if (m.contains("HTTP 403") || m.contains("unauthorized")) return "Bridge rejected the app. Run setup again.";
        return m;
    }

    // ------------------------------------------------------------------ notification & locks

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Light sync", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SyncService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("LightBox")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    /** "3 Hue lamps, Desk strip, 2 DMX lights" */
    private static String targetsText(Prefs prefs) {
        List<String> t = new ArrayList<>();
        int hue = Rig.streamedLamps(prefs).size();
        if (hue > 0) t.add(hue == 1 ? "1 Hue lamp" : hue + " Hue lamps");
        for (Strip st : Rig.activeStrips(prefs)) t.add(st.name);
        int dmx = Rig.activeDmx(prefs).size();
        if (dmx > 0) t.add(dmx == 1 ? "1 DMX light" : dmx + " DMX lights");
        return t.isEmpty() ? "lights" : String.join(", ", t);
    }

    private void updateNotification(Prefs prefs) {
        String src;
        switch (prefs.mode()) {
            case Prefs.MODE_APP: src = prefs.appLabel(); break;
            case Prefs.MODE_MIC: src = "microphone"; break;
            default: src = "phone audio";
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification("Syncing " + targetsText(prefs) + " to " + src));
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "huebeat:sync");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(6 * 60 * 60 * 1000L);
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "huebeat:wifi");
            wifiLock.setReferenceCounted(false);
        }
        wifiLock.acquire();
    }

    private void releaseLocks() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
    }
}
