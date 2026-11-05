package app.huebeat;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Lets the user pick which app's audio to follow. Recently used apps are shown first. */
public class AppPickerActivity extends Activity {
    private LinearLayout list;

    private static final class AppEntry {
        String pkg;
        String label;
        Drawable icon;
        long lastUsed;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 24));
        scroll.addView(root);
        root.addView(Ui.text(this, "Follow one app", 26, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Only this app's sound drives the lights. Notifications and other apps are ignored.", 15, Ui.SUB, false);
        root.addView(sub, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 14)));
        list = Ui.column(this);
        root.addView(list);
        Ui.setupWindow(this, scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private boolean hasUsageAccess() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void load() {
        list.removeAllViews();
        TextView loading = Ui.text(this, "Loading apps…", 15, Ui.SUB, false);
        list.addView(loading);
        final boolean usage = hasUsageAccess();
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(launcher, 0);
            Map<String, Long> lastUsed = new HashMap<>();
            if (usage) {
                UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 6 * 3600_000L, now);
                if (stats != null) {
                    for (UsageStats s : stats) {
                        long t = Math.max(s.getLastTimeUsed(), s.getLastTimeVisible());
                        Long prev = lastUsed.get(s.getPackageName());
                        if (t > 0 && (prev == null || t > prev)) lastUsed.put(s.getPackageName(), t);
                    }
                }
            }
            List<AppEntry> apps = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : infos) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                AppEntry e = new AppEntry();
                e.pkg = pkg;
                e.label = String.valueOf(ri.loadLabel(pm));
                e.icon = ri.loadIcon(pm);
                Long lu = lastUsed.get(pkg);
                e.lastUsed = lu == null ? 0 : lu;
                apps.add(e);
            }
            Collections.sort(apps, (a, b2) -> a.label.compareToIgnoreCase(b2.label));
            List<AppEntry> recent = new ArrayList<>();
            for (AppEntry e : apps) if (e.lastUsed > 0) recent.add(e);
            Collections.sort(recent, (a, b2) -> Long.compare(b2.lastUsed, a.lastUsed));
            if (recent.size() > 8) recent = new ArrayList<>(recent.subList(0, 8));
            final List<AppEntry> fRecent = recent;
            runOnUiThread(() -> show(apps, fRecent, usage));
        }, "huebeat-apps").start();
    }

    private void show(List<AppEntry> all, List<AppEntry> recent, boolean usage) {
        if (isFinishing()) return;
        list.removeAllViews();
        if (!usage) {
            TextView banner = Ui.text(this, "Show recently opened apps first\nTap to allow usage access for HueSoundCompanion", 14, Ui.TEXT, true);
            banner.setLineSpacing(0, 1.2f);
            int p = Ui.dp(this, 14);
            banner.setPadding(p, p, p, p);
            Ui.clickable(banner, 0xFF2A2214, Ui.dp(this, 14));
            banner.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                } catch (Exception ignored) {
                }
            });
            list.addView(banner, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 14)));
        }
        if (!recent.isEmpty()) {
            list.addView(header("Recently open"));
            for (AppEntry e : recent) list.addView(row(e));
        }
        list.addView(header("All apps"));
        for (AppEntry e : all) list.addView(row(e));
    }

    private TextView header(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), 12, Ui.SUB, true);
        t.setLetterSpacing(0.08f);
        t.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(this, 2), Ui.dp(this, 14), 0, Ui.dp(this, 6)));
        return t;
    }

    private View row(AppEntry e) {
        LinearLayout r = Ui.row(this);
        int p = Ui.dp(this, 10);
        r.setPadding(p, p, p, p);
        Ui.clickable(r, Ui.BG, Ui.dp(this, 12));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(e.icon);
        r.addView(icon, Ui.lp(Ui.dp(this, 40), Ui.dp(this, 40)));
        TextView label = Ui.text(this, e.label, 16, Ui.TEXT, false);
        r.addView(label, Ui.margins(Ui.lp(0, Ui.WRAP, 1), Ui.dp(this, 14), 0, 0, 0));
        if (e.pkg.equals(Prefs.get(this).appPackage())) r.addView(Ui.text(this, "✓", 18, Ui.ACCENT, true));
        r.setOnClickListener(v -> {
            Prefs.get(this).setApp(e.pkg, e.label);
            setResult(RESULT_OK);
            finish();
        });
        return r;
    }
}
