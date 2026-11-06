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

    private static Prefs instance;

    private final SharedPreferences sp;
    public final LightSettings light = new LightSettings();

    private Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("huebeat", Context.MODE_PRIVATE);
        light.style = sp.getInt("style", LightSettings.STYLE_BEAT);
        light.palette = sp.getInt("palette", 0);
        light.intensity = sp.getFloat("intensity", 0.65f);
        light.colorMove = sp.getFloat("colorMove", 0.5f);
        light.maxBrightness = sp.getFloat("maxBrightness", 1f);
        light.strobe = sp.getBoolean("strobe", false);
        light.screenColors = sp.getBoolean("screenColors", false);
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
                .putBoolean("screenColors", light.screenColors)
                .apply();
    }

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
            JSONArray a = new JSONArray(sp.getString("strips", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(Strip.fromJson(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        return l;
    }

    public void saveStrips(java.util.List<Strip> l) {
        JSONArray a = new JSONArray();
        try {
            for (Strip s : l) a.put(s.toJson());
        } catch (JSONException ignored) {
        }
        sp.edit().putString("strips", a.toString()).apply();
    }

    public boolean hasEnabledStrips() {
        for (Strip s : strips()) if (s.enabled) return true;
        return false;
    }

    /** Something to send light to: a Hue bridge and/or an enabled LED strip. */
    public boolean hasOutputs() {
        return isConfigured() || hasEnabledStrips();
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
