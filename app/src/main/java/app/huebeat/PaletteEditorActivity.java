package app.huebeat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.huebeat.core.Palettes;

/** Create or edit an own color palette (2–6 colors). */
public class PaletteEditorActivity extends Activity {
    public static final String EXTRA_INDEX = "paletteIndex";
    private static final int MAX_COLORS = 6, MIN_COLORS = 2;

    private Prefs prefs;
    private int editIndex = -1;
    private final List<float[]> hsv = new ArrayList<>(); // hue 0..360, sat 0..1, val 0..1
    private int selected = 0;

    private EditText name;
    private View preview;
    private LinearLayout swatches;
    private SeekBar hueBar, satBar, valBar;
    private TextView removeBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        editIndex = getIntent().getIntExtra(EXTRA_INDEX, -1);
        Palettes.Custom existing = editIndex >= 0 ? prefs.customAt(editIndex) : null;
        if (existing == null) editIndex = -1;
        int[] start = existing != null ? existing.colors : new int[]{0xFF2D95, 0xFF8A00, 0x7B2DFF};
        for (int c : start) {
            float[] h = new float[3];
            Color.colorToHSV(0xFF000000 | c, h);
            hsv.add(h);
        }
        buildUi(existing != null ? existing.name : "My palette " + (Palettes.count() - Palettes.BUILT_IN_COUNT + 1));
        select(0);
    }

    private void buildUi(String initialName) {
        int pad = Ui.dp(this, 20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 24));
        scroll.addView(root);

        root.addView(Ui.text(this, editIndex >= 0 ? "Edit palette" : "New palette", 24, Ui.TEXT, true));

        name = new EditText(this);
        name.setText(initialName);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setTextColor(Ui.TEXT);
        name.setHintTextColor(Ui.SUB);
        name.setHint("Name");
        name.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        root.addView(name, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 14), 0, Ui.dp(this, 14)));

        preview = new View(this);
        root.addView(preview, Ui.lp(Ui.MATCH, Ui.dp(this, 56)));

        root.addView(label("Colors · tap one to change it"));
        swatches = Ui.row(this);
        root.addView(swatches);

        root.addView(label("Hue"));
        hueBar = bar(360);
        hueBar.setProgressDrawable(track(new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}));
        root.addView(hueBar);
        root.addView(label("Saturation"));
        satBar = bar(100);
        root.addView(satBar);
        root.addView(label("Brightness"));
        valBar = bar(100);
        root.addView(valBar);

        removeBtn = Ui.button(this, "Remove this color", false);
        removeBtn.setOnClickListener(v -> {
            if (hsv.size() > MIN_COLORS) {
                hsv.remove(selected);
                select(Math.min(selected, hsv.size() - 1));
            }
        });
        root.addView(removeBtn, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 18), 0, 0));

        LinearLayout actions = Ui.row(this);
        TextView cancel = Ui.button(this, "Cancel", false);
        cancel.setOnClickListener(v -> finish());
        actions.addView(cancel, Ui.margins(Ui.lp(0, Ui.WRAP, 1), 0, 0, Ui.dp(this, 6), 0));
        TextView save = Ui.button(this, "Save", true);
        save.setOnClickListener(v -> save());
        actions.addView(save, Ui.margins(Ui.lp(0, Ui.WRAP, 1), Ui.dp(this, 6), 0, 0, 0));
        root.addView(actions, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 26), 0, 0));

        if (editIndex >= 0) {
            TextView delete = Ui.text(this, "Delete palette", 15, Ui.ERROR, true);
            delete.setGravity(Gravity.CENTER);
            delete.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage("Delete \"" + Palettes.name(editIndex) + "\"?")
                    .setPositiveButton("Delete", (d, w) -> {
                        prefs.deleteCustom(editIndex);
                        setResult(RESULT_OK, new Intent());
                        finish();
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
            root.addView(delete, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 10), 0, 0));
        }

        Ui.setupWindow(this, scroll);
    }

    private TextView label(String s) {
        TextView t = Ui.text(this, s.toUpperCase(), 12, Ui.SUB, true);
        t.setLetterSpacing(0.08f);
        t.setLayoutParams(Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 18), 0, Ui.dp(this, 8)));
        return t;
    }

    private SeekBar bar(int max) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || selected >= hsv.size()) return;
                float[] h = hsv.get(selected);
                h[0] = hueBar.getProgress();
                h[1] = satBar.getProgress() / 100f;
                h[2] = Math.max(0.15f, valBar.getProgress() / 100f);
                redraw();
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

    private void select(int i) {
        selected = i;
        float[] h = hsv.get(i);
        hueBar.setProgress(Math.round(h[0]));
        satBar.setProgress(Math.round(h[1] * 100));
        valBar.setProgress(Math.round(h[2] * 100));
        redraw();
    }

    /** A slider track showing the colors it picks from (the whole bar, not just the filled part). */
    private GradientDrawable track(int[] colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(Ui.dp(this, 6));
        g.setSize(Ui.dp(this, 200), Ui.dp(this, 12));
        return g;
    }

    /** Saturation and brightness tracks follow the selected color's hue. */
    private void updateTracks() {
        if (selected >= hsv.size()) return;
        float[] h = hsv.get(selected);
        satBar.setProgressDrawable(track(new int[]{0xFFFFFFFF, Color.HSVToColor(new float[]{h[0], 1f, 1f})}));
        valBar.setProgressDrawable(track(new int[]{0xFF000000, Color.HSVToColor(new float[]{h[0], h[1], 1f})}));
    }

    private void redraw() {
        updateTracks();
        int[] cols = new int[Math.max(2, hsv.size() + 1)];
        for (int i = 0; i < hsv.size(); i++) cols[i] = Color.HSVToColor(hsv.get(i));
        cols[cols.length - 1] = cols[0]; // palettes loop around
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, cols);
        g.setCornerRadius(Ui.dp(this, 14));
        preview.setBackground(g);

        swatches.removeAllViews();
        int size = Ui.dp(this, 44);
        for (int i = 0; i < hsv.size(); i++) {
            final int k = i;
            View v = new View(this);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(Color.HSVToColor(hsv.get(i)));
            if (i == selected) d.setStroke(Ui.dp(this, 3), Color.WHITE);
            v.setBackground(d);
            v.setOnClickListener(x -> select(k));
            swatches.addView(v, Ui.margins(Ui.lp(size, size), 0, 0, Ui.dp(this, 10), 0));
        }
        if (hsv.size() < MAX_COLORS) {
            TextView add = Ui.text(this, "+", 22, Ui.TEXT, true);
            add.setGravity(Gravity.CENTER);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(Ui.CARD_2);
            add.setBackground(d);
            add.setOnClickListener(x -> {
                float[] last = hsv.get(hsv.size() - 1);
                hsv.add(new float[]{(last[0] + 50) % 360, last[1], last[2]});
                select(hsv.size() - 1);
            });
            swatches.addView(add, Ui.lp(size, size));
        }
        removeBtn.setVisibility(hsv.size() > MIN_COLORS ? View.VISIBLE : View.GONE);
    }

    private void save() {
        String n = name.getText().toString().trim();
        if (n.isEmpty()) n = "My palette";
        if (n.length() > 20) n = n.substring(0, 20);
        int[] colors = new int[hsv.size()];
        for (int i = 0; i < colors.length; i++) colors[i] = Color.HSVToColor(hsv.get(i)) & 0xFFFFFF;
        int idx = prefs.saveCustom(editIndex, n, colors);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_INDEX, idx));
        finish();
    }
}
