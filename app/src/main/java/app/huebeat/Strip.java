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
    /** Which slider sets the strip's brightness: StripOutput.BRIGHT_BOTH / BRIGHT_OWN / BRIGHT_LIGHTS. */
    int brightnessMode = 0;
    boolean enabled = true;
    boolean wled = false;  // found as a WLED device
    // effect
    int effect = 0;        // StripEffect.LIGHTS
    float speed = 0.5f;
    int palette = -1;      // -1 = same as the lights
    int variant = 0;       // LedFx effect option (e.g. Bar: wipe/bounce/in-out)
    float blur = 0f;       // 0 = none
    // ambient mode
    int ambientEffect = -1;        // -1 = same as the lights, else Ambient.*
    int ambientColor = -1;            // -1 = same as the lights
    /** Where it runs in the room (see core.Room); a group shares the look of its members. */
    float x1 = -0.8f, y1 = 0.9f, x2 = 0.8f, y2 = 0.9f;
    String group = "";
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
                .put("strobeAmount", (double) strobeAmount)
                .put("variant", variant).put("blur", (double) blur)
                .put("ambientEffect", ambientEffect).put("ambientColor", ambientColor)
                .put("brightnessMode", brightnessMode)
                .put("x1", (double) x1).put("y1", (double) y1).put("x2", (double) x2).put("y2", (double) y2).put("group", group);
    }

    /** Everything that needs a new network output when it changes. */
    String layoutKey() {
        return ip + "|" + protocol + "|" + leds + "|" + universe + "|" + mirror + "|" + reverse;
    }

    /** Copies the visual settings onto a running output (takes effect on the next frame). */
    void applyLive(app.huebeat.core.StripOutput so) {
        so.palette = palette;
        so.brightness = brightness;
        so.brightnessMode = brightnessMode;
        so.x1 = x1;
        so.y1 = y1;
        so.x2 = x2;
        so.y2 = y2;
        so.effect.effect = effect;
        so.effect.speed = speed;
        so.effect.strobeTrigger = strobeTrigger;
        so.effect.strobeColor = strobeColor;
        so.effect.strobeLength = strobeLength;
        so.effect.strobeCover = strobeCover;
        so.effect.strobeAmount = strobeAmount;
        so.effect.variant = variant;
        so.effect.blur = blur;
        so.ambientEffect = ambientEffect;
        so.ambientColor = ambientColor;
    }

    /** Takes over the look of another member of the same group (not its connection, place or switch). */
    void copyLookFrom(Strip o) {
        effect = o.effect;
        speed = o.speed;
        variant = o.variant;
        blur = o.blur;
        palette = o.palette;
        brightness = o.brightness;
        brightnessMode = o.brightnessMode;
        strobeTrigger = o.strobeTrigger;
        strobeColor = o.strobeColor;
        strobeLength = o.strobeLength;
        strobeCover = o.strobeCover;
        strobeAmount = o.strobeAmount;
        ambientEffect = o.ambientEffect;
        ambientColor = o.ambientColor;
    }

    /** Builds the network output for this strip with its effect settings. */
    app.huebeat.core.StripOutput output() throws java.io.IOException {
        app.huebeat.core.StripOutput so = new app.huebeat.core.StripOutput(name, java.net.InetAddress.getByName(ip),
                protocol, leds, universe, mirror, reverse, brightness);
        applyLive(so);
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
        s.variant = o.optInt("variant", 0);
        s.blur = (float) o.optDouble("blur", 0.0);
        s.ambientEffect = o.optInt("ambientEffect", -1);
        s.ambientColor = o.optInt("ambientColor", -1);
        s.brightnessMode = Math.max(0, Math.min(2, o.optInt("brightnessMode", 0)));
        s.x1 = (float) o.optDouble("x1", -0.8);
        s.y1 = (float) o.optDouble("y1", 0.9);
        s.x2 = (float) o.optDouble("x2", 0.8);
        s.y2 = (float) o.optDouble("y2", 0.9);
        s.group = o.optString("group", "");
        return s;
    }
}

