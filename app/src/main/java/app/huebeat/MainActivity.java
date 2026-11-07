package app.huebeat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.huebeat.core.Ambient;
import app.huebeat.core.LightSettings;
import app.huebeat.core.Palettes;
import app.huebeat.core.StripEffect;

public class MainActivity extends Activity implements SyncState.Listener {
    private static final int REQ_PERMS = 1;
    private static final int REQ_PROJECTION = 2;
    private static final int REQ_PICK_APP = 3;
    private static final int REQ_SETUP = 4;
    private static final int REQ_EDIT_PALETTE = 5;
    private static final int REQ_STRIP = 6;
    private LinearLayout stripsBox, dmxBox, shuffleRow, hueBox;
    private List<HueLamp> hueList = new ArrayList<>();
    private static final int REQ_ROOM = 10;
    private List<DmxLight> dmxList;
    private static final int REQ_DMX = 9;
    private LinearLayout palettesRow;
    private TextView screenChip;
    private boolean sessionHasScreen = false;

    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());

    private PowerButton power;
    private LevelMeter meter;
    private TextView status, hint, appRow, footer, delayLabel;
    private SeekBar delayBar;
    private LinearLayout appRowBox, strobeRow;
    private final List<TextView> modeChips = new ArrayList<>();
    private final List<TextView> styleChips = new ArrayList<>();
    private final List<TextView> paletteChips = new ArrayList<>();
    private boolean pendingStart = false;
    private boolean pendingShow = false;
    private boolean askedNotif = false;
    /** Palette editor bookkeeping: which palette is edited (-1 = new) and where the result goes. */
    private int editingPalette = -1;
    private Ui.IntSet paletteTarget;
    private LinearLayout ambPaletteBox;
    private final List<TextView> appModeChips = new ArrayList<>();
    private LinearLayout musicTop, musicLook, ambientLook;
    private TextView ambAnimBtn, ambColorBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        migrateHue();
        SyncState.add(this);
        refresh();
    }

    @Override
    protected void onPause() {
        SyncState.remove(this);
        super.onPause();
    }

    @Override
    public void onSyncStateChanged() {
        refresh();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        int pad = Ui.dp(this, 20);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 24));
        scroll.addView(root);

        // header
        LinearLayout header = Ui.row(this);
        TextView title = Ui.text(this, "LightBox", 22, Ui.TEXT, true);
        header.addView(title, Ui.lp(0, Ui.WRAP, 1));
        TextView show = Ui.text(this, "✦ Phone show", 14, Ui.SUB, true);
        show.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        Ui.clickable(show, Ui.CARD_2, Ui.dp(this, 16));
        show.setContentDescription("Phone show: screen and flashlight play along");
        show.setOnClickListener(v -> phoneShow());
        header.addView(show);
        root.addView(header, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 18)));

        // what the app does: follow music, or just light the room
        LinearLayout appModes = Ui.row(this);
        String[] appModeNames = {"Music sync", "Just lights"};
        for (int i = 0; i < 2; i++) {
            final int m = i;
            TextView chip = Ui.chip(this, appModeNames[i]);
            chip.setOnClickListener(v -> onAppModeSelected(m));
            appModeChips.add(chip);
            appModes.addView(chip, Ui.margins(Ui.lp(0, Ui.WRAP, 1), i == 0 ? 0 : Ui.dp(this, 4), 0, i == 1 ? 0 : Ui.dp(this, 4), 0));
        }
        root.addView(appModes, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 4)));

        // audio source
        musicTop = Ui.column(this);
        root.addView(musicTop);
        LinearLayout top = musicTop;
        top.addView(section("Listen to"));
        LinearLayout modes = Ui.row(this);
        String[] modeNames = {"Phone audio", "One app", "Microphone"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView chip = Ui.chip(this, modeNames[i]);
            chip.setOnClickListener(v -> onModeSelected(m));
            modeChips.add(chip);
            modes.addView(chip, Ui.margins(Ui.lp(0, Ui.WRAP, 1), i == 0 ? 0 : Ui.dp(this, 4), 0, i == 2 ? 0 : Ui.dp(this, 4), 0));
        }
        top.addView(modes);

        appRowBox = Ui.row(this);
        appRowBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        appRowBox.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        appRow = Ui.text(this, "", 15, Ui.TEXT, false);
        appRowBox.addView(appRow, Ui.lp(0, Ui.WRAP, 1));
        TextView change = Ui.text(this, "Change", 14, Ui.ACCENT, true);
        appRowBox.addView(change);
        appRowBox.setOnClickListener(v -> startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP));
        top.addView(appRowBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));

        // the big button
        power = new PowerButton(this);
        power.setOnClickListener(v -> onPowerClicked());
        root.addView(power, Ui.margins(Ui.lp(Ui.MATCH, Ui.dp(this, 290)), 0, Ui.dp(this, 6), 0, 0));

        status = Ui.text(this, "", 16, Ui.TEXT, true);
        status.setGravity(Gravity.CENTER);
        root.addView(status, Ui.lp(Ui.MATCH, Ui.WRAP));
        meter = new LevelMeter(this);
        LinearLayout.LayoutParams mlp = Ui.margins(Ui.lp(Ui.dp(this, 160), Ui.dp(this, 6)), 0, Ui.dp(this, 10), 0, Ui.dp(this, 2));
        mlp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(meter, mlp);
        hint = Ui.text(this, "", 13, Ui.SUB, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), 0);
        root.addView(hint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 18)));

        // style
        musicLook = Ui.column(this);
        root.addView(musicLook);
        LinearLayout look = collapsible(musicLook, "Global look", "open_look", true);
        roomControls(look);
        look.addView(section("Style"));
        HorizontalScrollView styleScroll = new HorizontalScrollView(this);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styles = Ui.row(this);
        for (int i = 0; i < LightSettings.STYLE_ORDER.length; i++) {
            final int s = LightSettings.STYLE_ORDER[i];
            TextView chip = Ui.chip(this, LightSettings.STYLE_NAMES[s]);
            chip.setOnClickListener(v -> {
                prefs.light.style = s;
                prefs.saveLight();
                if (s == LightSettings.STYLE_SCREEN) ensureScreenCapture();
                refresh();
            });
            styleChips.add(chip);
            styles.addView(chip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        }
        styleScroll.addView(styles);
        look.addView(styleScroll);
        shuffleRow = Ui.row(this);
        TextView shHint = Ui.text(this, "Shuffle picks style, palette, intensity and strip effects to fit the music, "
                + "and changes them after phrases and on drops.", 13, Ui.SUB, false);
        shHint.setLineSpacing(0, 1.1f);
        shuffleRow.addView(shHint, Ui.lp(0, Ui.WRAP, 1));
        TextView shNow = Ui.chip(this, "Shuffle now");
        shNow.setTextColor(Ui.ACCENT);
        shNow.setOnClickListener(v -> SyncState.reshuffle = true);
        shuffleRow.addView(shNow, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), Ui.dp(this, 10), 0, 0, 0));
        look.addView(shuffleRow, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));

        // palette
        look.addView(section("Colors"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        palettesRow = Ui.row(this);
        hs.addView(palettesRow);
        look.addView(hs);
        rebuildPalettes();

        // sliders
        look.addView(section("Intensity"));
        look.addView(slider(Math.round(prefs.light.intensity * 100), 100, v -> {
            prefs.light.intensity = v / 100f;
            prefs.saveLight();
        }));
        look.addView(section("Color movement"));
        look.addView(slider(Math.round(prefs.light.colorMove * 100), 100, v -> {
            prefs.light.colorMove = v / 100f;
            prefs.saveLight();
        }));
        LinearLayout buildRow = Ui.row(this);
        LinearLayout buildText = Ui.column(this);
        buildText.addView(Ui.text(this, "Build-ups & drops", 15, Ui.TEXT, false));
        buildText.addView(Ui.text(this, "Risers make the lights brighter, whiter and flash faster; black before the drop, white when it hits.",
                13, Ui.SUB, false));
        buildRow.addView(buildText, Ui.lp(0, Ui.WRAP, 1));
        Switch buildSw = new Switch(this);
        buildSw.setChecked(prefs.light.buildups);
        buildSw.setOnCheckedChangeListener((bv, on) -> {
            prefs.light.buildups = on;
            prefs.saveLight();
        });
        buildRow.addView(buildSw);
        look.addView(buildRow, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 14), 0, 0));
        look.addView(section("Max brightness"));
        look.addView(slider(Math.round(prefs.light.maxBrightness * 100), 100, v -> {
            prefs.light.maxBrightness = Math.max(0.05f, v / 100f);
            prefs.saveLight();
        }));

        strobeRow = Ui.row(this);
        look.addView(section("Lamp strobe"));
        look.addView(Ui.chipRow(this, LightSettings.STROBE_MODES, () -> prefs.light.strobeMode, v -> {
            prefs.light.strobeMode = v;
            prefs.light.strobe = v == 1;
            prefs.saveLight();
        }));

        ambientLook = Ui.column(this);
        root.addView(ambientLook);
        buildAmbient(collapsible(ambientLook, "Global look", "open_ambient", true));

        // all devices in one list, one button to add any kind
        LinearLayout lights = collapsible(root, "Lights", "open_devices", true);
        hueBox = Ui.column(this);
        lights.addView(hueBox);
        stripsBox = Ui.column(this);
        lights.addView(stripsBox);
        dmxBox = Ui.column(this);
        lights.addView(dmxBox);
        TextView addLight = Ui.button(this, "+ Add light", false);
        addLight.setOnClickListener(v -> addDeviceDialog());
        lights.addView(addLight, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 12), 0, 0));
        rebuildHue();
        rebuildStrips();
        rebuildDmx();

        delayLabel = section("Light delay");
        root.addView(delayLabel);
        delayBar = slider(0, 50, v -> {
            prefs.setDelay(currentBluetooth(), v * 10);
            updateDelayLabel();
        });
        root.addView(delayBar);

        footer = Ui.text(this, "", 13, Ui.SUB, false);
        footer.setGravity(Gravity.CENTER);
        root.addView(footer, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 26), 0, 0));
        TextView credits = Ui.text(this, "Free software (GPL-3.0). Strip effects and some animations ported from LedFx, © the LedFx developers. Tap for source.", 11, Ui.SUB, false);
        credits.setGravity(Gravity.CENTER);
        credits.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        credits.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/LonelyPlayer/HueSoundCompanion")));
            } catch (Exception ignored) {
            }
        });
        root.addView(credits, Ui.lp(Ui.MATCH, Ui.WRAP));

        Ui.setupWindow(this, scroll);
    }

    // ------------------------------------------------------------------ just lights

    private static String[] withSame(String[] names) {
        String[] o = new String[names.length + 1];
        o[0] = "Global";
        System.arraycopy(names, 0, o, 1, names.length);
        return o;
    }

    private static String[] withSameCat(String[] cats) {
        String[] o = new String[cats.length + 1];
        o[0] = "";
        System.arraycopy(cats, 0, o, 1, cats.length);
        return o;
    }

    private TextView pickerButton(String text) {
        TextView t = Ui.text(this, text, 15, Ui.TEXT, true);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        t.setCompoundDrawablePadding(Ui.dp(this, 10));
        Ui.clickable(t, Ui.CARD_2, Ui.dp(this, 14));
        return t;
    }

    private void setDot(TextView t, int rgb) {
        t.setCompoundDrawablesRelative(Pickers.dot(this, rgb, 18), null, null, null);
    }

    private static String[] paletteNames() {
        String[] pal = new String[Palettes.count()];
        for (int k = 0; k < pal.length; k++) pal[k] = Palettes.name(k);
        return pal;
    }

    private void buildAmbient(LinearLayout box) {
        Ambient.Settings a = prefs.ambient;
        roomControls(box);
        box.addView(section("Animation"));
        ambAnimBtn = pickerButton(Ambient.NAMES[a.animation] + "  ▸");
        ambAnimBtn.setOnClickListener(v -> Pickers.effect(this, "Animation", Ambient.NAMES, Ambient.CATEGORIES, a.animation, k -> {
            a.animation = k;
            prefs.saveAmbient();
            ambAnimBtn.setText(Ambient.NAMES[k] + "  ▸");
            rebuildStrips();
            refresh();
        }));
        box.addView(ambAnimBtn, Ui.lp(Ui.MATCH, Ui.WRAP));

        box.addView(section("Colors"));
        ambPaletteBox = Ui.column(this);
        box.addView(ambPaletteBox);
        rebuildAmbientPalettes();
        ambColorBtn = pickerButton("Main color");
        setDot(ambColorBtn, a.color);
        ambColorBtn.setOnClickListener(v -> Pickers.color(this, "Main color", a.color, c -> {
            a.color = c;
            prefs.saveAmbient();
            setDot(ambColorBtn, c);

        }));
        box.addView(ambColorBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));

        box.addView(section("Speed"));
        box.addView(Ui.slider(this, a.speed, v -> {
            a.speed = v;
            prefs.saveAmbient();
        }));
        box.addView(section("Brightness"));
        box.addView(Ui.slider(this, a.brightness, v -> {
            a.brightness = Math.max(0.02f, v);
            prefs.saveAmbient();
        }));

        LinearLayout keepRow = Ui.row(this);
        TextView kt = Ui.text(this, "Keep the colors when turned off", 15, Ui.TEXT, false);
        keepRow.addView(kt, Ui.lp(0, Ui.WRAP, 1));
        Switch keep = new Switch(this);
        keep.setChecked(prefs.keepColors());
        keep.setOnCheckedChangeListener((bv, on) -> prefs.setKeepColors(on));
        keepRow.addView(keep);
        box.addView(keepRow, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 14), 0, 0));
    }

    private void onAppModeSelected(int m) {
        if (m == prefs.appMode()) return;
        boolean wasActive = SyncState.isActive();
        prefs.setAppMode(m);
        rebuildStrips();
        rebuildDmx();
        rebuildHue();
        refresh();
        if (wasActive) {
            SyncService.stop(this);
            main.postDelayed(this::startFlow, 900);
        }
    }

    private boolean ambientMode() {
        return prefs.appMode() == Prefs.APP_AMBIENT;
    }

    private TextView section(String label) {
        TextView t = Ui.text(this, label.toUpperCase(), 12, Ui.SUB, true);
        t.setLetterSpacing(0.08f);
        t.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), Ui.dp(this, 2), Ui.dp(this, 16), 0, Ui.dp(this, 8)));
        return t;
    }

    private interface IntChange {
        void onChange(int v);
    }

    private SeekBar slider(int value, int max, IntChange cb) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(value);
        s.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        s.setThumbTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) cb.onChange(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        return s;
    }

    /** A section with a header that folds its content in and out (state is remembered). */
    private LinearLayout collapsible(LinearLayout parent, String title, String key, boolean defOpen) {
        android.content.SharedPreferences ui = getSharedPreferences("huebeat_ui", MODE_PRIVATE);
        boolean open = ui.getBoolean(key, defOpen);
        LinearLayout head = Ui.row(this);
        TextView t = Ui.text(this, title.toUpperCase(), 13, Ui.TEXT, true);
        t.setLetterSpacing(0.08f);
        head.addView(t, Ui.lp(0, Ui.WRAP, 1));
        TextView chev = Ui.text(this, open ? "▾" : "▸", 20, Ui.SUB, true);
        head.addView(chev);
        head.setPadding(0, Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 6));
        head.setClickable(true);
        LinearLayout body = Ui.column(this);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        head.setOnClickListener(v -> {
            boolean o = body.getVisibility() != View.VISIBLE;
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            chev.setText(o ? "▾" : "▸");
            ui.edit().putBoolean(key, o).apply();
        });
        parent.addView(head, Ui.lp(Ui.MATCH, Ui.WRAP));
        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        parent.addView(line, Ui.lp(Ui.MATCH, Ui.dp(this, 1)));
        parent.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP));
        return body;
    }

    private static final int[] STROBE_COLORS = {0xFFFFFF, 0xFF0000, 0x0040FF, 0x7A00FF, 0xFFA000, 0x00FF40, 0xFF00C8, 0x00E5FF};
    private static final String[] STROBE_COLOR_NAMES = {"White", "Red", "Blue", "UV", "Amber", "Green", "Pink", "Cyan"};
    private List<Strip> stripList;

    private static int strobeColorIndex(int c) {
        for (int i = 0; i < STROBE_COLORS.length; i++) if (STROBE_COLORS[i] == c) return i;
        return 0;
    }

    /** Saves the strips; a running sync picks the change up on its next frame. */
    private void saveStrips() {
        prefs.saveStrips(stripList);
    }

    // ------------------------------------------------------------------ room, groups, Hue lamps

    /** Global: which way effects run through the room, and the room plan. */
    private void roomControls(LinearLayout box) {
        box.addView(section("Direction in the room"));
        box.addView(Ui.chipRow(this, Prefs.FLOWS, () -> prefs.flow(), v -> prefs.setFlow(v)));
        TextView plan = pickerButton("Room plan: place your lights  ▸");
        plan.setOnClickListener(v -> startActivityForResult(new Intent(this, RoomActivity.class), REQ_ROOM));
        box.addView(plan, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
    }

    /** A collapsible device/group card: header (title, gear, switch) and a body. */
    private LinearLayout[] shell(String titleText, String key, Runnable gear, boolean on,
                                 android.widget.CompoundButton.OnCheckedChangeListener sw, TextView[] titleOut) {
        return shell(titleText, key, null, gear, on, sw, titleOut);
    }

    private LinearLayout[] shell(String titleText, String key, Runnable link, Runnable gear, boolean on,
                                 android.widget.CompoundButton.OnCheckedChangeListener sw, TextView[] titleOut) {
        android.content.SharedPreferences ui = getSharedPreferences("huebeat_ui", MODE_PRIVATE);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 16)));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        LinearLayout head = Ui.row(this);
        boolean open = ui.getBoolean(key, false);
        TextView chev = Ui.text(this, open ? "▾" : "▸", 18, Ui.SUB, true);
        chev.setPadding(0, 0, Ui.dp(this, 10), 0);
        head.addView(chev);
        TextView t = Ui.text(this, titleText, 15, Ui.TEXT, true);
        t.setLineSpacing(0, 1.15f);
        head.addView(t, Ui.lp(0, Ui.WRAP, 1));
        if (titleOut != null) titleOut[0] = t;
        if (link != null) head.addView(linkIcon(false, link));
        if (gear != null) {
            TextView g = Ui.text(this, "⚙", 20, Ui.SUB, false);
            g.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            g.setContentDescription("Settings");
            g.setOnClickListener(v -> gear.run());
            head.addView(g);
        }
        Switch onSw = new Switch(this);
        onSw.setChecked(on);
        onSw.setOnCheckedChangeListener(sw);
        head.addView(onSw);
        card.addView(head);
        LinearLayout body = Ui.column(this);
        body.setPadding(0, 0, Ui.dp(this, 6), Ui.dp(this, 4));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        head.setClickable(true);
        head.setOnClickListener(v -> {
            boolean o = body.getVisibility() != View.VISIBLE;
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            chev.setText(o ? "▾" : "▸");
            ui.edit().putBoolean(key, o).apply();
        });
        card.addView(body);
        return new LinearLayout[]{card, body};
    }

    /** The chain icon in a device header: link (into a group) or unlink (out of its group). */
    private View linkIcon(boolean unlink, Runnable action) {
        LinkIcon v = new LinkIcon(this, unlink);
        v.setLayoutParams(Ui.lp(Ui.dp(this, 38), Ui.dp(this, 34)));
        v.setClickable(true);
        v.setOnClickListener(x -> action.run());
        return v;
    }

    private View iconGear(Runnable action) {
        TextView g = Ui.text(this, "⚙", 18, Ui.SUB, false);
        g.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
        g.setContentDescription("Settings");
        g.setOnClickListener(v -> action.run());
        return g;
    }

    /** A name not used by another group in {@code taken}. */
    private static String freeName(String want, java.util.Collection<String> taken) {
        String g = want;
        for (int n = 2; taken.contains(g); n++) g = want + " " + n;
        return g;
    }

    /**
     * The link modal of one device: join one of the existing groups of its kind ({@code ok}: the
     * group fits, e.g. the same DMX light type), or start a new group with it.
     */
    private void linkDialog(String device, String defName, String[] groups, String[] info, boolean[] ok,
                            java.util.function.Consumer<String> join, java.util.function.Consumer<String> create) {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 4));
        sc.addView(box);
        android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        if (groups.length > 0) {
            box.addView(Ui.label(this, "Add to a group"));
            for (int i = 0; i < groups.length; i++) {
                final String g = groups[i];
                TextView c = Ui.text(this, g + "\n" + info[i], 15, ok[i] ? Ui.TEXT : Ui.SUB, true);
                c.setLineSpacing(0, 1.15f);
                c.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
                if (ok[i]) {
                    Ui.clickable(c, Ui.CARD_2, Ui.dp(this, 12));
                    c.setOnClickListener(v -> {
                        join.accept(g);
                        if (dlg[0] != null) dlg[0].dismiss();
                    });
                } else {
                    c.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 12)));
                }
                box.addView(c, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 6)));
            }
        }
        box.addView(Ui.label(this, "New group"));
        android.widget.EditText name = new android.widget.EditText(this);
        name.setHint(defName);
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(0xFF5C5D66);
        box.addView(name);
        TextView make = Ui.button(this, "Create group", true);
        make.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            create.accept(n.isEmpty() ? defName : n);
            if (dlg[0] != null) dlg[0].dismiss();
        });
        box.addView(make, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        TextView note = Ui.text(this, "A group shares one look. Link more lights to it with their own link symbol.", 13, Ui.SUB, false);
        box.addView(note, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
        dlg[0] = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Group \"" + device + "\"")
                .setView(sc)
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** One button for every kind of light. */
    private void addDeviceDialog() {
        String[][] kinds = {
                {"Hue lamp", "Philips Hue, through a Hue Bridge"},
                {"LED strip", "WLED / ESP32 · DDP, E1.31 or WLED UDP"},
                {"DMX light", "Through an Art-Net or sACN node"}};
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, Ui.dp(this, 4));
        android.app.AlertDialog[] dlg = new android.app.AlertDialog[1];
        for (int i = 0; i < kinds.length; i++) {
            final int k = i;
            LinearLayout c = Ui.column(this);
            c.addView(Ui.text(this, kinds[i][0], 16, Ui.TEXT, true));
            c.addView(Ui.text(this, kinds[i][1], 13, Ui.SUB, false));
            c.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
            Ui.clickable(c, Ui.CARD_2, Ui.dp(this, 14));
            c.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                if (k == 0) addHueLamps();
                else if (k == 1) startActivityForResult(new Intent(this, StripSetupActivity.class), REQ_STRIP);
                else startActivityForResult(new Intent(this, DmxSetupActivity.class), REQ_DMX);
            });
            box.addView(c, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
        }
        dlg[0] = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add a light")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void linkHue(HueLamp h) {
        java.util.LinkedHashMap<String, Integer> gs = new java.util.LinkedHashMap<>();
        for (HueLamp o : hueList) if (!o.group.isEmpty()) gs.merge(o.group, 1, Integer::sum);
        String[] names = gs.keySet().toArray(new String[0]);
        String[] info = new String[names.length];
        boolean[] ok = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            info[i] = gs.get(names[i]) + (gs.get(names[i]) == 1 ? " lamp" : " lamps");
            ok[i] = true;
        }
        linkDialog(h.name, "Lamps", names, info, ok, g -> {
            for (HueLamp o : hueList) if (g.equals(o.group)) {
                h.copyLookFrom(o);
                break;
            }
            h.group = g;
            saveHue(false);
            rebuildHue();
        }, n -> {
            h.group = freeName(n, gs.keySet());
            saveHue(false);
            rebuildHue();
        });
    }

    private void linkStrip(Strip st) {
        java.util.LinkedHashMap<String, Integer> gs = new java.util.LinkedHashMap<>();
        for (Strip o : stripList) if (!o.group.isEmpty()) gs.merge(o.group, 1, Integer::sum);
        String[] names = gs.keySet().toArray(new String[0]);
        String[] info = new String[names.length];
        boolean[] ok = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            info[i] = gs.get(names[i]) + (gs.get(names[i]) == 1 ? " strip" : " strips");
            ok[i] = true;
        }
        linkDialog(st.name, "Strips", names, info, ok, g -> {
            for (Strip o : stripList) if (g.equals(o.group)) {
                st.copyLookFrom(o);
                break;
            }
            st.group = g;
            saveStrips();
            rebuildStrips();
        }, n -> {
            st.group = freeName(n, gs.keySet());
            saveStrips();
            rebuildStrips();
        });
    }

    /** DMX lights only join groups of the same light type (the same channels). */
    private void linkDmx(DmxLight d) {
        java.util.LinkedHashMap<String, Integer> gs = new java.util.LinkedHashMap<>();
        java.util.Map<String, DmxLight> lead = new java.util.HashMap<>();
        for (DmxLight o : dmxList) if (!o.group.isEmpty()) {
            gs.merge(o.group, 1, Integer::sum);
            lead.putIfAbsent(o.group, o);
        }
        String[] names = gs.keySet().toArray(new String[0]);
        String[] info = new String[names.length];
        boolean[] ok = new boolean[names.length];
        for (int i = 0; i < names.length; i++) {
            ok[i] = java.util.Arrays.equals(lead.get(names[i]).roles, d.roles);
            info[i] = gs.get(names[i]) + (gs.get(names[i]) == 1 ? " light" : " lights") + (ok[i] ? "" : " · other light type");
        }
        linkDialog(d.name, d.name.replaceAll("\\s*\\d+$", "") + "s", names, info, ok, g -> {
            d.copyLookFrom(lead.get(g));
            d.group = g;
            prefs.saveDmx(dmxList);
            rebuildDmx();
        }, n -> {
            d.group = freeName(n, gs.keySet());
            prefs.saveDmx(dmxList);
            rebuildDmx();
        });
    }

    /** Rename or dissolve a group. */
    private void groupDialog(String name, java.util.function.Consumer<String> rename, Runnable ungroup) {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setText(name);
        field.setSingleLine(true);
        field.setTextColor(Ui.TEXT);
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(field);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Group")
                .setView(box)
                .setPositiveButton("Rename", (dlg, w) -> {
                    String n = field.getText().toString().trim();
                    if (!n.isEmpty()) rename.accept(n);
                })
                .setNeutralButton("Ungroup", (dlg, w) -> ungroup.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Hue lamps

    private void rebuildHue() {
        if (hueBox == null) return;
        hueBox.removeAllViews();
        hueList = prefs.hueLamps();
        if (!prefs.isPaired() || hueList.isEmpty()) return;
        hueBox.addView(section("Hue lamps"));
        int on = 0;
        for (HueLamp h : hueList) if (h.enabled) on++;
        if (on > 10) {
            TextView w = Ui.text(this, "Hue streams to at most 10 lamps at a time: only the first 10 switched on take part.",
                    13, Ui.SUB, false);
            hueBox.addView(w, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
        }
        java.util.Set<String> shown = new java.util.HashSet<>();
        for (int i = 0; i < hueList.size(); i++) {
            final int idx = i;
            final HueLamp h = hueList.get(i);
            if (!h.group.isEmpty()) {
                if (shown.add(h.group)) hueBox.addView(hueGroupCard(h.group), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
                continue;
            }
            TextView[] title = new TextView[1];
            LinearLayout[] c = shell(hueTitle(h), "open_hue_" + h.entService, () -> linkHue(h), () -> startActivityForResult(new Intent(this, HueSetupActivity.class)
                    .putExtra(HueSetupActivity.EXTRA_INDEX, idx), REQ_SETUP), h.enabled, (bv, checked) -> {
                h.enabled = checked;
                saveHue(true);
            }, title);
            buildHueBody(c[1], h, false, title[0]);
            hueBox.addView(c[0], Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
        }
    }

    private String hueTitle(HueLamp h) {
        String what = ambientMode() ? (h.ambientEffect < 0 ? "Global" : Ambient.NAMES[Math.min(h.ambientEffect, Ambient.NAMES.length - 1)])
                : app.huebeat.core.Dmx.EFFECTS[h.effect];
        return h.name + "\n" + what + (h.gradient() ? " · " + h.segments + " segments" : "");
    }

    /** Saves the lamps; {@code members}: the set of streamed lamps changed (a running sync restarts). */
    private void saveHue(boolean members) {
        prefs.saveHueLamps(hueList);
        if (members && SyncState.isActive()) {
            SyncService.stop(this);
            main.postDelayed(this::startFlow, 900);
        }
        refresh();
    }

    private void saveHueFrom(HueLamp h) {
        if (!h.group.isEmpty()) {
            for (HueLamp o : hueList) if (o != h && h.group.equals(o.group)) o.copyLookFrom(h);
        }
        prefs.saveHueLamps(hueList);
    }

    /** The look controls of a Hue lamp card (or a group card: they set every member). */
    private void buildHueBody(LinearLayout body, HueLamp h, boolean group, TextView title) {
        final boolean amb = ambientMode();
        if (amb) {
            body.addView(Ui.label(this, "Animation"));
            TextView animBtn = pickerButton((h.ambientEffect < 0 ? "Global" : Ambient.NAMES[h.ambientEffect]) + "  ▸");
            animBtn.setOnClickListener(v -> Pickers.effect(this, "Animation", withSame(Ambient.NAMES), withSameCat(Ambient.CATEGORIES),
                    h.ambientEffect + 1, k -> {
                        h.ambientEffect = k - 1;
                        saveHueFrom(h);
                        animBtn.setText((h.ambientEffect < 0 ? "Global" : Ambient.NAMES[h.ambientEffect]) + "  ▸");
                        if (title != null && !group) title.setText(hueTitle(h));
                    }));
            body.addView(animBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
            TextView colBtn = pickerButton("");
            Runnable showCol = () -> {
                colBtn.setText(h.ambientColor < 0 ? "Color: global" : "Color");
                setDot(colBtn, h.ambientColor < 0 ? prefs.ambient.color : h.ambientColor);
            };
            showCol.run();
            colBtn.setOnClickListener(v -> Pickers.color(this, "Color", h.ambientColor, c -> {
                h.ambientColor = c;
                saveHueFrom(h);
                showCol.run();
            }, () -> {
                h.ambientColor = -1;
                saveHueFrom(h);
                showCol.run();
            }));
            body.addView(colBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        } else {
            body.addView(Ui.label(this, "Effect"));
            LinearLayout colorBox = Ui.column(this);
            TextView fxBtn = pickerButton(app.huebeat.core.Dmx.EFFECTS[h.effect] + "  ▸");
            body.addView(fxBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
            body.addView(colorBox);
            Runnable showColor = () -> {
                colorBox.removeAllViews();
                boolean solid = h.effect == app.huebeat.core.Dmx.FX_SOLID || h.effect == app.huebeat.core.Dmx.FX_SOLID_PULSE;
                if (!solid) return;
                TextView colBtn = pickerButton("Color");
                setDot(colBtn, h.color);
                colBtn.setOnClickListener(v -> Pickers.color(this, "Color", h.color, c -> {
                    h.color = c;
                    saveHueFrom(h);
                    setDot(colBtn, c);
                }));
                colorBox.addView(colBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
            };
            fxBtn.setOnClickListener(v -> Pickers.effect(this, "Effect", app.huebeat.core.Dmx.EFFECTS,
                    app.huebeat.core.Dmx.EFFECT_CATEGORIES, h.effect, k -> {
                        h.effect = k;
                        saveHueFrom(h);
                        fxBtn.setText(app.huebeat.core.Dmx.EFFECTS[k] + "  ▸");
                        if (title != null && !group) title.setText(hueTitle(h));
                        showColor.run();
                    }));
            showColor.run();
        }
        body.addView(Ui.label(this, "Colors"));
        body.addView(paletteRow(true, amb ? prefs.ambient.palette : prefs.light.palette, () -> h.palette, v -> {
            h.palette = v;
            saveHueFrom(h);
        }));
        body.addView(Ui.label(this, "Brightness"));
        body.addView(Ui.slider(this, h.brightness, v -> {
            h.brightness = Math.max(0.02f, v);
            saveHueFrom(h);
        }));
        if (group) {
            body.addView(Ui.label(this, "Together"));
            body.addView(Ui.chipRow(this, new String[]{"Spread over the room", "All the same"}, () -> h.together, v -> {
                h.together = v;
                saveHueFrom(h);
            }));
        }
    }

    private View hueGroupCard(String name) {
        List<HueLamp> members = new ArrayList<>();
        for (HueLamp x : hueList) if (name.equals(x.group)) members.add(x);
        HueLamp lead = members.get(0);
        boolean any = false;
        for (HueLamp m : members) any |= m.enabled;
        LinearLayout[] c = shell(name + "\n" + members.size() + " lamps · " + (ambientMode()
                        ? (lead.ambientEffect < 0 ? "Global" : Ambient.NAMES[lead.ambientEffect]) : app.huebeat.core.Dmx.EFFECTS[lead.effect]),
                "open_huegroup_" + name, () -> groupDialog(name, n -> {
                    for (HueLamp x : hueList) if (name.equals(x.group)) x.group = n;
                    saveHue(false);
                    rebuildHue();
                }, () -> {
                    for (HueLamp x : hueList) if (name.equals(x.group)) x.group = "";
                    saveHue(false);
                    rebuildHue();
                }), any, (bv, checked) -> {
                    for (HueLamp m : members) m.enabled = checked;
                    saveHue(true);
                }, null);
        buildHueBody(c[1], lead, true, null);
        c[1].addView(section("Members"));
        for (HueLamp m : members) {
            LinearLayout row = Ui.row(this);
            row.addView(Ui.text(this, m.name, 14, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1));
            final int mi = hueList.indexOf(m);
            row.addView(linkIcon(true, () -> {
                m.group = "";
                saveHue(false);
                rebuildHue();
            }));
            row.addView(iconGear(() -> startActivityForResult(new Intent(this, HueSetupActivity.class)
                    .putExtra(HueSetupActivity.EXTRA_INDEX, mi), REQ_SETUP)));
            Switch ms = new Switch(this);
            ms.setChecked(m.enabled);
            ms.setOnCheckedChangeListener((bv, ch) -> {
                m.enabled = ch;
                saveHue(true);
            });
            row.addView(ms);
            c[1].addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 4)));
        }
        return c[0];
    }

    /** The Hue screen: bridge and the lamps to add (like the Art-Net screen). */
    private void addHueLamps() {
        startActivityForResult(new Intent(this, HueSetupActivity.class), REQ_SETUP);
    }

    /**
     * Once: the lamps of the old entertainment area become lamp devices, with their places and
     * their colors from "Just lights".
     */
    private void migrateHue() {
        if (prefs.hueMigrated() || !prefs.isPaired()) return;
        if (!prefs.isConfigured() || !prefs.hueLamps().isEmpty()) {
            prefs.setHueMigrated();
            return;
        }
        final String ip = prefs.bridgeIp(), user = prefs.username(), area = prefs.areaId();
        new Thread(() -> {
            try {
                List<HueApi.Lamp> all = HueApi.listLamps(ip, user);
                List<HueApi.ChannelLight> chLights = HueApi.channelLights(ip, user, area);
                HueApi.Channels pos = HueApi.channels(ip, user, area);
                List<HueLamp> out = new ArrayList<>();
                java.util.Map<String, HueLamp> byLight = new java.util.LinkedHashMap<>();
                for (int i = 0; i < chLights.size(); i++) {
                    HueApi.ChannelLight cl = chLights.get(i);
                    if (cl.lightId == null) continue;
                    float x = i < pos.x.length ? pos.x[i] : 0f, y = i < pos.y.length ? pos.y[i] : 0f, z = i < pos.z.length ? pos.z[i] : 0f;
                    HueLamp h = byLight.get(cl.lightId);
                    if (h == null) {
                        h = new HueLamp();
                        h.lightId = cl.lightId;
                        h.name = cl.name.replaceAll(" \\(\\d+/\\d+\\)$", "");
                        h.x = h.x2 = x;
                        h.y = h.y2 = y;
                        h.z = z;
                        for (HueApi.Lamp l : all) {
                            if (cl.lightId.equals(l.lightId)) {
                                h.entService = l.entService;
                                h.segments = l.segments;
                            }
                        }
                        Integer col = prefs.lightColors.get(cl.channel);
                        if (col != null) h.ambientColor = col;
                        byLight.put(cl.lightId, h);
                    } else {
                        h.x2 = x; // gradient lights: the line runs to their last channel
                        h.y2 = y;
                    }
                }
                for (HueLamp h : byLight.values()) if (!h.entService.isEmpty()) out.add(h);
                main.post(() -> {
                    if (prefs.hueLamps().isEmpty()) {
                        hueList = out;
                        prefs.saveHueLamps(out);
                    }
                    prefs.setHueMigrated();
                    rebuildHue();
                });
            } catch (Exception ignored) {
                // try again next time
            }
        }, "hue-migrate").start();
    }

    // ---- strip groups

    private View stripGroupCard(String name, android.content.SharedPreferences ui) {
        List<Strip> members = new ArrayList<>();
        for (Strip x : stripList) if (name.equals(x.group)) members.add(x);
        Strip lead = members.get(0);
        boolean any = false;
        for (Strip m : members) any |= m.enabled;
        TextView[] title = new TextView[1];
        LinearLayout[] c = shell(name + "\n" + members.size() + " strips · " + stripTitle(lead).split("\n")[1].replaceAll("^\\d+ LEDs · ", ""),
                "open_stripgroup_" + name, () -> groupDialog(name, n -> {
                    for (Strip x : stripList) if (name.equals(x.group)) x.group = n;
                    saveStrips();
                    rebuildStrips();
                }, () -> {
                    for (Strip x : stripList) if (name.equals(x.group)) x.group = "";
                    saveStrips();
                    rebuildStrips();
                }), any, (bv, checked) -> {
                    for (Strip m : members) {
                        m.enabled = checked;
                        switchStrip(m, checked);
                    }
                    saveStrips();
                    refresh();
                }, title);
        buildStripBody(c[1], lead, title[0]);
        c[1].addView(section("Members"));
        for (Strip m : members) {
            final int mi = stripList.indexOf(m);
            LinearLayout row = Ui.row(this);
            row.addView(Ui.text(this, m.name + " · " + m.leds + " LEDs", 14, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1));
            row.addView(linkIcon(true, () -> {
                m.group = "";
                saveStrips();
                rebuildStrips();
            }));
            TextView mg = Ui.text(this, "⚙", 18, Ui.SUB, false);
            mg.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
            mg.setOnClickListener(v -> startActivityForResult(new Intent(this, StripSetupActivity.class)
                    .putExtra(StripSetupActivity.EXTRA_INDEX, mi), REQ_STRIP));
            row.addView(mg);
            Switch ms = new Switch(this);
            ms.setChecked(m.enabled);
            ms.setOnCheckedChangeListener((bv, ch) -> {
                m.enabled = ch;
                saveStrips();
                switchStrip(m, ch);
                refresh();
            });
            row.addView(ms);
            c[1].addView(row, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 4)));
        }
        return c[0];
    }

    /** The look controls of a strip card; in a group card they set every member. */
    private void buildStripBody(LinearLayout body, Strip st, TextView t) {
        final boolean amb = ambientMode();
        if (amb) {
            body.addView(Ui.label(this, "Animation"));
            TextView animBtn = pickerButton(stripAmbientName(st) + "  ▸");
            animBtn.setOnClickListener(v -> Pickers.effect(this, "Animation", withSame(Ambient.NAMES), withSameCat(Ambient.CATEGORIES),
                    st.ambientEffect + 1, k -> {
                        st.ambientEffect = k - 1;
                        saveStripsFrom(st);
                        animBtn.setText(stripAmbientName(st) + "  ▸");
                        t.setText(stripTitle(st));
                    }));
            body.addView(animBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
            TextView colBtn = pickerButton("");
            Runnable showCol = () -> {
                colBtn.setText(st.ambientColor < 0 ? "Strip color: global" : "Strip color");
                setDot(colBtn, st.ambientColor < 0 ? prefs.ambient.color : st.ambientColor);
            };
            showCol.run();
            colBtn.setOnClickListener(v -> Pickers.color(this, "Strip color", st.ambientColor, c -> {
                st.ambientColor = c;
                saveStripsFrom(st);
                showCol.run();
            }, () -> {
                st.ambientColor = -1;
                saveStripsFrom(st);
                showCol.run();
            }));
            body.addView(colBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        } else {
            body.addView(Ui.label(this, "Effect"));
            LinearLayout variantBox = Ui.column(this);
            TextView fxBtn = pickerButton(StripEffect.NAMES[Math.min(st.effect, StripEffect.NAMES.length - 1)] + "  ▸");
            fxBtn.setOnClickListener(v -> Pickers.effect(this, "Effect", StripEffect.NAMES, StripEffect.CATEGORIES, st.effect, k -> {
                st.effect = k;
                st.variant = 0;
                saveStripsFrom(st);
                rebuildStrips();
            }));
            body.addView(fxBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
            String[] vars = StripEffect.variants(st.effect);
            if (vars != null && vars.length > 1) {
                variantBox.addView(Ui.label(this, "Variant"));
                variantBox.addView(Ui.chipRow(this, vars, () -> st.variant, v -> {
                    st.variant = v;
                    saveStripsFrom(st);
                }));
            }
            body.addView(variantBox);
        }
        body.addView(Ui.label(this, "Speed"));
        body.addView(Ui.slider(this, st.speed, v -> {
            st.speed = v;
            saveStripsFrom(st);
        }));
        body.addView(Ui.label(this, "Blur"));
        body.addView(Ui.slider(this, st.blur, v -> {
            st.blur = v;
            saveStripsFrom(st);
        }));
        body.addView(Ui.label(this, "Colors"));
        body.addView(paletteRow(true, amb ? prefs.ambient.palette : prefs.light.palette, () -> st.palette, v -> {
            st.palette = v;
            saveStripsFrom(st);
        }));
        body.addView(Ui.label(this, "Brightness"));
        android.widget.SeekBar ownBright = Ui.slider(this, st.brightness, v -> {
            st.brightness = Math.max(0.03f, v);
            saveStripsFrom(st);
        });
        TextView brightHint = Ui.text(this, "", 13, Ui.SUB, false);
        Runnable showBright = () -> {
            ownBright.setVisibility(st.brightnessMode == app.huebeat.core.StripOutput.BRIGHT_LIGHTS ? View.GONE : View.VISIBLE);
            String lightsSlider = amb ? "the Brightness slider under Lights" : "Max brightness under Look";
            brightHint.setText(st.brightnessMode == app.huebeat.core.StripOutput.BRIGHT_OWN
                    ? "Only this slider sets the strip's brightness."
                    : st.brightnessMode == app.huebeat.core.StripOutput.BRIGHT_LIGHTS
                    ? "The strip follows " + lightsSlider + "."
                    : "This slider and " + lightsSlider + " both dim the strip.");
        };
        body.addView(Ui.chipRow(this, new String[]{"Own + lights", "Own only", "Follow lights"}, () -> st.brightnessMode, v -> {
            st.brightnessMode = v;
            saveStripsFrom(st);
            showBright.run();
        }));
        body.addView(brightHint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, 0));
        body.addView(ownBright);
        showBright.run();

        if (!amb) {
        body.addView(Ui.label(this, "Strobe overlay"));
        TextView sh = Ui.text(this, "Flashes in its own color on top of the effect, which keeps showing the music underneath.", 13, Ui.SUB, false);
        body.addView(sh, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
        LinearLayout strobeBox = Ui.column(this);
        body.addView(Ui.chipRow(this, StripEffect.STROBE_TRIGGERS, () -> st.strobeTrigger, v -> {
            st.strobeTrigger = v;
            saveStripsFrom(st);
            strobeBox.setVisibility(v == StripEffect.STROBE_OFF ? View.GONE : View.VISIBLE);
        }));
        strobeBox.addView(Ui.label(this, "Strobe color"));
        android.graphics.drawable.Drawable[] strobeIcons = new android.graphics.drawable.Drawable[STROBE_COLORS.length];
        for (int k = 0; k < STROBE_COLORS.length; k++) strobeIcons[k] = Pickers.dot(this, STROBE_COLORS[k], 14);
        strobeBox.addView(Ui.chipRow(this, STROBE_COLOR_NAMES, strobeIcons, () -> strobeColorIndex(st.strobeColor), v -> {
            st.strobeColor = STROBE_COLORS[v];
            saveStripsFrom(st);
        }));
        strobeBox.addView(Ui.label(this, "Flash length"));
        strobeBox.addView(Ui.slider(this, st.strobeLength, v -> {
            st.strobeLength = v;
            saveStripsFrom(st);
        }));
        strobeBox.addView(Ui.label(this, "Flash strength"));
        strobeBox.addView(Ui.slider(this, st.strobeAmount, v -> {
            st.strobeAmount = Math.max(0.1f, v);
            saveStripsFrom(st);
        }));
        strobeBox.addView(Ui.label(this, "Where it flashes"));
        strobeBox.addView(Ui.chipRow(this, StripEffect.STROBE_COVERS, () -> st.strobeCover, v -> {
            st.strobeCover = v;
            saveStripsFrom(st);
        }));
        TextView warn = Ui.text(this, "Fast flashing lights can trigger seizures in people with photosensitive epilepsy.", 12, Ui.SUB, false);
        strobeBox.addView(warn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        strobeBox.setVisibility(st.strobeTrigger == StripEffect.STROBE_OFF ? View.GONE : View.VISIBLE);
        body.addView(strobeBox);
        }
    }

    /** Saves the strips; a grouped strip passes its look on to the rest of its group. */
    private void saveStripsFrom(Strip st) {
        if (!st.group.isEmpty()) {
            for (Strip o : stripList) if (o != st && st.group.equals(o.group)) o.copyLookFrom(st);
        }
        saveStrips();
    }

    private void rebuildStrips() {
        if (stripsBox == null) return;
        stripsBox.removeAllViews();
        stripList = prefs.strips();
        android.content.SharedPreferences ui = getSharedPreferences("huebeat_ui", MODE_PRIVATE);
        java.util.Set<String> shownStripGroups = new java.util.HashSet<>();
        if (!stripList.isEmpty()) stripsBox.addView(section("LED strips"));
        for (int i = 0; i < stripList.size(); i++) {
            final int idx = i;
            final Strip st = stripList.get(i);
            if (!st.group.isEmpty()) {
                if (shownStripGroups.add(st.group)) stripsBox.addView(stripGroupCard(st.group, ui), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
                continue;
            }
            LinearLayout card = Ui.column(this);
            card.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 16)));
            int p = Ui.dp(this, 14);
            card.setPadding(p, Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));

            // header: name, on/off, settings
            LinearLayout head = Ui.row(this);
            final String key = "open_strip_" + st.ip + "_" + idx;
            boolean open = ui.getBoolean(key, false);
            TextView chev = Ui.text(this, open ? "▾" : "▸", 18, Ui.SUB, true);
            chev.setPadding(0, 0, Ui.dp(this, 10), 0);
            head.addView(chev);
            TextView t = Ui.text(this, stripTitle(st), 15, Ui.TEXT, true);
            t.setLineSpacing(0, 1.15f);
            head.addView(t, Ui.lp(0, Ui.WRAP, 1));
            TextView gear = Ui.text(this, "⚙", 20, Ui.SUB, false);
            gear.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            gear.setContentDescription("Connection settings");
            head.addView(linkIcon(false, () -> linkStrip(st)));
            gear.setOnClickListener(v -> startActivityForResult(new Intent(this, StripSetupActivity.class)
                    .putExtra(StripSetupActivity.EXTRA_INDEX, idx), REQ_STRIP));
            head.addView(gear);
            Switch on = new Switch(this);
            on.setChecked(st.enabled);
            on.setOnCheckedChangeListener((bv, checked) -> {
                st.enabled = checked;
                saveStrips();
                refresh();
                switchStrip(st, checked);
            });
            head.addView(on);
            card.addView(head);

            // body: everything visual, changes apply live
            LinearLayout body = Ui.column(this);
            body.setPadding(0, 0, Ui.dp(this, 6), Ui.dp(this, 4));
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            head.setClickable(true);
            head.setOnClickListener(v -> {
                boolean o = body.getVisibility() != View.VISIBLE;
                body.setVisibility(o ? View.VISIBLE : View.GONE);
                chev.setText(o ? "▾" : "▸");
                ui.edit().putBoolean(key, o).apply();
            });
            buildStripBody(body, st, t);
            card.addView(body);

            stripsBox.addView(card, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
        }
    }

    /** Turning a strip off in the app turns the LEDs off too; turning it on wakes a WLED controller. */
    private void switchStrip(Strip st, boolean on) {
        if (st.ip.isEmpty()) return;
        new Thread(() -> {
            try {
                if (!on) {
                    Thread.sleep(250); // let a running sync drop the strip first
                    try (java.net.DatagramSocket sock = new java.net.DatagramSocket()) {
                        app.huebeat.core.StripOutput so = st.output();
                        float[] black = so.fill(new float[3]);
                        for (int k = 0; k < 3; k++) {
                            so.send(sock, black);
                            Thread.sleep(40);
                        }
                    } catch (Exception ignored) {
                    }
                }
                WledApi.setOn(st.ip, on);
            } catch (Exception ignored) {
                // not a WLED controller (plain DDP/E1.31 firmware): the black frame is all we can do
            }
        }, "strip-switch").start();
    }

    // ------------------------------------------------------------------ DMX lights

    /** The look controls of a DMX light card; for a group ({@code group}) they set every member. */
    private void buildDmxBody(LinearLayout body, DmxLight d, boolean group) {
        final boolean amb = ambientMode();
        final boolean colors = app.huebeat.core.Dmx.hasColor(d.roles);
        if (amb) {
            body.addView(Ui.label(this, "Animation"));
            TextView animBtn = pickerButton(dmxAmbientName(d) + "  ▸");
            animBtn.setOnClickListener(v -> Pickers.effect(this, "Animation", withSame(Ambient.NAMES), withSameCat(Ambient.CATEGORIES),
                    d.ambientEffect + 1, k -> {
                        d.ambientEffect = k - 1;
                        saveDmxFrom(d);
                        animBtn.setText(dmxAmbientName(d) + "  ▸");
                    }));
            body.addView(animBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
            if (colors) {
                TextView colBtn = pickerButton("");
                Runnable showCol = () -> {
                    colBtn.setText(d.ambientColor < 0 ? "Light color: global" : "Light color");
                    setDot(colBtn, d.ambientColor < 0 ? prefs.ambient.color : d.ambientColor);
                };
                showCol.run();
                colBtn.setOnClickListener(v -> Pickers.color(this, "Light color", d.ambientColor, c -> {
                    d.ambientColor = c;
                    saveDmxFrom(d);
                    showCol.run();
                }, () -> {
                    d.ambientColor = -1;
                    saveDmxFrom(d);
                    showCol.run();
                }));
                body.addView(colBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
            }
        } else {
            body.addView(Ui.label(this, "Effect"));
            LinearLayout colorBox = Ui.column(this);
            TextView fxBtn = pickerButton(app.huebeat.core.Dmx.EFFECTS[d.effect] + "  ▸");
            body.addView(fxBtn, Ui.lp(Ui.MATCH, Ui.WRAP));
            body.addView(colorBox);
            Runnable showColor = () -> {
                colorBox.removeAllViews();
                boolean solid = d.effect == app.huebeat.core.Dmx.FX_SOLID || d.effect == app.huebeat.core.Dmx.FX_SOLID_PULSE;
                if (!solid || !colors) return;
                TextView colBtn = pickerButton("Light color");
                setDot(colBtn, d.color);
                colBtn.setOnClickListener(v -> Pickers.color(this, "Light color", d.color, c -> {
                    d.color = c;
                    saveDmxFrom(d);
                    setDot(colBtn, c);
                }));
                colorBox.addView(colBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
            };
            fxBtn.setOnClickListener(v -> Pickers.effect(this, "Effect", app.huebeat.core.Dmx.EFFECTS,
                    app.huebeat.core.Dmx.EFFECT_CATEGORIES, d.effect, k -> {
                        d.effect = k;
                        saveDmxFrom(d);
                        fxBtn.setText(app.huebeat.core.Dmx.EFFECTS[k] + "  ▸");
                        showColor.run();
                    }));
            showColor.run();
        }
        if (colors) {
            body.addView(Ui.label(this, "Colors"));
            body.addView(paletteRow(true, amb ? prefs.ambient.palette : prefs.light.palette, () -> d.palette, v -> {
                d.palette = v;
                saveDmxFrom(d);
            }));
        }
        body.addView(Ui.label(this, "Brightness"));
        body.addView(Ui.slider(this, d.brightness, v -> {
            d.brightness = Math.max(0.02f, v);
            saveDmxFrom(d);
        }));
        if (app.huebeat.core.Dmx.moves(d.roles)) {
            body.addView(Ui.label(this, "Movement"));
            body.addView(Ui.chipRow(this, app.huebeat.core.Dmx.MOVES, () -> d.movement, v -> {
                d.movement = v;
                saveDmxFrom(d);
            }));
            body.addView(Ui.label(this, "Movement size (0 = stays on the aim)"));
            body.addView(Ui.slider(this, d.move, v -> {
                d.move = v;
                saveDmxFrom(d);
            }));
            body.addView(Ui.label(this, amb ? "Movement speed" : "Movement speed (middle = one cycle per bar)"));
            body.addView(Ui.slider(this, d.moveSpeed, v -> {
                d.moveSpeed = v;
                saveDmxFrom(d);
            }));
            if (!group) {
                body.addView(Ui.label(this, "Aim: pan"));
                body.addView(Ui.slider(this, d.panCenter, v -> {
                    d.panCenter = v;
                    saveDmxFrom(d);
                }));
                body.addView(Ui.label(this, "Aim: tilt"));
                body.addView(Ui.slider(this, d.tiltCenter, v -> {
                    d.tiltCenter = v;
                    saveDmxFrom(d);
                }));
            } else {
                body.addView(Ui.label(this, "Together"));
                body.addView(Ui.chipRow(this, new String[]{"Same moves", "Mirrored", "Chase"}, () -> d.together, v -> {
                    d.together = v;
                    saveDmxFrom(d);
                }));
            }
        }
        if (app.huebeat.core.Dmx.hasGobo(d.roles)) {
            body.addView(Ui.label(this, "Gobo"));
            body.addView(Ui.chipRow(this, app.huebeat.core.Dmx.GOBOS, () -> d.gobo, v -> {
                d.gobo = v;
                saveDmxFrom(d);
            }));
        }
        if (app.huebeat.core.Dmx.hasStrobe(d.roles)) {
            body.addView(Ui.label(this, "Built-in strobe"));
            body.addView(Ui.chipRow(this, app.huebeat.core.Dmx.STROBE_MODES, () -> d.strobeMode, v -> {
                d.strobeMode = v;
                saveDmxFrom(d);
            }));
        }
    }

    /** A group's card: one set of look controls for all members, then each member's place and aim. */
    private View groupCard(String name, android.content.SharedPreferences ui) {
        List<DmxLight> members = new ArrayList<>();
        for (DmxLight x : dmxList) if (name.equals(x.group)) members.add(x);
        final DmxLight lead = members.get(0);
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 16)));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        LinearLayout head = Ui.row(this);
        final String key = "open_dmxgroup_" + name;
        boolean open = ui.getBoolean(key, false);
        TextView chev = Ui.text(this, open ? "▾" : "▸", 18, Ui.SUB, true);
        chev.setPadding(0, 0, Ui.dp(this, 10), 0);
        head.addView(chev);
        String what = ambientMode() ? dmxAmbientName(lead) : app.huebeat.core.Dmx.EFFECTS[lead.effect];
        TextView t = Ui.text(this, name + "\n" + members.size() + " lights · " + what, 15, Ui.TEXT, true);
        t.setLineSpacing(0, 1.15f);
        head.addView(t, Ui.lp(0, Ui.WRAP, 1));
        TextView gear = Ui.text(this, "⚙", 20, Ui.SUB, false);
        gear.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        gear.setContentDescription("Group settings");
        gear.setOnClickListener(v -> groupSettings(name));
        head.addView(gear);
        Switch on = new Switch(this);
        boolean any = false;
        for (DmxLight m : members) any |= m.enabled;
        on.setChecked(any);
        on.setOnCheckedChangeListener((bv, checked) -> {
            for (DmxLight m : members) m.enabled = checked;
            prefs.saveDmx(dmxList);
            refresh();
        });
        head.addView(on);
        card.addView(head);

        LinearLayout body = Ui.column(this);
        body.setPadding(0, 0, Ui.dp(this, 6), Ui.dp(this, 4));
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        head.setClickable(true);
        head.setOnClickListener(v -> {
            boolean o = body.getVisibility() != View.VISIBLE;
            body.setVisibility(o ? View.VISIBLE : View.GONE);
            chev.setText(o ? "▾" : "▸");
            ui.edit().putBoolean(key, o).apply();
        });
        buildDmxBody(body, lead, true);

        body.addView(section("Members"));
        boolean moves = app.huebeat.core.Dmx.moves(lead.roles);
        for (DmxLight m : members) {
            final int mi = dmxList.indexOf(m);
            LinearLayout mc = Ui.column(this);
            mc.setBackground(Ui.rounded(Ui.CARD_2, Ui.dp(this, 12)));
            mc.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            LinearLayout mh = Ui.row(this);
            mh.addView(Ui.text(this, m.name + " · Ch " + m.address + "–" + m.lastChannel(), 14, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1));
            mh.addView(linkIcon(true, () -> {
                m.group = "";
                prefs.saveDmx(dmxList);
                rebuildDmx();
            }));
            TextView mg = Ui.text(this, "⚙", 18, Ui.SUB, false);
            mg.setPadding(Ui.dp(this, 10), Ui.dp(this, 2), Ui.dp(this, 10), Ui.dp(this, 2));
            mg.setOnClickListener(v -> startActivityForResult(new Intent(this, DmxSetupActivity.class)
                    .putExtra(DmxSetupActivity.EXTRA_INDEX, mi), REQ_DMX));
            mh.addView(mg);
            Switch ms = new Switch(this);
            ms.setChecked(m.enabled);
            ms.setOnCheckedChangeListener((bv, c) -> {
                m.enabled = c;
                prefs.saveDmx(dmxList);
                refresh();
            });
            mh.addView(ms);
            mc.addView(mh);
            if (moves) {
                mc.addView(Ui.label(this, "Aim: pan"));
                mc.addView(Ui.slider(this, m.panCenter, v -> {
                    m.panCenter = v;
                    prefs.saveDmx(dmxList);
                }));
                mc.addView(Ui.label(this, "Aim: tilt"));
                mc.addView(Ui.slider(this, m.tiltCenter, v -> {
                    m.tiltCenter = v;
                    prefs.saveDmx(dmxList);
                }));
            }
            body.addView(mc, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
        }
        card.addView(body);
        return card;
    }

    private void groupSettings(String name) {
        android.widget.EditText field = new android.widget.EditText(this);
        field.setText(name);
        field.setSingleLine(true);
        field.setTextColor(Ui.TEXT);
        LinearLayout box = Ui.column(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(field);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Group")
                .setView(box)
                .setPositiveButton("Rename", (dlg, w) -> {
                    String n = field.getText().toString().trim();
                    if (n.isEmpty()) return;
                    for (DmxLight x : dmxList) if (name.equals(x.group)) x.group = n;
                    prefs.saveDmx(dmxList);
                    rebuildDmx();
                })
                .setNeutralButton("Ungroup", (dlg, w) -> {
                    for (DmxLight x : dmxList) if (name.equals(x.group)) x.group = "";
                    prefs.saveDmx(dmxList);
                    rebuildDmx();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Saves the DMX lights; a grouped light passes its shared look on to the rest of its group. */
    private void saveDmxFrom(DmxLight d) {
        if (!d.group.isEmpty()) {
            for (DmxLight o : dmxList) if (o != d && d.group.equals(o.group)) o.copyLookFrom(d);
        }
        prefs.saveDmx(dmxList);
    }

    private void rebuildDmx() {
        if (dmxBox == null) return;
        dmxBox.removeAllViews();
        dmxList = prefs.dmxLights();
        android.content.SharedPreferences ui = getSharedPreferences("huebeat_ui", MODE_PRIVATE);
        java.util.Set<String> shownGroups = new java.util.HashSet<>();
        if (!dmxList.isEmpty()) dmxBox.addView(section("DMX lights"));
        for (int i = 0; i < dmxList.size(); i++) {
            final int idx = i;
            final DmxLight d = dmxList.get(i);
            if (!d.group.isEmpty()) {
                if (shownGroups.add(d.group)) dmxBox.addView(groupCard(d.group, ui), Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
                continue;
            }
            LinearLayout card = Ui.column(this);
            card.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 16)));
            card.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));

            LinearLayout head = Ui.row(this);
            final String key = "open_dmx_" + d.ip + "_" + d.address + "_" + idx;
            boolean open = ui.getBoolean(key, false);
            TextView chev = Ui.text(this, open ? "▾" : "▸", 18, Ui.SUB, true);
            chev.setPadding(0, 0, Ui.dp(this, 10), 0);
            head.addView(chev);
            String what = ambientMode() ? dmxAmbientName(d) : app.huebeat.core.Dmx.EFFECTS[d.effect];
            TextView t = Ui.text(this, d.name + "\n" + what + " · Ch " + d.address + "–" + d.lastChannel(), 15, Ui.TEXT, true);
            t.setLineSpacing(0, 1.15f);
            head.addView(t, Ui.lp(0, Ui.WRAP, 1));
            TextView gear = Ui.text(this, "⚙", 20, Ui.SUB, false);
            gear.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            gear.setContentDescription("Light settings");
            head.addView(linkIcon(false, () -> linkDmx(d)));
            gear.setOnClickListener(v -> startActivityForResult(new Intent(this, DmxSetupActivity.class)
                    .putExtra(DmxSetupActivity.EXTRA_INDEX, idx), REQ_DMX));
            head.addView(gear);
            Switch on = new Switch(this);
            on.setChecked(d.enabled);
            on.setOnCheckedChangeListener((bv, checked) -> {
                d.enabled = checked;
                prefs.saveDmx(dmxList);
                refresh();
            });
            head.addView(on);
            card.addView(head);

            LinearLayout body = Ui.column(this);
            body.setPadding(0, 0, Ui.dp(this, 6), Ui.dp(this, 4));
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            head.setClickable(true);
            head.setOnClickListener(v -> {
                boolean o = body.getVisibility() != View.VISIBLE;
                body.setVisibility(o ? View.VISIBLE : View.GONE);
                chev.setText(o ? "▾" : "▸");
                ui.edit().putBoolean(key, o).apply();
            });
            buildDmxBody(body, d, false);
            card.addView(body);
            dmxBox.addView(card, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 10)));
        }
    }

    private String dmxAmbientName(DmxLight d) {
        return d.ambientEffect < 0 ? "Global" : Ambient.NAMES[Math.min(d.ambientEffect, Ambient.NAMES.length - 1)];
    }

    private String stripAmbientName(Strip st) {
        return st.ambientEffect < 0 ? "Global" : Ambient.NAMES[Math.min(st.ambientEffect, Ambient.NAMES.length - 1)];
    }

    private String stripTitle(Strip st) {
        String what = ambientMode()
                ? (st.ambientEffect < 0 ? Ambient.NAMES[prefs.ambient.animation] : stripAmbientName(st))
                : StripEffect.NAMES[Math.min(st.effect, StripEffect.NAMES.length - 1)];
        return st.name + "\n" + st.leds + " LEDs · " + what;
    }

    /** Outputs changed while syncing: restart so the engine picks them up. */
    private void restartIfRunning() {
        if (SyncState.isActive()) {
            SyncService.stop(this);
            main.postDelayed(this::startFlow, 700);
        }
    }

    private void rebuildPalettes() {
        palettesRow.removeAllViews();
        paletteChips.clear();
        screenChip = Ui.chip(this, "Screen");
        screenChip.setOnClickListener(v -> {
            prefs.light.screenColors = true;
            prefs.saveLight();
            ensureScreenCapture();
            refresh();
        });
        palettesRow.addView(screenChip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        TextView tvChip = Ui.chip(this, "TV camera");
        tvChip.setOnClickListener(v -> startActivity(new Intent(this, CameraActivity.class)));
        palettesRow.addView(tvChip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        for (int i = 0; i < Palettes.count(); i++) {
            final int p = i;
            TextView chip = Ui.chip(this, Palettes.name(i));
            chip.setCompoundDrawablePadding(Ui.dp(this, 8));
            chip.setCompoundDrawablesRelative(swatch(i), null, null, null);
            chip.setOnClickListener(v -> {
                prefs.light.palette = p;
                prefs.light.screenColors = false;
                prefs.saveLight();
                rebuildStrips();
                refresh();
            });
            if (Palettes.isCustom(i)) {
                chip.setOnLongClickListener(v -> {
                    editPalette(p, this::selectLightsPalette);
                    return true;
                });
            }
            paletteChips.add(chip);
            palettesRow.addView(chip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        }
        TextView add = Ui.chip(this, "+ New");
        add.setTextColor(Ui.ACCENT);
        add.setOnClickListener(v -> editPalette(-1, this::selectLightsPalette));
        palettesRow.addView(add, Ui.lp(Ui.WRAP, Ui.WRAP));
    }

    private void selectLightsPalette(int p) {
        prefs.light.palette = p;
        prefs.light.screenColors = false;
        prefs.saveLight();
    }

    /** Opens the palette editor; the saved palette is then picked through {@code target}. */
    private void editPalette(int index, Ui.IntSet target) {
        editingPalette = index;
        paletteTarget = target;
        Intent i = new Intent(this, PaletteEditorActivity.class);
        if (index >= 0) i.putExtra(PaletteEditorActivity.EXTRA_INDEX, index);
        startActivityForResult(i, REQ_EDIT_PALETTE);
    }

    /**
     * A palette row with color previews: custom palettes can be edited with a long press and
     * "+ New" makes one. With {@code withSame}, the first chip is "Global" (value -1).
     */
    private View paletteRow(boolean withSame, int samePalette, Ui.IntGet get, Ui.IntSet set) {
        final int off = withSame ? 1 : 0;
        String[] names = new String[Palettes.count() + off];
        if (withSame) names[0] = "Global";
        for (int k = 0; k < Palettes.count(); k++) names[k + off] = Palettes.name(k);
        android.graphics.drawable.Drawable[] icons = paletteSwatches(off);
        if (withSame) icons[0] = swatch(samePalette);
        android.view.ViewGroup hs = (android.view.ViewGroup) Ui.chipRow(this, names, icons, () -> get.get() + off, v -> set.set(v - off));
        LinearLayout row = (LinearLayout) hs.getChildAt(0);
        for (int k = 0; k < Palettes.count(); k++) {
            if (!Palettes.isCustom(k)) continue;
            final int pk = k;
            row.getChildAt(k + off).setOnLongClickListener(v -> {
                editPalette(pk, set);
                return true;
            });
        }
        TextView add = Ui.chip(this, "+ New");
        add.setTextColor(Ui.ACCENT);
        add.setOnClickListener(v -> editPalette(-1, set));
        row.addView(add, Ui.lp(Ui.WRAP, Ui.WRAP));
        return hs;
    }

    private void rebuildAmbientPalettes() {
        if (ambPaletteBox == null) return;
        ambPaletteBox.removeAllViews();
        Ambient.Settings a = prefs.ambient;
        ambPaletteBox.addView(paletteRow(false, 0, () -> a.palette, v -> {
            a.palette = v;
            prefs.saveAmbient();
            rebuildStrips();
        }));
    }

    /** A palette was deleted: everything pointing past it moves down one, anything using it falls back. */
    private void paletteDeleted(int d) {
        prefs.light.palette = shiftPalette(prefs.light.palette, d, 0);
        prefs.saveLight();
        prefs.ambient.palette = shiftPalette(prefs.ambient.palette, d, 0);
        prefs.saveAmbient();
        List<Strip> list = prefs.strips();
        for (Strip st : list) if (st.palette >= 0) st.palette = shiftPalette(st.palette, d, -1);
        prefs.saveStrips(list);
        List<HueLamp> hl = prefs.hueLamps();
        for (HueLamp x : hl) if (x.palette >= 0) x.palette = shiftPalette(x.palette, d, -1);
        prefs.saveHueLamps(hl);
        List<DmxLight> dl = prefs.dmxLights();
        for (DmxLight x : dl) if (x.palette >= 0) x.palette = shiftPalette(x.palette, d, -1);
        prefs.saveDmx(dl);
    }

    private static int shiftPalette(int p, int deleted, int fallback) {
        if (p == deleted) return fallback;
        return p > deleted ? p - 1 : p;
    }

    /** Swatches for every palette, shifted by {@code offset} empty slots in front. */
    private android.graphics.drawable.Drawable[] paletteSwatches(int offset) {
        android.graphics.drawable.Drawable[] d = new android.graphics.drawable.Drawable[Palettes.count() + offset];
        for (int k = 0; k < Palettes.count(); k++) d[k + offset] = swatch(k);
        return d;
    }

    private GradientDrawable swatch(int palette) {
        int[] colors = new int[5];
        float[] c = new float[3];
        for (int i = 0; i < colors.length; i++) {
            if (palette == 0) Palettes.hsv(i / 5f, 0.85f, 1f, c, 0);
            else Palettes.color(palette, i / 4f, 0f, 1f, c, 0);
            colors[i] = 0xFF000000 | (Math.round(c[0] * 255) << 16) | (Math.round(c[1] * 255) << 8) | Math.round(c[2] * 255);
        }
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        d.setShape(GradientDrawable.OVAL);
        int s = Ui.dp(this, 14);
        d.setBounds(0, 0, s, s);
        return d;
    }

    /** The phone plays along: starts listening if needed, then opens the full-screen show. */
    private void phoneShow() {
        SyncState.phoneShow = true;
        if (SyncState.status == SyncState.Status.RUNNING) {
            startActivity(new Intent(this, PhoneShowActivity.class));
            return;
        }
        pendingShow = true;
        if (!SyncState.isActive()) startFlow();
    }

    private void refresh() {
        if (pendingShow) {
            if (SyncState.status == SyncState.Status.RUNNING) {
                pendingShow = false;
                startActivity(new Intent(this, PhoneShowActivity.class));
            } else if (SyncState.status == SyncState.Status.ERROR) {
                pendingShow = false;
                SyncState.phoneShow = false;
            }
        }
        int mode = prefs.mode();
        boolean amb = ambientMode();
        for (int i = 0; i < appModeChips.size(); i++) Ui.setChipSelected(appModeChips.get(i), i == prefs.appMode());
        musicTop.setVisibility(amb ? View.GONE : View.VISIBLE);
        musicLook.setVisibility(amb ? View.GONE : View.VISIBLE);
        ambientLook.setVisibility(amb ? View.VISIBLE : View.GONE);

        for (int i = 0; i < modeChips.size(); i++) Ui.setChipSelected(modeChips.get(i), i == mode);
        for (int i = 0; i < styleChips.size(); i++) Ui.setChipSelected(styleChips.get(i), LightSettings.STYLE_ORDER[i] == prefs.light.style);
        boolean screenCols = prefs.light.screenColors;
        boolean screenStyle = prefs.light.style == LightSettings.STYLE_SCREEN;
        if (shuffleRow != null) shuffleRow.setVisibility(prefs.light.style == LightSettings.STYLE_SHUFFLE ? View.VISIBLE : View.GONE);
        Ui.setChipSelected(screenChip, screenCols || screenStyle);
        for (int i = 0; i < paletteChips.size(); i++) {
            Ui.setChipSelected(paletteChips.get(i), !screenCols && !screenStyle && i == prefs.light.palette);
        }
        appRowBox.setVisibility(mode == Prefs.MODE_APP ? View.VISIBLE : View.GONE);
        String label = prefs.appLabel();
        appRow.setText(label == null ? "Choose the app to follow" : "Following: " + label);
        strobeRow.setVisibility(View.GONE);
        delayLabel.setVisibility(amb || mode == Prefs.MODE_MIC ? View.GONE : View.VISIBLE);
        delayBar.setVisibility(amb || mode == Prefs.MODE_MIC ? View.GONE : View.VISIBLE);
        updateDelayLabel();

        meter.setVisibility(!amb && SyncState.status == SyncState.Status.RUNNING ? View.VISIBLE : View.INVISIBLE);
        meter.invalidate();
        switch (SyncState.status) {
            case RUNNING:
                status.setTextColor(Ui.TEXT);
                String st = amb ? "Lights on" : SyncState.bpm > 0 ? "Syncing · " + Math.round(SyncState.bpm) + " BPM" : "Syncing";
                if (!amb && prefs.light.style == LightSettings.STYLE_SHUFFLE && !SyncState.shuffle.isEmpty()) {
                    st += "\nShuffle: " + SyncState.shuffle;
                }
                if (!amb && prefs.light.style == LightSettings.STYLE_CHORDS) {
                    if (!SyncState.chord.isEmpty()) st += " · " + SyncState.chord;
                    else if (prefs.mode() != Prefs.MODE_MIC && SyncState.visualizer) st += " · no chords via visualizer";
                }
                status.setText(st);
                hint.setText(SyncState.hint);
                break;
            case STARTING:
                status.setTextColor(Ui.ACCENT);
                status.setText(SyncState.message.isEmpty() ? "Starting…" : SyncState.message);
                hint.setText("");
                break;
            case ERROR:
                status.setTextColor(Ui.ERROR);
                status.setText("Couldn't start");
                hint.setText(SyncState.message);
                break;
            default:
                status.setTextColor(Ui.SUB);
                status.setText(!prefs.hasOutputs() ? "Tap to add your lights"
                        : amb ? "Tap to turn your lights on" : "Tap to sync your lights");
                hint.setText("");
        }
        footer.setText(prefs.isPaired() ? prefs.bridgeName() : "No Hue bridge connected");
        if (SyncState.status == SyncState.Status.RUNNING && !amb) {
            // update BPM text a few times per second while running
            main.removeCallbacks(bpmTicker);
            main.postDelayed(bpmTicker, 500);
        }
    }

    private final Runnable bpmTicker = () -> {
        if (SyncState.status == SyncState.Status.RUNNING && !isFinishing()) refresh();
    };

    private boolean currentBluetooth() {
        if (SyncState.isActive()) return SyncState.bluetooth;
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                    || t == AudioDeviceInfo.TYPE_BLE_SPEAKER || t == AudioDeviceInfo.TYPE_HEARING_AID) return true;
        }
        return false;
    }

    private void updateDelayLabel() {
        boolean bt = currentBluetooth();
        int ms = bt ? prefs.delayBluetooth() : prefs.delaySpeaker();
        delayLabel.setText(("Light delay · " + (bt ? "Bluetooth" : "Phone speaker") + " · " + ms + " ms").toUpperCase());
        if (delayBar.getProgress() != ms / 10) delayBar.setProgress(ms / 10);
    }

    // ------------------------------------------------------------------ start / stop

    private void onModeSelected(int m) {
        if (m == prefs.mode()) {
            if (m == Prefs.MODE_APP) startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP);
            return;
        }
        boolean wasActive = SyncState.isActive();
        prefs.setMode(m);
        refresh();
        if (m == Prefs.MODE_APP && prefs.appPackage() == null) {
            pendingStart = wasActive;
            if (wasActive) SyncService.stop(this);
            startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP);
            return;
        }
        if (wasActive) {
            SyncService.stop(this);
            main.postDelayed(this::startFlow, 700);
        }
    }

    private void onPowerClicked() {
        if (SyncState.isActive()) {
            SyncService.stop(this);
            SyncState.set(SyncState.Status.OFF, "");
            return;
        }
        startFlow();
    }

    private void startFlow() {
        if (!prefs.hasOutputs() && !SyncState.phoneShow) {
            // nothing to light yet: Hue is the usual start; with other lights set up, one has to be on
            if (prefs.hueLamps().isEmpty() && prefs.strips().isEmpty() && prefs.dmxLights().isEmpty()) addDeviceDialog();
            else android.widget.Toast.makeText(this, "Switch on at least one light below", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (ambientMode()) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !askedNotif) {
                askedNotif = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
                return;
            }
            launchService(0, null);
            return;
        }
        if (prefs.mode() == Prefs.MODE_APP && prefs.appPackage() == null) {
            pendingStart = true;
            startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP);
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        if (prefs.mode() == Prefs.MODE_MIC && !screenNeeded()) {
            launchService(0, null);
        } else {
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent consent = Build.VERSION.SDK_INT >= 34
                    ? mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : mpm.createScreenCaptureIntent();
            startActivityForResult(consent, REQ_PROJECTION);
        }
    }

    private boolean screenNeeded() {
        return prefs.light.screenColors || prefs.light.style == LightSettings.STYLE_SCREEN;
    }

    /** Microphone sessions run without screen sharing; restart with it when screen colors are wanted. */
    private void ensureScreenCapture() {
        if (SyncState.isActive() && !sessionHasScreen) {
            SyncService.stop(this);
            main.postDelayed(this::startFlow, 700);
        }
    }

    private void launchService(int resultCode, Intent data) {
        SyncState.set(SyncState.Status.STARTING, "Starting…");
        Intent i = new Intent(this, SyncService.class)
                .setAction(SyncService.ACTION_START)
                .putExtra(SyncService.EXTRA_MODE, ambientMode() ? Prefs.MODE_AMBIENT : prefs.mode());
        if (data != null) {
            i.putExtra(SyncService.EXTRA_RESULT_CODE, resultCode);
            i.putExtra(SyncService.EXTRA_RESULT_DATA, data);
            i.putExtra(SyncService.EXTRA_SCREEN, true);
        }
        sessionHasScreen = data != null;
        try {
            startForegroundService(i);
        } catch (Exception e) {
            SyncState.set(SyncState.Status.ERROR, "Could not start: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_PERMS) return;
        if (ambientMode()) {
            startFlow();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startFlow();
        } else {
            SyncState.set(SyncState.Status.ERROR,
                    "LightBox needs the audio permission to hear the music. Android files phone-audio capture under it too.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) launchService(resultCode, data);
            else {
                SyncState.set(SyncState.Status.OFF, "");
                pendingShow = false;
                SyncState.phoneShow = false;
            }
        } else if (requestCode == REQ_PICK_APP) {
            refresh();
            if (pendingStart && resultCode == RESULT_OK) startFlow();
            pendingStart = false;
        } else if (requestCode == REQ_SETUP) {
            if (resultCode == RESULT_OK) {
                rebuildHue();
                restartIfRunning();
            }
            refresh();
        } else if (requestCode == REQ_ROOM) {
            rebuildHue();
            rebuildStrips();
            rebuildDmx();
        } else if (requestCode == REQ_DMX) {
            rebuildDmx();
            refresh();
        } else if (requestCode == REQ_STRIP) {
            rebuildStrips();
            refresh();
        } else if (requestCode == REQ_EDIT_PALETTE) {
            if (resultCode == RESULT_OK && data != null) {
                int idx = data.getIntExtra(PaletteEditorActivity.EXTRA_INDEX, -1);
                if (idx >= 0) {
                    if (paletteTarget != null) paletteTarget.set(idx); // pick it where it was made
                } else if (editingPalette >= 0) {
                    paletteDeleted(editingPalette);
                }
            }
            paletteTarget = null;
            editingPalette = -1;
            rebuildPalettes();
            rebuildAmbientPalettes();
            rebuildDmx();
            rebuildHue();
            rebuildStrips();
            refresh();
        }
    }
}
