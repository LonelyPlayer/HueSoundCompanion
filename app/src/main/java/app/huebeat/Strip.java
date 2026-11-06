package app.huebeat;

import org.json.JSONException;
import org.json.JSONObject;

/** A saved LED strip (ESP32 / ESP8266 with WLED or another firmware that speaks DDP, E1.31 or WLED UDP). */
final class Strip {
    String name = "LED strip";
    String ip = "";
    int protocol = 0;      // StripProtocol.DDP
    int leds = 60;
    int universe = 1;      // E1.31 only
    boolean mirror = false;
    boolean reverse = false;
    float brightness = 1f;
    boolean enabled = true;
    boolean wled = false;  // found as a WLED device

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("name", name).put("ip", ip).put("protocol", protocol).put("leds", leds)
                .put("universe", universe).put("mirror", mirror).put("reverse", reverse)
                .put("brightness", (double) brightness).put("enabled", enabled).put("wled", wled);
    }

    static Strip fromJson(JSONObject o) {
        Strip s = new Strip();
        s.name = o.optString("name", s.name);
        s.ip = o.optString("ip", "");
        s.protocol = o.optInt("protocol", 0);
        s.leds = Math.max(1, o.optInt("leds", 60));
        s.universe = Math.max(1, o.optInt("universe", 1));
        s.mirror = o.optBoolean("mirror", false);
        s.reverse = o.optBoolean("reverse", false);
        s.brightness = (float) o.optDouble("brightness", 1.0);
        s.enabled = o.optBoolean("enabled", true);
        s.wled = o.optBoolean("wled", false);
        return s;
    }
}
