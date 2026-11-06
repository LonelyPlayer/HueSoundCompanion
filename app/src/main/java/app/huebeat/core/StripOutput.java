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
    /** The strip's own palette, or -1 to use the lights' palette (and screen colors). */
    public volatile int palette = -1;
    private final LightSettings own = new LightSettings();

    private final InetAddress address;
    private final int port, protocol, universe;
    private final boolean mirror, reverse;
    /** 0..1, may be changed live. */
    public volatile float brightness;
    private final int span;
    private final StripProtocol proto = new StripProtocol();
    private final float[] logical;
    private final byte[] pixels;
    private final float[] gammaLut = new float[1025];

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
        // WS2812-type LEDs are linear; the frames are perceptual → extra gamma
        for (int i = 0; i <= 1024; i++) gammaLut[i] = (float) Math.pow(i / 1024.0, 1.6);
    }

    /** Screen / camera colors for this strip's segments (rgb per segment), or null. */
    public void setScreenColors(float[] seg) {
        renderer.setScreenColors(seg);
        effect.setScreenColors(seg, seg == null ? 0 : segments);
    }

    /** Renders one frame into the logical pixels and returns them (rgb 0..1). */
    public float[] render(Features f, double t, float dt, LightSettings lights) {
        LightSettings s = lights;
        if (palette >= 0) {
            own.copyFrom(lights);
            own.palette = Math.min(palette, Palettes.count() - 1);
            own.screenColors = false;
            s = own;
        }
        if (effect.effect == StripEffect.LIGHTS) {
            float[] seg = renderer.render(f, t, dt, s);
            upsample(seg, segments, logical, span);
        } else {
            float[] px = effect.render(f, dt, s);
            System.arraycopy(px, 0, logical, 0, logical.length);
        }
        effect.strobeOnly(logical, f, dt);
        return logical;
    }

    /** Shows one plain color (used during the room-light calibration). */
    public float[] fill(float[] c) {
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

    private static void upsample(float[] src, int ns, float[] dst, int nd) {
        for (int p = 0; p < nd; p++) {
            float s = nd == 1 ? 0f : (float) p / (nd - 1) * (ns - 1);
            int a = Math.min(ns - 1, (int) s), b = Math.min(ns - 1, a + 1);
            float f = s - a;
            for (int k = 0; k < 3; k++) dst[3 * p + k] = src[3 * a + k] * (1 - f) + src[3 * b + k] * f;
        }
    }

    /** Maps the logical frame onto the physical LEDs (mirror, reverse), brightness and gamma. */
    byte[] buildPixels(float[] lg) {
        float half = (leds - 1) / 2f;
        for (int p = 0; p < leds; p++) {
            float t;
            if (mirror) t = half <= 0 ? 0f : Math.abs(p - half) / half; // 0 = centre, 1 = ends
            else t = leds == 1 ? 0f : (float) p / (leds - 1);
            if (reverse) t = 1f - t;
            float s = t * (span - 1);
            int a = Math.min(span - 1, (int) s), b = Math.min(span - 1, a + 1);
            float f = s - a;
            for (int k = 0; k < 3; k++) {
                float v = lg[3 * a + k] * (1 - f) + lg[3 * b + k] * f;
                v = Math.max(0f, Math.min(1f, v)) * Math.max(0f, Math.min(1f, brightness));
                pixels[3 * p + k] = (byte) Math.round(gammaLut[Math.round(v * 1024)] * 255f);
            }
        }
        return pixels;
    }

    /** Sends the logical frame. */
    public void send(DatagramSocket socket, float[] lg) throws IOException {
        byte[] px = buildPixels(lg);
        List<byte[]> packets = proto.packets(protocol, px, leds, universe);
        for (byte[] p : packets) socket.send(new DatagramPacket(p, p.length, address, port));
    }
}
