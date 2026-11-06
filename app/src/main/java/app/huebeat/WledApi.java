package app.huebeat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reads name and LED count from a WLED controller (http://ip/json/info). Call off the main thread. */
final class WledApi {
    static final class Info {
        String name;
        int leds;
        String version;
    }

    private WledApi() {
    }

    static Info info(String ip) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + "/json/info").openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(3000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject o = new JSONObject(b.toString("UTF-8"));
            Info i = new Info();
            i.name = o.optString("name", "WLED");
            JSONObject leds = o.optJSONObject("leds");
            i.leds = leds != null ? leds.optInt("count", 0) : 0;
            i.version = o.optString("ver", "");
            if (i.leds <= 0) throw new IOException("not a WLED device");
            return i;
        } catch (JSONException e) {
            throw new IOException("not a WLED device", e);
        } finally {
            c.disconnect();
        }
    }

    /** Puts per-LED colors on a WLED controller as a solid state, so they stay after streaming ends. */
    static void setColors(String ip, int[] colors) throws IOException {
        StringBuilder seg = new StringBuilder();
        int i = 0;
        while (i < colors.length) {
            int j = i;
            while (j + 1 < colors.length && colors[j + 1] == colors[i]) j++;
            if (seg.length() > 0) seg.append(',');
            seg.append(i).append(',').append(j + 1).append(",\"").append(String.format("%06X", colors[i] & 0xFFFFFF)).append('"');
            i = j + 1;
        }
        String body;
        if (seg.length() < 15000) {
            body = "{\"on\":true,\"bri\":255,\"live\":false,\"seg\":[{\"id\":0,\"fx\":0,\"i\":[" + seg + "]}]}";
        } else {
            // too many different colors for one request: use the average
            long r = 0, g = 0, b = 0;
            for (int c : colors) {
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            int n = Math.max(1, colors.length);
            body = "{\"on\":true,\"bri\":255,\"live\":false,\"seg\":[{\"id\":0,\"fx\":0,\"col\":[[" + r / n + "," + g / n + "," + b / n + "]]}]}";
        }
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + "/json/state").openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(3000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (java.io.OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code);
    }
}
