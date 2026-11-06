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

import app.huebeat.core.Analyzer;
import app.huebeat.core.CameraColors;
import app.huebeat.core.DtlsPskClient;
import app.huebeat.core.Features;
import app.huebeat.core.HueStream;
import app.huebeat.core.LightSettings;
import app.huebeat.core.Renderer;
import app.huebeat.core.StripOutput;

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
        boolean wantsProjection = mode != Prefs.MODE_MIC || intent.getBooleanExtra(EXTRA_SCREEN, false);
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
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
        engineThread = new Thread(() -> runEngine(mode, fmp), "huebeat-engine");
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
        Thread t = engineThread;
        engineThread = null;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(3000);
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

    private void runEngine(int mode, MediaProjection mp) {
        Prefs prefs = Prefs.get(this);
        String ip = prefs.bridgeIp(), user = prefs.username(), key = prefs.clientKey(), area = prefs.areaId();
        AudioRecord rec = null;
        DtlsPskClient dtls = null;
        Thread audioThread = null;
        VisualizerSource viz = null;
        ScreenSource screen = null;
        String screenError = null;
        boolean streamingStarted = false;
        DatagramSocket stripSock = null;
        boolean hue = prefs.isConfigured();
        try {
            List<StripOutput> strips = new ArrayList<>();
            for (Strip st : prefs.strips()) {
                if (!st.enabled || st.ip.isEmpty()) continue;
                strips.add(st.output());
            }
            if (!hue && strips.isEmpty()) throw new IOException("Connect a Hue bridge or add an LED strip first.");
            if (!strips.isEmpty()) stripSock = new DatagramSocket();

            SyncState.set(SyncState.Status.STARTING, hue ? "Connecting to bridge…" : "Starting…");
            HueApi.Channels ch = hue ? HueApi.channels(ip, user, area) : HueApi.Channels.empty();

            rec = createRecorder(mode, mp, prefs);
            final AudioRecord frec = rec;
            final Analyzer analyzer = new Analyzer(SAMPLE_RATE, mode != Prefs.MODE_MIC);
            rec.startRecording();
            audioThread = new Thread(() -> {
                short[] buf = new short[960];
                while (running) {
                    int n = frec.read(buf, 0, buf.length);
                    if (n > 0) analyzer.push(buf, n, System.nanoTime());
                    else if (n < 0) break;
                }
            }, "huebeat-audio");
            audioThread.setPriority(Thread.MAX_PRIORITY);
            audioThread.start();

            if (hue) {
                HueApi.setStreaming(ip, user, area, true);
                streamingStarted = true;
                dtls = connect(ip, user, key);
            }

            Renderer renderer = hue ? new Renderer(ch.x) : null;
            HueStream stream = hue ? new HueStream(area) : null;
            LightSettings ls = prefs.light;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean bt = isBluetoothOutput(am);
            SyncState.bluetooth = bt;
            SyncState.set(SyncState.Status.RUNNING, "");
            updateNotification(prefs);

            long start = System.nanoTime(), last = start, nextFrame = start, lastCheck = start;
            long silentSince = start, captureSilentSince = start, captureLoudSince = 0, vizRetryAt = start;
            int failures = 0;
            boolean useViz = false;
            String vizError = null;
            String blocker = mode == Prefs.MODE_APP ? prefs.appLabel() : "The playing app";
            // TV camera: where each light sits on the screen, and what the lights showed recently
            // all outputs side by side: Hue channels first, then every strip's segments
            int nHue = ch.ids.length;
            int nch = nHue;
            for (StripOutput so : strips) nch += so.segments;
            float[] allX = new float[nch], allZ = new float[nch];
            System.arraycopy(ch.x, 0, allX, 0, nHue);
            System.arraycopy(ch.z, 0, allZ, 0, nHue);
            int[] stripOff = new int[strips.size()];
            float[][] stripScreen = new float[strips.size()][];
            for (int k = 0, o = nHue; k < strips.size(); k++) {
                StripOutput so = strips.get(k);
                stripOff[k] = o;
                System.arraycopy(so.segX, 0, allX, o, so.segments); // strips run along the screen's width
                stripScreen[k] = new float[3 * so.segments];
                o += so.segments;
            }
            float[] hueScreen = new float[3 * nHue];
            float[] camU = new float[nch], camV = new float[nch], camOut = new float[3 * nch], calRgb = new float[3 * nHue];
            for (int i = 0; i < nch; i++) {
                camU[i] = Math.max(0f, Math.min(1f, (allX[i] + 1f) / 2f));
                camV[i] = Math.max(0f, Math.min(1f, 1f - (allZ[i] + 1f) / 2f));
            }
            long[] lightT = new long[128];
            float[] lightC = new float[3 * 128];
            int lightPos = 0;
            CameraColors.Calibrator calibrator = null;
            long lastCalFrame = 0;
            while (running) {
                long now = System.nanoTime();
                float dt = Math.min(0.1f, (now - last) / 1e9f);
                last = now;

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
                        ScreenSource sc = new ScreenSource(this, allX, allZ);
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
                    CameraFeed.colors.compute(camGrid, past, camU, camV, camOut);
                    distribute(camOut, renderer, hueScreen, strips, stripOff, stripScreen);
                } else {
                    distribute(screen != null ? screen.colors() : null, renderer, hueScreen, strips, stripOff, stripScreen);
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
                float[] rgb = null;
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
                        for (int i = 0; i < nHue; i++) {
                            calRgb[3 * i] = lc[0];
                            calRgb[3 * i + 1] = lc[1];
                            calRgb[3 * i + 2] = lc[2];
                        }
                        rgb = calRgb;
                        calibrating = true;
                        calColor = lc;
                    }
                }
                Features f = active.at(now - ls.delayMs * 1_000_000L);
                double tSec = (now - start) / 1e9;
                if (!calibrating && renderer != null) rgb = renderer.render(f, tSec, dt, ls);
                float[][] stripRgb = new float[strips.size()][];
                for (int k = 0; k < strips.size(); k++) {
                    stripRgb[k] = calibrating ? strips.get(k).fill(calColor) : strips.get(k).render(f, tSec, dt, ls);
                }
                // remember what the lights showed (average), for removing their glow from the camera image
                float sr = 0f, sg = 0f, sb = 0f;
                for (int i = 0; i < nHue; i++) {
                    sr += rgb[3 * i];
                    sg += rgb[3 * i + 1];
                    sb += rgb[3 * i + 2];
                }
                for (float[] sRgb : stripRgb) {
                    for (int i = 0; i < sRgb.length; i += 3) {
                        sr += sRgb[i];
                        sg += sRgb[i + 1];
                        sb += sRgb[i + 2];
                    }
                }
                lightT[lightPos] = now;
                lightC[3 * lightPos] = sr / nch;
                lightC[3 * lightPos + 1] = sg / nch;
                lightC[3 * lightPos + 2] = sb / nch;
                lightPos = (lightPos + 1) % lightT.length;
                for (int k = 0; k < strips.size(); k++) {
                    try {
                        strips.get(k).send(stripSock, stripRgb[k]);
                    } catch (IOException e) {
                        if (!running) break;
                        // a strip that's switched off or out of Wi-Fi range shouldn't stop the rest
                    }
                }
                if (hue) try {
                    dtls.send(stream.build(ch.ids, rgb));
                    failures = 0;
                } catch (IOException e) {
                    if (!running) break;
                    if (++failures > 3) throw e;
                    SyncState.set(SyncState.Status.STARTING, "Reconnecting…");
                    try { dtls.close(); } catch (Exception ignored) { }
                    Thread.sleep(500);
                    HueApi.setStreaming(ip, user, area, true);
                    dtls = connect(ip, user, key);
                    SyncState.set(SyncState.Status.RUNNING, "");
                }

                float[] m = renderer != null ? renderer.meanColor() : strips.get(0).meanColor();
                SyncState.r = m[0];
                SyncState.g = m[1];
                SyncState.b = m[2];
                Features latest = active.latest();
                SyncState.meter = latest != null ? latest.inputMeter : 0f;
                SyncState.bpm = latest != null ? latest.bpm : 0f;
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
            if (dtls != null) dtls.close();
            dtls = null;
            if (streamingStarted) {
                try {
                    HueApi.setStreaming(ip, user, area, false);
                } catch (IOException ignored) {
                }
            }
        } catch (InterruptedException ignored) {
            // stopping
        } catch (Exception e) {
            final String msg = friendly(e);
            if (running) main.post(() -> shutdown(msg));
        } finally {
            running = false;
            if (stripSock != null) stripSock.close();
            if (viz != null) viz.stop();
            if (screen != null) screen.stop();
            if (dtls != null) dtls.close();
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
            if (streamingStarted && dtls != null) {
                try {
                    HueApi.setStreaming(ip, user, area, false);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Hands the screen/camera colors (all outputs side by side) to each output's renderer. */
    private static void distribute(float[] all, Renderer hueRenderer, float[] hueBuf, List<StripOutput> strips,
                                   int[] stripOff, float[][] stripBuf) {
        if (all == null) {
            if (hueRenderer != null) hueRenderer.setScreenColors(null);
            for (StripOutput so : strips) so.setScreenColors(null);
            return;
        }
        if (hueRenderer != null) {
            System.arraycopy(all, 0, hueBuf, 0, hueBuf.length);
            hueRenderer.setScreenColors(hueBuf.clone());
        }
        for (int k = 0; k < strips.size(); k++) {
            System.arraycopy(all, 3 * stripOff[k], stripBuf[k], 0, stripBuf[k].length);
            strips.get(k).setScreenColors(stripBuf[k].clone());
        }
    }

    private static DtlsPskClient connect(String ip, String user, String key) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            DtlsPskClient c = new DtlsPskClient(InetAddress.getByName(ip), 2100, user, DtlsPskClient.hex(key));
            try {
                c.handshake(6000);
                return c;
            } catch (IOException e) {
                c.close();
                last = e;
            }
        }
        throw last;
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
        return "Music is playing, but no sound reaches HueSoundCompanion. " + who.substring(0, 1).toUpperCase() + who.substring(1)
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
                .setContentTitle("HueSoundCompanion")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void updateNotification(Prefs prefs) {
        String src;
        switch (prefs.mode()) {
            case Prefs.MODE_APP: src = prefs.appLabel(); break;
            case Prefs.MODE_MIC: src = "microphone"; break;
            default: src = "phone audio";
        }
        List<String> targets = new ArrayList<>();
        if (prefs.isConfigured()) targets.add(prefs.areaName());
        for (Strip st : prefs.strips()) if (st.enabled) targets.add(st.name);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification("Syncing " + String.join(", ", targets) + " to " + src));
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
