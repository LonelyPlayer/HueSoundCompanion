package app.huebeat.core;

import java.nio.charset.StandardCharsets;

/**
 * Builds HueStream v2 packets for the Entertainment API (API v2 / entertainment_configuration).
 *
 * Layout: "HueStream" | version 2.0 | sequence | 2 reserved | color space | 1 reserved |
 *         entertainment configuration id (36 ASCII chars) | per channel: id(1) + R,G,B (uint16 BE)
 */
public final class HueStream {
    public static final int MAX_CHANNELS = 20;
    private static final byte[] MAGIC = "HueStream".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER = 52;

    private final byte[] configId;
    private int sequence = 0;

    public HueStream(String entertainmentConfigurationId) {
        byte[] id = entertainmentConfigurationId.getBytes(StandardCharsets.US_ASCII);
        if (id.length != 36) throw new IllegalArgumentException("Entertainment configuration id must be a 36-char UUID");
        this.configId = id;
    }

    /**
     * @param channelIds channel ids (0..19)
     * @param rgb        per channel r,g,b in 0..1 (length = 3 * channels)
     */
    public byte[] build(int[] channelIds, float[] rgb) {
        int n = Math.min(channelIds.length, MAX_CHANNELS);
        byte[] p = new byte[HEADER + 7 * n];
        System.arraycopy(MAGIC, 0, p, 0, 9);
        p[9] = 0x02;  // major version
        p[10] = 0x00; // minor version
        p[11] = (byte) (sequence++ & 0xFF);
        p[12] = 0;
        p[13] = 0;
        p[14] = 0x00; // color space RGB
        p[15] = 0;
        System.arraycopy(configId, 0, p, 16, 36);
        int o = HEADER;
        for (int i = 0; i < n; i++) {
            p[o++] = (byte) channelIds[i];
            for (int c = 0; c < 3; c++) {
                float v = rgb[3 * i + c];
                if (!(v > 0f)) v = 0f;
                if (v > 1f) v = 1f;
                int u = Math.round(v * 65535f);
                p[o++] = (byte) (u >>> 8);
                p[o++] = (byte) u;
            }
        }
        return p;
    }
}
