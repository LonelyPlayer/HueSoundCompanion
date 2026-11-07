package app.huebeat.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/**
 * One addressable LED strip on the network.
 *
 * Frames are built on the strip's "logical" pixels (all LEDs, or half of them when mirrored),
 * either by a strip effect at full resolution or, for "like the lights", by the lamp renderer on a
 * few dozen segments interpolated smoothly. The strobe overlay goes on top, then the frame is
 * mirrored/reversed, gamma-corrected and sent.
 */
public final class StripOutput {
    private static final int MAX_SEGMENTS = 40;

    public final String name;
    public final int leds;
    public final int segments;
    /** Segment positions start → end (-1..1), for the lamp renderer and screen colors. */
    public final float[] segX;
    public final Renderer renderer;
    public final StripEffect effect;
    /** Ambient mode: the strip's own animation (-1 = same as the lights) and color (-1 = the lights'). */
    public volatile int ambientEffect = -1;
    public volatile int ambientColor = -1;
    private int[] pixelColors;
    private Ambient ambient;
    private final Ambient.Settings ambientOwn = new Ambient.Settings();

    /** The strip's own palette, or -1 to use the lights' palette (and screen colors). */
    public volatile int palette = -1;
    private final LightSettings own = new LightSettings();

    private final InetAddress address;
    private final int port, protocol, universe;
    private final boolean mirror, reverse;
    /** The strip's own brightness slider, 0..1, may be changed live. */
    public volatile float brightness;
    /** Which slider sets the brightness: the strip's own and the lights' together, only the own, only the lights'. */
    public static final int BRIGHT_BOTH = 0, BRIGHT_OWN = 1, BRIGHT_LIGHTS = 2;
    public volatile int brightnessMode = BRIGHT_BOTH;
    /** The lights' brightness for the current frame (music: max brightness, just lights: brightness). */
    private float lightsLevel = 1f;
    private final int span;
    private final StripProtocol proto = new StripProtocol();
    private final float[] logical;
    private final byte[] pixels;
    /** Temporal dithering: per LED a threshold that walks through 0..1, the level it last moved
     *  from, and how many frames it has stood still. */
    private final float[] phase;
    private final float[] ditherRef;
    private final int[] still;
    private final float[] want = new float[3];
    private static final int STILL_FRAMES = 50; // 1 s at 50 fps

    public StripOutput(String name, InetAddress address, int protocol, int leds, int universe,
                       boolean mirror, boolean reverse, float brightness) {
        this.name = name;
        this.address = address;
        this.protocol = protocol;
        this.port = StripProtocol.PORTS[Math.max(0, Math.min(StripProtocol.PORTS.length - 1, protocol))];
        this.leds = Math.max(1, leds);
        this.universe = universe;
        this.mirror = mirror;
        this.reverse = reverse;
        this.brightness = Math.max(0f, Math.min(1f, brightness));
        span = mirror ? (this.leds + 1) / 2 : this.leds;
        segments = Math.max(1, Math.min(MAX_SEGMENTS, span));
        segX = new float[segments];
        for (int i = 0; i < segments; i++) segX[i] = segments == 1 ? 0f : -1f + 2f * i / (segments - 1);
        renderer = new Renderer(segX);
        effect = new StripEffect(span);
        logical = new float[3 * span];
        pixels = new byte[3 * this.leds];
        phase = new float[this.leds];
        ditherRef = new float[this.leds];
        still = new int[this.leds];
        // different start offsets per LED, so neighbours step at different moments (no strip-wide pulsing)
        java.util.Random rnd = new java.util.Random(1);
        for (int i = 0; i < phase.length; i++) phase[i] = rnd.nextFloat();
    }

    /** Screen / camera colors for this strip's segments (rgb per segment), or null. */
    public void setScreenColors(float[] seg) {
        renderer.setScreenColors(seg);
        effect.setScreenColors(seg, seg == null ? 0 : segments);
    }

    /** Renders one frame into the logical pixels and returns them (rgb 0..1). */
    public float[] render(Features f, double t, float dt, LightSettings lights) {
        // brightness is applied once at the end (see brightnessMode), not inside the effect
        own.copyFrom(lights);
        own.maxBrightness = 1f;
        lightsLevel = lights.maxBrightness;
        if (palette >= 0) {
            own.palette = Math.min(palette, Palettes.count() - 1);
            own.screenColors = false;
        }
        LightSettings s = own;
        if (effect.effect == StripEffect.LIGHTS) {
            float[] seg = renderer.render(f, t, dt, s);
            upsample(seg, segments, logical, span);
        } else {
            float[] px = effect.render(f, dt, s);
            System.arraycopy(px, 0, logical, 0, logical.length);
        }
        applyBlur();
        effect.strobeOnly(logical, f, dt);
        return logical;
    }

    /** Ambient mode frame: the lights' animation or the strip's own, in its palette and color. */
    public float[] renderAmbient(float dt, Ambient.Settings lights) {
        if (ambient == null) ambient = new Ambient(Ambient.line(span), true);
        ambientOwn.copyFrom(lights);
        if (ambientEffect >= 0) ambientOwn.animation = ambientEffect;
        if (palette >= 0) ambientOwn.palette = palette;
        int own = ambientColor;
        ambientOwn.color = own >= 0 ? own : lights.color;
        ambientOwn.speed = effect.speed;
        // static colors: each pixel its color; "same as the lights" = the lamps' colors along the strip
        if (pixelColors == null || pixelColors.length != span) pixelColors = new int[span];
        for (int p = 0; p < span; p++) {
            float x = span == 1 ? 0f : -1f + 2f * p / (span - 1);
            pixelColors[p] = own >= 0 ? own : LampColors.at(x, lights.color);
        }
        ambientOwn.brightness = 1f;
        lightsLevel = lights.brightness;
        float[] px = ambient.render(dt, ambientOwn, pixelColors);
        System.arraycopy(px, 0, logical, 0, logical.length);
        applyBlur();
        return logical;
    }

    /** The last frame mapped onto the physical LEDs (0xRRGGBB each), e.g. to keep it after stopping. */
    public int[] physicalColors() {
        byte[] px = buildPixels(logical, false);
        int[] c = new int[leds];
        for (int i = 0; i < leds; i++) c[i] = ((px[3 * i] & 0xFF) << 16) | ((px[3 * i + 1] & 0xFF) << 8) | (px[3 * i + 2] & 0xFF);
        return c;
    }

    /** Shows one plain color (used during the room-light calibration). */
    public float[] fill(float[] c) {
        lightsLevel = 1f;
        for (int i = 0; i < span; i++) {
            logical[3 * i] = c[0];
            logical[3 * i + 1] = c[1];
            logical[3 * i + 2] = c[2];
        }
        return logical;
    }

    /** Average color of the last frame (for the UI). */
    public float[] meanColor() {
        float[] m = new float[3];
        for (int i = 0; i < span; i++) {
            m[0] += logical[3 * i] / span;
            m[1] += logical[3 * i + 1] / span;
            m[2] += logical[3 * i + 2] / span;
        }
        return m;
    }

    /**
     * The blur slider: 0 = off, 1 = colors flow into each other over about a third of the strip.
     * Mixed in linear light (like overlapping real light), so blends stay bright instead of going muddy.
     */
    private void applyBlur() {
        float b = effect.blur;
        if (b <= 0.005f || span < 3) return;
        float sigma = Math.min(span / 2f, b * b * 0.12f * span + 2.5f * b);
        for (int i = 0; i < 3 * span; i++) logical[i] = (float) Math.pow(Math.max(0f, logical[i]), 2.2);
        LedFx.blurRgb(logical, span, sigma);
        for (int i = 0; i < 3 * span; i++) logical[i] = (float) Math.pow(Math.max(0f, logical[i]), 1 / 2.2);
    }

    private static void upsample(float[] src, int ns, float[] dst, int nd) {
        for (int p = 0; p < nd; p++) {
            float s = nd == 1 ? 0f : (float) p / (nd - 1) * (ns - 1);
            int a = Math.min(ns - 1, (int) s), b = Math.min(ns - 1, a + 1);
            float f = s - a;
            for (int k = 0; k < 3; k++) dst[3 * p + k] = src[3 * a + k] * (1 - f) + src[3 * b + k] * f;
        }
    }

    /** Maps the logical frame onto the physical LEDs (mirror, reverse), brightness and gamma. */
    byte[] buildPixels(float[] lg, boolean dithered) {
        float half = (leds - 1) / 2f;
        float own = Math.max(0f, Math.min(1f, brightness)), lit = Math.max(0f, Math.min(1f, lightsLevel));
        int mode = brightnessMode;
        float level = mode == BRIGHT_OWN ? own : mode == BRIGHT_LIGHTS ? lit : own * lit;
        for (int p = 0; p < leds; p++) {
            float t;
            if (mirror) t = half <= 0 ? 0f : Math.abs(p - half) / half; // 0 = centre, 1 = ends
            else t = leds == 1 ? 0f : (float) p / (leds - 1);
            if (reverse) t = 1f - t;
            float s = t * (span - 1);
            int a = Math.min(span - 1, (int) s), b = Math.min(span - 1, a + 1);
            float f = s - a;
            float maxWant = 0f;
            for (int k = 0; k < 3; k++) {
                float v = lg[3 * a + k] * (1 - f) + lg[3 * b + k] * f;
                v = Math.max(0f, Math.min(1f, v)) * level;
                // WS2812-type LEDs are linear; the frames are perceptual → extra gamma
                want[k] = (float) Math.pow(v, 1.6) * 255f;
                maxWant = Math.max(maxWant, want[k]);
            }
            boolean dither = false;
            if (dithered) {
                // an LED that hasn't moved by a quarter level for a second is a still image: no dithering
                if (Math.abs(maxWant - ditherRef[p]) > 0.25f) {
                    ditherRef[p] = maxWant;
                    still[p] = 0;
                } else if (still[p] < STILL_FRAMES) {
                    still[p]++;
                }
                // in the very dark, switching levels would change the hue visibly: leave it off there
                dither = still[p] < STILL_FRAMES && maxWant >= 3f;
            }
            // one threshold for all three channels of an LED, so they step up and down together and
            // the hue stays put; it walks through 0..1 (golden ratio) so the average hits the exact value
            float th = 0.5f;
            if (dither) {
                phase[p] = (phase[p] + 0.618034f) % 1f;
                th = phase[p];
            }
            for (int k = 0; k < 3; k++) {
                int o = Math.max(0, Math.min(255, (int) Math.floor(want[k] + th)));
                pixels[3 * p + k] = (byte) o;
            }
        }
        return pixels;
    }

    /** Sends the logical frame. */
    public void send(DatagramSocket socket, float[] lg) throws IOException {
        byte[] px = buildPixels(lg, true);
        List<byte[]> packets = proto.packets(protocol, px, leds, universe);
        for (byte[] p : packets) socket.send(new DatagramPacket(p, p.length, address, port));
    }
}
