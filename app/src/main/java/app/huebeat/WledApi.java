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
}
