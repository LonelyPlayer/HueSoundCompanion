package app.huebeat;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import app.huebeat.core.DtlsPskClient;
import app.huebeat.core.HueStream;

/**
 * The connection to the Hue bridge, on its own thread: sets up the app's entertainment
 * configuration, starts streaming and sends the frames. The sync never waits for it, so a bridge
 * that's off or out of reach doesn't hold up the other lights; it keeps trying in the background
 * and the lamps join as soon as the bridge answers.
 */
final class HueLink {
    interface Listener {
        void changed();
    }

    private final Prefs prefs;
    private final List<HueLamp> lamps;
    private final String ip, user, key;
    private final Listener listener;
    private final Thread thread;
    private final Object lock = new Object();
    private volatile boolean running = true;

    private volatile HueApi.ChannelMap newMap;
    private volatile HueStream stream;
    private volatile boolean connected;
    /** What's wrong right now (empty when fine). */
    volatile String problem = "";
    private byte[] frame;

    HueLink(Prefs prefs, List<HueLamp> lamps, Listener listener) {
        this.prefs = prefs;
        this.lamps = new ArrayList<>(lamps);
        this.ip = prefs.bridgeIp();
        this.user = prefs.username();
        this.key = prefs.clientKey();
        this.listener = listener;
        thread = new Thread(this::run, "hue-link");
        thread.start();
    }

    /** The lamps' channels, once the bridge answered (then null again until it changes). */
    HueApi.ChannelMap takeMap() {
        HueApi.ChannelMap m = newMap;
        newMap = null;
        return m;
    }

    boolean connected() {
        return connected;
    }

    /** Hands over the colors of this frame; never blocks (frames are dropped while offline). */
    void send(int[] channelIds, float[] rgb) {
        HueStream s = stream;
        if (!connected || s == null || channelIds.length == 0) return;
        byte[] p = s.build(channelIds, rgb);
        synchronized (lock) {
            frame = p;
            lock.notify();
        }
    }

    /** Stops streaming; waits briefly so the bridge is free again for other apps. */
    void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void setProblem(String p) {
        if (!p.equals(problem)) {
            problem = p;
            if (listener != null) listener.changed();
        }
    }

    private void run() {
        String area = null;
        boolean streaming = false;
        DtlsPskClient dtls = null;
        long wait = 3000;
        while (running) {
            try {
                if (area == null) {
                    Setup s = setup(prefs, lamps);
                    area = s.id;
                    stream = new HueStream(area);
                    newMap = s.map;
                }
                HueApi.setStreaming(ip, user, area, true);
                streaming = true;
                dtls = connect(ip, user, key);
                connected = true;
                wait = 3000;
                setProblem("");
                darkenSwitchedOff();
                long again = System.currentTimeMillis() + 3000; // once more, in case the old stream ended late
                int failures = 0;
                while (running) {
                    byte[] p;
                    synchronized (lock) {
                        if (frame == null) lock.wait(1000);
                        p = frame;
                        frame = null;
                    }
                    if (again > 0 && System.currentTimeMillis() > again) {
                        again = 0;
                        darkenSwitchedOff();
                    }
                    if (p == null) continue;
                    try {
                        dtls.send(p);
                        failures = 0;
                    } catch (IOException e) {
                        if (++failures > 3) throw e;
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                if (!running) break;
                connected = false;
                setProblem("Hue bridge not reachable, trying again… (" + reason(e) + ")");
                if (dtls != null) dtls.close();
                dtls = null;
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    break;
                }
                wait = Math.min(15000, wait * 2);
            }
        }
        connected = false;
        Thread.interrupted(); // the calls below must not be cut short
        if (dtls != null) dtls.close();
        if (streaming && area != null) {
            try {
                HueApi.setStreaming(ip, user, area, false);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Lamps switched off in the app aren't in the stream; when the old stream ends, the bridge
     * gives them back their earlier state. So they're switched off here, once streaming runs.
     */
    private void darkenSwitchedOff() {
        java.util.Set<String> streamed = new java.util.HashSet<>();
        for (HueLamp h : lamps) streamed.add(h.entService);
        for (HueLamp h : prefs.hueLamps()) {
            if (h.enabled || streamed.contains(h.entService) || h.lightId.isEmpty()) continue;
            try {
                HueApi.setLightOn(ip, user, h.lightId, false);
            } catch (IOException ignored) {
            }
        }
    }

    private static String reason(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("HTTP 403") || m.contains("unauthorized")) return "it rejected the app, connect it again under + Add light";
        return m;
    }

    static final class Setup {
        String id;
        HueApi.ChannelMap map;
    }

    /**
     * Puts the streamed lamps into the app's own entertainment configuration on the bridge
     * (created or updated when the set of lamps changed) and reads its channels.
     */
    static Setup setup(Prefs prefs, List<HueLamp> lamps) throws IOException {
        String ip = prefs.bridgeIp(), user = prefs.username();
        String id = prefs.hueConfigId();
        StringBuilder want = new StringBuilder();
        for (HueLamp h : lamps) want.append(h.entService).append(',');
        if (id == null || !want.toString().equals(prefs.hueConfigKey())) {
            if (id != null) {
                try {
                    HueApi.setStreaming(ip, user, id, false);
                } catch (IOException ignored) {
                }
            }
            List<HueApi.Placed> placed = new ArrayList<>();
            for (HueLamp h : lamps) {
                HueApi.Placed p = new HueApi.Placed();
                p.entService = h.entService;
                p.positions = h.gradient() ? new float[][]{{h.x, h.y, h.z}, {h.x2, h.y2, h.z}} : new float[][]{{h.x, h.y, h.z}};
                placed.add(p);
            }
            id = HueApi.ensureConfig(ip, user, id, placed);
            prefs.setHueConfigId(id);
            prefs.setHueConfigKey(want.toString());
        }
        Setup s = new Setup();
        s.id = id;
        try {
            s.map = HueApi.channelMap(ip, user, id);
        } catch (IOException e) {
            if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                    || e instanceof java.net.NoRouteToHostException) throw e;
            // removed on the bridge in the meantime: make it again
            prefs.setHueConfigKey("");
            prefs.setHueConfigId(null);
            return setup(prefs, lamps);
        }
        return s;
    }

    static DtlsPskClient connect(String ip, String user, String key) throws IOException {
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
}
