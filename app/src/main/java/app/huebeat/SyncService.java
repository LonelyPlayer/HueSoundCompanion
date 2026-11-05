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
import java.net.InetAddress;

import app.huebeat.core.Analyzer;
import app.huebeat.core.DtlsPskClient;
import app.huebeat.core.Features;
import app.huebeat.core.HueStream;
import app.huebeat.core.LightSettings;
import app.huebeat.core.Renderer;

/** Foreground service: captures audio, analyses it and streams colors to the Hue bridge. */
public class SyncService extends Service {
    public static final String ACTION_START = "app.huebeat.START";
    public static final String ACTION_STOP = "app.huebeat.STOP";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

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
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (mode != Prefs.MODE_MIC) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Starting light sync…"), type);
        } catch (Exception e) {
            SyncState.set(SyncState.Status.ERROR, "Android refused to start the sync service: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }

        if (running) stopEngine();

        MediaProjection mp = null;
        if (mode != Prefs.MODE_MIC) {
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
        boolean streamingStarted = false;
        try {
            SyncState.set(SyncState.Status.STARTING, "Connecting to bridge…");
            HueApi.Channels ch = HueApi.channels(ip, user, area);

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

            HueApi.setStreaming(ip, user, area, true);
            streamingStarted = true;
            dtls = connect(ip, user, key);

            Renderer renderer = new Renderer(ch.x);
            HueStream stream = new HueStream(area);
            LightSettings ls = prefs.light;
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean bt = isBluetoothOutput(am);
            SyncState.bluetooth = bt;
            SyncState.set(SyncState.Status.RUNNING, "");
            updateNotification(prefs);

            long start = System.nanoTime(), last = start, nextFrame = start, lastCheck = start;
            long silentSince = start;
            int failures = 0;
            while (running) {
                long now = System.nanoTime();
                float dt = Math.min(0.1f, (now - last) / 1e9f);
                last = now;

                if (now - lastCheck > 1_000_000_000L) {
                    lastCheck = now;
                    boolean b = isBluetoothOutput(am);
                    if (b != SyncState.bluetooth) {
                        SyncState.bluetooth = b;
                        SyncState.notifyListeners();
                    }
                }
                ls.delayMs = mode == Prefs.MODE_MIC ? 0
                        : (SyncState.bluetooth ? prefs.delayBluetooth() : prefs.delaySpeaker());

                Features f = analyzer.at(now - ls.delayMs * 1_000_000L);
                float[] rgb = renderer.render(f, (now - start) / 1e9, dt, ls);
                try {
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

                float[] m = renderer.meanColor();
                SyncState.r = m[0];
                SyncState.g = m[1];
                SyncState.b = m[2];
                Features latest = analyzer.latest();
                SyncState.meter = latest != null ? latest.inputMeter : 0f;
                SyncState.bpm = latest != null ? latest.bpm : 0f;
                if (latest == null || latest.silent) {
                    if (now - silentSince > 6_000_000_000L) SyncState.setHint(silenceHint(mode));
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
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, buildNotification("Syncing " + prefs.areaName() + " to " + src));
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
