package app.huebeat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Small blocking client for the local Hue bridge REST/CLIP v2 API. Call from a background thread. */
public final class HueApi {

    public static final class Bridge {
        public final String ip;
        public String id = "";
        public String name = "Hue Bridge";

        public Bridge(String ip) {
            this.ip = ip;
        }
    }

    public static final class Area {
        public final String id;
        public final String name;
        public final int channelCount;
        public final String status;

        Area(String id, String name, int channelCount, String status) {
            this.id = id;
            this.name = name;
            this.channelCount = channelCount;
            this.status = status;
        }
    }

    public static final class Channels {
        public int[] ids;
        public float[] x, y;
    }

    /** Thrown while the link button has not been pressed yet. */
    public static final class LinkButtonNotPressed extends IOException {
        LinkButtonNotPressed() {
            super("Press the link button on the bridge");
        }
    }

    private static SSLSocketFactory bridgeSsl;

    /**
     * The bridge uses a certificate signed by Signify's private CA and named after its bridge id,
     * so normal verification fails. These requests only go to the bridge on the local network.
     */
    private static synchronized SSLSocketFactory bridgeSocketFactory() throws IOException {
        if (bridgeSsl == null) {
            try {
                TrustManager[] tm = {new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }};
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, tm, new SecureRandom());
                bridgeSsl = ctx.getSocketFactory();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        return bridgeSsl;
    }

    private static String request(String method, String url, String appKey, String body, boolean bridge) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (bridge && c instanceof HttpsURLConnection) {
            HttpsURLConnection h = (HttpsURLConnection) c;
            h.setSSLSocketFactory(bridgeSocketFactory());
            h.setHostnameVerifier((hostname, session) -> true);
        }
        c.setConnectTimeout(4000);
        c.setReadTimeout(6000);
        c.setRequestMethod(method);
        if (appKey != null) c.setRequestProperty("hue-application-key", appKey);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + ": " + shorten(text));
        return text;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    private static String shorten(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    // ------------------------------------------------------------------ discovery

    /** Philips' cloud discovery service (returns bridges seen from your public IP). */
    public static List<Bridge> cloudDiscovery() throws IOException {
        List<Bridge> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(request("GET", "https://discovery.meethue.com/", null, null, false));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Bridge b = new Bridge(o.getString("internalipaddress"));
                b.id = o.optString("id", "");
                list.add(b);
            }
        } catch (JSONException e) {
            throw new IOException("Bad discovery answer", e);
        }
        return list;
    }

    /** Reads the public config of a bridge (works without pairing) to confirm it is a Hue bridge. */
    public static Bridge describe(String ip) throws IOException {
        try {
            JSONObject o = new JSONObject(request("GET", "https://" + ip + "/api/0/config", null, null, true));
            Bridge b = new Bridge(ip);
            b.name = o.optString("name", "Hue Bridge");
            b.id = o.optString("bridgeid", "");
            String api = o.optString("apiversion", "0");
            if (compareVersions(api, "1.22") < 0) {
                throw new IOException("Bridge firmware too old for the Entertainment API (" + api + "). Update it in the Hue app.");
            }
            return b;
        } catch (JSONException e) {
            throw new IOException("Not a Hue bridge at " + ip, e);
        }
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0, yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ pairing

    /** @return {username, clientkey}; throws {@link LinkButtonNotPressed} until the button is pressed. */
    public static String[] pair(String ip, String deviceName) throws IOException {
        String body = "{\"devicetype\":\"huebeat#" + deviceName.replaceAll("[^A-Za-z0-9 _-]", "") + "\",\"generateclientkey\":true}";
        String resp = request("POST", "https://" + ip + "/api", null, body, true);
        try {
            JSONObject o = new JSONArray(resp).getJSONObject(0);
            if (o.has("success")) {
                JSONObject s = o.getJSONObject("success");
                return new String[]{s.getString("username"), s.getString("clientkey")};
            }
            JSONObject err = o.optJSONObject("error");
            if (err != null && err.optInt("type") == 101) throw new LinkButtonNotPressed();
            throw new IOException(err != null ? err.optString("description") : "Unexpected answer");
        } catch (JSONException e) {
            throw new IOException("Unexpected pairing answer: " + shorten(resp), e);
        }
    }

    // ------------------------------------------------------------------ entertainment

    public static List<Area> areas(String ip, String appKey) throws IOException {
        List<Area> list = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration", appKey, null, true));
            checkErrors(o);
            JSONArray data = o.getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                JSONObject a = data.getJSONObject(i);
                JSONObject meta = a.optJSONObject("metadata");
                JSONArray ch = a.optJSONArray("channels");
                list.add(new Area(a.getString("id"), meta != null ? meta.optString("name", "Area") : "Area",
                        ch != null ? ch.length() : 0, a.optString("status", "")));
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
        return list;
    }

    public static Channels channels(String ip, String appKey, String areaId) throws IOException {
        try {
            JSONObject o = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration/" + areaId, appKey, null, true));
            checkErrors(o);
            JSONArray data = o.getJSONArray("data");
            if (data.length() == 0) throw new IOException("Entertainment area not found. Choose it again in setup.");
            JSONArray ch = data.getJSONObject(0).getJSONArray("channels");
            Channels c = new Channels();
            int n = Math.min(ch.length(), 20);
            c.ids = new int[n];
            c.x = new float[n];
            c.y = new float[n];
            for (int i = 0; i < n; i++) {
                JSONObject one = ch.getJSONObject(i);
                c.ids[i] = one.getInt("channel_id");
                JSONObject pos = one.optJSONObject("position");
                c.x[i] = pos != null ? (float) pos.optDouble("x", 0) : 0f;
                c.y[i] = pos != null ? (float) pos.optDouble("y", 0) : 0f;
            }
            if (n == 0) throw new IOException("This entertainment area has no lights.");
            return c;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
    }

    public static void setStreaming(String ip, String appKey, String areaId, boolean start) throws IOException {
        try {
            JSONObject o = new JSONObject(request("PUT", "https://" + ip + "/clip/v2/resource/entertainment_configuration/" + areaId,
                    appKey, start ? "{\"action\":\"start\"}" : "{\"action\":\"stop\"}", true));
            checkErrors(o);
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
    }

    private static void checkErrors(JSONObject o) throws IOException {
        JSONArray errs = o.optJSONArray("errors");
        if (errs != null && errs.length() > 0) {
            JSONObject e = errs.optJSONObject(0);
            throw new IOException(e != null ? e.optString("description", "Bridge error") : "Bridge error");
        }
    }
}
