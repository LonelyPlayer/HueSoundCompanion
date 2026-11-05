package app.huebeat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Three steps: find the bridge, press its link button, choose the entertainment area. */
public class SetupActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;

    private LinearLayout content;
    private volatile boolean alive = true;
    private int step = 0; // 1 find, 2 link, 3 area

    // discovery
    private NsdManager nsd;
    private NsdManager.DiscoveryListener discovery;
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private boolean resolving = false;
    private final Map<String, HueApi.Bridge> found = new LinkedHashMap<>();
    private final java.util.Set<String> checking = new java.util.HashSet<>();
    private LinearLayout bridgeList;
    private TextView findStatus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        ScrollView scroll = new ScrollView(this);
        content = Ui.column(this);
        int pad = Ui.dp(this, 22);
        content.setPadding(pad, Ui.dp(this, 20), pad, Ui.dp(this, 24));
        scroll.addView(content);
        Ui.setupWindow(this, scroll);
        if (prefs.bridgeIp() != null && prefs.username() != null) showAreas();
        else showFind();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        stopDiscovery();
        super.onDestroy();
    }

    private void clear(String title, String subtitle) {
        content.removeAllViews();
        content.addView(Ui.text(this, title, 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, subtitle, 15, Ui.SUB, false);
        sub.setLineSpacing(0, 1.15f);
        content.addView(sub, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 22)));
    }

    private TextView card(String title, String detail) {
        TextView t = Ui.text(this, detail == null ? title : title + "\n" + detail, 16, Ui.TEXT, true);
        t.setLineSpacing(0, 1.2f);
        int p = Ui.dp(this, 16);
        t.setPadding(p, p, p, p);
        Ui.clickable(t, Ui.CARD, Ui.dp(this, 16));
        t.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
        return t;
    }

    private void bg(Runnable r) {
        new Thread(r, "huebeat-setup").start();
    }

    private void ui(Runnable r) {
        main.post(() -> {
            if (alive && !isFinishing()) r.run();
        });
    }

    // ------------------------------------------------------------------ step 1: find

    private void showFind() {
        step = 1;
        clear("Find your bridge", "Make sure your phone is on the same Wi-Fi as your Hue Bridge.");
        LinearLayout statusRow = Ui.row(this);
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        statusRow.addView(pb, Ui.lp(Ui.dp(this, 24), Ui.dp(this, 24)));
        findStatus = Ui.text(this, "Searching…", 15, Ui.SUB, false);
        statusRow.addView(findStatus, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(this, 12), 0, 0, 0));
        content.addView(statusRow, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 16)));

        bridgeList = Ui.column(this);
        content.addView(bridgeList);

        content.addView(Ui.text(this, "Or enter the bridge's IP address", 13, Ui.SUB, true),
                Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 22), 0, Ui.dp(this, 6)));
        LinearLayout manual = Ui.row(this);
        EditText ip = new EditText(this);
        ip.setHint("192.168.1.20");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        ip.setTextColor(Ui.TEXT);
        ip.setHintTextColor(0xFF5C5D66);
        ip.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        manual.addView(ip, Ui.lp(0, Ui.WRAP, 1));
        TextView go = Ui.button(this, "Connect", true);
        go.setOnClickListener(v -> {
            String s = ip.getText().toString().trim();
            if (!s.isEmpty()) addCandidate(s, true);
        });
        manual.addView(go, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(this, 10), 0, 0, 0));
        content.addView(manual);

        synchronized (found) {
            found.clear();
            checking.clear();
        }
        startDiscovery();
        bg(() -> {
            try {
                List<HueApi.Bridge> cloud = HueApi.cloudDiscovery();
                for (HueApi.Bridge b : cloud) addCandidate(b.ip, false);
                if (cloud.isEmpty()) ui(() -> {
                    if (step == 1 && found.isEmpty()) findStatus.setText("Searching… (Philips' online search found no bridge on this connection)");
                });
            } catch (IOException e) {
                // mDNS or manual entry still work
                ui(() -> {
                    if (step == 1 && found.isEmpty()) findStatus.setText("Searching… (online search failed: " + e.getMessage() + ")");
                });
            }
        });
        main.postDelayed(() -> {
            if (step == 1 && found.isEmpty() && findStatus != null) {
                findStatus.setText("Still searching… You can also type the IP (shown in the Hue app under Settings › My Hue system › Bridge).");
            }
        }, 8000);
    }

    /** Checks an IP; if it is a Hue bridge, shows it (or goes straight to pairing for manual input). */
    private void addCandidate(String ip, boolean manual) {
        synchronized (found) {
            if (!manual && (found.containsKey(ip) || checking.contains(ip))) return;
            checking.add(ip);
        }
        bg(() -> {
            try {
                HueApi.Bridge b = HueApi.describe(ip);
                synchronized (found) {
                    found.put(ip, b);
                }
                ui(() -> {
                    if (manual) {
                        showLink(b);
                        return;
                    }
                    if (step != 1) return;
                    findStatus.setText(found.size() == 1 ? "Found 1 bridge" : "Found " + found.size() + " bridges");
                    TextView c = card(b.name, b.ip);
                    c.setOnClickListener(v -> showLink(b));
                    bridgeList.addView(c);
                });
            } catch (IOException e) {
                final String why = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                if (manual) {
                    ui(() -> findStatus.setText("Couldn't reach " + ip + " (" + why + ")"));
                    return;
                }
                // Found by the cloud or mDNS but not reachable: still show it, with the reason,
                // so pairing can be tried and the problem is visible.
                HueApi.Bridge b = new HueApi.Bridge(ip);
                ui(() -> {
                    if (step != 1) return;
                    findStatus.setText("Found a bridge, but the phone can't reach it");
                    TextView c = card("Hue Bridge", ip + "\nNot reachable: " + why);
                    c.setOnClickListener(v -> showLink(b));
                    bridgeList.addView(c);
                });
            }
        });
    }

    private void startDiscovery() {
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        discovery = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                main.post(() -> {
                    if (step == 1 && found.isEmpty() && findStatus != null) {
                        findStatus.setText("Local search unavailable (code " + errorCode + "). Enter the IP below.");
                    }
                });
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
                    if (host != null && host.getAddress().length == 4) addCandidate(host.getHostAddress(), false);
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
    }

    // ------------------------------------------------------------------ step 2: link button

    private void showLink(HueApi.Bridge bridge) {
        step = 2;
        stopDiscovery();
        clear("Press the link button", "Press the big round button on top of \"" + bridge.name
                + "\". HueBeat connects automatically within a few seconds.");
        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        content.addView(pb, Ui.margins(Ui.lp(Ui.MATCH, Ui.dp(this, 64)), 0, Ui.dp(this, 10), 0, Ui.dp(this, 10)));
        TextView state = Ui.text(this, "Waiting for the button…", 15, Ui.SUB, false);
        state.setGravity(Gravity.CENTER);
        content.addView(state, Ui.lp(Ui.MATCH, Ui.WRAP));
        TextView back = Ui.button(this, "Back", false);
        back.setOnClickListener(v -> showFind());
        content.addView(back, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 30), 0, 0));

        final long deadline = System.currentTimeMillis() + 90_000;
        bg(() -> {
            String device = (Build.MODEL == null ? "android" : Build.MODEL);
            while (alive && step == 2 && System.currentTimeMillis() < deadline) {
                try {
                    String[] creds = HueApi.pair(bridge.ip, device);
                    prefs.saveBridge(bridge.ip, bridge.name, creds[0], creds[1]);
                    ui(this::showAreas);
                    return;
                } catch (HueApi.LinkButtonNotPressed e) {
                    // keep polling
                } catch (IOException e) {
                    ui(() -> state.setText(e.getMessage()));
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (alive && step == 2) ui(() -> state.setText("Timed out. Go back and try again."));
        });
    }

    // ------------------------------------------------------------------ step 3: area

    private void showAreas() {
        step = 3;
        clear("Choose your lights", "Pick the entertainment area that should follow the music.");
        TextView loading = Ui.text(this, "Loading…", 15, Ui.SUB, false);
        content.addView(loading);
        LinearLayout list = Ui.column(this);
        content.addView(list);

        TextView other = Ui.text(this, "Use a different bridge", 15, Ui.ACCENT, true);
        other.setGravity(Gravity.CENTER);
        other.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 18));
        other.setOnClickListener(v -> showFind());
        content.addView(other, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 16), 0, 0));

        String ip = prefs.bridgeIp(), user = prefs.username();
        bg(() -> {
            try {
                List<HueApi.Area> areas = HueApi.areas(ip, user);
                ui(() -> {
                    if (areas.isEmpty()) {
                        loading.setText("Your bridge has no entertainment area yet. In the Hue app go to Settings › Entertainment areas, "
                                + "create one with your color lights, then come back.");
                        TextView open = Ui.button(this, "Open Hue app", true);
                        open.setOnClickListener(v -> {
                            Intent i = getPackageManager().getLaunchIntentForPackage("com.philips.lighting.hue2");
                            if (i != null) startActivity(i);
                        });
                        list.addView(open, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 16), 0, Ui.dp(this, 10)));
                        TextView retry = Ui.button(this, "Check again", false);
                        retry.setOnClickListener(v -> showAreas());
                        list.addView(retry, Ui.lp(Ui.MATCH, Ui.WRAP));
                        return;
                    }
                    loading.setVisibility(View.GONE);
                    for (HueApi.Area a : areas) {
                        String detail = a.channelCount + (a.channelCount == 1 ? " light channel" : " light channels")
                                + (a.id.equals(prefs.areaId()) ? " · current" : "");
                        TextView c = card(a.name, detail);
                        c.setOnClickListener(v -> {
                            prefs.saveArea(a.id, a.name);
                            setResult(RESULT_OK);
                            finish();
                        });
                        list.addView(c);
                    }
                });
            } catch (IOException e) {
                ui(() -> {
                    loading.setText("Couldn't reach the bridge: " + e.getMessage());
                    TextView retry = Ui.button(this, "Try again", false);
                    retry.setOnClickListener(v -> showAreas());
                    list.addView(retry, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 14), 0, 0));
                });
            }
        });
    }
}
