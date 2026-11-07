package app.huebeat;

import android.app.AlertDialog;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Dialogs: a color picker and an effect list grouped by category. */
final class Pickers {
    private Pickers() {
    }

    interface ColorSet {
        void set(int rgb);
    }

    private static final int[] PRESETS = {0xFFB46B, 0xFF8A2A, 0xFFFFFF, 0xFF0000, 0xFF5000, 0xFFC800,
            0x30FF30, 0x00E5FF, 0x0040FF, 0x7A00FF, 0xFF00C8, 0xFF3070};

    /** A small round color dot (for buttons and rows). */
    static GradientDrawable dot(Activity a, int rgb, int sizeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFF000000 | rgb);
        d.setStroke(Ui.dp(a, 1), 0x55FFFFFF);
        int s = Ui.dp(a, sizeDp);
        d.setSize(s, s);
        d.setBounds(0, 0, s, s);
        return d;
    }

    /** Color picker: changes are shown live through {@code set}; Cancel puts the old color back. */
    static void color(Activity a, String title, int initial, ColorSet set) {
        color(a, title, initial, set, null);
    }

    /** With {@code same}, the dialog offers "Same as lights" (and {@code initial} < 0 means that is chosen). */
    static void color(Activity a, String title, int initial, ColorSet set, Runnable same) {
        final int start = initial;
        if (initial < 0) initial = 0xFF8A2A;
        float[] hsv = new float[3];
        Color.colorToHSV(0xFF000000 | initial, hsv);
        final float[] cur = hsv.clone();
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 20);
        box.setPadding(p, Ui.dp(a, 8), p, 0);

        View preview = new View(a);
        GradientDrawable pv = Ui.rounded(0xFF000000 | initial, Ui.dp(a, 14));
        preview.setBackground(pv);
        box.addView(preview, Ui.margins(Ui.lp(Ui.MATCH, Ui.dp(a, 56)), 0, 0, 0, Ui.dp(a, 8)));

        SeekBar[] bars = new SeekBar[3];
        Runnable apply = () -> {
            int c = Color.HSVToColor(cur) & 0xFFFFFF;
            pv.setColor(0xFF000000 | c);
            set.set(c);
            // saturation and brightness tracks follow the hue
            int pure = Color.HSVToColor(new float[]{cur[0], 1f, 1f});
            bars[1].setProgressDrawable(track(a, new int[]{0xFFFFFFFF, pure}));
            bars[2].setProgressDrawable(track(a, new int[]{0xFF000000, Color.HSVToColor(new float[]{cur[0], cur[1], 1f})}));
        };
        String[] labels = {"Hue", "Saturation", "Brightness"};
        int[] max = {360, 100, 100};
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            box.addView(Ui.label(a, labels[k]));
            SeekBar b = new SeekBar(a);
            b.setMax(max[k]);
            b.setProgress(Math.round(k == 0 ? cur[0] : cur[k] * 100));
            b.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFFFFFFF));
            b.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int v, boolean user) {
                    if (!user) return;
                    cur[kk] = kk == 0 ? v : v / 100f;
                    apply.run();
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                }
            });
            bars[k] = b;
            box.addView(b, Ui.lp(Ui.MATCH, Ui.dp(a, 32)));
        }
        int[] rainbow = new int[7];
        for (int i = 0; i < 7; i++) rainbow[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        bars[0].setProgressDrawable(track(a, rainbow));

        box.addView(Ui.label(a, "Quick picks"));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(a);
        for (int c : PRESETS) {
            View sw = new View(a);
            sw.setBackground(dot(a, c, 34));
            sw.setOnClickListener(v -> {
                Color.colorToHSV(0xFF000000 | c, cur);
                bars[0].setProgress(Math.round(cur[0]));
                bars[1].setProgress(Math.round(cur[1] * 100));
                bars[2].setProgress(Math.round(cur[2] * 100));
                apply.run();
            });
            row.addView(sw, Ui.margins(Ui.lp(Ui.dp(a, 34), Ui.dp(a, 34)), 0, 0, Ui.dp(a, 10), 0));
        }
        hs.addView(row);
        box.addView(hs, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(a, 8)));
        if (start >= 0 || same == null) apply.run();
        else {
            int pure = Color.HSVToColor(new float[]{cur[0], 1f, 1f});
            bars[1].setProgressDrawable(track(a, new int[]{0xFFFFFFFF, pure}));
            bars[2].setProgressDrawable(track(a, new int[]{0xFF000000, Color.HSVToColor(new float[]{cur[0], cur[1], 1f})}));
        }

        final int back = initial;
        Runnable revert = () -> {
            if (start < 0 && same != null) same.run();
            else set.set(back);
        };
        AlertDialog.Builder bd = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Done", null)
                .setNegativeButton("Cancel", (d, w) -> revert.run())
                .setOnCancelListener(d -> revert.run());
        if (same != null) bd.setNeutralButton("Same as lights", (d, w) -> same.run());
        bd.show();
    }

    private static GradientDrawable track(Activity a, int[] colors) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        g.setCornerRadius(Ui.dp(a, 6));
        g.setSize(Ui.dp(a, 200), Ui.dp(a, 12));
        return g;
    }

    /** A list of effects with category headers; tapping one picks it. */
    static void effect(Activity a, String title, String[] names, String[] categories, int selected, Ui.IntSet set) {
        ScrollView sc = new ScrollView(a);
        LinearLayout box = Ui.column(a);
        int p = Ui.dp(a, 16);
        box.setPadding(p, 0, p, p);
        sc.addView(box);
        AlertDialog[] dlg = new AlertDialog[1];
        String lastCat = null;
        for (int i = 0; i < names.length; i++) {
            if (names[i] == null) continue;
            String cat = categories != null && categories[i] != null ? categories[i] : "";
            if (!cat.equals(lastCat)) {
                box.addView(Ui.label(a, cat));
                lastCat = cat;
            }
            final int v = i;
            TextView t = Ui.text(a, names[i], 15, i == selected ? Ui.ACCENT : Ui.TEXT, i == selected);
            t.setPadding(Ui.dp(a, 12), Ui.dp(a, 11), Ui.dp(a, 12), Ui.dp(a, 11));
            Ui.clickable(t, i == selected ? Ui.CARD_2 : Ui.CARD, Ui.dp(a, 12));
            t.setOnClickListener(x -> {
                set.set(v);
                if (dlg[0] != null) dlg[0].dismiss();
            });
            box.addView(t, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, 0, 0, Ui.dp(a, 4)));
        }
        dlg[0] = new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setView(sc)
                .setNegativeButton("Close", null)
                .show();
    }
}
