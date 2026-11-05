package app.huebeat;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tiny helpers for building the UI in code (no XML layouts, no extra libraries). */
final class Ui {
    static final int BG = 0xFF0D0E12;
    static final int CARD = 0xFF17181E;
    static final int CARD_2 = 0xFF22232B;
    static final int LINE = 0xFF2E3039;
    static final int TEXT = 0xFFF3F3F6;
    static final int SUB = 0xFF9C9DA8;
    static final int ACCENT = 0xFFFFB443;
    static final int ERROR = 0xFFFF5A5F;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("deprecation")
    static void setupWindow(Activity a, View root) {
        Window w = a.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.getDecorView().setBackgroundColor(BG);
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(l + insets.getSystemWindowInsetLeft(), t + insets.getSystemWindowInsetTop(),
                    r + insets.getSystemWindowInsetRight(), b + insets.getSystemWindowInsetBottom());
            return insets;
        });
        a.setContentView(root);
        root.requestApplyInsets();
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable rounded(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static void clickable(View v, int bgColor, float radiusPx) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(bgColor, radiusPx), rounded(Color.WHITE, radiusPx)));
        v.setClickable(true);
        v.setFocusable(true);
    }

    /** A pill-shaped selectable chip. */
    static TextView chip(Context c, String label) {
        TextView t = text(c, label, 14, TEXT, true);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 14), v = dp(c, 9);
        t.setPadding(h, v, h, v);
        setChipSelected(t, false);
        return t;
    }

    static void setChipSelected(TextView t, boolean selected) {
        Context c = t.getContext();
        t.setSelected(selected);
        t.setTextColor(selected ? 0xFF1A1206 : TEXT);
        clickable(t, selected ? ACCENT : CARD_2, dp(c, 22));
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView t = text(c, label, 15, primary ? 0xFF1A1206 : TEXT, true);
        t.setGravity(Gravity.CENTER);
        int h = dp(c, 18), v = dp(c, 13);
        t.setPadding(h, v, h, v);
        clickable(t, primary ? ACCENT : CARD_2, dp(c, 14));
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams p, int l, int t, int r, int b) {
        p.setMargins(l, t, r, b);
        return p;
    }

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
}
