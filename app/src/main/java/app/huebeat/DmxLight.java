package app.huebeat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import app.huebeat.core.Dmx;

/** A DMX light behind an Art-Net / sACN node, as configured by the user. */
final class DmxLight {
    String name = "DMX light";
    String ip = "";
    int protocol = 0;        // 0 Art-Net, 1 sACN
    int universe = 0;        // Art-Net port-address (sACN: universe number, from 1)
    int address = 1;         // first DMX channel, 1..512
    int preset = 0;
    int[] roles = Dmx.PRESETS[0].roles.clone();
    float x = 0f;            // -1 left .. 1 right
    float brightness = 1f;
    boolean enabled = true;
    float panCenter = 0.5f, tiltCenter = 0.5f, move = 0.5f;
    int strobeMode = 0;

    JSONObject toJson() throws JSONException {
        JSONArray r = new JSONArray();
        for (int v : roles) r.put(v);
        return new JSONObject().put("name", name).put("ip", ip).put("protocol", protocol).put("universe", universe)
                .put("address", address).put("preset", preset).put("roles", r).put("x", (double) x)
                .put("brightness", (double) brightness).put("enabled", enabled)
                .put("pan", (double) panCenter).put("tilt", (double) tiltCenter).put("move", (double) move)
                .put("strobe", strobeMode);
    }

    static DmxLight fromJson(JSONObject o) {
        DmxLight d = new DmxLight();
        d.name = o.optString("name", d.name);
        d.ip = o.optString("ip", "");
        d.protocol = o.optInt("protocol", 0);
        d.universe = Math.max(0, o.optInt("universe", 0));
        d.address = Math.max(1, Math.min(512, o.optInt("address", 1)));
        d.preset = Math.max(0, Math.min(Dmx.PRESETS.length - 1, o.optInt("preset", 0)));
        JSONArray r = o.optJSONArray("roles");
        if (r != null && r.length() > 0) {
            d.roles = new int[Math.min(64, r.length())];
            for (int i = 0; i < d.roles.length; i++) d.roles[i] = Math.max(0, Math.min(Dmx.ROLE_NAMES.length - 1, r.optInt(i, 0)));
        } else {
            d.roles = Dmx.PRESETS[d.preset].roles.clone();
        }
        d.x = (float) o.optDouble("x", 0.0);
        d.brightness = (float) o.optDouble("brightness", 1.0);
        d.enabled = o.optBoolean("enabled", true);
        d.panCenter = (float) o.optDouble("pan", 0.5);
        d.tiltCenter = (float) o.optDouble("tilt", 0.5);
        d.move = (float) o.optDouble("move", 0.3);
        d.strobeMode = o.optInt("strobe", 0);
        return d;
    }

    /** Everything that needs a new output when it changes. */
    String layoutKey() {
        return ip + "|" + protocol + "|" + universe + "|" + address + "|" + x + "|" + roles.length;
    }

    Dmx.Fixture fixture() {
        Dmx.Fixture f = new Dmx.Fixture(name, ip, protocol, universe, address, x, roles.clone());
        applyLive(f);
        return f;
    }

    void applyLive(Dmx.Fixture f) {
        f.roles = roles.clone();
        f.brightness = brightness;
        f.panCenter = panCenter;
        f.tiltCenter = tiltCenter;
        f.move = move;
        f.strobeMode = strobeMode;
    }

    int lastChannel() {
        return address + roles.length - 1;
    }
}
