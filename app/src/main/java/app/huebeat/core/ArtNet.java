package app.huebeat.core;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Art-Net 4 (UDP port 6454): DMX data (ArtDmx), node discovery (ArtPoll / ArtPollReply) and
 * remote port configuration (ArtAddress). Plus sACN (E1.31) DMX packets for nodes set to sACN.
 */
public final class ArtNet {
    public static final int PORT = 6454;
    private static final byte[] ID = "Art-Net\0".getBytes(StandardCharsets.US_ASCII);
    private static final int OP_POLL = 0x2000, OP_POLL_REPLY = 0x2100, OP_DMX = 0x5000, OP_ADDRESS = 0x6000;

    private ArtNet() {
    }

    // ------------------------------------------------------------------ DMX data

    /** ArtDmx packet for one universe (15-bit port-address: net 7 bits, sub-net 4, universe 4). */
    public static byte[] dmx(int portAddress, int sequence, byte[] data, int length) {
        int len = Math.max(2, Math.min(512, length + (length & 1))); // even, 2..512
        byte[] p = new byte[18 + len];
        System.arraycopy(ID, 0, p, 0, 8);
        p[8] = (byte) (OP_DMX & 0xFF);
        p[9] = (byte) (OP_DMX >> 8);
        p[10] = 0;
        p[11] = 14;                                   // protocol version
        p[12] = (byte) (sequence == 0 ? 1 : sequence); // 0 would disable reordering
        p[13] = 0;                                    // physical
        p[14] = (byte) (portAddress & 0xFF);          // SubUni
        p[15] = (byte) ((portAddress >> 8) & 0x7F);   // Net
        p[16] = (byte) (len >> 8);
        p[17] = (byte) len;
        System.arraycopy(data, 0, p, 18, Math.min(len, data.length));
        return p;
    }

    /** sACN / E1.31 packet for one universe (1..63999), 512 slots. */
    public static byte[] sacn(int universe, int sequence, byte[] data, byte[] cid) {
        int slots = 512;
        byte[] p = new byte[126 + slots];
        p[1] = 0x10;
        byte[] id = "ASC-E1.17".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(id, 0, p, 4, id.length);
        flagsLength(p, 16, p.length - 16);
        p[21] = 0x04;
        System.arraycopy(cid, 0, p, 22, 16);
        flagsLength(p, 38, p.length - 38);
        p[43] = 0x02;
        byte[] name = "HueSoundCompanion".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, p, 44, name.length);
        p[108] = 100;
        p[111] = (byte) sequence;
        int u = Math.max(1, Math.min(63999, universe));
        p[113] = (byte) (u >>> 8);
        p[114] = (byte) u;
        flagsLength(p, 115, p.length - 115);
        p[117] = 0x02;
        p[118] = (byte) 0xA1;
        p[122] = 1;
        int values = slots + 1;
        p[123] = (byte) (values >>> 8);
        p[124] = (byte) values;
        System.arraycopy(data, 0, p, 126, Math.min(slots, data.length));
        return p;
    }

    private static void flagsLength(byte[] p, int off, int len) {
        p[off] = (byte) (0x70 | ((len >>> 8) & 0x0F));
        p[off + 1] = (byte) len;
    }

    // ------------------------------------------------------------------ discovery

    /** One node (or one port group of a node: big nodes answer once per group). */
    public static final class Node {
        public String ip = "";
        public String shortName = "", longName = "", report = "";
        public int bindIndex;
        public int net, subNet;
        public int numPorts;
        /** Per port: can it output DMX, can it input, its universe (low 4 bits). */
        public boolean[] canOut = new boolean[4], canIn = new boolean[4];
        public int[] swOut = new int[4], swIn = new int[4];

        /** Full 15-bit port-address of output port i. */
        public int outAddress(int i) {
            return (net << 8) | (subNet << 4) | (swOut[i] & 0x0F);
        }

        public String label() {
            String n = !longName.isEmpty() ? longName : !shortName.isEmpty() ? shortName : "Art-Net node";
            return n + " · " + ip;
        }
    }

    static byte[] poll() {
        byte[] p = new byte[14];
        System.arraycopy(ID, 0, p, 0, 8);
        p[8] = (byte) (OP_POLL & 0xFF);
        p[9] = (byte) (OP_POLL >> 8);
        p[11] = 14;
        p[12] = 0x02; // please reply when something changes, too
        return p;
    }

    /** Parses an ArtPollReply, or null if the packet is something else. */
    public static Node parseReply(byte[] b, int len) {
        if (len < 207) return null;
        for (int i = 0; i < 8; i++) if (b[i] != ID[i]) return null;
        int op = (b[8] & 0xFF) | ((b[9] & 0xFF) << 8);
        if (op != OP_POLL_REPLY) return null;
        Node n = new Node();
        n.ip = (b[10] & 0xFF) + "." + (b[11] & 0xFF) + "." + (b[12] & 0xFF) + "." + (b[13] & 0xFF);
        n.net = b[18] & 0x7F;
        n.subNet = b[19] & 0x0F;
        n.shortName = cString(b, 26, 18);
        n.longName = cString(b, 44, 64);
        n.report = cString(b, 108, 64);
        n.numPorts = Math.min(4, ((b[172] & 0xFF) << 8) | (b[173] & 0xFF));
        for (int i = 0; i < 4; i++) {
            int type = b[174 + i] & 0xFF;
            n.canOut[i] = (type & 0x80) != 0;
            n.canIn[i] = (type & 0x40) != 0;
            n.swIn[i] = b[186 + i] & 0x0F;
            n.swOut[i] = b[190 + i] & 0x0F;
        }
        n.bindIndex = len > 211 ? b[211] & 0xFF : 0;
        return n;
    }

    private static String cString(byte[] b, int off, int max) {
        int end = off;
        while (end < off + max && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.US_ASCII).trim();
    }

    /**
     * Sends ArtPoll (broadcast plus any given addresses) and collects the replies for
     * {@code timeoutMs}. Needs port 6454 free: replies are addressed to it.
     */
    public static List<Node> discover(List<InetAddress> targets, int timeoutMs) throws IOException {
        List<Node> found = new ArrayList<>();
        DatagramSocket s = new DatagramSocket(null);
        try {
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(PORT));
            byte[] poll = poll();
            for (InetAddress a : targets) {
                try {
                    s.send(new DatagramPacket(poll, poll.length, a, PORT));
                } catch (IOException ignored) {
                    // an unreachable broadcast address on this network
                }
            }
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[600];
            while (true) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                s.setSoTimeout((int) Math.max(1, left));
                DatagramPacket pk = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(pk);
                } catch (SocketTimeoutException e) {
                    break;
                }
                Node n = parseReply(pk.getData(), pk.getLength());
                if (n == null) continue;
                if (n.ip.equals("0.0.0.0")) n.ip = pk.getAddress().getHostAddress();
                boolean dup = false;
                for (Node o : found) dup |= o.ip.equals(n.ip) && o.bindIndex == n.bindIndex;
                if (!dup) found.add(n);
            }
        } finally {
            s.close();
        }
        return found;
    }

    // ------------------------------------------------------------------ remote configuration

    /**
     * ArtAddress: gives output port {@code port} of a node the 15-bit port-address and optionally
     * a new name. Net and sub-net are shared by the ports of one bind group. Not every node
     * accepts this (some only take settings from their own menu or web page).
     */
    public static byte[] address(int bindIndex, int port, int portAddress, String shortName) {
        byte[] p = new byte[107];
        System.arraycopy(ID, 0, p, 0, 8);
        p[8] = (byte) (OP_ADDRESS & 0xFF);
        p[9] = (byte) (OP_ADDRESS >> 8);
        p[11] = 14;
        p[12] = (byte) (0x80 | ((portAddress >> 8) & 0x7F));      // NetSwitch, programmed
        p[13] = (byte) bindIndex;
        if (shortName != null && !shortName.isEmpty()) {
            byte[] n = shortName.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(n, 0, p, 14, Math.min(17, n.length));
        }
        for (int i = 0; i < 4; i++) {
            p[96 + i] = 0x7F;                                         // SwIn: no change
            p[100 + i] = (byte) (i == port ? 0x80 | (portAddress & 0x0F) : 0x7F); // SwOut
        }
        p[104] = (byte) (0x80 | ((portAddress >> 4) & 0x0F));         // SubSwitch, programmed
        p[105] = 0;                                                    // AcnPriority/SwVideo: no change
        p[106] = 0;                                                    // command: none
        return p;
    }

    public static void sendAddress(String ip, int bindIndex, int port, int portAddress, String shortName) throws IOException {
        byte[] p = address(bindIndex, port, portAddress, shortName);
        try (DatagramSocket s = new DatagramSocket()) {
            InetAddress a = InetAddress.getByName(ip);
            for (int k = 0; k < 3; k++) s.send(new DatagramPacket(p, p.length, a, PORT));
        }
    }
}
