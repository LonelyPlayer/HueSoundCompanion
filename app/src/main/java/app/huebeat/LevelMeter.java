package app.huebeat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin bar showing how loud the incoming audio is, so you can see whether sound arrives at all. */
public class LevelMeter extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float shown = 0f;

    public LevelMeter(Context c) {
        super(c);
        track.setColor(Ui.CARD_2);
        bar.setColor(Ui.ACCENT);
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth(), rad = h / 2f;
        boolean on = SyncState.status == SyncState.Status.RUNNING;
        float target = on ? SyncState.meter : 0f;
        shown += (target - shown) * (target > shown ? 0.6f : 0.15f);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, track);
        if (shown > 0.005f) {
            r.set(0, 0, Math.max(h, w * shown), h);
            c.drawRoundRect(r, rad, rad, bar);
        }
        if (on || shown > 0.005f) postInvalidateOnAnimation();
    }
}
