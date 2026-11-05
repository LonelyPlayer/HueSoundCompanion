package app.huebeat.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal DTLS 1.2 client for the Philips Hue Entertainment API.
 *
 * Supports exactly what the Hue bridge needs: pre-shared key authentication with the
 * cipher suite TLS_PSK_WITH_AES_128_GCM_SHA256 (0x00A8), including the cookie exchange
 * (HelloVerifyRequest), fragment reassembly and flight retransmission. After the handshake
 * it only sends application data; it never expects data from the bridge.
 *
 * Pure Java (javax.crypto), so it works on Android without extra libraries.
 */
public final class DtlsPskClient implements Closeable {

    private static final int CT_CHANGE_CIPHER_SPEC = 20;
    private static final int CT_ALERT = 21;
    private static final int CT_HANDSHAKE = 22;
    private static final int CT_APPLICATION_DATA = 23;

    private static final int HT_CLIENT_HELLO = 1;
    private static final int HT_SERVER_HELLO = 2;
    private static final int HT_HELLO_VERIFY_REQUEST = 3;
    private static final int HT_SERVER_KEY_EXCHANGE = 12;
    private static final int HT_SERVER_HELLO_DONE = 14;
    private static final int HT_CLIENT_KEY_EXCHANGE = 16;
    private static final int HT_FINISHED = 20;

    private static final int CIPHER_PSK_AES128_GCM_SHA256 = 0x00A8;
    private static final int CIPHER_EMPTY_RENEGOTIATION_SCSV = 0x00FF;

    private static final byte VER_MAJOR = (byte) 0xFE;
    private static final byte VER_MINOR = (byte) 0xFD; // DTLS 1.2

    private final DatagramSocket socket;
    private final InetAddress host;
    private final int port;
    private final byte[] identity;
    private final byte[] psk;
    private final SecureRandom random = new SecureRandom();

    private int writeEpoch = 0;
    private long writeSeq = 0;
    private int nextMessageSeq = 0;

    private byte[] clientRandom;
    private byte[] serverRandom;
    private byte[] masterSecret;
    private byte[] clientWriteKey, serverWriteKey, clientWriteIv, serverWriteIv;
    private ByteArrayOutputStream transcript = new ByteArrayOutputStream();

    private boolean connected = false;
    private boolean closed = false;

    public DtlsPskClient(InetAddress host, int port, String identity, byte[] psk) throws IOException {
        this.host = host;
        this.port = port;
        this.identity = identity.getBytes(StandardCharsets.US_ASCII);
        this.psk = psk.clone();
        this.socket = new DatagramSocket();
        this.socket.connect(host, port);
    }

    public boolean isConnected() {
        return connected && !closed;
    }

    // ------------------------------------------------------------------ handshake

    /** Performs the full DTLS handshake. Throws IOException on failure or timeout. */
    public void handshake(int totalTimeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + totalTimeoutMs;
        try {
            clientRandom = new byte[32];
            random.nextBytes(clientRandom);

            // Flight 1: ClientHello without cookie
            byte[] hello = handshakeMessage(HT_CLIENT_HELLO, nextMessageSeq++, clientHelloBody(new byte[0]));
            byte[] flight = record(CT_HANDSHAKE, hello);
            transcript.write(hello);

            ServerFlight sf = awaitServerHelloFlight(flight, deadline);
            if (sf.cookie != null) {
                // Flight 3: ClientHello again, now with the cookie. Transcript restarts here.
                transcript = new ByteArrayOutputStream();
                byte[] hello2 = handshakeMessage(HT_CLIENT_HELLO, nextMessageSeq++, clientHelloBody(sf.cookie));
                transcript.write(hello2);
                flight = record(CT_HANDSHAKE, hello2);
                sf = awaitServerHelloFlight(flight, deadline);
                if (sf.cookie != null) throw new IOException("Bridge sent a second HelloVerifyRequest");
            }
            for (byte[] m : sf.messages.values()) transcript.write(m);

            // Key derivation
            byte[] pms = pskPremasterSecret(psk);
            masterSecret = prf(pms, "master secret", concat(clientRandom, serverRandom), 48);
            byte[] keyBlock = prf(masterSecret, "key expansion", concat(serverRandom, clientRandom), 40);
            clientWriteKey = Arrays.copyOfRange(keyBlock, 0, 16);
            serverWriteKey = Arrays.copyOfRange(keyBlock, 16, 32);
            clientWriteIv = Arrays.copyOfRange(keyBlock, 32, 36);
            serverWriteIv = Arrays.copyOfRange(keyBlock, 36, 40);

            // Flight 5: ClientKeyExchange, ChangeCipherSpec, Finished
            ByteArrayOutputStream cke = new ByteArrayOutputStream();
            writeU16(cke, identity.length);
            cke.write(identity);
            byte[] ckeMsg = handshakeMessage(HT_CLIENT_KEY_EXCHANGE, nextMessageSeq++, cke.toByteArray());
            transcript.write(ckeMsg);

            byte[] verify = prf(masterSecret, "client finished", sha256(transcript.toByteArray()), 12);
            byte[] finMsg = handshakeMessage(HT_FINISHED, nextMessageSeq++, verify);

            ByteArrayOutputStream f5 = new ByteArrayOutputStream();
            f5.write(record(CT_HANDSHAKE, ckeMsg));
            f5.write(record(CT_CHANGE_CIPHER_SPEC, new byte[]{1}));
            writeEpoch = 1;
            writeSeq = 0;
            f5.write(record(CT_HANDSHAKE, finMsg));
            transcript.write(finMsg);
            byte[] expectedServerVerify = prf(masterSecret, "server finished", sha256(transcript.toByteArray()), 12);

            awaitServerFinished(f5.toByteArray(), expectedServerVerify, deadline);
            connected = true;
        } catch (GeneralSecurityException e) {
            throw new IOException("Crypto failure: " + e.getMessage(), e);
        }
    }

    private static final class ServerFlight {
        byte[] cookie; // non-null if HelloVerifyRequest
        final TreeMap<Integer, byte[]> messages = new TreeMap<>(); // full DTLS-framed messages
    }

    /** Sends {@code flight} and waits for either a HelloVerifyRequest or ServerHello..ServerHelloDone. */
    private ServerFlight awaitServerHelloFlight(byte[] flight, long deadline) throws IOException {
        Reassembler reasm = new Reassembler();
        int timeout = 500;
        socket.send(new DatagramPacket(flight, flight.length));
        byte[] buf = new byte[4096];
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("Bridge did not answer the DTLS handshake");
            socket.setSoTimeout((int) Math.max(1, Math.min(timeout, left)));
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                socket.send(new DatagramPacket(flight, flight.length)); // retransmit
                timeout = Math.min(timeout * 2, 4000);
                continue;
            }
            int off = 0, end = p.getLength();
            while (off + 13 <= end) {
                int type = buf[off] & 0xFF;
                int epoch = u16(buf, off + 3);
                int len = u16(buf, off + 11);
                if (off + 13 + len > end) break;
                byte[] frag = Arrays.copyOfRange(buf, off + 13, off + 13 + len);
                off += 13 + len;
                if (epoch != 0) continue;
                if (type == CT_ALERT) throwAlert(frag);
                if (type != CT_HANDSHAKE) continue;
                reasm.add(frag);
            }
            // HelloVerifyRequest?
            for (byte[] m : reasm.complete.values()) {
                if ((m[0] & 0xFF) == HT_HELLO_VERIFY_REQUEST) {
                    // body: server_version(2) cookie_len(1) cookie
                    int cl = m[12 + 2] & 0xFF;
                    ServerFlight sf = new ServerFlight();
                    sf.cookie = Arrays.copyOfRange(m, 12 + 3, 12 + 3 + cl);
                    return sf;
                }
            }
            // ServerHello ... ServerHelloDone complete and contiguous?
            Integer helloSeq = null, doneSeq = null;
            for (java.util.Map.Entry<Integer, byte[]> e : reasm.complete.entrySet()) {
                int t = e.getValue()[0] & 0xFF;
                if (t == HT_SERVER_HELLO) helloSeq = e.getKey();
                if (t == HT_SERVER_HELLO_DONE) doneSeq = e.getKey();
            }
            if (helloSeq != null && doneSeq != null) {
                ServerFlight sf = new ServerFlight();
                boolean contiguous = true;
                for (int s = helloSeq; s <= doneSeq; s++) {
                    byte[] m = reasm.complete.get(s);
                    if (m == null) { contiguous = false; break; }
                    sf.messages.put(s, m);
                }
                if (contiguous) {
                    parseServerHello(sf.messages.get(helloSeq));
                    return sf;
                }
            }
        }
    }

    private void parseServerHello(byte[] msg) throws IOException {
        int p = 12; // skip DTLS handshake header
        p += 2; // server_version
        serverRandom = Arrays.copyOfRange(msg, p, p + 32);
        p += 32;
        int sidLen = msg[p] & 0xFF;
        p += 1 + sidLen;
        int cipher = u16(msg, p);
        if (cipher != CIPHER_PSK_AES128_GCM_SHA256) {
            throw new IOException(String.format("Bridge chose unsupported cipher 0x%04X", cipher));
        }
    }

    private void awaitServerFinished(byte[] flight, byte[] expectedVerify, long deadline)
            throws IOException, GeneralSecurityException {
        int timeout = 500;
        socket.send(new DatagramPacket(flight, flight.length));
        byte[] buf = new byte[4096];
        boolean gotCcs = false;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("Bridge did not finish the DTLS handshake");
            socket.setSoTimeout((int) Math.max(1, Math.min(timeout, left)));
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                socket.send(new DatagramPacket(flight, flight.length));
                timeout = Math.min(timeout * 2, 4000);
                continue;
            }
            int off = 0, end = p.getLength();
            while (off + 13 <= end) {
                int type = buf[off] & 0xFF;
                int epoch = u16(buf, off + 3);
                int len = u16(buf, off + 11);
                if (off + 13 + len > end) break;
                byte[] header = Arrays.copyOfRange(buf, off, off + 13);
                byte[] frag = Arrays.copyOfRange(buf, off + 13, off + 13 + len);
                off += 13 + len;
                if (epoch == 0) {
                    if (type == CT_ALERT) throwAlert(frag);
                    if (type == CT_CHANGE_CIPHER_SPEC) gotCcs = true;
                    continue; // retransmitted server flight etc.
                }
                byte[] plain = decrypt(header, frag);
                if (type == CT_ALERT) throwAlert(plain);
                if (type == CT_HANDSHAKE && (plain[0] & 0xFF) == HT_FINISHED) {
                    byte[] verify = Arrays.copyOfRange(plain, 12, 24);
                    if (!MessageDigest.isEqual(verify, expectedVerify)) {
                        throw new IOException("DTLS server Finished did not verify (wrong client key?)");
                    }
                    return;
                }
            }
            if (gotCcs) timeout = Math.max(timeout, 500);
        }
    }

    private void throwAlert(byte[] alert) throws IOException {
        int level = alert.length > 0 ? alert[0] & 0xFF : -1;
        int desc = alert.length > 1 ? alert[1] & 0xFF : -1;
        String name;
        switch (desc) {
            case 0: name = "close_notify"; break;
            case 10: name = "unexpected_message"; break;
            case 20: name = "bad_record_mac"; break;
            case 40: name = "handshake_failure"; break;
            case 47: name = "illegal_parameter"; break;
            case 51: name = "decrypt_error (wrong client key?)"; break;
            case 115: name = "unknown_psk_identity (wrong username?)"; break;
            default: name = "code " + desc;
        }
        throw new IOException("Bridge sent DTLS alert: " + name + " (level " + level + ")");
    }

    /** Collects (possibly fragmented) handshake messages, keyed by message_seq. */
    private static final class Reassembler {
        final TreeMap<Integer, byte[]> complete = new TreeMap<>();
        private final TreeMap<Integer, byte[]> partial = new TreeMap<>();
        private final TreeMap<Integer, boolean[]> covered = new TreeMap<>();

        void add(byte[] frag) {
            int off = 0;
            while (off + 12 <= frag.length) {
                int type = frag[off] & 0xFF;
                int length = u24(frag, off + 1);
                int seq = u16(frag, off + 4);
                int fOff = u24(frag, off + 6);
                int fLen = u24(frag, off + 9);
                if (off + 12 + fLen > frag.length || fOff + fLen > length) return;
                if (!complete.containsKey(seq)) {
                    byte[] whole = partial.get(seq);
                    boolean[] cov = covered.get(seq);
                    if (whole == null) {
                        whole = new byte[12 + length];
                        whole[0] = (byte) type;
                        putU24(whole, 1, length);
                        putU16(whole, 4, seq);
                        putU24(whole, 6, 0);
                        putU24(whole, 9, length);
                        cov = new boolean[length];
                        partial.put(seq, whole);
                        covered.put(seq, cov);
                    }
                    System.arraycopy(frag, off + 12, whole, 12 + fOff, fLen);
                    for (int i = fOff; i < fOff + fLen; i++) cov[i] = true;
                    boolean done = true;
                    for (boolean b : cov) if (!b) { done = false; break; }
                    if (done) {
                        complete.put(seq, whole);
                        partial.remove(seq);
                        covered.remove(seq);
                    }
                }
                off += 12 + fLen;
            }
        }
    }

    private byte[] clientHelloBody(byte[] cookie) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(VER_MAJOR);
        b.write(VER_MINOR);
        b.write(clientRandom);
        b.write(0); // session id length
        b.write(cookie.length);
        b.write(cookie);
        writeU16(b, 4); // cipher suites length
        writeU16(b, CIPHER_PSK_AES128_GCM_SHA256);
        writeU16(b, CIPHER_EMPTY_RENEGOTIATION_SCSV);
        b.write(1); // compression methods length
        b.write(0); // null compression
        return b.toByteArray();
    }

    private static byte[] handshakeMessage(int type, int messageSeq, byte[] body) {
        byte[] m = new byte[12 + body.length];
        m[0] = (byte) type;
        putU24(m, 1, body.length);
        putU16(m, 4, messageSeq);
        putU24(m, 6, 0);
        putU24(m, 9, body.length);
        System.arraycopy(body, 0, m, 12, body.length);
        return m;
    }

    // ------------------------------------------------------------------ records

    private byte[] record(int contentType, byte[] payload) throws GeneralSecurityException {
        long seq = writeSeq++;
        byte[] fragment = payload;
        if (writeEpoch > 0) fragment = encrypt(contentType, writeEpoch, seq, payload);
        byte[] r = new byte[13 + fragment.length];
        r[0] = (byte) contentType;
        r[1] = VER_MAJOR;
        r[2] = VER_MINOR;
        putU16(r, 3, writeEpoch);
        putU48(r, 5, seq);
        putU16(r, 11, fragment.length);
        System.arraycopy(fragment, 0, r, 13, fragment.length);
        return r;
    }

    private byte[] encrypt(int contentType, int epoch, long seq, byte[] plain) throws GeneralSecurityException {
        byte[] explicit = new byte[8];
        putU16(explicit, 0, epoch);
        putU48(explicit, 2, seq);
        byte[] nonce = concat(clientWriteIv, explicit);
        byte[] aad = new byte[13];
        System.arraycopy(explicit, 0, aad, 0, 8);
        aad[8] = (byte) contentType;
        aad[9] = VER_MAJOR;
        aad[10] = VER_MINOR;
        putU16(aad, 11, plain.length);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clientWriteKey, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad);
        byte[] ct = c.doFinal(plain);
        return concat(explicit, ct);
    }

    private byte[] decrypt(byte[] header, byte[] fragment) throws GeneralSecurityException, IOException {
        if (fragment.length < 8 + 16) throw new IOException("Short encrypted record");
        byte[] explicit = Arrays.copyOfRange(fragment, 0, 8);
        byte[] nonce = concat(serverWriteIv, explicit);
        byte[] aad = new byte[13];
        System.arraycopy(header, 3, aad, 0, 8); // epoch + sequence number
        aad[8] = header[0];
        aad[9] = header[1];
        aad[10] = header[2];
        putU16(aad, 11, fragment.length - 8 - 16);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(serverWriteKey, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad);
        return c.doFinal(fragment, 8, fragment.length - 8);
    }

    // ------------------------------------------------------------------ public sending

    /** Sends one encrypted application-data record. Thread-safe. */
    public synchronized void send(byte[] data) throws IOException {
        if (!connected || closed) throw new IOException("DTLS not connected");
        try {
            byte[] r = record(CT_APPLICATION_DATA, data);
            socket.send(new DatagramPacket(r, r.length));
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (connected) {
            try {
                byte[] r = record(CT_ALERT, new byte[]{1, 0}); // warning, close_notify
                socket.send(new DatagramPacket(r, r.length));
            } catch (Exception ignored) {
            }
        }
        closed = true;
        socket.close();
    }

    // ------------------------------------------------------------------ crypto helpers

    static byte[] pskPremasterSecret(byte[] psk) {
        int n = psk.length;
        byte[] pms = new byte[4 + 2 * n];
        putU16(pms, 0, n);
        // n zero bytes
        putU16(pms, 2 + n, n);
        System.arraycopy(psk, 0, pms, 4 + n, n);
        return pms;
    }

    static byte[] prf(byte[] secret, String label, byte[] seed, int length) throws GeneralSecurityException {
        byte[] labelSeed = concat(label.getBytes(StandardCharsets.US_ASCII), seed);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.length == 0 ? new byte[1] : secret, "HmacSHA256"));
        byte[] out = new byte[length];
        byte[] a = labelSeed;
        int pos = 0;
        while (pos < length) {
            a = mac.doFinal(a);
            mac.update(a);
            byte[] chunk = mac.doFinal(labelSeed);
            int n = Math.min(chunk.length, length - pos);
            System.arraycopy(chunk, 0, out, pos, n);
            pos += n;
        }
        return out;
    }

    private static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static void writeU16(ByteArrayOutputStream b, int v) {
        b.write((v >>> 8) & 0xFF);
        b.write(v & 0xFF);
    }

    private static int u16(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static int u24(byte[] b, int o) {
        return ((b[o] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] & 0xFF);
    }

    private static void putU16(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 8);
        b[o + 1] = (byte) v;
    }

    private static void putU24(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 16);
        b[o + 1] = (byte) (v >>> 8);
        b[o + 2] = (byte) v;
    }

    private static void putU48(byte[] b, int o, long v) {
        for (int i = 5; i >= 0; i--) {
            b[o + i] = (byte) v;
            v >>>= 8;
        }
    }

    /** Parses a hex string such as the Hue "clientkey" into bytes. */
    public static byte[] hex(String s) {
        s = s.trim();
        byte[] r = new byte[s.length() / 2];
        for (int i = 0; i < r.length; i++) {
            r[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return r;
    }
}
