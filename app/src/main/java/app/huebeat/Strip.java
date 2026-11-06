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
    // effect
    int effect = 0;        // StripEffect.LIGHTS
    float speed = 0.5f;
    int palette = -1;      // -1 = same as the lights
    // strobe overlay
    int strobeTrigger = 0; // off
    int strobeColor = 0xFFFFFF;
    float strobeLength = 0.3f;
    int strobeCover = 0;
    float strobeAmount = 1f;

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("name", name).put("ip", ip).put("protocol", protocol).put("leds", leds)
                .put("universe", universe).put("mirror", mirror).put("reverse", reverse)
                .put("brightness", (double) brightness).put("enabled", enabled).put("wled", wled)
                .put("effect", effect).put("speed", (double) speed).put("palette", palette)
                .put("strobeTrigger", strobeTrigger).put("strobeColor", strobeColor)
                .put("strobeLength", (double) strobeLength).put("strobeCover", strobeCover)
                .put("strobeAmount", (double) strobeAmount);
    }

    /** Builds the network output for this strip with its effect settings. */
    app.huebeat.core.StripOutput output() throws java.io.IOException {
        app.huebeat.core.StripOutput so = new app.huebeat.core.StripOutput(name, java.net.InetAddress.getByName(ip),
                protocol, leds, universe, mirror, reverse, brightness);
        so.palette = palette;
        so.effect.effect = effect;
        so.effect.speed = speed;
        so.effect.strobeTrigger = strobeTrigger;
        so.effect.strobeColor = strobeColor;
        so.effect.strobeLength = strobeLength;
        so.effect.strobeCover = strobeCover;
        so.effect.strobeAmount = strobeAmount;
        return so;
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
        s.effect = o.optInt("effect", 0);
        s.speed = (float) o.optDouble("speed", 0.5);
        s.palette = o.optInt("palette", -1);
        s.strobeTrigger = o.optInt("strobeTrigger", 0);
        s.strobeColor = o.optInt("strobeColor", 0xFFFFFF);
        s.strobeLength = (float) o.optDouble("strobeLength", 0.3);
        s.strobeCover = o.optInt("strobeCover", 0);
        s.strobeAmount = (float) o.optDouble("strobeAmount", 1.0);
        return s;
    }
}

