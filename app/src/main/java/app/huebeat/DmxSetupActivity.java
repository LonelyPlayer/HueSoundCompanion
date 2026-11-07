package app.huebeat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.huebeat.core.ArtNet;
import app.huebeat.core.Dmx;

/** Add or edit a DMX light: find the Art-Net node, set its universes, pick the light model, test channels. */
public class DmxSetupActivity extends Activity {
    static final String EXTRA_INDEX = "dmxIndex";
    private static final String[] PROTOCOLS = {"Art-Net", "sACN (E1.31)"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private int index = -1;
    private DmxLight light;
    private volatile boolean alive = true;

    private EditText name, ip, universe, address;
    private TextView presetBtn, presetNote, channelsInfo, searchState;
    private LinearLayout nodes, channelList;
    private final List<TextView> protoChips = new ArrayList<>();

    // channel tester
    private volatile int[] testValues;
    private volatile boolean testing = false;
    private Thread tester;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        List<DmxLight> all = prefs.dmxLights();
        light = index >= 0 && index < all.size() ? all.get(index) : new DmxLight();
        if (index >= all.size()) index = -1;
        buildUi();
        if (index < 0) search();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        stopTester();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        int pad = Ui.dp(this, 20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.text(this, index < 0 ? "Add DMX light" : "DMX light", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "DMX lights connected to an Art-Net or sACN node (e.g. Showtec NET-2/3 Pocket). "
                + "The phone sends to the node over Wi-Fi; the node drives the DMX cable.", 14, Ui.SUB, false);
        sub.setLineSpacing(0, 1.15f);
        root.addView(sub, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 6)));

        // nodes
        root.addView(label("Art-Net nodes"));
        LinearLayout searchRow = Ui.row(this);
        searchState = Ui.text(this, "", 14, Ui.SUB, false);
        searchRow.addView(searchState, Ui.lp(0, Ui.WRAP, 1));
        TextView again = Ui.button(this, "Search", false);
        again.setOnClickListener(v -> search());
        searchRow.addView(again);
        root.addView(searchRow);
        nodes = Ui.column(this);
        root.addView(nodes);
        TextView netHint = Ui.text(this, "A new node usually sits at 2.0.0.1, which your phone can't reach on home Wi-Fi. "
                + "Give it an address in your network first (node menu › IP settings, e.g. 192.168.1.50, mask 255.255.255.0).",
                13, Ui.SUB, false);
        netHint.setLineSpacing(0, 1.1f);
        root.addView(netHint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));

        // light
        root.addView(label("Name"));
        name = field(light.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(name);

        root.addView(label("Light model"));
        presetBtn = Ui.text(this, "", 15, Ui.TEXT, true);
        presetBtn.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        Ui.clickable(presetBtn, Ui.CARD_2, Ui.dp(this, 14));
        presetBtn.setOnClickListener(v -> {
            String[] names = new String[Dmx.PRESETS.length];
            String[] cats = new String[Dmx.PRESETS.length];
            for (int i = 0; i < names.length; i++) {
                names[i] = Dmx.PRESETS[i].name;
                cats[i] = i < 5 ? "Your lights" : "Generic";
            }
            Pickers.effect(this, "Light model", names, cats, light.preset, k -> {
                light.preset = k;
                if (k != Dmx.CUSTOM) light.roles = Dmx.PRESETS[k].roles.clone();
                if (name.getText().toString().trim().isEmpty() || name.getText().toString().equals("DMX light")) {
                    name.setText(Dmx.PRESETS[k].name.replaceAll(" \\(.*\\)", ""));
                }
                refreshChannels();
            });
        });
        root.addView(presetBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
        presetNote = Ui.text(this, "", 13, Ui.SUB, false);
        root.addView(presetNote, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));

        root.addView(label("Node IP address"));
        ip = field(light.ip, InputType.TYPE_CLASS_PHONE);
        ip.setHint("192.168.1.50");
        root.addView(ip);

        root.addView(label("Protocol"));
        LinearLayout protos = Ui.row(this);
        for (int i = 0; i < PROTOCOLS.length; i++) {
            final int p = i;
            TextView chip = Ui.chip(this, PROTOCOLS[i]);
            chip.setOnClickListener(v -> {
                light.protocol = p;
                refreshProto();
            });
            protoChips.add(chip);
            protos.addView(chip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        }
        root.addView(protos);

        LinearLayout addrRow = Ui.row(this);
        LinearLayout uCol = Ui.column(this), aCol = Ui.column(this);
        uCol.addView(label("Universe"));
        universe = field(String.valueOf(light.universe), InputType.TYPE_CLASS_NUMBER);
        uCol.addView(universe);
        aCol.addView(label("DMX address"));
        address = field(String.valueOf(light.address), InputType.TYPE_CLASS_NUMBER);
        aCol.addView(address);
        addrRow.addView(uCol, Ui.margins(Ui.lp(0, Ui.WRAP, 1), 0, 0, Ui.dp(this, 8), 0));
        addrRow.addView(aCol, Ui.margins(Ui.lp(0, Ui.WRAP, 1), Ui.dp(this, 8), 0, 0, 0));
        root.addView(addrRow);
        channelsInfo = Ui.text(this, "", 13, Ui.SUB, false);
        root.addView(channelsInfo, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));
        address.setOnFocusChangeListener((v, f) -> refreshChannels());

        root.addView(label("Place in the room (left … right)"));
        root.addView(Ui.slider(this, (light.x + 1f) / 2f, v -> light.x = v * 2f - 1f));

        // channels + tester
        root.addView(label("Channels"));
        TextView ch = Ui.text(this, "Tap a channel to change what it does. Turn on the tester to move each channel "
                + "by hand and see what the light does (the show pauses for DMX while testing).", 13, Ui.SUB, false);
        ch.setLineSpacing(0, 1.1f);
        root.addView(ch, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 6)));
        LinearLayout testRow = Ui.row(this);
        testRow.addView(Ui.text(this, "Channel tester", 15, Ui.TEXT, false), Ui.lp(0, Ui.WRAP, 1));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setOnCheckedChangeListener((bv, on) -> {
            if (on) startTester();
            else stopTester();
            refreshChannels();
        });
        testRow.addView(sw);
        root.addView(testRow);
        TextView lightUp = Ui.chip(this, "Light up (safe test values)");
        lightUp.setTextColor(Ui.ACCENT);
        lightUp.setOnClickListener(v -> {
            int[] tv = new int[light.roles.length];
            for (int i = 0; i < tv.length; i++) tv[i] = safeValue(light.roles[i]);
            testValues = tv;
            if (!sw.isChecked()) sw.setChecked(true); // starts the tester
            else refreshChannels();
        });
        root.addView(lightUp, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(this, 6), 0, Ui.dp(this, 6)));
        channelList = Ui.column(this);
        root.addView(channelList);

        LinearLayout actions = Ui.row(this);
        TextView save = Ui.button(this, "Save", true);
        save.setOnClickListener(v -> save());
        actions.addView(save, Ui.lp(0, Ui.WRAP, 1));
        root.addView(actions, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 26), 0, 0));

        if (index >= 0) {
            TextView del = Ui.text(this, "Remove light", 15, Ui.ERROR, true);
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            del.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Remove \"" + light.name + "\"?")
                    .setPositiveButton("Remove", (d, w) -> {
                        List<DmxLight> all = prefs.dmxLights();
                        if (index < all.size()) all.remove(index);
                        prefs.saveDmx(all);
                        setResult(RESULT_OK);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            root.addView(del, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
        }

        refreshProto();
        refreshChannels();
        Ui.setupWindow(this, scroll);
    }

    private void refreshProto() {
        for (int i = 0; i < protoChips.size(); i++) Ui.setChipSelected(protoChips.get(i), i == light.protocol);
    }

    private int parse(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void refreshChannels() {
        presetBtn.setText(Dmx.PRESETS[light.preset].name + "  ▸");
        String note = Dmx.PRESETS[light.preset].note;
        presetNote.setText(note);
        presetNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        int a = parse(address, light.address, 1, 512);
        int last = a + light.roles.length - 1;
        channelsInfo.setText(light.roles.length + " channels: " + a + "–" + last
                + (last > 512 ? "  (past channel 512! Lower the address)" : ""));
        channelList.removeAllViews();
        if (testValues == null || testValues.length != light.roles.length) testValues = new int[light.roles.length];
        for (int i = 0; i < light.roles.length; i++) {
            final int ci = i;
            LinearLayout row = Ui.column(this);
            TextView t = Ui.text(this, "Ch " + (i + 1) + " (" + (a + i) + ")  ·  " + Dmx.ROLE_NAMES[light.roles[i]] + "  ▸", 15, Ui.TEXT, false);
            t.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            Ui.clickable(t, Ui.CARD, Ui.dp(this, 12));
            t.setOnClickListener(v -> Pickers.effect(this, "Channel " + (ci + 1), Dmx.ROLE_NAMES, null, light.roles[ci], r -> {
                light.roles[ci] = r;
                light.preset = matchingPreset(light.roles);
                refreshChannels();
            }));
            row.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP));
            if (testing) {
                SeekBar sb = new SeekBar(this);
                sb.setMax(255);
                sb.setProgress(testValues[i]);
                sb.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
                sb.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
                TextView val = Ui.text(this, String.valueOf(testValues[i]), 13, Ui.SUB, false);
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar s, int p, boolean u) {
                        int[] tv = testValues.clone();
                        tv[ci] = p;
                        testValues = tv;
                        val.setText(String.valueOf(p));
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar s) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar s) {
                    }
                });
                LinearLayout sr = Ui.row(this);
                sr.addView(sb, Ui.lp(0, Ui.WRAP, 1));
                sr.addView(val, Ui.lp(Ui.dp(this, 40), Ui.WRAP));
                row.addView(sr);
            }
            channelList.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 6)));
        }
        LinearLayout plusMinus = Ui.row(this);
        TextView add = Ui.chip(this, "+ Channel");
        add.setOnClickListener(v -> {
            if (light.roles.length >= 64) return;
            int[] r = java.util.Arrays.copyOf(light.roles, light.roles.length + 1);
            light.roles = r;
            light.preset = matchingPreset(r);
            refreshChannels();
        });
        TextView rem = Ui.chip(this, "− Channel");
        rem.setOnClickListener(v -> {
            if (light.roles.length <= 1) return;
            light.roles = java.util.Arrays.copyOf(light.roles, light.roles.length - 1);
            light.preset = matchingPreset(light.roles);
            refreshChannels();
        });
        plusMinus.addView(add, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        plusMinus.addView(rem);
        channelList.addView(plusMinus, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 4), 0, 0));
    }

    /** A value per role that makes the light visibly shine: full dimmer and colors, open shutter, head centered. */
    private static int safeValue(int role) {
        switch (role) {
            case Dmx.FULL: case Dmx.DIMMER: case Dmx.RED: case Dmx.GREEN: case Dmx.BLUE: case Dmx.WHITE: case Dmx.UV:
                return 255;
            case Dmx.PAN: case Dmx.TILT:
                return 128;
            case Dmx.WHEEL:
                return 5; // white
            default:
                return 0; // strobe off / shutter open, speed fast, gobo open, programs off
        }
    }

    private static int matchingPreset(int[] roles) {
        for (int i = 0; i < Dmx.CUSTOM; i++) if (java.util.Arrays.equals(Dmx.PRESETS[i].roles, roles)) return i;
        return Dmx.CUSTOM;
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), 12, Ui.SUB, true);
        t.setLetterSpacing(0.08f);
        t.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 18), 0, Ui.dp(this, 6)));
        return t;
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setInputType(type);
        e.setTextColor(Ui.TEXT);
        e.setHintTextColor(0xFF5C5D66);
        e.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        return e;
    }

    private void save() {
        light.name = name.getText().toString().trim().isEmpty() ? "DMX light" : name.getText().toString().trim();
        light.ip = ip.getText().toString().trim();
        light.universe = parse(universe, light.universe, 0, 32767);
        light.address = parse(address, light.address, 1, 512);
        if (light.ip.isEmpty()) {
            ip.setError("Enter the node's IP address");
            ip.requestFocus();
            android.widget.Toast.makeText(this, "Pick a node above or enter its IP address", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        List<DmxLight> all = prefs.dmxLights();
        if (index >= 0 && index < all.size()) all.set(index, light);
        else all.add(light);
        prefs.saveDmx(all);
        setResult(RESULT_OK);
        finish();
    }

    // ------------------------------------------------------------------ node search and setup

    private void search() {
        searchState.setText("Searching…");
        nodes.removeAllViews();
        final String typed = ip.getText().toString().trim();
        new Thread(() -> {
            List<ArtNet.Node> found;
            String error = null;
            WifiManager.MulticastLock lock = null;
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    lock = wm.createMulticastLock("huebeat-artnet");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
            } catch (Exception ignored) {
            }
            try {
                List<InetAddress> targets = new ArrayList<>();
                targets.add(InetAddress.getByName("255.255.255.255"));
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getAddress() instanceof Inet4Address && ia.getBroadcast() != null) targets.add(ia.getBroadcast());
                    }
                }
                targets.add(InetAddress.getByName("2.255.255.255"));
                if (!typed.isEmpty()) {
                    try {
                        targets.add(InetAddress.getByName(typed));
                    } catch (Exception ignored) {
                    }
                }
                found = ArtNet.discover(targets, 2500);
            } catch (Exception e) {
                found = new ArrayList<>();
                error = e.getMessage();
            } finally {
                if (lock != null) try {
                    lock.release();
                } catch (Exception ignored) {
                }
            }
            final List<ArtNet.Node> res = found;
            final String err = error;
            main.post(() -> {
                if (!alive) return;
                if (res.isEmpty()) {
                    searchState.setText(err != null ? "Search failed: " + err
                            : "No node answered. Check that it's in your network, or type its IP below and search again.");
                    return;
                }
                searchState.setText(res.size() == 1 ? "1 node found" : res.size() + " nodes found");
                boolean picked = false;
                for (ArtNet.Node n : res) {
                    View card = nodeCard(n);
                    nodes.addView(card, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
                    // nothing entered yet: take the first node with an output port right away
                    if (!picked && ip.getText().toString().trim().isEmpty() && hasOut(n)) {
                        card.performClick();
                        picked = true;
                    } else if (n.ip.equals(ip.getText().toString().trim())) {
                        markUsing(card);
                    }
                }
            });
        }, "artnet-search").start();
    }

    private static boolean hasOut(ArtNet.Node n) {
        for (int i = 0; i < n.numPorts; i++) if (n.canOut[i]) return true;
        return false;
    }

    private void markUsing(View card) {
        Object tag = card.getTag();
        if (tag instanceof TextView) ((TextView) tag).setText("✓ Using this node");
    }

    private View nodeCard(ArtNet.Node n) {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        Ui.clickable(card, Ui.CARD, Ui.dp(this, 14));
        card.addView(Ui.text(this, n.label(), 15, Ui.TEXT, true));
        StringBuilder ports = new StringBuilder();
        final List<Integer> outPorts = new ArrayList<>();
        for (int i = 0; i < n.numPorts; i++) {
            if (ports.length() > 0) ports.append('\n');
            String dir = n.canOut[i] ? "DMX out" : n.canIn[i] ? "DMX in" : "port";
            ports.append("Port ").append(n.portLetter(i)).append(" · ").append(dir)
                    .append(" · universe ").append(n.canOut[i] ? n.outAddress(i) : ((n.net << 8) | (n.subNet << 4) | n.swIn[i]));
            if (n.canOut[i]) {
                ports.append(n.sending[i] ? " · sending DMX now" : " · idle");
                if (n.sacn[i]) ports.append(" · set to sACN");
            }
            if (n.canOut[i]) outPorts.add(i);
        }
        if (ports.length() == 0) ports.append("No port information");
        card.addView(Ui.text(this, ports.toString(), 13, Ui.SUB, false));
        TextView hint = Ui.text(this, "Tap to use this node", 12, Ui.ACCENT, true);
        card.addView(hint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));
        card.setTag(hint);
        card.setOnClickListener(v -> {
            ip.setText(n.ip);
            ip.setError(null);
            light.protocol = !outPorts.isEmpty() && n.sacn[outPorts.get(0)] ? 1 : 0;
            refreshProto();
            if (!outPorts.isEmpty()) universe.setText(String.valueOf(n.outAddress(outPorts.get(0))));
            for (int k = 0; k < nodes.getChildCount(); k++) {
                Object t = nodes.getChildAt(k).getTag();
                if (t instanceof TextView) ((TextView) t).setText("Tap to use this node");
            }
            hint.setText("✓ Using this node · port " + (outPorts.isEmpty() ? "?" : String.valueOf(n.portLetter(outPorts.get(0)))));
        });

        LinearLayout actions = Ui.row(this);
        TextView setU = Ui.chip(this, "Set universes");
        setU.setOnClickListener(v -> setUniverses(n, outPorts));
        TextView web = Ui.chip(this, "Open settings page");
        web.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://" + n.ip + "/")));
            } catch (Exception ignored) {
            }
        });
        actions.addView(setU, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        actions.addView(web);
        card.addView(actions, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
        return card;
    }

    /** ArtAddress: new universe per output port, then a fresh poll shows whether the node took it. */
    private void setUniverses(ArtNet.Node n, List<Integer> outPorts) {
        if (outPorts.isEmpty()) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("This node reports no DMX output ports here. Set the port direction to output in its menu or settings page.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        List<EditText> fields = new ArrayList<>();
        for (int port : outPorts) {
            box.addView(label("Port " + n.portLetter(port) + " universe"));
            EditText e = field(String.valueOf(n.outAddress(port)), InputType.TYPE_CLASS_NUMBER);
            box.addView(e);
            fields.add(e);
        }
        TextView note = Ui.text(this, "Ports of one node share the same net and sub-net: universes 0–15 work together, "
                + "e.g. 0 and 1.", 13, Ui.SUB, false);
        box.addView(note, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(n.longName.isEmpty() ? "Node universes" : n.longName)
                .setView(box)
                .setPositiveButton("Send to node", (d, w) -> {
                    int[] want = new int[outPorts.size()];
                    for (int k = 0; k < want.length; k++) want[k] = parse(fields.get(k), n.outAddress(outPorts.get(k)), 0, 32767);
                    sendUniverses(n, outPorts, want);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendUniverses(ArtNet.Node n, List<Integer> outPorts, int[] want) {
        searchState.setText("Sending to " + n.ip + "…");
        new Thread(() -> {
            String result;
            try {
                for (int k = 0; k < want.length; k++) {
                    ArtNet.sendAddress(n.ip, n.bindIndex, outPorts.get(k), want[k], null);
                    Thread.sleep(150);
                }
                Thread.sleep(600);
                List<ArtNet.Node> again = ArtNet.discover(java.util.Collections.singletonList(InetAddress.getByName(n.ip)), 1500);
                ArtNet.Node now = null;
                for (ArtNet.Node o : again) if (o.bindIndex == n.bindIndex) now = o;
                if (now == null) {
                    result = "Sent, but the node didn't answer afterwards. Search again to check.";
                } else {
                    boolean ok = true;
                    for (int k = 0; k < want.length; k++) ok &= now.outAddress(outPorts.get(k)) == want[k];
                    result = ok ? "The node took the new universes."
                            : "The node didn't change its universes. It probably only accepts them in its own menu or on its settings page.";
                }
            } catch (Exception e) {
                result = "Couldn't send: " + e.getMessage();
            }
            final String r = result;
            main.post(() -> {
                if (!alive) return;
                searchState.setText(r);
                search();
                main.postDelayed(() -> {
                    if (alive) searchState.setText(r);
                }, 2800);
            });
        }, "artnet-address").start();
    }

    // ------------------------------------------------------------------ channel tester

    private void startTester() {
        testing = true;
        if (testValues == null || testValues.length != light.roles.length) testValues = new int[light.roles.length];
        Prefs.testLight = "testing"; // pauses the show's DMX output while the tester runs
        tester = new Thread(() -> {
            byte[] dmx = new byte[512];
            int seq = 1;
            try (DatagramSocket s = new DatagramSocket()) {
                while (testing && alive) {
                    String host = ip.getText().toString().trim();
                    int a = parse(address, light.address, 1, 512), u = parse(universe, light.universe, 0, 32767);
                    java.util.Arrays.fill(dmx, (byte) 0);
                    int[] tv = testValues;
                    for (int i = 0; i < tv.length; i++) if (a - 1 + i < 512) dmx[a - 1 + i] = (byte) tv[i];
                    if (!host.isEmpty()) {
                        try {
                            InetAddress to = InetAddress.getByName(host);
                            byte[] p = light.protocol == 1 ? ArtNet.sacn(u, seq, dmx, new byte[16]) : ArtNet.dmx(u, seq, dmx, 512);
                            s.send(new DatagramPacket(p, p.length, to, light.protocol == 1 ? 5568 : ArtNet.PORT));
                        } catch (Exception ignored) {
                        }
                    }
                    seq = seq >= 255 ? 1 : seq + 1;
                    Thread.sleep(40);
                }
            } catch (Exception ignored) {
            }
        }, "dmx-tester");
        tester.start();
    }

    private void stopTester() {
        testing = false;
        Prefs.testLight = null;
        if (tester != null) tester.interrupt();
        tester = null;
    }
}
