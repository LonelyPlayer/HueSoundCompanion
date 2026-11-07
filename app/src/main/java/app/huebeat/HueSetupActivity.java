package app.huebeat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hue in one place, like the Art-Net screen: the bridge (search, link button, other bridge) and,
 * below it, the bridge's lamps to add, or the settings of one lamp.
 */
public class HueSetupActivity extends Activity {
    static final String EXTRA_INDEX = "index";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private int index = -1;
    private HueLamp lamp;
    private volatile boolean alive = true;

    // bridges
    private TextView searchState;
    private LinearLayout bridgeList;
    private EditText ipField;
    private final Map<String, BridgeCard> cards = new LinkedHashMap<>();
    private final Set<String> checking = new HashSet<>();
    private volatile String pairingIp;
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;

    // lamps
    private LinearLayout lampsBox;
    private TextView addBtn;
    private final List<HueApi.Lamp> fresh = new ArrayList<>();
    private final List<CheckBox> checks = new ArrayList<>();
    private EditText nameField;

    private static final class BridgeCard {
        HueApi.Bridge bridge;
        LinearLayout view;
        TextView title, detail, status;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        index = getIntent().getIntExtra(EXTRA_INDEX, -1);
        List<HueLamp> all = prefs.hueLamps();
        if (index >= all.size()) index = -1;
        lamp = index >= 0 ? all.get(index) : null;
        buildUi();
        search();
        if (lamp == null) loadLamps();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        pairingIp = null;
        stopDiscovery();
        super.onDestroy();
    }

    private void bg(Runnable r) {
        new Thread(r, "hue-setup").start();
    }

    private void ui(Runnable r) {
        main.post(() -> {
            if (alive && !isFinishing()) r.run();
        });
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        int pad = Ui.dp(this, 20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.text(this, lamp == null ? "Add Hue lamps" : "Hue lamp", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Philips Hue lamps through a Hue Bridge. The phone streams the colors to the bridge "
                + "over Wi-Fi; up to 10 lamps play along at a time.", 14, Ui.SUB, false);
        sub.setLineSpacing(0, 1.15f);
        root.addView(sub, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 6)));

        // bridge
        root.addView(Ui.label(this, "Hue bridge"));
        LinearLayout searchRow = Ui.row(this);
        searchState = Ui.text(this, "", 14, Ui.SUB, false);
        searchRow.addView(searchState, Ui.lp(0, Ui.WRAP, 1));
        TextView again = Ui.button(this, "Search", false);
        again.setOnClickListener(v -> search());
        searchRow.addView(again);
        root.addView(searchRow);
        bridgeList = Ui.column(this);
        root.addView(bridgeList);
        LinearLayout manual = Ui.row(this);
        ipField = new EditText(this);
        ipField.setHint("Bridge IP, e.g. 192.168.1.20");
        ipField.setInputType(InputType.TYPE_CLASS_PHONE);
        ipField.setTextColor(Ui.TEXT);
        ipField.setHintTextColor(0xFF5C5D66);
        ipField.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        manual.addView(ipField, Ui.lp(0, Ui.WRAP, 1));
        TextView go = Ui.button(this, "Connect", false);
        go.setOnClickListener(v -> {
            String s = ipField.getText().toString().trim();
            if (!s.isEmpty()) check(s, true);
        });
        manual.addView(go, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(this, 10), 0, 0, 0));
        root.addView(manual, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        TextView hint = Ui.text(this, "A new bridge: tap it, then press the big round button on top of the bridge. "
                + "The IP is in the Hue app under Settings › My Hue system › Bridge.", 13, Ui.SUB, false);
        hint.setLineSpacing(0, 1.1f);
        root.addView(hint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));

        if (lamp == null) {
            root.addView(Ui.label(this, "Lamps"));
            lampsBox = Ui.column(this);
            root.addView(lampsBox);
            addBtn = Ui.button(this, "Add lamps", true);
            addBtn.setOnClickListener(v -> addChosen());
            root.addView(addBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 22), 0, 0));
        } else {
            root.addView(Ui.label(this, "Name"));
            nameField = new EditText(this);
            nameField.setText(lamp.name);
            nameField.setSingleLine(true);
            nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            nameField.setTextColor(Ui.TEXT);
            nameField.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
            root.addView(nameField);
            root.addView(Ui.text(this, lamp.gradient() ? "Gradient lamp · " + lamp.segments + " segments, each with its own color"
                    : "One color", 13, Ui.SUB, false), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 4), 0, 0));
            TextView blink = Ui.chip(this, "Blink to find it");
            blink.setTextColor(Ui.ACCENT);
            blink.setOnClickListener(v -> blink(lamp.lightId));
            root.addView(blink, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
            TextView place = Ui.text(this, "Its place in the room is set on the room plan.", 13, Ui.SUB, false);
            root.addView(place, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));

            TextView save = Ui.button(this, "Save", true);
            save.setOnClickListener(v -> saveLamp());
            root.addView(save, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 26), 0, 0));
            TextView del = Ui.text(this, "Remove lamp", 15, Ui.ERROR, true);
            del.setGravity(Gravity.CENTER);
            del.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            del.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Remove \"" + lamp.name + "\"?")
                    .setPositiveButton("Remove", (d, w) -> {
                        List<HueLamp> all = prefs.hueLamps();
                        if (index < all.size()) all.remove(index);
                        prefs.saveHueLamps(all);
                        setResult(RESULT_OK);
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            root.addView(del, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
        }
        Ui.setupWindow(this, scroll);
    }

    // ------------------------------------------------------------------ bridges

    private boolean isCurrent(HueApi.Bridge b) {
        if (!prefs.isPaired()) return false;
        if (b.ip.equals(prefs.bridgeIp())) return true;
        return !b.id.isEmpty() && b.id.equalsIgnoreCase(prefs.bridgeId());
    }

    private void search() {
        stopDiscovery();
        bridgeList.removeAllViews();
        cards.clear();
        synchronized (checking) {
            checking.clear();
        }
        searchState.setText("Searching…");
        if (prefs.isPaired()) {
            HueApi.Bridge cur = new HueApi.Bridge(prefs.bridgeIp());
            cur.name = prefs.bridgeName();
            cur.id = prefs.bridgeId();
            showBridge(cur, null);
            check(cur.ip, false); // confirms it's reachable (and learns its id)
        }
        startDiscovery();
        bg(() -> {
            try {
                for (HueApi.Bridge b : HueApi.cloudDiscovery()) check(b.ip, false);
            } catch (IOException ignored) {
                // the local search or a typed IP still work
            }
        });
        main.postDelayed(() -> {
            if (!alive) return;
            int others = 0;
            for (BridgeCard c : cards.values()) if (!isCurrent(c.bridge)) others++;
            if (cards.isEmpty()) searchState.setText("No bridge found yet. Type its IP below.");
            else if (others == 0) searchState.setText(prefs.isPaired() ? "No other bridge found" : "");
            else searchState.setText(cards.size() == 1 ? "1 bridge" : cards.size() + " bridges");
        }, 6000);
    }

    /** Asks an IP whether it's a Hue bridge; {@code manual}: typed in, so connect right away. */
    private void check(String ip, boolean manual) {
        synchronized (checking) {
            if (!manual && checking.contains(ip)) return;
            checking.add(ip);
        }
        bg(() -> {
            try {
                HueApi.Bridge b = HueApi.describe(ip);
                ui(() -> {
                    // the connected bridge moved to a new address: follow it, no new pairing needed
                    if (prefs.isPaired() && !b.ip.equals(prefs.bridgeIp()) && !b.id.isEmpty() && b.id.equalsIgnoreCase(prefs.bridgeId())) {
                        BridgeCard old = cards.remove(prefs.bridgeIp());
                        if (old != null) bridgeList.removeView(old.view);
                        prefs.setBridgeIp(b.ip);
                        setResult(RESULT_OK);
                    }
                    if (isCurrent(b)) {
                        if (!b.id.isEmpty()) prefs.setBridgeId(b.id);
                        if (!b.name.equals(prefs.bridgeName())) {
                            prefs.saveBridge(prefs.bridgeIp(), b.name, prefs.username(), prefs.clientKey());
                        }
                    }
                    BridgeCard c = showBridge(b, null);
                    if (manual) connect(c);
                });
            } catch (IOException e) {
                final String why = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                ui(() -> {
                    if (manual) {
                        Toast.makeText(this, "Couldn't reach " + ip + ": " + why, Toast.LENGTH_LONG).show();
                        return;
                    }
                    BridgeCard c = cards.get(ip);
                    if (c != null && isCurrent(c.bridge)) c.status.setText("✓ Connected · not reachable right now: " + why);
                });
            }
        });
    }

    /** Adds or updates the card of a bridge. */
    private BridgeCard showBridge(HueApi.Bridge b, String status) {
        BridgeCard c = cards.get(b.ip);
        if (c == null) {
            c = new BridgeCard();
            c.view = Ui.column(this);
            c.view.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            Ui.clickable(c.view, Ui.CARD, Ui.dp(this, 14));
            c.title = Ui.text(this, "", 15, Ui.TEXT, true);
            c.detail = Ui.text(this, "", 13, Ui.SUB, false);
            c.status = Ui.text(this, "", 12, Ui.ACCENT, true);
            c.view.addView(c.title);
            c.view.addView(c.detail);
            c.view.addView(c.status, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));
            final BridgeCard cc = c;
            c.view.setOnClickListener(v -> connect(cc));
            cards.put(b.ip, c);
            if (isCurrent(b)) bridgeList.addView(c.view, 0, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
            else bridgeList.addView(c.view, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        }
        if (b.id.isEmpty() && c.bridge != null) b.id = c.bridge.id;
        c.bridge = b;
        c.title.setText(b.name);
        c.detail.setText(b.ip);
        if (status != null) c.status.setText(status);
        else if (isCurrent(b)) c.status.setText("✓ Connected");
        else if (!b.ip.equals(pairingIp)) c.status.setText("Tap to connect");
        if (!cards.isEmpty() && searchState.getText().toString().startsWith("Searching")) {
            int others = 0;
            for (BridgeCard x : cards.values()) if (!isCurrent(x.bridge)) others++;
            if (others > 0) searchState.setText(cards.size() == 1 ? "1 bridge" : cards.size() + " bridges");
        }
        return c;
    }

    private void connect(BridgeCard c) {
        if (isCurrent(c.bridge)) {
            Toast.makeText(this, "Already connected to " + c.bridge.name, Toast.LENGTH_SHORT).show();
            return;
        }
        int have = prefs.hueLamps().size();
        if (prefs.isPaired() && have > 0) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Switch to " + c.bridge.name + "?")
                    .setMessage("Your " + have + (have == 1 ? " lamp" : " lamps") + " from " + prefs.bridgeName()
                            + " will be removed from LightBox. You can add the lamps of the new bridge right after.")
                    .setPositiveButton("Switch", (d, w) -> pair(c))
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            pair(c);
        }
    }

    /** Waits for the link button (up to 90 s) while the card says what to do. */
    private void pair(BridgeCard c) {
        final HueApi.Bridge bridge = c.bridge;
        pairingIp = bridge.ip;
        for (BridgeCard o : cards.values()) if (o != c && !isCurrent(o.bridge)) o.status.setText("Tap to connect");
        c.status.setText("Press the round link button on top of this bridge now…");
        final long deadline = System.currentTimeMillis() + 90_000;
        bg(() -> {
            String device = Build.MODEL == null ? "android" : Build.MODEL;
            while (alive && bridge.ip.equals(pairingIp) && System.currentTimeMillis() < deadline) {
                try {
                    String[] creds = HueApi.pair(bridge.ip, device);
                    ui(() -> linked(bridge, creds));
                    return;
                } catch (HueApi.LinkButtonNotPressed e) {
                    // keep waiting
                } catch (IOException e) {
                    ui(() -> c.status.setText("Press the link button… (" + e.getMessage() + ")"));
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (bridge.ip.equals(pairingIp)) ui(() -> {
                pairingIp = null;
                c.status.setText("Timed out. Tap to try again.");
            });
        });
    }

    private void linked(HueApi.Bridge b, String[] creds) {
        pairingIp = null;
        boolean other = prefs.isPaired() && !isCurrent(b);
        if (other) {
            // the lamps and the light setup on the bridge belong to the old one
            prefs.saveHueLamps(new ArrayList<>());
            prefs.setHueConfigId(null);
            prefs.setHueConfigKey("");
        }
        prefs.saveBridge(b.ip, b.name, creds[0], creds[1]);
        prefs.setBridgeId(b.id);
        prefs.setHueMigrated(); // a fresh bridge: nothing to take over from the old area setup
        setResult(RESULT_OK);
        Toast.makeText(this, "Connected to " + b.name, Toast.LENGTH_SHORT).show();
        if (lamp != null && other) {
            // the edited lamp was on the old bridge: continue with adding lamps
            getIntent().removeExtra(EXTRA_INDEX);
            recreate();
            return;
        }
        for (BridgeCard c : cards.values()) showBridge(c.bridge, null);
        if (lamp == null) loadLamps();
    }

    // ------------------------------------------------------------------ lamps

    private void loadLamps() {
        lampsBox.removeAllViews();
        fresh.clear();
        checks.clear();
        updateAddBtn();
        if (!prefs.isPaired()) {
            lampsBox.addView(Ui.text(this, "Connect a bridge above to see its lamps.", 14, Ui.SUB, false));
            return;
        }
        TextView loading = Ui.text(this, "Loading lamps…", 14, Ui.SUB, false);
        lampsBox.addView(loading);
        final String ip = prefs.bridgeIp(), user = prefs.username();
        bg(() -> {
            try {
                List<HueApi.Lamp> all = HueApi.listLamps(ip, user);
                ui(() -> showLamps(all));
            } catch (IOException e) {
                ui(() -> {
                    loading.setText("Couldn't load the lamps: " + e.getMessage());
                    TextView retry = Ui.button(this, "Try again", false);
                    retry.setOnClickListener(v -> loadLamps());
                    lampsBox.addView(retry, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
                });
            }
        });
    }

    private void showLamps(List<HueApi.Lamp> all) {
        lampsBox.removeAllViews();
        Set<String> have = new HashSet<>();
        for (HueLamp h : prefs.hueLamps()) have.add(h.entService);
        if (all.isEmpty()) {
            lampsBox.addView(Ui.text(this, "This bridge has no color lamps that can play along "
                    + "(the Entertainment API needs color lamps, light strips or gradient lights).", 14, Ui.SUB, false));
            return;
        }
        for (HueApi.Lamp l : all) {
            boolean added = have.contains(l.entService);
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));
            row.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
            CheckBox cb = new CheckBox(this);
            cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
            cb.setText(l.name + (l.segments > 1 ? "\nGradient · " + l.segments + " segments" : "") + (added ? "\nAdded already" : ""));
            cb.setTextColor(added ? Ui.SUB : Ui.TEXT);
            cb.setChecked(true);
            cb.setEnabled(!added);
            row.addView(cb, Ui.lp(0, Ui.WRAP, 1));
            if (!l.lightId.isEmpty()) {
                TextView blink = Ui.chip(this, "Blink");
                blink.setOnClickListener(v -> blink(l.lightId));
                row.addView(blink);
            }
            lampsBox.addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 6)));
            if (!added) {
                fresh.add(l);
                checks.add(cb);
                cb.setOnCheckedChangeListener((bv, on) -> updateAddBtn());
            }
        }
        if (fresh.isEmpty()) {
            lampsBox.addView(Ui.text(this, "All lamps of this bridge are added.", 13, Ui.SUB, false),
                    Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 4), 0, 0));
        }
        updateAddBtn();
    }

    private void updateAddBtn() {
        if (addBtn == null) return;
        int n = 0;
        for (CheckBox c : checks) if (c.isChecked()) n++;
        addBtn.setText(n == 0 ? (fresh.isEmpty() ? "Done" : "Add lamps") : n == 1 ? "Add 1 lamp" : "Add " + n + " lamps");
    }

    private void addChosen() {
        List<HueApi.Lamp> chosen = new ArrayList<>();
        for (int i = 0; i < checks.size(); i++) if (checks.get(i).isChecked()) chosen.add(fresh.get(i));
        if (chosen.isEmpty()) {
            if (fresh.isEmpty()) finish();
            else Toast.makeText(this, "Tick the lamps to add", Toast.LENGTH_SHORT).show();
            return;
        }
        List<HueLamp> list = prefs.hueLamps();
        for (int i = 0; i < chosen.size(); i++) {
            HueApi.Lamp l = chosen.get(i);
            HueLamp h = new HueLamp();
            h.name = l.name;
            h.lightId = l.lightId;
            h.entService = l.entService;
            h.segments = l.segments;
            if (l.hasPosition) {
                h.x = l.x;
                h.y = l.y;
                h.z = l.z;
                h.x2 = l.x2;
                h.y2 = l.y2;
            } else {
                // along the front wall until placed on the room plan
                h.x = chosen.size() == 1 ? 0f : -0.8f + 1.6f * i / (chosen.size() - 1);
                h.y = 0.8f;
                h.x2 = h.x + (h.gradient() ? 0.3f : 0f);
                h.y2 = h.y;
            }
            list.add(h);
        }
        prefs.saveHueLamps(list);
        setResult(RESULT_OK);
        finish();
    }

    private void saveLamp() {
        String n = nameField.getText().toString().trim();
        List<HueLamp> all = prefs.hueLamps();
        if (index < all.size() && !n.isEmpty()) {
            all.get(index).name = n;
            prefs.saveHueLamps(all);
        }
        setResult(RESULT_OK);
        finish();
    }

    private void blink(String lightId) {
        if (lightId == null || lightId.isEmpty() || !prefs.isPaired()) return;
        final String ip = prefs.bridgeIp(), user = prefs.username();
        bg(() -> {
            try {
                HueApi.identify(ip, user, lightId);
            } catch (IOException e) {
                ui(() -> Toast.makeText(this, "Couldn't reach the bridge: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ------------------------------------------------------------------ local search (mDNS)

    private void startDiscovery() {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
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
            nsd.discoverServices("_hue._tcp", NsdManager.PROTOCOL_DNS_SD, discovery);
        } catch (Exception e) {
            discovery = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void resolveNext() {
        if (resolving || resolveQueue.isEmpty() || nsd == null) return;
        resolving = true;
        NsdServiceInfo info = resolveQueue.poll();
        try {
            nsd.resolveService(info, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    main.post(() -> {
                        resolving = false;
                        resolveNext();
                    });
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    InetAddress host = serviceInfo.getHost();
                    if (host != null && host.getAddress().length == 4) check(host.getHostAddress(), false);
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

    private void stopDiscovery() {
        if (nsd != null && discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (Exception ignored) {
            }
        }
        discovery = null;
        resolveQueue.clear();
        resolving = false;
    }
}
