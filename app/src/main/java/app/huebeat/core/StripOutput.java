package app.huebeat.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;

/**
 * One addressable LED strip on the network. The effects are rendered on a few dozen "segments"
 * (so sparkles and beats are visible as patches, not single pixels), then interpolated smoothly
 * to every LED, mirrored/reversed if wanted, gamma-corrected and sent.
 */
public final class StripOutput {
    private static final int MAX_SEGMENTS = 40;

    public final String name;
    public final int leds;
    public final int segments;
    /** Segment positions left → right (-1..1), as the renderer expects. */
    public final float[] segX;
    public final Renderer renderer;

    private final InetAddress address;
    private final int port, protocol, universe;
    private final boolean mirror, reverse;
    private final float brightness;
    private final StripProtocol proto = new StripProtocol();
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
        int span = mirror ? (this.leds + 1) / 2 : this.leds;
        segments = Math.max(1, Math.min(MAX_SEGMENTS, span));
        segX = new float[segments];
        for (int i = 0; i < segments; i++) segX[i] = segments == 1 ? 0f : -1f + 2f * i / (segments - 1);
        renderer = new Renderer(segX);
        pixels = new byte[3 * this.leds];
        // WS2812-type LEDs are linear; the renderer's values are perceptual → extra gamma
        for (int i = 0; i <= 1024; i++) gammaLut[i] = (float) Math.pow(i / 1024.0, 1.6);
    }

    /** Builds the LED bytes from per-segment colors (rgb 0..1). Package-visible for tests. */
    byte[] buildPixels(float[] seg) {
        float half = (leds - 1) / 2f;
        for (int p = 0; p < leds; p++) {
            float t;
            if (mirror) t = half <= 0 ? 0f : Math.abs(p - half) / half; // 0 = centre, 1 = ends
            else t = leds == 1 ? 0.5f : (float) p / (leds - 1);
            if (reverse) t = 1f - t;
            float s = t * (segments - 1);
            int a = Math.min(segments - 1, (int) s), b = Math.min(segments - 1, a + 1);
            float f = s - a;
            for (int k = 0; k < 3; k++) {
                float v = seg[3 * a + k] * (1 - f) + seg[3 * b + k] * f;
                v = Math.max(0f, Math.min(1f, v)) * brightness;
                pixels[3 * p + k] = (byte) Math.round(gammaLut[Math.round(v * 1024)] * 255f);
            }
        }
        return pixels;
    }

    public void send(DatagramSocket socket, float[] seg) throws IOException {
        byte[] px = buildPixels(seg);
        List<byte[]> packets = proto.packets(protocol, px, leds, universe);
        for (byte[] p : packets) socket.send(new DatagramPacket(p, p.length, address, port));
    }
}
