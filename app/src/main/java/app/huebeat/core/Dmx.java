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

    public static boolean hasGobo(int[] roles) {
        for (int r : roles) if (r == GOBO) return true;
        return false;
    }

    /** Can show colors (RGB channels or a color wheel); a plain dimmer or UV light can't. */
    public static boolean hasColor(int[] roles) {
        for (int r : roles) if (r == RED || r == GREEN || r == BLUE || r == WHEEL || r == WHITE) return true;
        return false;
    }

    public static boolean hasStrobe(int[] roles) {
        for (int r : roles) if (r == STROBE) return true;
        return false;
    }

    // ------------------------------------------------------------------ per-light choices

    /** What a light shows in music sync. */
    public static final int FX_LIKE = 0, FX_BEAT = 1, FX_MELODY = 2, FX_CHORDS = 3, FX_SPECTRUM = 4, FX_AMBIENT = 5,
            FX_PARTY = 6, FX_SOLID_PULSE = 7, FX_SOLID = 8, FX_OFF = 9;
    public static final String[] EFFECTS = {"Like the lights", "Beat", "Melody", "Chords", "Spectrum", "Ambient", "Party",
            "Solid color · pulse on beats", "Solid color · steady", "Off"};
    public static final String[] EFFECT_CATEGORIES = {"Follow", "Own style", "Own style", "Own style", "Own style", "Own style",
            "Own style", "Fixed color", "Fixed color", "Fixed color"};
    private static final int[] FX_STYLE = {-1, LightSettings.STYLE_BEAT, LightSettings.STYLE_MELODY, LightSettings.STYLE_CHORDS,
            LightSettings.STYLE_SPECTRUM, LightSettings.STYLE_AMBIENT, LightSettings.STYLE_PARTY, -1, -1, -1};

    /** How a moving head moves. */
    public static final int MOVE_FIXED = 0, MOVE_SWING = 1, MOVE_CIRCLE = 2, MOVE_EIGHT = 3, MOVE_WANDER = 4, MOVE_SWEEP = 5,
            MOVE_NOD = 6, MOVE_MELODY = 7;
    public static final String[] MOVES = {"Fixed", "Swing on beats", "Circle", "Figure 8", "Wander", "Sweep", "Nod on beats",
            "Follow melody"};

    /** Gobo choice: open, one of 7 gobos, or a new one every bar. */
    public static final String[] STROBE_MODES = {"Off", "Strong beats", "High intensity", "Build-ups", "High intensity + build-ups"};

    public static final String[] GOBOS = {"Open", "1", "2", "3", "4", "5", "6", "7", "New every bar"};
    static final int[] GOBO_DMX = {7, 23, 39, 54, 70, 86, 101, 117};

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
        public volatile float panCenter = 0.5f, tiltCenter = 0.5f, move = 0.5f;
        /** 0 = no strobe, 1 = strong beats, 2 = high intensity, 3 = build-ups, 4 = high intensity + build-ups. */
        public volatile int strobeMode = 0;
        /** Music: what it shows (FX_*), its own palette (-1 = the lights'), its color for the solid effects. */
        public volatile int effect = FX_LIKE, palette = -1, color = 0xFF2A6D;
        /** Just lights: own animation (-1 = the lights') and color. */
        public volatile int ambientEffect = -1, ambientColor = -1;
        /** Moving heads: movement type, its tempo (0.5 = one cycle per bar) and the gobo. */
        public volatile int movement = MOVE_SWING, gobo = 0;
        public volatile float moveSpeed = 0.5f;
        /** Groups: how members move together (0 same, 1 mirrored, 2 chase), this light's place in its group. */
        public volatile int together = 0, memberIdx = 0, memberCount = 1;
        /** Channel tester: when not null, these raw values are sent instead of the show. */
        public volatile int[] test;

        // state
        private float pan = 0.5f, tilt = 0.5f, panT = 0.5f, tiltT = 0.5f;
        private float peak = 0.4f;
        private boolean side;
        private int wheelSlot = 0;
        private float wheelHold = 0f;
        private float strobeLeft = 0f;
        private double ambientT = 0;
        private final Random rnd;
        private double phase = 0;
        private float glide = 0.2f, nod = 0f, nodLevel = 0f, wanderLeft = 0f, buildStrobe = 0f;
        private final Cues.Timer cueTimer = new Cues.Timer();
        private int lastSnare = Integer.MIN_VALUE;
        private int barBeats = 0, goboSlot = 1;
        // own looks
        private Renderer ownRenderer;
        private Ambient ownAmbient;
        private final LightSettings ownLs = new LightSettings();
        private final Ambient.Settings ownAs = new Ambient.Settings();
        private float env = 0f;
        private final float[] one = new float[3];

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

        /** Members of a group share the random choices (same swings, same wander points). */
        public void seed(String group) {
            rnd.setSeed(group.hashCode() * 7919L);
        }

        String key() {
            return ip + "|" + protocol + "|" + universe;
        }

        /** The light's own color in music sync (or the shared one for "Like the lights"). */
        float[] musicColor(float[] shared, int i, Features f, double t, float dt, LightSettings lights, boolean beat) {
            int fx = effect;
            if (fx == FX_LIKE && palette < 0) {
                one[0] = shared[3 * i];
                one[1] = shared[3 * i + 1];
                one[2] = shared[3 * i + 2];
                return one;
            }
            if (fx == FX_OFF) {
                one[0] = one[1] = one[2] = 0f;
                return one;
            }
            if (fx == FX_SOLID || fx == FX_SOLID_PULSE) {
                float v = 1f;
                if (fx == FX_SOLID_PULSE) {
                    float inten = lights.intensity, period = f != null && f.bpm > 0 ? 60f / f.bpm : 0.5f;
                    if (beat) env = 1f;
                    float tau = Math.max(0.06f, (1 - inten) * Math.max(0.25f, period * 0.9f) + inten * 0.06f);
                    env *= (float) Math.exp(-dt / tau);
                    float floor = (0.45f - 0.43f * inten);
                    v = floor + (1f - floor) * env;
                }
                int c = color;
                one[0] = ((c >> 16) & 0xFF) / 255f * v;
                one[1] = ((c >> 8) & 0xFF) / 255f * v;
                one[2] = (c & 0xFF) / 255f * v;
                return one;
            }
            // an own style (or "like the lights" in its own palette): its own renderer
            if (ownRenderer == null) ownRenderer = new Renderer(new float[]{x});
            ownLs.copyFrom(lights);
            ownLs.maxBrightness = 1f;
            if (FX_STYLE[fx] >= 0) ownLs.style = FX_STYLE[fx];
            if (palette >= 0) {
                ownLs.palette = Math.min(palette, Palettes.count() - 1);
                ownLs.screenColors = false;
            }
            float[] c = ownRenderer.render(f, t, dt, ownLs);
            one[0] = c[0];
            one[1] = c[1];
            one[2] = c[2];
            return one;
        }

        /** The light's own color in "Just lights". */
        float[] ambientColor(float[] shared, int i, float dt, Ambient.Settings lights) {
            if (ambientEffect < 0 && palette < 0 && ambientColor < 0) {
                one[0] = shared[3 * i];
                one[1] = shared[3 * i + 1];
                one[2] = shared[3 * i + 2];
                return one;
            }
            if (ownAmbient == null) ownAmbient = new Ambient(new float[]{clamp01((x + 1f) / 2f)}, false);
            ownAs.copyFrom(lights);
            ownAs.brightness = 1f;
            if (ambientEffect >= 0) ownAs.animation = ambientEffect;
            ownAmbient.follow = ambientEffect < 0 ? Ambient.master : null;
            if (palette >= 0) ownAs.palette = Math.min(palette, Palettes.count() - 1);
            int col = ambientColor >= 0 ? ambientColor : LampColors.at(x, lights.color);
            ownAs.color = ambientColor >= 0 ? ambientColor : lights.color;
            float[] c = ownAmbient.render(dt, ownAs, new int[]{col});
            one[0] = c[0];
            one[1] = c[1];
            one[2] = c[2];
            return one;
        }

        /**
         * Where the head points. Music: cycles are tied to the tempo (Speed 0.5 = one per bar);
         * just lights: slow, set by the speed slider.
         */
        void motion(Features f, float dt, boolean beat, boolean ambient, float ambSpeed) {
            float mul = (float) Math.pow(2.0, (moveSpeed - 0.5) * 4.0); // ¼× … 4×
            float bpm = f != null && f.bpm > 0 ? f.bpm : 120f;
            float cps = ambient ? (0.02f + 0.2f * ambSpeed) * mul : bpm / 60f / 4f * mul;
            phase += dt * cps;
            boolean chase = together == 2 && memberCount > 1;
            // chase: each member a step behind the one before it
            double a = 2 * Math.PI * (phase - (chase ? (double) memberIdx / memberCount : 0));
            // chase on beat-driven moves: the beat walks from member to member
            boolean myBeat = beat && (!chase || barBeats % memberCount == memberIdx);
            float m = move;
            boolean snare = f != null && lastSnare != Integer.MIN_VALUE && f.snareCount != lastSnare;
            if (f != null) lastSnare = f.snareCount;
            buildStrobe = 0f;
            if (!ambient && strobeMode >= 2) {
                int cue = strobeMode == 2 ? Cues.HIGH_INTENSITY : strobeMode == 3 ? Cues.BUILDUPS : Cues.BOTH;
                if (Cues.fire(cue, beat, snare, 0f, null)) strobeLeft = 0.15f;
                // the built-in strobe speeds up with the build (its own rate channel)
                if ((cue & Cues.BUILDUPS) != 0 && Cues.build > 0.5f) buildStrobe = (Cues.build - 0.5f) / 0.5f;
            }
            if (beat && !ambient) {
                barBeats++;
                if (strobeMode == 1 && f.beatStrength > 0.8f) strobeLeft = 0.18f;
                if (gobo == 8 && barBeats % 4 == 0) goboSlot = 1 + (goboSlot % 7);
            }
            if (ambient && gobo == 8) {
                ambientT += dt;
                if (ambientT > 8.0 / Math.max(0.1, mul)) {
                    ambientT = 0;
                    goboSlot = 1 + (goboSlot % 7);
                }
            }
            switch (m <= 0.001f ? MOVE_FIXED : movement) {
                case MOVE_FIXED:
                    panT = panCenter;
                    tiltT = tiltCenter;
                    glide = 0.3f;
                    break;
                case MOVE_SWING:
                    if (ambient) {
                        panT = clamp01(panCenter + 0.5f * m * (float) Math.sin(a));
                        tiltT = clamp01(tiltCenter + 0.4f * m * (float) Math.sin(a * 1.7 + 0.6));
                        glide = 0.3f;
                    } else if (myBeat) {
                        // to the other side of the aim point every beat: big, visible moves
                        side = !side;
                        float reach = 0.5f + 0.5f * rnd.nextFloat();
                        panT = clamp01(panCenter + (side ? 1f : -1f) * reach * m * 0.5f);
                        tiltT = clamp01(tiltCenter + (rnd.nextFloat() * 2f - 1f) * m * 0.4f);
                        glide = 60f / bpm * 0.45f / Math.max(0.5f, Math.min(2f, mul));
                    }
                    break;
                case MOVE_CIRCLE:
                    panT = clamp01(panCenter + 0.5f * m * (float) Math.cos(a));
                    tiltT = clamp01(tiltCenter + 0.4f * m * (float) Math.sin(a));
                    glide = 0.06f;
                    break;
                case MOVE_EIGHT:
                    panT = clamp01(panCenter + 0.5f * m * (float) Math.sin(a));
                    tiltT = clamp01(tiltCenter + 0.4f * m * (float) Math.sin(2 * a));
                    glide = 0.06f;
                    break;
                case MOVE_WANDER:
                    wanderLeft -= dt;
                    if (wanderLeft <= 0f) {
                        panT = clamp01(panCenter + (rnd.nextFloat() * 2f - 1f) * m * 0.5f);
                        tiltT = clamp01(tiltCenter + (rnd.nextFloat() * 2f - 1f) * m * 0.4f);
                        wanderLeft = 1f / Math.max(0.05f, cps) * 0.5f;
                        glide = wanderLeft * 0.45f;
                    }
                    break;
                case MOVE_SWEEP: {
                    float tri = (float) (2 * Math.abs(phase - Math.floor(phase + 0.5))) * 2f - 1f; // -1..1..-1
                    panT = clamp01(panCenter + 0.5f * m * tri);
                    tiltT = tiltCenter;
                    glide = 0.06f;
                    break;
                }
                case MOVE_NOD:
                    if (ambient) {
                        nod = (float) (0.5 + 0.5 * Math.sin(a * 2));
                    } else {
                        // down on the beat, hold for a moment, then swing back up before the next one
                        if (myBeat) nod = 0f;
                        nod += dt;
                        float per = 60f / bpm;
                        float down = Math.max(0.12f, per * 0.3f);
                        nodLevel = nod < down ? 1f : (float) Math.exp(-(nod - down) / (per * 0.25f));
                    }
                    panT = clamp01(panCenter + 0.35f * m * (float) Math.sin(a * 0.25));
                    tiltT = clamp01(tiltCenter - 0.4f * m * (ambient ? nod : nodLevel));
                    glide = 0.05f;
                    break;
                case MOVE_MELODY:
                    if (f != null && f.melody > 0 && f.melodyConf > 0.08f) {
                        float pc = (f.melody / 12f) - (float) Math.floor(f.melody / 12f);
                        float height = clamp01((f.melody - 55f) / 30f);
                        panT = clamp01(panCenter + (pc - 0.5f) * m);
                        tiltT = clamp01(tiltCenter + (height - 0.5f) * m * 0.8f);
                    }
                    glide = 0.15f;
                    break;
                default:
                    break;
            }
        }

        /** Writes the channels for color {@code rgb} (0..1, brightness included) into the universe. */
        void write(byte[] dmx, float r, float g, float b, float dt, float lightsLevel) {
            float glideTau = glide;
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
            // the loudest moments of the last seconds count as full: beat peaks really reach 255
            peak = Math.max(Math.max(level, 0.3f), peak - dt * 0.12f);
            float norm = clamp01(level / peak);
            // no extra gamma: fixtures have their own dimmer curve
            float lin = norm * bright;
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
            // mirrored groups: every second member swings the other way around its own aim
            float panOut = together == 1 && memberIdx % 2 == 1 ? clamp01(2f * panCenter - pan) : pan;
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
                    case STROBE: v = strobeLeft > 0f ? 230 : buildStrobe > 0f ? Math.round(30 + 225 * buildStrobe) : 0; break;
                    case PAN: v = (int) (clamp01(panOut) * 65535f) >> 8; break;
                    case PAN_FINE: v = (int) (clamp01(panOut) * 65535f) & 0xFF; break;
                    case TILT: v = (int) (clamp01(tilt) * 65535f) >> 8; break;
                    case TILT_FINE: v = (int) (clamp01(tilt) * 65535f) & 0xFF; break;
                    case PT_SPEED: v = 0; break; // fastest: the app glides the movement itself
                    case WHEEL: v = WHEEL_DMX[wheelSlot]; break;
                    case GOBO: v = gobo == 0 ? 0 : GOBO_DMX[gobo == 8 ? goboSlot : Math.min(7, gobo)]; break;
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
            float[] shared = renderer.render(f, t, dt, own);
            boolean beat = f != null && f.beatCount != lastBeat && lastBeat != Integer.MIN_VALUE;
            if (f != null) lastBeat = f.beatCount;
            float[] rgb = new float[3 * fixtures.size()];
            for (int i = 0; i < fixtures.size(); i++) {
                float[] c = fixtures.get(i).musicColor(shared, i, f, t, dt, own, beat);
                System.arraycopy(c, 0, rgb, 3 * i, 3);
            }
            if (overlay != null) overlay.apply(rgb, fixtures.size());
            for (byte[] u : universes.values()) java.util.Arrays.fill(u, (byte) 0);
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture fx = fixtures.get(i);
                fx.motion(f, dt, beat, false, 0f);
                fx.write(universes.get(fx.key()), rgb[3 * i], rgb[3 * i + 1], rgb[3 * i + 2], dt, s.maxBrightness);
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
            // static colors: every light takes the lamp color at its place
            int[] cols = new int[fixtures.size()];
            for (int i = 0; i < cols.length; i++) cols[i] = LampColors.at(xs[i], s.color);
            ambient.follow = Ambient.master;
            float[] shared = ambient.render(dt, own, cols);
            float[] rgb = new float[3 * fixtures.size()];
            for (byte[] u : universes.values()) java.util.Arrays.fill(u, (byte) 0);
            for (int i = 0; i < fixtures.size(); i++) {
                Fixture fx = fixtures.get(i);
                float[] c = fx.ambientColor(shared, i, dt, own);
                System.arraycopy(c, 0, rgb, 3 * i, 3);
                fx.motion(null, dt, false, true, s.speed);
                fx.write(universes.get(fx.key()), c[0], c[1], c[2], dt, s.brightness);
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
