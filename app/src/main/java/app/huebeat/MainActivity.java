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
    private LinearLayout stripsBox;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        buildUi();
        if (!prefs.hasOutputs() && b == null) {
            startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        TextView title = Ui.text(this, "HueSoundCompanion", 22, Ui.TEXT, true);
        header.addView(title, Ui.lp(0, Ui.WRAP, 1));
        TextView setup = Ui.text(this, "Setup", 14, Ui.SUB, true);
        setup.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        Ui.clickable(setup, Ui.CARD_2, Ui.dp(this, 16));
        setup.setOnClickListener(v -> startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP));
        header.addView(setup);
        root.addView(header, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, Ui.dp(this, 18)));

        // audio source
        root.addView(section("Listen to"));
        LinearLayout modes = Ui.row(this);
        String[] modeNames = {"Phone audio", "One app", "Microphone"};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView chip = Ui.chip(this, modeNames[i]);
            chip.setOnClickListener(v -> onModeSelected(m));
            modeChips.add(chip);
            modes.addView(chip, Ui.margins(Ui.lp(0, Ui.WRAP, 1), i == 0 ? 0 : Ui.dp(this, 4), 0, i == 2 ? 0 : Ui.dp(this, 4), 0));
        }
        root.addView(modes);

        appRowBox = Ui.row(this);
        appRowBox.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        appRowBox.setBackground(Ui.rounded(Ui.CARD, Ui.dp(this, 14)));
        appRow = Ui.text(this, "", 15, Ui.TEXT, false);
        appRowBox.addView(appRow, Ui.lp(0, Ui.WRAP, 1));
        TextView change = Ui.text(this, "Change", 14, Ui.ACCENT, true);
        appRowBox.addView(change);
        appRowBox.setOnClickListener(v -> startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP));
        root.addView(appRowBox, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));

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
        LinearLayout look = collapsible(root, "Look", "open_look", true);
        look.addView(section("Style"));
        HorizontalScrollView styleScroll = new HorizontalScrollView(this);
        styleScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout styles = Ui.row(this);
        for (int i = 0; i < LightSettings.STYLE_NAMES.length; i++) {
            final int s = i;
            TextView chip = Ui.chip(this, LightSettings.STYLE_NAMES[i]);
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
        look.addView(section("Max brightness"));
        look.addView(slider(Math.round(prefs.light.maxBrightness * 100), 100, v -> {
            prefs.light.maxBrightness = Math.max(0.05f, v / 100f);
            prefs.saveLight();
        }));

        strobeRow = Ui.row(this);
        TextView strobeText = Ui.text(this, "Strobe on snares (Party)", 15, Ui.TEXT, false);
        strobeRow.addView(strobeText, Ui.lp(0, Ui.WRAP, 1));
        Switch strobe = new Switch(this);
        strobe.setChecked(prefs.light.strobe);
        strobe.setOnCheckedChangeListener((CompoundButton bv, boolean on) -> {
            prefs.light.strobe = on;
            prefs.saveLight();
        });
        strobeRow.addView(strobe);
        look.addView(strobeRow, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 14), 0, 0));

        LinearLayout stripsSection = collapsible(root, "LED strips", "open_strips", true);
        stripsBox = Ui.column(this);
        stripsSection.addView(stripsBox);
        rebuildStrips();

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

        Ui.setupWindow(this, scroll);
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

    private void rebuildStrips() {
        stripsBox.removeAllViews();
        stripList = prefs.strips();
        android.content.SharedPreferences ui = getSharedPreferences("huebeat_ui", MODE_PRIVATE);
        for (int i = 0; i < stripList.size(); i++) {
            final int idx = i;
            final Strip st = stripList.get(i);
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
            TextView t = Ui.text(this, st.name + "\n" + st.leds + " LEDs · " + StripEffect.NAMES[Math.min(st.effect, StripEffect.NAMES.length - 1)], 15, Ui.TEXT, true);
            t.setLineSpacing(0, 1.15f);
            head.addView(t, Ui.lp(0, Ui.WRAP, 1));
            TextView gear = Ui.text(this, "⚙", 20, Ui.SUB, false);
            gear.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
            gear.setContentDescription("Connection settings");
            gear.setOnClickListener(v -> startActivityForResult(new Intent(this, StripSetupActivity.class)
                    .putExtra(StripSetupActivity.EXTRA_INDEX, idx), REQ_STRIP));
            head.addView(gear);
            Switch on = new Switch(this);
            on.setChecked(st.enabled);
            on.setOnCheckedChangeListener((bv, checked) -> {
                st.enabled = checked;
                saveStrips();
                refresh();
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
            body.addView(Ui.label(this, "Effect"));
            body.addView(Ui.chipRow(this, StripEffect.NAMES, () -> st.effect, v -> {
                st.effect = v;
                saveStrips();
                t.setText(st.name + "\n" + st.leds + " LEDs · " + StripEffect.NAMES[v]);
            }));
            body.addView(Ui.label(this, "Speed"));
            body.addView(Ui.slider(this, st.speed, v -> {
                st.speed = v;
                saveStrips();
            }));
            body.addView(Ui.label(this, "Colors"));
            String[] pal = new String[Palettes.count() + 1];
            pal[0] = "Same as lights";
            for (int k = 0; k < Palettes.count(); k++) pal[k + 1] = Palettes.name(k);
            body.addView(Ui.chipRow(this, pal, () -> st.palette + 1, v -> {
                st.palette = v - 1;
                saveStrips();
            }));
            body.addView(Ui.label(this, "Brightness"));
            body.addView(Ui.slider(this, st.brightness, v -> {
                st.brightness = Math.max(0.03f, v);
                saveStrips();
            }));

            body.addView(Ui.label(this, "Strobe overlay"));
            TextView sh = Ui.text(this, "Flashes in its own color on top of the effect, which keeps showing the music underneath.", 13, Ui.SUB, false);
            body.addView(sh, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(this, 8)));
            LinearLayout strobeBox = Ui.column(this);
            body.addView(Ui.chipRow(this, StripEffect.STROBE_TRIGGERS, () -> st.strobeTrigger, v -> {
                st.strobeTrigger = v;
                saveStrips();
                strobeBox.setVisibility(v == StripEffect.STROBE_OFF ? View.GONE : View.VISIBLE);
            }));
            strobeBox.addView(Ui.label(this, "Strobe color"));
            strobeBox.addView(Ui.chipRow(this, STROBE_COLOR_NAMES, () -> strobeColorIndex(st.strobeColor), v -> {
                st.strobeColor = STROBE_COLORS[v];
                saveStrips();
            }));
            strobeBox.addView(Ui.label(this, "Flash length"));
            strobeBox.addView(Ui.slider(this, st.strobeLength, v -> {
                st.strobeLength = v;
                saveStrips();
            }));
            strobeBox.addView(Ui.label(this, "Flash strength"));
            strobeBox.addView(Ui.slider(this, st.strobeAmount, v -> {
                st.strobeAmount = Math.max(0.1f, v);
                saveStrips();
            }));
            strobeBox.addView(Ui.label(this, "Where it flashes"));
            strobeBox.addView(Ui.chipRow(this, StripEffect.STROBE_COVERS, () -> st.strobeCover, v -> {
                st.strobeCover = v;
                saveStrips();
            }));
            TextView warn = Ui.text(this, "Fast flashing lights can trigger seizures in people with photosensitive epilepsy.", 12, Ui.SUB, false);
            strobeBox.addView(warn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
            strobeBox.setVisibility(st.strobeTrigger == StripEffect.STROBE_OFF ? View.GONE : View.VISIBLE);
            body.addView(strobeBox);
            card.addView(body);

            stripsBox.addView(card, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 8), 0, 0));
        }
        TextView add = Ui.button(this, "+ Add LED strip (WLED / ESP32)", false);
        add.setOnClickListener(v -> startActivityForResult(new Intent(this, StripSetupActivity.class), REQ_STRIP));
        stripsBox.addView(add, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
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
                refresh();
            });
            if (Palettes.isCustom(i)) {
                chip.setOnLongClickListener(v -> {
                    startActivityForResult(new Intent(this, PaletteEditorActivity.class)
                            .putExtra(PaletteEditorActivity.EXTRA_INDEX, p), REQ_EDIT_PALETTE);
                    return true;
                });
            }
            paletteChips.add(chip);
            palettesRow.addView(chip, Ui.margins(Ui.lp(Ui.WRAP, Ui.WRAP), 0, 0, Ui.dp(this, 8), 0));
        }
        TextView add = Ui.chip(this, "+ New");
        add.setTextColor(Ui.ACCENT);
        add.setOnClickListener(v -> startActivityForResult(new Intent(this, PaletteEditorActivity.class), REQ_EDIT_PALETTE));
        palettesRow.addView(add, Ui.lp(Ui.WRAP, Ui.WRAP));
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

    private void refresh() {
        int mode = prefs.mode();
        for (int i = 0; i < modeChips.size(); i++) Ui.setChipSelected(modeChips.get(i), i == mode);
        for (int i = 0; i < styleChips.size(); i++) Ui.setChipSelected(styleChips.get(i), i == prefs.light.style);
        boolean screenCols = prefs.light.screenColors;
        boolean screenStyle = prefs.light.style == LightSettings.STYLE_SCREEN;
        Ui.setChipSelected(screenChip, screenCols || screenStyle);
        for (int i = 0; i < paletteChips.size(); i++) {
            Ui.setChipSelected(paletteChips.get(i), !screenCols && !screenStyle && i == prefs.light.palette);
        }
        appRowBox.setVisibility(mode == Prefs.MODE_APP ? View.VISIBLE : View.GONE);
        String label = prefs.appLabel();
        appRow.setText(label == null ? "Choose the app to follow" : "Following: " + label);
        strobeRow.setVisibility(prefs.light.style == LightSettings.STYLE_PARTY ? View.VISIBLE : View.GONE);
        delayLabel.setVisibility(mode == Prefs.MODE_MIC ? View.GONE : View.VISIBLE);
        delayBar.setVisibility(mode == Prefs.MODE_MIC ? View.GONE : View.VISIBLE);
        updateDelayLabel();

        meter.setVisibility(SyncState.status == SyncState.Status.RUNNING ? View.VISIBLE : View.INVISIBLE);
        meter.invalidate();
        switch (SyncState.status) {
            case RUNNING:
                status.setTextColor(Ui.TEXT);
                status.setText(SyncState.bpm > 0 ? "Syncing · " + Math.round(SyncState.bpm) + " BPM" : "Syncing");
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
                status.setText(prefs.hasOutputs() ? "Tap to sync your lights" : "Tap to connect your Hue bridge");
                hint.setText("");
        }
        footer.setText(prefs.isConfigured() ? prefs.bridgeName() + " · " + prefs.areaName() : "No Hue bridge connected");
        if (SyncState.status == SyncState.Status.RUNNING) {
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
        if (!prefs.hasOutputs()) {
            startActivityForResult(new Intent(this, SetupActivity.class), REQ_SETUP);
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
                .putExtra(SyncService.EXTRA_MODE, prefs.mode());
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
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startFlow();
        } else {
            SyncState.set(SyncState.Status.ERROR,
                    "HueSoundCompanion needs the audio permission to hear the music. Android files phone-audio capture under it too.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) launchService(resultCode, data);
            else SyncState.set(SyncState.Status.OFF, "");
        } else if (requestCode == REQ_PICK_APP) {
            refresh();
            if (pendingStart && resultCode == RESULT_OK) startFlow();
            pendingStart = false;
        } else if (requestCode == REQ_SETUP) {
            refresh();
        } else if (requestCode == REQ_STRIP) {
            rebuildStrips();
            refresh();
        } else if (requestCode == REQ_EDIT_PALETTE) {
            if (resultCode == RESULT_OK && data != null) {
                int idx = data.getIntExtra(PaletteEditorActivity.EXTRA_INDEX, -1);
                if (idx >= 0) {
                    prefs.light.palette = idx;
                    prefs.saveLight();
                }
            }
            rebuildPalettes();
            refresh();
        }
    }
}
