package app.huebeat;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide sync status, observed by the UI. */
public final class SyncState {
    public enum Status { OFF, STARTING, RUNNING, ERROR }

    public interface Listener {
        void onSyncStateChanged();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static volatile Status status = Status.OFF;
    public static volatile String message = "";
    /** Hint shown while running (e.g. "no audio captured"). */
    public static volatile String hint = "";
    // live values for the button animation (written ~50x/s by the engine)
    public static volatile float r, g, b, meter, bpm;
    public static volatile boolean bluetooth;
    /** The chord the music is on right now ("Am"), "" when unknown or not detected in this mode. */
    public static volatile String chord = "";
    /** The visualizer fallback is feeding the analysis (e.g. Spotify blocks capture). */
    public static volatile boolean visualizer;
    /** Shuffle: the look playing now ("Chords · Synthwave"), and a request for a new one. */
    public static volatile String shuffle = "";
    public static volatile boolean reshuffle;
    /** The phone itself plays along (screen show + flashlight); the engine runs even without lights. */
    public static volatile boolean phoneShow;
    /** The analysis the lights use right now (delayed like them), null in "Just lights". */
    public static volatile app.huebeat.core.Features features;
    /** The palette the lights use right now (Shuffle's pick included). */
    public static volatile int palette;

    private SyncState() {
    }

    public static void set(Status s, String msg) {
        status = s;
        message = msg == null ? "" : msg;
        if (s != Status.RUNNING) hint = "";
        notifyListeners();
    }

    public static void setHint(String h) {
        if (!h.equals(hint)) {
            hint = h;
            notifyListeners();
        }
    }

    public static void notifyListeners() {
        main.post(() -> {
            for (Listener l : listeners) l.onSyncStateChanged();
        });
    }

    public static void add(Listener l) {
        listeners.add(l);
    }

    public static void remove(Listener l) {
        listeners.remove(l);
    }

    public static boolean isActive() {
        return status == Status.STARTING || status == Status.RUNNING;
    }
}
