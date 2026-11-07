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
        public float[] x, y, z;

        /** No Hue lights (only LED strips are used). */
        public static Channels empty() {
            Channels c = new Channels();
            c.ids = new int[0];
            c.x = new float[0];
            c.y = new float[0];
            c.z = new float[0];
            return c;
        }
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
            c.z = new float[n];
            for (int i = 0; i < n; i++) {
                JSONObject one = ch.getJSONObject(i);
                c.ids[i] = one.getInt("channel_id");
                JSONObject pos = one.optJSONObject("position");
                c.x[i] = pos != null ? (float) pos.optDouble("x", 0) : 0f;
                c.y[i] = pos != null ? (float) pos.optDouble("y", 0) : 0f;
                c.z[i] = pos != null ? (float) pos.optDouble("z", 0) : 0f;
            }
            if (n == 0) throw new IOException("This entertainment area has no lights.");
            return c;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
    }

    // ------------------------------------------------------------------ lamps as devices

    /** A lamp that can stream (has an entertainment service), with its position if a Hue area knows it. */
    public static final class Lamp {
        public String entService = "", lightId = "", name = "";
        public int segments = 1;
        public boolean hasPosition;
        public float x, y, z, x2, y2;
    }

    /** All lamps on the bridge that can take part in entertainment streaming. */
    public static List<Lamp> listLamps(String ip, String appKey) throws IOException {
        List<Lamp> out = new ArrayList<>();
        try {
            JSONObject ents = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment", appKey, null, true));
            checkErrors(ents);
            JSONObject devs = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/device", appKey, null, true));
            JSONObject cfgs = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration", appKey, null, true));
            java.util.Map<String, String> devName = new java.util.HashMap<>(), devLight = new java.util.HashMap<>();
            JSONArray dd = devs.getJSONArray("data");
            for (int i = 0; i < dd.length(); i++) {
                JSONObject d = dd.getJSONObject(i);
                JSONObject meta = d.optJSONObject("metadata");
                devName.put(d.getString("id"), meta != null ? meta.optString("name", "") : "");
                JSONArray sv = d.optJSONArray("services");
                if (sv == null) continue;
                for (int k = 0; k < sv.length(); k++) {
                    JSONObject x = sv.getJSONObject(k);
                    if ("light".equals(x.optString("rtype"))) devLight.put(d.getString("id"), x.optString("rid"));
                }
            }
            // positions the user already gave the lamps in Hue entertainment areas
            java.util.Map<String, JSONArray> known = new java.util.HashMap<>();
            JSONArray cd = cfgs.optJSONArray("data");
            for (int i = 0; cd != null && i < cd.length(); i++) {
                JSONObject loc = cd.getJSONObject(i).optJSONObject("locations");
                JSONArray sl = loc != null ? loc.optJSONArray("service_locations") : null;
                for (int k = 0; sl != null && k < sl.length(); k++) {
                    JSONObject one = sl.getJSONObject(k);
                    JSONObject svc = one.optJSONObject("service");
                    JSONArray pos = one.optJSONArray("positions");
                    if (svc != null && pos != null && pos.length() > 0 && !known.containsKey(svc.optString("rid"))) known.put(svc.optString("rid"), pos);
                }
            }
            JSONArray ed = ents.getJSONArray("data");
            for (int i = 0; i < ed.length(); i++) {
                JSONObject e = ed.getJSONObject(i);
                if (!e.optBoolean("renderer", false)) continue; // sensors etc.
                Lamp l = new Lamp();
                l.entService = e.getString("id");
                JSONObject owner = e.optJSONObject("owner");
                String dev = owner != null ? owner.optString("rid") : "";
                l.name = devName.containsKey(dev) ? devName.get(dev) : "Hue lamp";
                l.lightId = devLight.containsKey(dev) ? devLight.get(dev) : "";
                JSONObject seg = e.optJSONObject("segments");
                JSONArray sgs = seg != null ? seg.optJSONArray("segments") : null;
                l.segments = sgs != null ? Math.max(1, Math.min(7, sgs.length())) : 1;
                JSONArray pos = known.get(l.entService);
                if (pos != null) {
                    JSONObject p0 = pos.getJSONObject(0), p1 = pos.getJSONObject(pos.length() - 1);
                    l.hasPosition = true;
                    l.x = (float) p0.optDouble("x", 0);
                    l.y = (float) p0.optDouble("y", 0);
                    l.z = (float) p0.optDouble("z", 0);
                    l.x2 = (float) p1.optDouble("x", l.x);
                    l.y2 = (float) p1.optDouble("y", l.y);
                }
                out.add(l);
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
        return out;
    }

    /** One lamp's place in the app's own entertainment configuration. */
    public static final class Placed {
        public String entService;
        public float[][] positions; // 1 point, or 2 (start, end) for gradient lights
    }

    /**
     * Creates or updates the app's own entertainment configuration with exactly these lamps and
     * places (the bridge allows 10 lamps). Returns its id. Must not be streaming while it changes.
     */
    public static String ensureConfig(String ip, String appKey, String id, List<Placed> lamps) throws IOException {
        try {
            return writeConfig(ip, appKey, id, lamps, false);
        } catch (IOException e) {
            // some bridges take only one position per lamp
            return writeConfig(ip, appKey, id, lamps, true);
        }
    }

    private static String writeConfig(String ip, String appKey, String id, List<Placed> lamps, boolean single) throws IOException {
        try {
            JSONObject body = configBody(lamps, single);
            boolean exists = false;
            if (id != null && !id.isEmpty()) {
                try {
                    JSONObject cur = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration/" + id, appKey, null, true));
                    exists = cur.optJSONArray("data") != null && cur.getJSONArray("data").length() > 0;
                } catch (IOException ignored) {
                    exists = false;
                }
            }
            if (exists) {
                JSONObject r = new JSONObject(request("PUT", "https://" + ip + "/clip/v2/resource/entertainment_configuration/" + id, appKey, body.toString(), true));
                checkErrors(r);
                return id;
            }
            body.put("type", "entertainment_configuration").put("configuration_type", "music");
            JSONObject r = new JSONObject(request("POST", "https://" + ip + "/clip/v2/resource/entertainment_configuration", appKey, body.toString(), true));
            checkErrors(r);
            return r.getJSONArray("data").getJSONObject(0).getString("rid");
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
    }

    /** The body for creating/updating the app's entertainment configuration. */
    static JSONObject configBody(List<Placed> lamps, boolean single) throws JSONException {
        {
            JSONArray sl = new JSONArray();
            for (Placed p : lamps) {
                JSONArray pos = new JSONArray();
                int n = single ? 1 : p.positions.length;
                for (int i = 0; i < n; i++) {
                    float[] q = p.positions[i];
                    pos.put(new JSONObject().put("x", (double) clampPos(q[0])).put("y", (double) clampPos(q[1])).put("z", (double) clampPos(q[2])));
                }
                sl.put(new JSONObject().put("service", new JSONObject().put("rid", p.entService).put("rtype", "entertainment"))
                        .put("positions", pos));
            }
            return new JSONObject()
                    .put("metadata", new JSONObject().put("name", "HueSoundCompanion"))
                    .put("locations", new JSONObject().put("service_locations", sl));
        }
    }

    private static float clampPos(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    /** Streaming channels of a configuration, each with the lamp (entertainment service) and segment it drives. */
    public static final class ChannelMap {
        public int[] ids;
        public String[] service;
        public int[] segment;
    }

    public static ChannelMap channelMap(String ip, String appKey, String id) throws IOException {
        try {
            JSONObject o = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration/" + id, appKey, null, true));
            checkErrors(o);
            JSONArray data = o.getJSONArray("data");
            if (data.length() == 0) throw new IOException("The app's lamp setup was removed from the bridge. Add the lamps again.");
            JSONArray ch = data.getJSONObject(0).getJSONArray("channels");
            ChannelMap m = new ChannelMap();
            int n = Math.min(ch.length(), 20);
            m.ids = new int[n];
            m.service = new String[n];
            m.segment = new int[n];
            for (int i = 0; i < n; i++) {
                JSONObject c = ch.getJSONObject(i);
                m.ids[i] = c.getInt("channel_id");
                JSONArray mem = c.optJSONArray("members");
                JSONObject first = mem != null && mem.length() > 0 ? mem.getJSONObject(0) : null;
                JSONObject svc = first != null ? first.optJSONObject("service") : null;
                m.service[i] = svc != null ? svc.optString("rid", "") : "";
                m.segment[i] = first != null ? first.optInt("index", 0) : 0;
            }
            return m;
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
    }

    /** Name and light resource id behind every channel of an entertainment area. */
    public static final class ChannelLight {
        public int channel;
        public String name = "";
        public String lightId;
    }

    public static List<ChannelLight> channelLights(String ip, String appKey, String areaId) throws IOException {
        List<ChannelLight> out = new ArrayList<>();
        try {
            JSONObject cfg = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment_configuration/" + areaId, appKey, null, true));
            checkErrors(cfg);
            JSONObject ents = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/entertainment", appKey, null, true));
            JSONObject devs = new JSONObject(request("GET", "https://" + ip + "/clip/v2/resource/device", appKey, null, true));
            java.util.Map<String, String> entOwner = new java.util.HashMap<>();
            JSONArray ed = ents.getJSONArray("data");
            for (int i = 0; i < ed.length(); i++) {
                JSONObject e = ed.getJSONObject(i);
                JSONObject owner = e.optJSONObject("owner");
                if (owner != null) entOwner.put(e.getString("id"), owner.optString("rid"));
            }
            java.util.Map<String, String> devName = new java.util.HashMap<>(), devLight = new java.util.HashMap<>();
            JSONArray dd = devs.getJSONArray("data");
            for (int i = 0; i < dd.length(); i++) {
                JSONObject d = dd.getJSONObject(i);
                JSONObject meta = d.optJSONObject("metadata");
                devName.put(d.getString("id"), meta != null ? meta.optString("name", "") : "");
                JSONArray sv = d.optJSONArray("services");
                if (sv == null) continue;
                for (int k = 0; k < sv.length(); k++) {
                    JSONObject x = sv.getJSONObject(k);
                    if ("light".equals(x.optString("rtype"))) devLight.put(d.getString("id"), x.optString("rid"));
                }
            }
            JSONArray chans = cfg.getJSONArray("data").getJSONObject(0).getJSONArray("channels");
            java.util.Map<String, Integer> perDevice = new java.util.HashMap<>();
            for (int i = 0; i < chans.length(); i++) {
                JSONObject ch = chans.getJSONObject(i);
                ChannelLight cl = new ChannelLight();
                cl.channel = ch.getInt("channel_id");
                JSONArray mem = ch.optJSONArray("members");
                if (mem != null && mem.length() > 0) {
                    JSONObject svc = mem.getJSONObject(0).optJSONObject("service");
                    String dev = svc != null ? entOwner.get(svc.optString("rid")) : null;
                    if (dev != null) {
                        cl.name = devName.containsKey(dev) ? devName.get(dev) : "";
                        cl.lightId = devLight.get(dev);
                        Integer seen = perDevice.get(dev);
                        perDevice.put(dev, seen == null ? 1 : seen + 1);
                    }
                }
                if (cl.name.isEmpty()) cl.name = "Light " + (i + 1);
                out.add(cl);
            }
            // gradient lights have several channels: number them
            java.util.Map<String, Integer> idx = new java.util.HashMap<>();
            for (ChannelLight cl : out) {
                if (cl.lightId == null) continue;
                int total = 0;
                for (ChannelLight o : out) if (cl.lightId.equals(o.lightId)) total++;
                if (total > 1) {
                    int k = idx.containsKey(cl.lightId) ? idx.get(cl.lightId) + 1 : 1;
                    idx.put(cl.lightId, k);
                    cl.name = cl.name + " (" + k + "/" + total + ")";
                }
            }
        } catch (JSONException e) {
            throw new IOException("Unexpected answer from bridge", e);
        }
        return out;
    }

    /** Sets a Hue light to a color (rgb 0..1) through the normal API, so it stays after streaming. */
    public static void setLightColor(String ip, String appKey, String lightId, float r, float g, float b) throws IOException {
        float max = Math.max(r, Math.max(g, b));
        String body;
        if (max < 0.01f) {
            body = "{\"on\":{\"on\":false}}";
        } else {
            float[] lin = {lin(r / max), lin(g / max), lin(b / max)};
            double X = lin[0] * 0.664511 + lin[1] * 0.154324 + lin[2] * 0.162028;
            double Y = lin[0] * 0.283881 + lin[1] * 0.668433 + lin[2] * 0.047685;
            double Z = lin[0] * 0.000088 + lin[1] * 0.072310 + lin[2] * 0.986039;
            double sum = X + Y + Z;
            double x = sum > 0 ? X / sum : 0.3127, y = sum > 0 ? Y / sum : 0.3290;
            double bri = Math.max(1.0, Math.min(100.0, max * 100.0));
            body = String.format(java.util.Locale.US,
                    "{\"on\":{\"on\":true},\"dimming\":{\"brightness\":%.1f},\"color\":{\"xy\":{\"x\":%.4f,\"y\":%.4f}}}", bri, x, y);
        }
        request("PUT", "https://" + ip + "/clip/v2/resource/light/" + lightId, appKey, body, true);
    }

    /** Switches a Hue light on or off through the normal API. */
    public static void setLightOn(String ip, String appKey, String lightId, boolean on) throws IOException {
        request("PUT", "https://" + ip + "/clip/v2/resource/light/" + lightId, appKey, "{\"on\":{\"on\":" + on + "}}", true);
    }

    /**
     * Turns off the lamps of {@code oldArea} that aren't part of {@code newArea}
     * (lamps in neither area stay as they are). Returns how many were switched off.
     */
    public static int turnOffLeftovers(String ip, String appKey, String oldArea, String newArea) throws IOException {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (ChannelLight cl : channelLights(ip, appKey, newArea)) if (cl.lightId != null) keep.add(cl.lightId);
        java.util.Set<String> off = new java.util.LinkedHashSet<>();
        for (ChannelLight cl : channelLights(ip, appKey, oldArea)) {
            if (cl.lightId != null && !keep.contains(cl.lightId)) off.add(cl.lightId);
        }
        int n = 0;
        for (String id : off) {
            try {
                setLightOn(ip, appKey, id, false);
                n++;
            } catch (IOException ignored) {
            }
        }
        return n;
    }

    private static float lin(float c) {
        return c > 0.04045f ? (float) Math.pow((c + 0.055f) / 1.055f, 2.4f) : c / 12.92f;
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
