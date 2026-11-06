package app.huebeat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.huebeat.core.Palettes;
import app.huebeat.core.StripEffect;
import app.huebeat.core.StripOutput;
import app.huebeat.core.StripProtocol;

/** Add or edit an LED strip: WLED devices are found automatically, others by IP address. */
public class StripSetupActivity extends Activity {
    static final String EXTRA_INDEX = "stripIndex";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private int index = -1;
    private Strip strip;
    private volatile boolean alive = true;

    private EditText name, ip, leds, universe;
    private TextView lookupState;
    private LinearLayout found, universeRow;
    private final List<TextView> protoChips = new ArrayList<>();

    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private final Set<String> seen = new HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        List<Strip> all = prefs.strips();
        strip = index >= 0 && index < all.size() ? all.get(index) : new Strip();
        if (index >= all.size()) index = -1;
        buildUi();
        if (index < 0) startDiscovery();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        stopDiscovery();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        int pad = Ui.dp(this, 20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.text(this, index < 0 ? "Add LED strip" : "Strip connection", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "For ESP32/ESP8266 controllers with WLED, ESPixelStick or anything else that "
                + "speaks DDP or E1.31, the same protocols LedFx uses. The phone sends directly; no server needed.", 14, Ui.SUB, false);
        sub.setLineSpacing(0, 1.15f);
        root.addView(sub, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 6)));

        if (index < 0) {
            root.addView(label("Found on your Wi-Fi"));
            found = Ui.column(this);
            TextView searching = Ui.text(this, "Searching for WLED devices…", 14, Ui.SUB, false);
            found.addView(searching);
            root.addView(found);
        }

        root.addView(label("Name"));
        name = field(strip.name, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(name);

        root.addView(label("IP address"));
        LinearLayout ipRow = Ui.row(this);
        ip = field(strip.ip, InputType.TYPE_CLASS_PHONE);
        ip.setHint("192.168.1.50");
        ipRow.addView(ip, Ui.lp(0, Ui.WRAP, 1));
        TextView lookup = Ui.button(this, "Read from WLED", false);
        lookup.setOnClickListener(v -> lookup(ip.getText().toString().trim(), null));
        ipRow.addView(lookup, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(this, 10), 0, 0, 0));
        root.addView(ipRow);
        lookupState = Ui.text(this, "", 13, Ui.SUB, false);
        root.addView(lookupState);

        root.addView(label("Number of LEDs"));
        leds = field(String.valueOf(strip.leds), InputType.TYPE_CLASS_NUMBER);
        root.addView(leds);

        root.addView(label("Protocol"));
        LinearLayout protos = Ui.row(this);
        for (int i = 0; i < StripProtocol.NAMES.length; i++) {
            final int p = i;
            TextView chip = Ui.chip(this, StripProtocol.NAMES[i]);
            chip.setOnClickListener(v -> {
                strip.protocol = p;
                refreshProto();
            });
            protoChips.add(chip);
            protos.addView(chip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        }
        root.addView(protos);
        TextView protoHint = Ui.text(this, "DDP works with WLED and most ESP firmwares (LedFx's default). Use E1.31 for sACN setups.", 13, Ui.SUB, false);
        root.addView(protoHint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));

        universeRow = Ui.column(this);
        universeRow.addView(label("Start universe"));
        universe = field(String.valueOf(strip.universe), InputType.TYPE_CLASS_NUMBER);
        universeRow.addView(universe);
        root.addView(universeRow);

        root.addView(label("Layout"));
        root.addView(toggle("Mirror from the center", strip.mirror, (bv, on) -> strip.mirror = on));
        root.addView(toggle("Reverse direction", strip.reverse, (bv, on) -> strip.reverse = on));


        LinearLayout actions = Ui.row(this);
        TextView test = Ui.button(this, "Test", false);
        test.setOnClickListener(v -> test());
        actions.addView(test, Ui.margins(Ui.lp(0, Ui.WRAP, 1), 0, 0, Ui.dp(this, 6), 0));
        TextView save = Ui.button(this, "Save", true);
        save.setOnClickListener(v -> save());
        actions.addView(save, Ui.margins(Ui.lp(0, Ui.WRAP, 1), Ui.dp(this, 6), 0, 0, 0));
        root.addView(actions, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 26), 0, 0));

        if (index >= 0) {
            TextView del = Ui.text(this, "Remove strip", 15, Ui.ERROR, true);
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            del.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Remove \"" + strip.name + "\"?")
                    .setPositiveButton("Remove", (d, w) -> {
                        List<Strip> all = prefs.strips();
                        if (index < all.size()) all.remove(index);
                        prefs.saveStrips(all);
                        setResult(RESULT_OK);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            root.addView(del, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
        }

        refreshProto();
        Ui.setupWindow(this, scroll);
    }

    private static final int[] STROBE_COLORS = {0xFFFFFF, 0xFF0000, 0x0040FF, 0x7A00FF, 0xFFA000, 0x00FF40, 0xFF00C8, 0x00E5FF};
    private static final String[] STROBE_COLOR_NAMES = {"White", "Red", "Blue", "UV", "Amber", "Green", "Pink", "Cyan"};
    private LinearLayout strobeBox;

    private static int colorIndex(int c) {
        for (int i = 0; i < STROBE_COLORS.length; i++) if (STROBE_COLORS[i] == c) return i;
        return 0;
    }

    private interface IntGet {
        int get();
    }

    private interface IntSet {
        void set(int v);
    }

    private interface FloatSet {
        void set(float v);
    }

    /** A horizontally scrolling row of single-choice chips. */
    private View chipRow(String[] names, IntGet get, IntSet set) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(this);
        List<TextView> chips = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            final int v = i;
            TextView chip = Ui.chip(this, names[i]);
            chip.setOnClickListener(x -> {
                set.set(v);
                for (int k = 0; k < chips.size(); k++) Ui.setChipSelected(chips.get(k), k == get.get());
            });
            chips.add(chip);
            row.addView(chip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        }
        for (int k = 0; k < chips.size(); k++) Ui.setChipSelected(chips.get(k), k == get.get());
        hs.addView(row);
        return hs;
    }

    private SeekBar slider(float value, FloatSet set) {
        SeekBar b = new SeekBar(this);
        b.setMax(100);
        b.setProgress(Math.round(value * 100));
        b.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        b.setThumbTintList(ColorStateList.valueOf(Ui.ACCENT));
        b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u) set.set(p / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        return b;
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

    private View toggle(String text, boolean value, CompoundButton.OnCheckedChangeListener l) {
        LinearLayout r = Ui.row(this);
        r.addView(Ui.text(this, text, 15, Ui.TEXT, false), Ui.lp(0, Ui.WRAP, 1));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(l);
        r.addView(sw);
        r.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        return r;
    }

    private void refreshProto() {
        for (int i = 0; i < protoChips.size(); i++) Ui.setChipSelected(protoChips.get(i), i == strip.protocol);
        universeRow.setVisibility(strip.protocol == StripProtocol.E131 ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ reading the form

    /** @return true if the form is valid; copies it into {@link #strip}. */
    private boolean readForm() {
        String n = name.getText().toString().trim();
        strip.name = n.isEmpty() ? "LED strip" : n;
        strip.ip = ip.getText().toString().trim();
        if (strip.ip.isEmpty()) {
            lookupState.setText("Enter the controller's IP address.");
            return false;
        }
        try {
            strip.leds = Math.max(1, Math.min(4000, Integer.parseInt(leds.getText().toString().trim())));
        } catch (NumberFormatException e) {
            lookupState.setText("Enter the number of LEDs.");
            return false;
        }
        try {
            strip.universe = Math.max(1, Math.min(63999, Integer.parseInt(universe.getText().toString().trim())));
        } catch (NumberFormatException e) {
            strip.universe = 1;
        }
        return true;
    }

    private void save() {
        if (!readForm()) return;
        List<Strip> all = prefs.strips();
        if (index >= 0 && index < all.size()) all.set(index, strip);
        else all.add(strip);
        prefs.saveStrips(all);
        setResult(RESULT_OK);
        finish();
    }

    /** Runs a 3-second color sweep on the strip. */
    private void test() {
        if (!readForm()) return;
        final Strip s = strip;
        lookupState.setText("Sending a test pattern…");
        new Thread(() -> {
            String result;
            try (DatagramSocket sock = new DatagramSocket()) {
                StripOutput out = new StripOutput(s.name, InetAddress.getByName(s.ip), s.protocol, s.leds, s.universe,
                        s.mirror, s.reverse, s.brightness);
                int span = s.mirror ? (s.leds + 1) / 2 : s.leds;
                float[] px = new float[3 * span];
                long start = System.nanoTime();
                while (System.nanoTime() - start < 3_000_000_000L && alive) {
                    float t = (System.nanoTime() - start) / 1e9f;
                    for (int i = 0; i < span; i++) Palettes.hsv(t * 0.5f + (float) i / span, 1f, 1f, px, 3 * i);
                    out.send(sock, px);
                    Thread.sleep(20);
                }
                result = "Test sent. The strip should have shown a moving rainbow (left = start of the strip).";
            } catch (Exception e) {
                result = "Couldn't send: " + e.getMessage();
            }
            final String r = result;
            main.post(() -> {
                if (alive) lookupState.setText(r);
            });
        }, "huebeat-strip-test").start();
    }

    // ------------------------------------------------------------------ WLED lookup & discovery

    private void lookup(String address, TextView card) {
        if (address.isEmpty()) {
            lookupState.setText("Enter the IP address first.");
            return;
        }
        lookupState.setText("Asking " + address + "…");
        new Thread(() -> {
            try {
                WledApi.Info info = WledApi.info(address);
                main.post(() -> {
                    if (!alive) return;
                    ip.setText(address);
                    name.setText(info.name);
                    leds.setText(String.valueOf(info.leds));
                    strip.wled = true;
                    strip.protocol = StripProtocol.DDP;
                    refreshProto();
                    lookupState.setText("WLED " + info.version + ", " + info.leds + " LEDs. Tap Test or Save.");
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (alive) lookupState.setText("No WLED answer from " + address + ". For other firmwares, enter the LED count and protocol yourself.");
                });
            }
        }, "huebeat-wled").start();
    }

    private void startDiscovery() {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String t, int e) {
                main.post(() -> showFoundMessage("Automatic search isn't available. Enter the IP address below."));
            }

            @Override
            public void onStopDiscoveryFailed(String t, int e) {
            }

            @Override
            public void onDiscoveryStarted(String t) {
            }

            @Override
            public void onDiscoveryStopped(String t) {
            }

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                main.post(() -> {
                    resolveQueue.add(info);
                    resolveNext();
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            nsd.discoverServices("_wled._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
        }
        main.postDelayed(() -> {
            if (alive && seen.isEmpty()) {
                showFoundMessage("No WLED device found yet. If yours runs other firmware, enter its IP address below.");
            }
        }, 8000);
    }

    private void showFoundMessage(String msg) {
        if (found == null || !seen.isEmpty()) return;
        found.removeAllViews();
        found.addView(Ui.text(this, msg, 14, Ui.SUB, false));
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo info = resolveQueue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo s, int e) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo s) {
                    InetAddress host = s.getHost();
                    if (host != null && host.getAddress().length == 4) checkFound(host.getHostAddress());
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }
            });
        } catch (Exception e) {
            resolving = false;
        }
    }

    private void checkFound(String address) {
        synchronized (seen) {
            if (!seen.add(address)) return;
        }
        new Thread(() -> {
            try {
                WledApi.Info info = WledApi.info(address);
                main.post(() -> {
                    if (!alive || found == null) return;
                    if (found.getChildCount() == 1 && !(found.getChildAt(0).isClickable())) found.removeAllViews();
                    TextView card = Ui.text(this, info.name + "\n" + address + " · " + info.leds + " LEDs", 16, Ui.TEXT, true);
                    card.setLineSpacing(0, 1.2f);
                    int p = Ui.dp(this, 14);
                    card.setPadding(p, p, p, p);
                    Ui.clickable(card, Ui.CARD, Ui.dp(this, 14));
                    card.setOnClickListener(v -> {
                        ip.setText(address);
                        name.setText(info.name);
                        leds.setText(String.valueOf(info.leds));
                        strip.wled = true;
                        strip.protocol = StripProtocol.DDP;
                        refreshProto();
                        lookupState.setText("WLED " + info.version + ", " + info.leds + " LEDs. Tap Test or Save.");
                    });
                    found.addView(card, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
                });
            } catch (Exception ignored) {
            }
        }, "huebeat-wled").start();
    }

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (Exception ignored) {
            }
        }
        discovery = null;
    }
}
