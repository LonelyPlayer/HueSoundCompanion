package app.huebeat.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * DMX lights behind an Art-Net / sACN node. Every fixture is a list of channel roles (red,
 * dimmer, pan, color wheel, …); the lamp renderer (or the just-lights animation) gives each
 * fixture a color and brightness, and the roles turn that into channel values.
 */
public final class Dmx {
    private Dmx() {
    }

    // ------------------------------------------------------------------ channel roles

    public static final int OFF = 0, FULL = 1, DIMMER = 2, RED = 3, GREEN = 4, BLUE = 5, WHITE = 6, AMBER = 7, UV = 8,
            STROBE = 9, PAN = 10, PAN_FINE = 11, TILT = 12, TILT_FINE = 13, PT_SPEED = 14, WHEEL = 15, GOBO = 16;
    public static final String[] ROLE_NAMES = {"Off (0)", "Full (255)", "Dimmer", "Red", "Green", "Blue", "White", "Amber", "UV",
            "Strobe", "Pan", "Pan fine", "Tilt", "Tilt fine", "Move speed", "Color wheel", "Gobo (open)"};

    /** Color wheel of the PicoSpot 20 LED (center DMX value of each slot and its color). */
    static final int[] WHEEL_DMX = {5, 16, 27, 38, 49, 60, 71, 82};
    static final int[] WHEEL_RGB = {0xFFFFFF, 0xFF0000, 0xFF7000, 0xFFE000, 0x00FF00, 0x0000FF, 0x00FFFF, 0xA000FF};

    // ------------------------------------------------------------------ presets

    public static final class Preset {
        public final String name;
        public final int[] roles;
        public final String note;

        Preset(String name, String note, int... roles) {
            this.name = name;
            this.note = note;
            this.roles = roles;
        }
    }

    public static final Preset[] PRESETS = {
            new Preset("Eurolite LED PARty TCL Spot (5 ch)", "Set the spot to 5-channel DMX mode.",
                    RED, GREEN, BLUE, DIMMER, STROBE),
            new Preset("Fun Generation PicoSpot 20 LED (11 ch)", "Set the PicoSpot to 11-channel mode. Programs stay off.",
                    PAN, TILT, PAN_FINE, TILT_FINE, PT_SPEED, WHEEL, GOBO, DIMMER, STROBE, OFF, OFF),
            new Preset("Fun Generation PicoSpot 20 LED (9 ch)", "Set the PicoSpot to 9-channel mode.",
                    PAN, TILT, PAN_FINE, TILT_FINE, PT_SPEED, WHEEL, GOBO, DIMMER, STROBE),
            new Preset("Fun Generation PicoSpot 20 LED (5 ch)", "5-channel mode: no strobe, no fine movement.",
                    PAN, TILT, PT_SPEED, WHEEL, DIMMER),
            new Preset("UKing UV 36 LED 72 W (7 ch, unverified)",
                    "No channel chart was published for this model. This is the usual UKing layout; check it with the channel tester.",
                    DIMMER, UV, UV, UV, STROBE, OFF, OFF),
            new Preset("RGB (3 ch)", "", RED, GREEN, BLUE),
            new Preset("RGBW (4 ch)", "", RED, GREEN, BLUE, WHITE),
            new Preset("Dimmer + RGB (4 ch)", "", DIMMER, RED, GREEN, BLUE),
            new Preset("RGB + Dimmer + Strobe (5 ch)", "", RED, GREEN, BLUE, DIMMER, STROBE),
            new Preset("Dimmer (1 ch)", "", DIMMER),
            new Preset("UV (1 ch)", "", UV),
            new Preset("Custom", "Set every channel's role yourself.", DIMMER),
    };
    public static final int CUSTOM = PRESETS.length - 1;

    public static boolean moves(int[] roles) {
        for (int r : roles) if (r == PAN || r == TILT) return true;
        return false;
    }

    public static boolean hasStrobe(int[] roles) {
        for (int r : roles) if (r == STROBE) return true;
        return false;
    }

    // ------------------------------------------------------------------ one fixture at runtime

    public static final class Fixture {
        public final String name;
        public final String ip;
        public final int protocol; // 0 Art-Net, 1 sACN
        public final int universe;
        public final int address;  // 1..512
        public final float x;      // position in the room, -1 (left) .. 1 (right)
        public volatile int[] roles;
        public volatile float brightness = 1f;
        /** Moving heads: where they point (0..1 of the full range) and how far they roam. */
        public volatile float panCenter = 0.5f, tiltCenter = 0.5f, move = 0.3f;
        /** 0 = no strobe, 1 = on strong beats. */
        public volatile int strobeMode = 0;
        /** Channel tester: when not null, these raw values are sent instead of the show. */
        public volatile int[] test;

        // state
        private float pan = 0.5f, tilt = 0.5f, panT = 0.5f, tiltT = 0.5f;
        private int wheelSlot = 0;
        private float wheelHold = 0f;
        private float strobeLeft = 0f;
        private double ambientT = 0;
        private final Random rnd;

        public Fixture(String name, String ip, int protocol, int universe, int address, float x, int[] roles) {
            this.name = name;
            this.ip = ip;
            this.protocol = protocol;
            this.universe = universe;
            this.address = Math.max(1, Math.min(512, address));
            this.x = x;
            this.roles = roles;
            rnd = new Random(name.hashCode() * 31L + address);
        }

        String key() {
            return ip + "|" + protocol + "|" + universe;
        }

        /** Music: a new target to point at on beats. */
        void onBeat(float beatStrength) {
            if (move <= 0.001f) return;
            panT = clamp01(panCenter + (rnd.nextFloat() - 0.5f) * move);
            tiltT = clamp01(tiltCenter + (rnd.nextFloat() - 0.5f) * move * 0.7f);
            if (strobeMode == 1 && beatStrength > 0.8f) strobeLeft = 0.18f;
        }

        /** Just lights: a slow figure-eight around the aim point. */
        void drift(float dt, float speed) {
            ambientT += dt * (0.15 + 0.6 * speed);
            panT = clamp01(panCenter + 0.5f * move * (float) Math.sin(ambientT));
            tiltT = clamp01(tiltCenter + 0.35f * move * (float) Math.sin(ambientT * 1.7 + 0.6));
        }

        /** Writes the channels for color {@code rgb} (0..1, brightness included) into the universe. */
        void write(byte[] dmx, float r, float g, float b, float dt, float glideTau, float lightsLevel) {
            int[] t = test;
            int[] ro = roles;
            if (t != null) {
                for (int i = 0; i < ro.length && i < t.length; i++) put(dmx, i, t[i]);
                return;
            }
            float level = Math.max(r, Math.max(g, b));
            float cr = level > 1e-4f ? r / level : 0f, cg = level > 1e-4f ? g / level : 0f, cb = level > 1e-4f ? b / level : 0f;
            float bright = clamp01(brightness) * clamp01(lightsLevel);
            // LED fixtures are linear; the frames are perceptual → gamma like the strips
            float lin = (float) Math.pow(clamp01(level), 1.6) * bright;
            boolean hasDimmer = false, hasWheel = false;
            for (int role : ro) {
                hasDimmer |= role == DIMMER;
                hasWheel |= role == WHEEL;
            }
            float colorScale = hasDimmer ? 1f : lin; // no dimmer: the colors carry the brightness
            float w = Math.min(cr, Math.min(cg, cb));
            boolean hasWhite = false;
            for (int role : ro) hasWhite |= role == WHITE;
            if (!hasWhite) w = 0f;

            float k = 1f - (float) Math.exp(-dt / Math.max(0.02f, glideTau));
            pan += (panT - pan) * k;
            tilt += (tiltT - tilt) * k;
            if (hasWheel) updateWheel(cr, cg, cb, level, dt);
            strobeLeft = Math.max(0f, strobeLeft - dt);

            for (int i = 0; i < ro.length; i++) {
                int v;
                switch (ro[i]) {
                    case FULL: v = 255; break;
                    case DIMMER: v = Math.round(lin * 255f); break;
                    case RED: v = Math.round((cr - w) * colorScale * 255f); break;
                    case GREEN: v = Math.round((cg - w) * colorScale * 255f); break;
                    case BLUE: v = Math.round((cb - w) * colorScale * 255f); break;
                    case WHITE: v = Math.round(w * colorScale * 255f); break;
                    case AMBER: v = 0; break;
                    case UV: v = Math.round((hasDimmer ? 1f : lin) * 255f); break;
                    case STROBE: v = strobeLeft > 0f ? 230 : 0; break;
                    case PAN: v = (int) (clamp01(pan) * 65535f) >> 8; break;
                    case PAN_FINE: v = (int) (clamp01(pan) * 65535f) & 0xFF; break;
                    case TILT: v = (int) (clamp01(tilt) * 65535f) >> 8; break;
                    case TILT_FINE: v = (int) (clamp01(tilt) * 65535f) & 0xFF; break;
                    case PT_SPEED: v = 0; break; // fastest: the app glides the movement itself
                    case WHEEL: v = WHEEL_DMX[wheelSlot]; break;
                    case GOBO: v = 0; break;
                    default: v = 0;
                }
                put(dmx, i, v);
            }
        }

        /** Nearest wheel color, with some patience so the wheel doesn't rattle between two slots. */
        private void updateWheel(float r, float g, float b, float level, float dt) {
            wheelHold -= dt;
            int best = 0;
            float bestD = 1e9f;
            for (int s = 0; s < WHEEL_RGB.length; s++) {
                float d = dist(WHEEL_RGB[s], r, g, b);
                if (d < bestD) {
                    bestD = d;
                    best = s;
                }
            }
            if (best == wheelSlot) return;
            float cur = dist(WHEEL_RGB[wheelSlot], r, g, b);
            if (wheelHold <= 0f && (bestD < cur * 0.8f || level < 0.05f)) {
                wheelSlot = best;
                wheelHold = 0.25f; // the wheel needs a moment to turn
            }
        }

        private static float dist(int rgb, float r, float g, float b) {
            float dr = ((rgb >> 16) & 0xFF) / 255f - r, dg = ((rgb >> 8) & 0xFF) / 255f - g, db = (rgb & 0xFF) / 255f - b;
            return dr * dr + dg * dg + db * db;
        }

        private void put(byte[] dmx, int i, int v) {
            int ch = address - 1 + i;
            if (ch >= 0 && ch < 512) dmx[ch] = (byte) Math.max(0, Math.min(255, v));
        }
    }

    // ------------------------------------------------------------------ all DMX lights

    /** Renders and sends all fixtures; one 512-byte buffer per node universe. */
    public static final class Output {
        public final List<Fixture> fixtures;
        private final Map<String, byte[]> universes = new LinkedHashMap<>();
        private final Map<String, Fixture> firstOf = new LinkedHashMap<>();
        private final Map<String, InetAddress> hosts = new LinkedHashMap<>();
        private final Renderer renderer;
        private Ambient ambient;
        private final float[] xs;
        private int seq = 1;
        private final byte[] cid = new byte[16];
        private int lastBeat = Integer.MIN_VALUE;
        private float[] lastRgb;
        /** Build-up overlay (null = none); applied to the colors before they become channels. */
        public Buildup overlay;

        public Output(List<Fixture> fixtures) {
            this.fixtures = fixtures;
            xs = new float[Math.max(1, fixtures.size())];
            for (int i = 0; i < fixtures.size(); i++) xs[i] = fixtures.get(i).x;
            renderer = new Renderer(xs);
            new Random().nextBytes(cid);
            for (Fixture f : fixtures) {
                universes.putIfAbsent(f.key(), new byte[512]);
                firstOf.putIfAbsent(f.key(), f);
            }
        }

        public boolean isEmpty() {
            return fixtures.isEmpty();
        }

        public void setScreenColors(float[] rgb) {
            renderer.setScreenColors(rgb);
        }

        /** Music frame. */
        public float[] render(Features f, double t, float dt, LightSettings s) {
            if (fixtures.isEmpty()) return new float[0];
            LightSettings own = new LightSettings();
            own.copyFrom(s);
            own.maxBrightness = 1f;
            float[] rgb = renderer.render(f, t, dt, own);
            if (overlay != null) {
                rgb = rgb.clone();
                overlay.apply(rgb, rgb.length / 3);
            }
            boolean beat = f != null && f.beatCount != lastBeat && lastBeat != Integer.MIN_VALUE;
            if (f != null) lastBeat = f.beatCount;
            float period = f != null && f.bpm > 0 ? 60f / f.bpm : 0.5f;
            for (byte[] u : universes.values()) java.util.Arrays.fill(u, (byte) 0);
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture fx = fixtures.get(i);
                if (beat) fx.onBeat(f.beatStrength);
                fx.write(universes.get(fx.key()), rgb[3 * i], rgb[3 * i + 1], rgb[3 * i + 2], dt, period * 0.45f, s.maxBrightness);
            }
            lastRgb = rgb;
            return rgb;
        }

        /** Just-lights frame. */
        public float[] renderAmbient(float dt, Ambient.Settings s) {
            if (fixtures.isEmpty()) return new float[0];
            if (ambient == null) {
                float[] pos = new float[xs.length];
                for (int i = 0; i < pos.length; i++) pos[i] = clamp01((xs[i] + 1f) / 2f);
                ambient = new Ambient(pos, false);
            }
            Ambient.Settings own = new Ambient.Settings();
            own.copyFrom(s);
            own.brightness = 1f;
            float[] rgb = ambient.render(dt, own, null);
            for (byte[] u : universes.values()) java.util.Arrays.fill(u, (byte) 0);
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture fx = fixtures.get(i);
                fx.drift(dt, s.speed);
                fx.write(universes.get(fx.key()), rgb[3 * i], rgb[3 * i + 1], rgb[3 * i + 2], dt, 0.6f, s.brightness);
            }
            lastRgb = rgb;
            return rgb;
        }

        /** Everything dark (sent when the sync stops). */
        public void blackout() {
            for (byte[] u : universes.values()) java.util.Arrays.fill(u, (byte) 0);
        }

        public float[] meanColor() {
            float[] m = new float[3];
            float[] rgb = lastRgb;
            if (rgb == null || rgb.length < 3) return m;
            int n = rgb.length / 3;
            for (int i = 0; i < n; i++) for (int k = 0; k < 3; k++) m[k] += rgb[3 * i + k] / n;
            return m;
        }

        public void send(DatagramSocket sock) throws IOException {
            int s = seq;
            seq = seq >= 255 ? 1 : seq + 1;
            IOException err = null;
            for (Map.Entry<String, byte[]> e : universes.entrySet()) {
                Fixture f = firstOf.get(e.getKey());
                try {
                    InetAddress a = hosts.get(f.ip);
                    if (a == null) hosts.put(f.ip, a = InetAddress.getByName(f.ip));
                    byte[] p = f.protocol == 1 ? ArtNet.sacn(f.universe, s, e.getValue(), cid)
                            : ArtNet.dmx(f.universe, s, e.getValue(), 512);
                    sock.send(new DatagramPacket(p, p.length, a, f.protocol == 1 ? 5568 : ArtNet.PORT));
                } catch (IOException ex) {
                    err = ex;
                }
            }
            if (err != null && universes.size() == 1) throw err;
        }

        /** Positions of the fixtures (for screen colors). */
        public float[] positions() {
            return xs;
        }
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    public static List<Fixture> none() {
        return new ArrayList<>();
    }
}
