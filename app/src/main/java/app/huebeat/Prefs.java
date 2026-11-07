package app.huebeat;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.huebeat.core.LightSettings;
import app.huebeat.core.Palettes;

/** Persistent settings plus the live {@link LightSettings} shared with the streaming engine. */
public final class Prefs {
    public static final int MODE_OUTPUT = 0, MODE_APP = 1, MODE_MIC = 2;
    /** Service mode for Ambient (no sound). */
    public static final int MODE_AMBIENT = 3;
    public static final int APP_MUSIC = 0, APP_AMBIENT = 1;

    private static Prefs instance;

    private final SharedPreferences sp;
    public final LightSettings light = new LightSettings();
    /** Ambient mode settings for the Hue lights (strips can follow or override them). */
    public final app.huebeat.core.Ambient.Settings ambient = new app.huebeat.core.Ambient.Settings();
    /** Per Hue channel colors for "Static colors" (channel id → 0xRRGGBB); replaced as a whole. */
    public volatile java.util.Map<Integer, Integer> lightColors = new java.util.HashMap<>();

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("huebeat", Context.MODE_PRIVATE);
        light.style = sp.getInt("style", LightSettings.STYLE_BEAT);
        light.palette = sp.getInt("palette", 0);
        light.intensity = sp.getFloat("intensity", 0.65f);
        light.colorMove = sp.getFloat("colorMove", 0.5f);
        light.maxBrightness = sp.getFloat("maxBrightness", 1f);
        light.strobe = sp.getBoolean("strobe", false);
        light.buildups = sp.getBoolean("buildups", true);
        light.screenColors = sp.getBoolean("screenColors", false);
        ambient.animation = sp.getInt("amb_anim", app.huebeat.core.Ambient.PALETTE);
        ambient.palette = sp.getInt("amb_palette", 1);
        ambient.color = sp.getInt("amb_color", 0xFF8A2A);
        ambient.speed = sp.getFloat("amb_speed", 0.5f);
        ambient.brightness = sp.getFloat("amb_bright", 1f);
        try {
            JSONObject lc = new JSONObject(sp.getString("light_colors", "{}"));
            java.util.Map<Integer, Integer> m = new java.util.HashMap<>();
            java.util.Iterator<String> it = lc.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(Integer.parseInt(k), lc.getInt(k));
            }
            lightColors = m;
        } catch (Exception ignored) {
        }
        loadCustomPalettes();
        // New built-in palettes push the user's own ones back; keep the same one selected.
        int oldBuiltIn = sp.getInt("builtInPalettes", 7);
        if (oldBuiltIn != Palettes.BUILT_IN_COUNT) {
            if (light.palette >= oldBuiltIn) light.palette += Palettes.BUILT_IN_COUNT - oldBuiltIn;
            sp.edit().putInt("builtInPalettes", Palettes.BUILT_IN_COUNT).putInt("palette", light.palette).apply();
        }
        if (light.palette >= Palettes.count()) light.palette = 0;
    }

    // ------------------------------------------------------------------ own palettes

    private final List<Palettes.Custom> customPalettes = new ArrayList<>();

    private void loadCustomPalettes() {
        customPalettes.clear();
        try {
            JSONArray arr = new JSONArray(sp.getString("customPalettes", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray cs = o.getJSONArray("colors");
                int[] colors = new int[cs.length()];
                for (int k = 0; k < colors.length; k++) colors[k] = cs.getInt(k) & 0xFFFFFF;
                if (colors.length > 0) customPalettes.add(new Palettes.Custom(o.optString("name", "Mine"), colors));
            }
        } catch (JSONException ignored) {
        }
        Palettes.setCustom(customPalettes);
    }

    private void storeCustomPalettes() {
        JSONArray arr = new JSONArray();
        try {
            for (Palettes.Custom c : customPalettes) {
                JSONArray cs = new JSONArray();
                for (int v : c.colors) cs.put(v);
                arr.put(new JSONObject().put("name", c.name).put("colors", cs));
            }
        } catch (JSONException ignored) {
        }
        sp.edit().putString("customPalettes", arr.toString()).apply();
        Palettes.setCustom(customPalettes);
    }

    /** @return the own palette at palette index {@code paletteIndex}, or null. */
    public Palettes.Custom customAt(int paletteIndex) {
        int k = paletteIndex - Palettes.BUILT_IN_COUNT;
        return k >= 0 && k < customPalettes.size() ? customPalettes.get(k) : null;
    }

    /** Adds (paletteIndex &lt; 0) or replaces a palette; returns its palette index. */
    public int saveCustom(int paletteIndex, String name, int[] colors) {
        Palettes.Custom p = new Palettes.Custom(name, colors);
        int k = paletteIndex - Palettes.BUILT_IN_COUNT;
        if (paletteIndex >= 0 && k >= 0 && k < customPalettes.size()) {
            customPalettes.set(k, p);
        } else {
            customPalettes.add(p);
            k = customPalettes.size() - 1;
        }
        storeCustomPalettes();
        return Palettes.BUILT_IN_COUNT + k;
    }

    public void deleteCustom(int paletteIndex) {
        int k = paletteIndex - Palettes.BUILT_IN_COUNT;
        if (k < 0 || k >= customPalettes.size()) return;
        customPalettes.remove(k);
        storeCustomPalettes();
        if (light.palette == paletteIndex) light.palette = 0;
        else if (light.palette > paletteIndex) light.palette--;
        saveLight();
    }

    public static synchronized Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c);
        return instance;
    }

    public void saveLight() {
        sp.edit()
                .putInt("style", light.style)
                .putInt("palette", light.palette)
                .putFloat("intensity", light.intensity)
                .putFloat("colorMove", light.colorMove)
                .putFloat("maxBrightness", light.maxBrightness)
                .putBoolean("strobe", light.strobe)
                .putBoolean("buildups", light.buildups)
                .putBoolean("screenColors", light.screenColors)
                .apply();
    }

    public void saveAmbient() {
        sp.edit().putInt("amb_anim", ambient.animation).putInt("amb_palette", ambient.palette)
                .putInt("amb_color", ambient.color).putFloat("amb_speed", ambient.speed)
                .putFloat("amb_bright", ambient.brightness).apply();
    }

    public void setLightColor(int channel, int rgb) {
        java.util.Map<Integer, Integer> m = new java.util.HashMap<>(lightColors);
        m.put(channel, rgb);
        lightColors = m;
        JSONObject o = new JSONObject();
        try {
            for (java.util.Map.Entry<Integer, Integer> e : m.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue());
        } catch (JSONException ignored) {
        }
        sp.edit().putString("light_colors", o.toString()).apply();
    }

    public int appMode() { return sp.getInt("app_mode", APP_MUSIC); }
    public void setAppMode(int m) { sp.edit().putInt("app_mode", m).apply(); }
    public boolean keepColors() { return sp.getBoolean("keep_colors", true); }
    public void setKeepColors(boolean k) { sp.edit().putBoolean("keep_colors", k).apply(); }

    /** Cached Hue light names per channel (from the bridge), as "id=name" lines. */
    public String channelNames() { return sp.getString("channel_names", ""); }
    public void setChannelNames(String s) { sp.edit().putString("channel_names", s).apply(); }

    // bridge
    public String bridgeIp() { return sp.getString("bridgeIp", null); }
    public String bridgeName() { return sp.getString("bridgeName", "Hue Bridge"); }
    public String username() { return sp.getString("username", null); }
    public String clientKey() { return sp.getString("clientKey", null); }
    public String areaId() { return sp.getString("areaId", null); }
    public String areaName() { return sp.getString("areaName", ""); }

    public boolean isConfigured() {
        return bridgeIp() != null && username() != null && clientKey() != null && areaId() != null;
    }

    public void saveBridge(String ip, String name, String user, String key) {
        sp.edit().putString("bridgeIp", ip).putString("bridgeName", name)
                .putString("username", user).putString("clientKey", key).apply();
    }

    public void saveArea(String id, String name) {
        sp.edit().putString("areaId", id).putString("areaName", name).apply();
    }

    // ------------------------------------------------------------------ LED strips

    public java.util.List<Strip> strips() {
        java.util.List<Strip> l = new java.util.ArrayList<>();
        try {
            String json = stripsCache != null ? stripsCache : sp.getString("strips", "[]");
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) l.add(Strip.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return l;
    }

    /** Bumped on every change to the strips; the sync engine applies changes live when it moves. */
    public static volatile int stripsVersion = 0;
    private volatile String stripsCache;

    public void saveStrips(java.util.List<Strip> l) {
        JSONArray a = new JSONArray();
        try {
            for (Strip s : l) a.put(s.toJson());
        } catch (JSONException ignored) {
        }
        sp.edit().putString("strips", a.toString()).apply();
        stripsCache = a.toString();
        stripsVersion++;
    }

    // ------------------------------------------------------------------ DMX lights

    public java.util.List<DmxLight> dmxLights() {
        java.util.List<DmxLight> l = new java.util.ArrayList<>();
        try {
            String json = dmxCache != null ? dmxCache : sp.getString("dmx", "[]");
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) l.add(DmxLight.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return l;
    }

    /** Bumped on every change to the DMX lights (applied live by the engine). */
    public static volatile int dmxVersion = 0;
    private volatile String dmxCache;
    /** Set while the DMX channel tester runs (the show then pauses its DMX output). */
    public static volatile String testLight;

    public void saveDmx(java.util.List<DmxLight> l) {
        JSONArray a = new JSONArray();
        try {
            for (DmxLight d : l) a.put(d.toJson());
        } catch (JSONException ignored) {
        }
        sp.edit().putString("dmx", a.toString()).apply();
        dmxCache = a.toString();
        dmxVersion++;
    }

    public boolean hasEnabledDmx() {
        for (DmxLight d : dmxLights()) if (d.enabled && !d.ip.isEmpty()) return true;
        return false;
    }

    public boolean hasEnabledStrips() {
        for (Strip s : strips()) if (s.enabled) return true;
        return false;
    }

    /** Something to send light to: a Hue bridge and/or an enabled LED strip. */
    public boolean hasOutputs() {
        return isConfigured() || hasEnabledStrips() || hasEnabledDmx();
    }

    // audio source
    public int mode() { return sp.getInt("mode", MODE_OUTPUT); }
    public void setMode(int m) { sp.edit().putInt("mode", m).apply(); }
    public String appPackage() { return sp.getString("appPackage", null); }
    public String appLabel() { return sp.getString("appLabel", null); }
    public void setApp(String pkg, String label) { sp.edit().putString("appPackage", pkg).putString("appLabel", label).apply(); }

    // latency, kept separately for the phone speaker and Bluetooth
    public int delaySpeaker() { return sp.getInt("delaySpeaker", 0); }
    public int delayBluetooth() { return sp.getInt("delayBluetooth", 200); }
    public void setDelay(boolean bluetooth, int ms) {
        sp.edit().putInt(bluetooth ? "delayBluetooth" : "delaySpeaker", ms).apply();
    }
}
