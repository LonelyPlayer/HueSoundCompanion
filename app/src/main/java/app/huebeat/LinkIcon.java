package app.huebeat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small chain icon for device headers: two joined links (link), or two apart (unlink). */
final class LinkIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean broken;

    LinkIcon(Context c, boolean broken) {
        super(c);
        this.broken = broken;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Ui.SUB);
        paint.setStrokeWidth(Ui.dp(c, 1.8f));
        setContentDescription(broken ? "Remove from group" : "Group");
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float u = Ui.dp(getContext(), 1); // one dp
        float h = 3.2f * u;               // half the link height
        c.save();
        c.rotate(-45f, cx, cy);
        if (broken) {
            c.drawRoundRect(cx - 13.5f * u, cy - h, cx - 3f * u, cy + h, h, h, paint);
            c.drawRoundRect(cx + 3f * u, cy - h, cx + 13.5f * u, cy + h, h, h, paint);
            // little break marks across the gap
            c.drawLine(cx, cy - 7.5f * u, cx, cy - 5f * u, paint);
            c.drawLine(cx, cy + 5f * u, cx, cy + 7.5f * u, paint);
        } else {
            c.drawRoundRect(cx - 10f * u, cy - h, cx + 1.5f * u, cy + h, h, h, paint);
            c.drawRoundRect(cx - 1.5f * u, cy - h, cx + 10f * u, cy + h, h, h, paint);
        }
        c.restore();
    }
}
