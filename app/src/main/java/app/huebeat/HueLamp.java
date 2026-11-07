package app.huebeat;

import org.json.JSONException;
import org.json.JSONObject;

import app.huebeat.core.Dmx;

/**
 * One Hue lamp as a device of its own (like a strip or a DMX light). The app builds its own
 * entertainment configuration on the bridge from these lamps and their room positions.
 */
final class HueLamp {
    String name = "Hue lamp";
    String lightId = "";      // light resource (normal API: keep colors, switch off)
    String entService = "";   // entertainment service (streaming)
    int segments = 1;         // gradient lights: several streaming channels
    /** Room position (-1..1): x left → right, y back → front, z floor → ceiling; gradient lights end at x2/y2. */
    float x = 0f, y = 0f, z = 0f, x2 = 0f, y2 = 0f;
    boolean enabled = true;
    String group = "";
    /** Groups: 0 = spread over the members' places, 1 = all members the same color. */
    int together = 0;
    // look (as for DMX lights)
    int effect = Dmx.FX_LIKE;
    int palette = -1;
    int color = 0xFF2A6D;
    int ambientEffect = -1;
    int ambientColor = -1;
    float brightness = 1f;

    boolean gradient() {
        return segments > 1;
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("name", name).put("lightId", lightId).put("entService", entService)
                .put("segments", segments).put("x", (double) x).put("y", (double) y).put("z", (double) z)
                .put("x2", (double) x2).put("y2", (double) y2).put("enabled", enabled).put("group", group)
                .put("together", together).put("effect", effect).put("palette", palette).put("color", color)
                .put("ambientEffect", ambientEffect).put("ambientColor", ambientColor).put("brightness", (double) brightness);
    }

    static HueLamp fromJson(JSONObject o) {
        HueLamp h = new HueLamp();
        h.name = o.optString("name", h.name);
        h.lightId = o.optString("lightId", "");
        h.entService = o.optString("entService", "");
        h.segments = Math.max(1, o.optInt("segments", 1));
        h.x = (float) o.optDouble("x", 0);
        h.y = (float) o.optDouble("y", 0);
        h.z = (float) o.optDouble("z", 0);
        h.x2 = (float) o.optDouble("x2", h.x);
        h.y2 = (float) o.optDouble("y2", h.y);
        h.enabled = o.optBoolean("enabled", true);
        h.group = o.optString("group", "");
        h.together = o.optInt("together", 0);
        h.effect = Math.max(0, Math.min(Dmx.EFFECTS.length - 1, o.optInt("effect", Dmx.FX_LIKE)));
        h.palette = o.optInt("palette", -1);
        h.color = o.optInt("color", 0xFF2A6D);
        h.ambientEffect = o.optInt("ambientEffect", -1);
        h.ambientColor = o.optInt("ambientColor", -1);
        h.brightness = (float) o.optDouble("brightness", 1.0);
        return h;
    }

    /** Takes over the look of another member of the same group (not its place or switch). */
    void copyLookFrom(HueLamp o) {
        effect = o.effect;
        palette = o.palette;
        color = o.color;
        ambientEffect = o.ambientEffect;
        ambientColor = o.ambientColor;
        brightness = o.brightness;
        together = o.together;
    }

    /** Room points of this lamp: one, or one per segment along its line for gradient lights. */
    float[][] points() {
        float[][] p = new float[segments][];
        for (int i = 0; i < segments; i++) {
            float t = segments == 1 ? 0f : (float) i / (segments - 1);
            p[i] = new float[]{x + (x2 - x) * t, y + (y2 - y) * t, z};
        }
        return p;
    }
}
