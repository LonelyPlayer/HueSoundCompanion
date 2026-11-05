package app.huebeat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

/** The big round on/off button. Glows in the current light color while syncing. */
public class PowerButton extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private float pressScale = 1f;
    private float shownR = 0.15f, shownG = 0.15f, shownB = 0.17f, shownMeter = 0f;

    public PowerButton(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Light sync on/off");
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) pressScale = 0.95f;
        if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) pressScale = 1f;
        invalidate();
        return super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) * 0.64f * pressScale;
        float density = getResources().getDisplayMetrics().density;
        SyncState.Status st = SyncState.status;
        boolean on = st == SyncState.Status.RUNNING;
        boolean starting = st == SyncState.Status.STARTING;
        long now = System.currentTimeMillis();

        // ease the displayed color toward the live light color
        float tr = 0.15f, tg = 0.15f, tb = 0.17f, tm = 0f;
        if (on) {
            float max = Math.max(0.001f, Math.max(SyncState.r, Math.max(SyncState.g, SyncState.b)));
            float k = 0.35f + 0.65f * Math.min(1f, max * 1.5f);
            tr = SyncState.r / max * k;
            tg = SyncState.g / max * k;
            tb = SyncState.b / max * k;
            tm = SyncState.meter;
        }
        shownR += (tr - shownR) * 0.35f;
        shownG += (tg - shownG) * 0.35f;
        shownB += (tb - shownB) * 0.35f;
        shownMeter += (tm - shownMeter) * 0.3f;
        int col = Color.rgb(clamp255(shownR), clamp255(shownG), clamp255(shownB));

        if (on) {
            float gr = r * (1.25f + 0.25f * shownMeter);
            glow.setShader(new RadialGradient(cx, cy, gr, new int[]{withAlpha(col, 150), withAlpha(col, 0)},
                    new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, gr, glow);
            fill.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.3f,
                    new int[]{lighten(col, 0.25f), col, darken(col, 0.45f)}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        } else {
            fill.setShader(new RadialGradient(cx, cy - r * 0.3f, r * 1.3f,
                    new int[]{0xFF2A2B33, 0xFF1C1D23, 0xFF141519}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        }
        c.drawCircle(cx, cy, r, fill);

        ring.setStrokeWidth(3 * density);
        if (starting) {
            ring.setColor(0x33FFFFFF);
            c.drawCircle(cx, cy, r, ring);
            ring.setColor(Ui.ACCENT);
            ring.setStrokeCap(Paint.Cap.ROUND);
            arc.set(cx - r, cy - r, cx + r, cy + r);
            c.drawArc(arc, (now % 1200) / 1200f * 360f, 90f, false, ring);
        } else {
            ring.setColor(on ? withAlpha(Color.WHITE, 90) : (st == SyncState.Status.ERROR ? Ui.ERROR : 0xFF34353F));
            c.drawCircle(cx, cy, r, ring);
        }

        // power icon
        float ir = r * 0.30f;
        icon.setStrokeWidth(Math.max(4 * density, r * 0.07f));
        icon.setColor(on ? (luma(col) > 0.6f ? 0xEE15161A : 0xF2FFFFFF) : (starting ? Ui.ACCENT : 0xFFB9BAC4));
        arc.set(cx - ir, cy - ir, cx + ir, cy + ir);
        c.drawArc(arc, -60f, 300f, false, icon);
        c.drawLine(cx, cy - ir * 1.25f, cx, cy - ir * 0.15f, icon);

        if (on || starting) postInvalidateOnAnimation();
    }

    private static int clamp255(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private static int lighten(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) + (255 - Color.red(c)) * f),
                Math.round(Color.green(c) + (255 - Color.green(c)) * f),
                Math.round(Color.blue(c) + (255 - Color.blue(c)) * f));
    }

    private static int darken(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * (1 - f)), Math.round(Color.green(c) * (1 - f)), Math.round(Color.blue(c) * (1 - f)));
    }

    private static float luma(int c) {
        return (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f;
    }
}
