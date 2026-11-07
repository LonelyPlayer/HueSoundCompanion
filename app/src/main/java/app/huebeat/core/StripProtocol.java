package app.huebeat.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Packet formats for addressable LED controllers on the local network (WLED, ESPixelStick and
 * other ESP32/ESP8266 firmwares), the same ones LedFx uses:
 *
 * - DDP (Distributed Display Protocol), UDP port 4048: WLED's and LedFx's default.
 * - E1.31 / sACN, UDP port 5568: 170 RGB pixels per DMX universe.
 * - WLED UDP realtime (DNRGB), UDP port 21324.
 *
 * Every method returns the UDP payloads for one frame; pixels are 8-bit RGB, already
 * brightness- and gamma-corrected.
 */
public final class StripProtocol {
    public static final int DDP = 0, E131 = 1, WLED_UDP = 2;
    public static final String[] NAMES = {"DDP", "E1.31 (sACN)", "WLED UDP"};
    public static final int[] PORTS = {4048, 5568, 21324};

    private static final int DDP_MAX_PIXELS = 480;     // 1440 data bytes per packet
    private static final int WLED_MAX_PIXELS = 489;    // DNRGB
    private static final int E131_PIXELS_PER_UNIVERSE = 170;

    private int ddpSequence = 1;
    private int e131Sequence = 0;
    private final byte[] cid = new byte[16];

    public StripProtocol() {
        java.util.Random r = new java.util.Random();
        r.nextBytes(cid);
    }

    public List<byte[]> packets(int protocol, byte[] rgb, int pixels, int startUniverse) {
        switch (protocol) {
            case E131: return e131(rgb, pixels, startUniverse);
            case WLED_UDP: return wledDnrgb(rgb, pixels);
            default: return ddp(rgb, pixels);
        }
    }

    /** DDP: 10-byte header (version 1, push on the last packet), RGB 8-bit, output id 1. */
    public List<byte[]> ddp(byte[] rgb, int pixels) {
        List<byte[]> out = new ArrayList<>();
        int seq = ddpSequence;
        ddpSequence = ddpSequence % 15 + 1; // 1..15
        for (int start = 0; start < pixels; start += DDP_MAX_PIXELS) {
            int count = Math.min(DDP_MAX_PIXELS, pixels - start);
            boolean last = start + count >= pixels;
            int len = count * 3, offset = start * 3;
            byte[] p = new byte[10 + len];
            p[0] = (byte) (0x40 | (last ? 0x01 : 0x00)); // version 1, push
            p[1] = (byte) seq;
            p[2] = 0x01;                                 // data type: RGB, as LedFx sends
            p[3] = 0x01;                                 // destination: default output device
            p[4] = (byte) (offset >>> 24);
            p[5] = (byte) (offset >>> 16);
            p[6] = (byte) (offset >>> 8);
            p[7] = (byte) offset;
            p[8] = (byte) (len >>> 8);
            p[9] = (byte) len;
            System.arraycopy(rgb, offset, p, 10, len);
            out.add(p);
        }
        return out;
    }

    /** WLED realtime UDP, protocol 4 (DNRGB): start index + RGB, 2 s timeout back to WLED's own effect. */
    public List<byte[]> wledDnrgb(byte[] rgb, int pixels) {
        List<byte[]> out = new ArrayList<>();
        for (int start = 0; start < pixels; start += WLED_MAX_PIXELS) {
            int count = Math.min(WLED_MAX_PIXELS, pixels - start);
            byte[] p = new byte[4 + count * 3];
            p[0] = 4;               // DNRGB
            p[1] = 2;               // seconds until WLED takes over again after the last packet
            p[2] = (byte) (start >>> 8);
            p[3] = (byte) start;
            System.arraycopy(rgb, start * 3, p, 4, count * 3);
            out.add(p);
        }
        return out;
    }

    /** E1.31 data packets, one per universe (170 pixels), starting at {@code startUniverse}. */
    public List<byte[]> e131(byte[] rgb, int pixels, int startUniverse) {
        List<byte[]> out = new ArrayList<>();
        int seq = e131Sequence;
        e131Sequence = (e131Sequence + 1) & 0xFF;
        int universe = Math.max(1, startUniverse);
        for (int start = 0; start < pixels; start += E131_PIXELS_PER_UNIVERSE, universe++) {
            int count = Math.min(E131_PIXELS_PER_UNIVERSE, pixels - start);
            int slots = count * 3;
            byte[] p = new byte[126 + slots];
            // --- root layer
            p[1] = 0x10;                                           // preamble size
            byte[] id = "ASC-E1.17".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(id, 0, p, 4, id.length);              // ACN packet identifier (12 bytes, zero padded)
            flagsLength(p, 16, p.length - 16);
            p[21] = 0x04;                                          // vector: VECTOR_ROOT_E131_DATA
            System.arraycopy(cid, 0, p, 22, 16);
            // --- framing layer
            flagsLength(p, 38, p.length - 38);
            p[43] = 0x02;                                          // vector: VECTOR_E131_DATA_PACKET
            byte[] name = "LightBox".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, p, 44, name.length);         // source name (64 bytes)
            p[108] = 100;                                          // priority
            p[111] = (byte) seq;
            p[112] = 0;                                            // options
            p[113] = (byte) (universe >>> 8);
            p[114] = (byte) universe;
            // --- DMP layer
            flagsLength(p, 115, p.length - 115);
            p[117] = 0x02;                                         // vector: VECTOR_DMP_SET_PROPERTY
            p[118] = (byte) 0xA1;                                  // address & data type
            p[119] = 0; p[120] = 0;                                // first property address
            p[121] = 0; p[122] = 1;                                // address increment
            int values = slots + 1;
            p[123] = (byte) (values >>> 8);
            p[124] = (byte) values;
            p[125] = 0;                                            // DMX start code
            System.arraycopy(rgb, start * 3, p, 126, slots);
            out.add(p);
        }
        return out;
    }

    private static void flagsLength(byte[] p, int off, int len) {
        p[off] = (byte) (0x70 | ((len >>> 8) & 0x0F));
        p[off + 1] = (byte) len;
    }
}
