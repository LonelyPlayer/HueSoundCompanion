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
    float y = 0.5f;          // -1 back .. 1 front
    float z = 0f;            // -1 floor .. 1 ceiling
    float brightness = 1f;
    boolean enabled = true;
    float panCenter = 0.5f, tiltCenter = 0.5f, move = 0.5f;
    int strobeMode = 0;
    // its own look (like the LED strips)
    int effect = Dmx.FX_LIKE;
    int palette = -1;           // -1 = same as the lights
    int color = 0xFF2A6D;
    int ambientEffect = -1;     // -1 = same as the lights
    int ambientColor = -1;            // -1 = same as the lights
    int movement = Dmx.MOVE_SWING;
    float moveSpeed = 0.5f;
    int gobo = 0;
    /** Group name ("" = none); members share their look. How they move together: 0 same, 1 mirrored, 2 chase. */
    String group = "";
    int together = 0;

    JSONObject toJson() throws JSONException {
        JSONArray r = new JSONArray();
        for (int v : roles) r.put(v);
        return new JSONObject().put("name", name).put("ip", ip).put("protocol", protocol).put("universe", universe)
                .put("address", address).put("preset", preset).put("roles", r).put("x", (double) x).put("y", (double) y).put("z", (double) z)
                .put("brightness", (double) brightness).put("enabled", enabled)
                .put("pan", (double) panCenter).put("tilt", (double) tiltCenter).put("move", (double) move)
                .put("strobe", strobeMode).put("effect", effect).put("palette", palette).put("color", color)
                .put("ambientEffect", ambientEffect).put("ambientColor", ambientColor).put("movement", movement)
                .put("moveSpeed", (double) moveSpeed).put("gobo", gobo).put("group", group).put("together", together);
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
        d.y = (float) o.optDouble("y", 0.5);
        d.z = (float) o.optDouble("z", 0.0);
        d.brightness = (float) o.optDouble("brightness", 1.0);
        d.enabled = o.optBoolean("enabled", true);
        d.panCenter = (float) o.optDouble("pan", 0.5);
        d.tiltCenter = (float) o.optDouble("tilt", 0.5);
        d.move = (float) o.optDouble("move", 0.3);
        d.strobeMode = o.optInt("strobe", 0);
        d.effect = Math.max(0, Math.min(Dmx.EFFECTS.length - 1, o.optInt("effect", Dmx.FX_LIKE)));
        d.palette = o.optInt("palette", -1);
        d.color = o.optInt("color", 0xFF2A6D);
        d.ambientEffect = o.optInt("ambientEffect", -1);
        d.ambientColor = o.optInt("ambientColor", -1);
        d.movement = Math.max(0, Math.min(Dmx.MOVES.length - 1, o.optInt("movement", Dmx.MOVE_SWING)));
        d.moveSpeed = (float) o.optDouble("moveSpeed", 0.5);
        d.gobo = Math.max(0, Math.min(Dmx.GOBOS.length - 1, o.optInt("gobo", 0)));
        d.group = o.optString("group", "");
        d.together = Math.max(0, Math.min(2, o.optInt("together", 0)));
        return d;
    }

    /** Everything that needs a new output when it changes. */
    String layoutKey() {
        return ip + "|" + protocol + "|" + universe + "|" + address + "|" + x + "|" + roles.length + "|" + group;
    }

    Dmx.Fixture fixture() {
        Dmx.Fixture f = new Dmx.Fixture(name, ip, protocol, universe, address, x, roles.clone());
        if (!group.isEmpty()) f.seed(group); // same random moves for the whole group
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
        f.effect = effect;
        f.palette = palette;
        f.color = color;
        f.ambientEffect = ambientEffect;
        f.ambientColor = ambientColor;
        f.movement = movement;
        f.moveSpeed = moveSpeed;
        f.gobo = gobo;
        f.together = group.isEmpty() ? 0 : together;
    }

    /** Takes over the look of another member of the same group (not its place, aim or switch). */
    void copyLookFrom(DmxLight o) {
        effect = o.effect;
        palette = o.palette;
        color = o.color;
        ambientEffect = o.ambientEffect;
        ambientColor = o.ambientColor;
        movement = o.movement;
        move = o.move;
        moveSpeed = o.moveSpeed;
        gobo = o.gobo;
        strobeMode = o.strobeMode;
        brightness = o.brightness;
        together = o.together;
    }

    int lastChannel() {
        return address + roles.length - 1;
    }
}
