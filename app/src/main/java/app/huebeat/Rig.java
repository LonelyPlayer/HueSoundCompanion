package app.huebeat;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.huebeat.core.Ambient;
import app.huebeat.core.Buildup;
import app.huebeat.core.Dmx;
import app.huebeat.core.Features;
import app.huebeat.core.LampColors;
import app.huebeat.core.LightSettings;
import app.huebeat.core.Look;
import app.huebeat.core.Room;
import app.huebeat.core.Scene;
import app.huebeat.core.StripOutput;

/**
 * Every light the app drives, in one room: Hue lamps (streamed through the app's own
 * entertainment configuration), LED strips and DMX lights. One {@link Scene} renders the global
 * look over all of their points; devices and groups with their own look render it with a
 * {@link Look} over their own points. Settings changed in the app are taken over live.
 */
final class Rig {
    // ---- Hue
    final List<HueLamp> lamps = new ArrayList<>();   // streamed lamps (enabled, at most 10)
    int[] channelIds = new int[0];
    private int[] chLamp = new int[0], chSeg = new int[0];
    private float[] hueRgb = new float[0];
    private int[] lampOff = new int[0];               // first scene point of each lamp

    // ---- strips
    List<StripOutput> strips = new ArrayList<>();
    private List<String> stripKeys = new ArrayList<>();
    private int[] stripOff = new int[0];

    // ---- DMX
    Dmx.Output dmx = new Dmx.Output(Dmx.none());
    private List<DmxLight> dmxCfg = new ArrayList<>();
    private List<String> dmxKeys = new ArrayList<>();
    private int[] dmxOff = new int[0];
    private float[] dmxRgb = new float[0];

    // ---- room
    Scene scene;
    /** The look of each Hue lamp / DMX light: its own, or its group's (shared object). */
    private Look[] lampLook = new Look[0], dmxLook = new Look[0];
    /** Where each lamp / fixture sits inside its look's points. */
    private int[] lampInLook = new int[0], dmxInLook = new int[0];
    private final Map<Look, float[]> frameCache = new HashMap<>();
    private final Map<Look, int[]> lookIdx = new HashMap<>();

    private app.huebeat.core.Devices devs;
    private int seenStrips = Integer.MIN_VALUE, seenDmx = Integer.MIN_VALUE, seenHue = Integer.MIN_VALUE, seenFlow = Integer.MIN_VALUE;
    private int lastBeat = Integer.MIN_VALUE;

    /** Hue lamps that take part (enabled, in list order, at most 10: the bridge's limit). */
    static List<HueLamp> streamedLamps(Prefs prefs) {
        List<HueLamp> l = new ArrayList<>();
        if (!prefs.isPaired()) return l;
        for (HueLamp h : prefs.hueLamps()) {
            if (h.enabled && !h.entService.isEmpty() && l.size() < 10) l.add(h);
        }
        return l;
    }

    static List<Strip> activeStrips(Prefs prefs) {
        List<Strip> l = new ArrayList<>();
        for (Strip st : prefs.strips()) if (st.enabled && !st.ip.isEmpty()) l.add(st);
        return l;
    }

    static List<DmxLight> activeDmx(Prefs prefs) {
        List<DmxLight> l = new ArrayList<>();
        for (DmxLight d : prefs.dmxLights()) if (d.enabled && !d.ip.isEmpty()) l.add(d);
        return l;
    }

    boolean isEmpty() {
        return lamps.isEmpty() && strips.isEmpty() && dmx.isEmpty();
    }

    /** Wires the streamed lamps to the channels of the app's entertainment configuration. */
    void setHue(List<HueLamp> l, HueApi.ChannelMap map) {
        lamps.clear();
        lamps.addAll(l);
        int n = map == null ? 0 : map.ids.length;
        channelIds = new int[n];
        chLamp = new int[n];
        chSeg = new int[n];
        for (int c = 0; c < n; c++) {
            channelIds[c] = map.ids[c];
            chLamp[c] = -1;
            for (int k = 0; k < lamps.size(); k++) {
                if (lamps.get(k).entService.equals(map.service[c])) {
                    chLamp[c] = k;
                    chSeg[c] = Math.max(0, Math.min(lamps.get(k).segments - 1, map.segment[c]));
                }
            }
        }
        hueRgb = new float[3 * n];
        seenHue = Prefs.hueVersion;
        layout(Prefs.flowStatic());
    }

    /** Takes over changes from the app (live where possible, otherwise a new layout). */
    void update(Prefs prefs) throws IOException {
        boolean relayout = false;
        if (Prefs.stripsVersion != seenStrips) {
            seenStrips = Prefs.stripsVersion;
            List<Strip> cfg = activeStrips(prefs);
            boolean same = cfg.size() == strips.size();
            for (int k = 0; same && k < cfg.size(); k++) same = cfg.get(k).layoutKey().equals(stripKeys.get(k));
            if (same) {
                for (int k = 0; k < cfg.size(); k++) cfg.get(k).applyLive(strips.get(k));
            } else {
                strips = new ArrayList<>();
                stripKeys = new ArrayList<>();
                for (Strip st : cfg) {
                    try {
                        strips.add(st.output());
                        stripKeys.add(st.layoutKey());
                    } catch (IOException ignored) {
                        // bad address: skip this strip
                    }
                }
            }
            relayout = true; // places may have changed
        }
        if (Prefs.dmxVersion != seenDmx) {
            seenDmx = Prefs.dmxVersion;
            List<DmxLight> cfg = activeDmx(prefs);
            boolean same = cfg.size() == dmxKeys.size();
            for (int k = 0; same && k < cfg.size(); k++) same = cfg.get(k).layoutKey().equals(dmxKeys.get(k));
            if (same) {
                for (int k = 0; k < cfg.size(); k++) cfg.get(k).applyLive(dmx.fixtures.get(k));
            } else {
                List<Dmx.Fixture> fx = new ArrayList<>();
                dmxKeys = new ArrayList<>();
                for (DmxLight d : cfg) {
                    fx.add(d.fixture());
                    dmxKeys.add(d.layoutKey());
                }
                Buildup ov = dmx.overlay;
                dmx = new Dmx.Output(fx);
                dmx.overlay = ov;
            }
            dmxCfg = cfg;
            groupPlaces();
            relayout = true;
        }
        if (Prefs.hueVersion != seenHue) {
            // looks and places of the streamed lamps (the set of lamps changes only with a restart)
            seenHue = Prefs.hueVersion;
            Map<String, HueLamp> now = new HashMap<>();
            for (HueLamp h : prefs.hueLamps()) now.put(h.entService, h);
            for (int k = 0; k < lamps.size(); k++) {
                HueLamp h = now.get(lamps.get(k).entService);
                if (h != null) lamps.set(k, h);
            }
            relayout = true;
        }
        if (Prefs.flowVersion != seenFlow) {
            seenFlow = Prefs.flowVersion;
            relayout = true;
        }
        if (relayout || scene == null) layout(prefs.flow());
    }

    /** DMX groups: each member's place in its group (mirrored and chasing moves). */
    private void groupPlaces() {
        Map<String, Integer> count = new HashMap<>(), seen = new HashMap<>();
        for (DmxLight d : dmxCfg) if (!d.group.isEmpty()) count.merge(d.group, 1, Integer::sum);
        for (int k = 0; k < dmxCfg.size() && k < dmx.fixtures.size(); k++) {
            DmxLight d = dmxCfg.get(k);
            Dmx.Fixture f = dmx.fixtures.get(k);
            if (d.group.isEmpty()) {
                f.memberIdx = 0;
                f.memberCount = 1;
            } else {
                f.memberIdx = seen.merge(d.group, 1, Integer::sum) - 1;
                f.memberCount = count.get(d.group);
            }
        }
    }

    /** All points in the room → the scene; looks for every device and group. */
    private void layout(int flow) {
        List<float[]> pts = new ArrayList<>();
        lampOff = new int[lamps.size()];
        for (int k = 0; k < lamps.size(); k++) {
            lampOff[k] = pts.size();
            for (float[] p : lamps.get(k).points()) pts.add(p);
        }
        stripOff = new int[strips.size()];
        for (int k = 0; k < strips.size(); k++) {
            stripOff[k] = pts.size();
            float[][] sp = strips.get(k).points();
            for (int i = 0; i < sp[0].length; i++) pts.add(new float[]{sp[0][i], sp[1][i], 0f});
        }
        dmxOff = new int[dmxCfg.size()];
        for (int k = 0; k < dmxCfg.size(); k++) {
            dmxOff[k] = pts.size();
            DmxLight d = dmxCfg.get(k);
            pts.add(new float[]{d.x, d.y, d.z});
        }
        int n = pts.size();
        float[] x = new float[n], y = new float[n], z = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = pts.get(i)[0];
            y[i] = pts.get(i)[1];
            z[i] = pts.get(i)[2];
        }
        scene = new Scene(x, y, z, flow);
        // every lamp, strip and DMX light is one device (a strip has many points, a lamp one or a few)
        int nd = lamps.size() + strips.size() + dmxCfg.size();
        int[] ds = new int[nd], dl = new int[nd];
        int dq = 0;
        for (int k = 0; k < lamps.size(); k++, dq++) {
            ds[dq] = lampOff[k];
            dl[dq] = lamps.get(k).points().length;
        }
        for (int k = 0; k < strips.size(); k++, dq++) {
            ds[dq] = stripOff[k];
            dl[dq] = strips.get(k).segments;
        }
        for (int k = 0; k < dmxCfg.size(); k++, dq++) {
            ds[dq] = dmxOff[k];
            dl[dq] = 1;
        }
        devs = new app.huebeat.core.Devices(ds, dl, n);
        scene.setDevices(devs);
        lookIdx.clear();

        // Hue lamps: one look per lamp, or one per group over all its members
        lampLook = new Look[lamps.size()];
        lampInLook = new int[lamps.size()];
        Map<String, List<Integer>> hueGroups = new LinkedHashMap<>();
        for (int k = 0; k < lamps.size(); k++) {
            String g = lamps.get(k).group;
            if (g.isEmpty()) hueGroups.put("\u0000" + k, java.util.Collections.singletonList(k));
            else hueGroups.computeIfAbsent(g, q -> new ArrayList<>()).add(k);
        }
        for (List<Integer> members : hueGroups.values()) {
            List<Integer> idx = new ArrayList<>();
            for (int k : members) {
                lampInLook[k] = idx.size();
                for (int s = 0; s < lamps.get(k).segments; s++) idx.add(lampOff[k] + s);
            }
            Look look = newLook(idx, flow);
            HueLamp lead = lamps.get(members.get(0));
            look.effect = lead.effect;
            look.palette = lead.palette;
            look.color = lead.color;
            look.ambientEffect = lead.ambientEffect;
            look.ambientColor = lead.ambientColor;
            look.together = lead.together;
            for (int k : members) lampLook[k] = look;
        }

        // DMX lights: likewise
        dmxLook = new Look[dmxCfg.size()];
        dmxInLook = new int[dmxCfg.size()];
        Map<String, List<Integer>> dmxGroups = new LinkedHashMap<>();
        for (int k = 0; k < dmxCfg.size(); k++) {
            String g = dmxCfg.get(k).group;
            if (g.isEmpty()) dmxGroups.put("\u0000" + k, java.util.Collections.singletonList(k));
            else dmxGroups.computeIfAbsent(g, q -> new ArrayList<>()).add(k);
        }
        for (List<Integer> members : dmxGroups.values()) {
            List<Integer> idx = new ArrayList<>();
            for (int k : members) {
                dmxInLook[k] = idx.size();
                idx.add(dmxOff[k]);
            }
            Look look = newLook(idx, flow);
            DmxLight lead = dmxCfg.get(members.get(0));
            look.effect = lead.effect;
            look.palette = lead.palette;
            look.color = lead.color;
            look.ambientEffect = lead.ambientEffect;
            look.ambientColor = lead.ambientColor;
            for (int k : members) dmxLook[k] = look;
        }
        dmxRgb = new float[3 * dmxCfg.size()];
    }

    private Look newLook(List<Integer> idx, int flow) {
        int m = idx.size();
        float[] u = new float[m], px = new float[m], py = new float[m];
        int[] ix = new int[m];
        for (int i = 0; i < m; i++) {
            ix[i] = idx.get(i);
            u[i] = scene.u[ix[i]];
            px[i] = scene.x[ix[i]];
            py[i] = scene.y[ix[i]];
        }
        Look l = new Look(u, px, py);
        java.util.Set<Integer> own = new java.util.HashSet<>();
        for (int i : ix) if (devs != null && i < devs.of.length) own.add(devs.of[i]);
        if (devs != null && devs.count() > 0) l.share = Math.min(1f, (float) Math.max(1, own.size()) / devs.count());
        lookIdx.put(l, ix);
        return l;
    }

    // ------------------------------------------------------------------ frames

    /** Music frame (calColor != null: every light shows that color, for the camera calibration). */
    void music(Features f, double t, float dt, LightSettings use, float[] calColor) {
        if (isEmpty() || scene == null) return; // only the phone plays along
        boolean beat = f != null && f.beatCount != lastBeat && lastBeat != Integer.MIN_VALUE;
        if (f != null) lastBeat = f.beatCount;
        frameCache.clear();
        float[] all;
        if (calColor != null) {
            all = new float[3 * scene.n];
            for (int i = 0; i < scene.n; i++) System.arraycopy(calColor, 0, all, 3 * i, 3);
        } else {
            all = scene.render(f, t, dt, use);
        }
        final float[] sceneRgb = all;
        // Hue lamps
        for (int c = 0; c < channelIds.length; c++) {
            int k = chLamp[c];
            if (k < 0) {
                hueRgb[3 * c] = hueRgb[3 * c + 1] = hueRgb[3 * c + 2] = 0f;
                continue;
            }
            HueLamp h = lamps.get(k);
            float[] col = calColor != null ? null : lookColors(lampLook[k], sceneRgb, f, t, dt, use, beat, false, null);
            int p = lampInLook[k] + chSeg[c];
            float b = calColor != null ? 1f : h.brightness;
            for (int q = 0; q < 3; q++) {
                hueRgb[3 * c + q] = (col == null ? sceneRgb[3 * (lampOff[k] + chSeg[c]) + q] : col[3 * p + q]) * b;
            }
        }
        // strips: the global look at their segments
        for (int k = 0; k < strips.size(); k++) {
            int o = stripOff[k], s = strips.get(k).segments;
            strips.get(k).setGlobal(java.util.Arrays.copyOfRange(sceneRgb, 3 * o, 3 * (o + s)));
        }
        // DMX
        for (int k = 0; k < dmxCfg.size(); k++) {
            float[] col = calColor != null ? null : lookColors(dmxLook[k], sceneRgb, f, t, dt, use, beat, false, null);
            int p = dmxInLook[k];
            for (int q = 0; q < 3; q++) dmxRgb[3 * k + q] = col == null ? sceneRgb[3 * dmxOff[k] + q] : col[3 * p + q];
        }
    }

    /** Just-lights frame. */
    void ambient(float dt, Ambient.Settings as) {
        if (isEmpty() || scene == null) return;
        frameCache.clear();
        // devices with their own static color paint the room around them
        List<float[]> field = new ArrayList<>();
        for (int k = 0; k < lamps.size(); k++) {
            HueLamp h = lamps.get(k);
            if (h.ambientColor >= 0) field.add(new float[]{(h.x + h.x2) / 2f, (h.y + h.y2) / 2f, h.ambientColor});
        }
        for (DmxLight d : dmxCfg) if (d.ambientColor >= 0) field.add(new float[]{d.x, d.y, d.ambientColor});
        for (StripOutput so : strips) {
            if (so.ambientColor >= 0) field.add(new float[]{(so.x1 + so.x2) / 2f, (so.y1 + so.y2) / 2f, so.ambientColor});
        }
        if (field.isEmpty()) {
            LampColors.clear();
        } else {
            float[] fx = new float[field.size()], fy = new float[field.size()];
            int[] fc = new int[field.size()];
            for (int i = 0; i < fc.length; i++) {
                fx[i] = field.get(i)[0];
                fy[i] = field.get(i)[1];
                fc[i] = (int) field.get(i)[2];
            }
            LampColors.set(fx, fy, fc);
        }
        final float[] sceneRgb = scene.renderAmbient(dt, as);
        for (int c = 0; c < channelIds.length; c++) {
            int k = chLamp[c];
            if (k < 0) {
                hueRgb[3 * c] = hueRgb[3 * c + 1] = hueRgb[3 * c + 2] = 0f;
                continue;
            }
            float[] col = lookColors(lampLook[k], sceneRgb, null, 0, dt, null, false, true, as);
            int p = lampInLook[k] + chSeg[c];
            float b = lamps.get(k).brightness;
            for (int q = 0; q < 3; q++) hueRgb[3 * c + q] = col[3 * p + q] * b;
        }
        for (int k = 0; k < strips.size(); k++) {
            int o = stripOff[k], s = strips.get(k).segments;
            strips.get(k).setGlobal(java.util.Arrays.copyOfRange(sceneRgb, 3 * o, 3 * (o + s)));
        }
        for (int k = 0; k < dmxCfg.size(); k++) {
            float[] col = lookColors(dmxLook[k], sceneRgb, null, 0, dt, null, false, true, as);
            int p = dmxInLook[k];
            System.arraycopy(col, 3 * p, dmxRgb, 3 * k, 3);
        }
    }

    /** A look's colors for this frame (computed once per frame, even when shared by a group). */
    private float[] lookColors(Look look, float[] sceneRgb, Features f, double t, float dt, LightSettings use,
                               boolean beat, boolean ambient, Ambient.Settings as) {
        float[] c = frameCache.get(look);
        if (c != null) return c;
        int[] idx = lookIdx.get(look);
        c = ambient ? look.ambient(sceneRgb, idx, dt, as) : look.music(sceneRgb, idx, f, t, dt, use, beat);
        frameCache.put(look, c);
        return c;
    }

    /** Hue channel colors of the last frame (rgb per channel), e.g. for overlays. */
    float[] hueColors() {
        return hueRgb;
    }

    float[] dmxColors() {
        return dmxRgb;
    }

    /** Sends the strips and DMX lights (Hue goes through the DTLS stream). */
    void sendLocal(DatagramSocket sock, float[][] stripFrames) {
        for (int k = 0; k < strips.size(); k++) {
            try {
                strips.get(k).send(sock, stripFrames[k]);
            } catch (IOException ignored) {
                // a strip that's switched off or out of Wi-Fi range shouldn't stop the rest
            }
        }
        if (!dmx.isEmpty() && Prefs.testLight == null) {
            try {
                dmx.send(sock);
            } catch (IOException ignored) {
            }
        }
    }

    void blackoutDmx(DatagramSocket sock) {
        if (dmx.isEmpty() || sock == null) return;
        dmx.blackout();
        for (int k = 0; k < 3; k++) {
            try {
                dmx.send(sock);
            } catch (IOException ignored) {
            }
        }
    }

    /** Average color of the whole room (for the app's button). */
    float[] meanColor() {
        return scene != null && !isEmpty() ? scene.meanColor() : new float[3];
    }

    /** Per lamp: its average color in the last frame (to keep colors after stopping). */
    float[] lampColor(int k) {
        float[] m = new float[3];
        int cnt = 0;
        for (int c = 0; c < channelIds.length; c++) {
            if (chLamp[c] != k) continue;
            for (int q = 0; q < 3; q++) m[q] += hueRgb[3 * c + q];
            cnt++;
        }
        if (cnt > 0) for (int q = 0; q < 3; q++) m[q] /= cnt;
        return m;
    }

    /** Screen / camera positions of all points: across = x, up = z. */
    float[] screenX() {
        return scene.x;
    }

    float[] screenZ() {
        return scene.z;
    }

    static float flowU(int flow, float x, float y) {
        return Room.u(flow, x, y);
    }
}
