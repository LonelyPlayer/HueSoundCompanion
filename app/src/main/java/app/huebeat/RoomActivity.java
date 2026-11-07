package app.huebeat;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.huebeat.core.Room;

/**
 * The room plan: every light on a floor plan, seen from above (top = front, where the TV or stage
 * is). Drag lamps and DMX lights to their places, and the two ends of each LED strip along its
 * run. The background shows which way effects flow through the room.
 */
public class RoomActivity extends Activity {
    private Prefs prefs;
    private List<HueLamp> lamps;
    private List<Strip> strips;
    private List<DmxLight> dmx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = Prefs.get(this);
        lamps = prefs.hueLamps();
        strips = prefs.strips();
        dmx = prefs.dmxLights();

        LinearLayout root = Ui.column(this);
        int pad = Ui.dp(this, 20);
        root.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 16));
        root.addView(Ui.text(this, "Room plan", 26, Ui.TEXT, true));
        TextView hint = Ui.text(this, "Drag your lights to where they are in the room. Top is the front (TV or stage). "
                + "Strips: drag their two ends; the middle moves the whole strip.", 14, Ui.SUB, false);
        hint.setLineSpacing(0, 1.15f);
        root.addView(hint, Ui.margins(Ui.lp(Ui.MATCH, Ui.WRAP), 0, Ui.dp(this, 6), 0, Ui.dp(this, 10)));
        root.addView(Ui.chipRow(this, Prefs.FLOWS, () -> prefs.flow(), v -> {
            prefs.setFlow(v);
            findViewById(1).invalidate();
        }));
        PlanView plan = new PlanView(this);
        plan.setId(1);
        root.addView(plan, Ui.margins(Ui.lp(Ui.MATCH, 0, 1), 0, Ui.dp(this, 10), 0, Ui.dp(this, 10)));
        TextView done = Ui.button(this, "Done", true);
        done.setOnClickListener(v -> {
            setResult(RESULT_OK);
            finish();
        });
        root.addView(done, Ui.lp(Ui.MATCH, Ui.WRAP));
        Ui.setupWindow(this, root);
    }

    /** One draggable thing: a point, or a line end / middle. */
    private static final class Handle {
        final int kind;   // 0 lamp, 1 strip, 2 dmx
        final int index;
        final int part;   // 0 point / start, 1 end, 2 middle (whole line)
        Handle(int kind, int index, int part) {
            this.kind = kind;
            this.index = index;
            this.part = part;
        }
    }

    private final class PlanView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
                text = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
        private final RectF room = new RectF();
        private Handle drag;
        private float lastX, lastY;

        PlanView(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            text.setColor(Ui.TEXT);
            text.setTextSize(Ui.dp(c, 12));
        }

        private float px(float x) {
            return room.left + (x + 1f) / 2f * room.width();
        }

        private float py(float y) {
            return room.top + (1f - y) / 2f * room.height(); // front (y = 1) at the top
        }

        private float rx(float sx) {
            return Math.max(-1f, Math.min(1f, (sx - room.left) / room.width() * 2f - 1f));
        }

        private float ry(float sy) {
            return Math.max(-1f, Math.min(1f, 1f - (sy - room.top) / room.height() * 2f));
        }

        @Override
        protected void onDraw(Canvas c) {
            float m = Ui.dp(getContext(), 14);
            room.set(m, m + Ui.dp(getContext(), 16), getWidth() - m, getHeight() - m);
            // flow: a soft color field, so it's visible which way effects run
            int flow = prefs.flow();
            int cells = 24;
            float cw = room.width() / cells, ch = room.height() / cells;
            for (int i = 0; i < cells; i++) {
                for (int j = 0; j < cells; j++) {
                    float x = -1f + 2f * (i + 0.5f) / cells, y = 1f - 2f * (j + 0.5f) / cells;
                    float u = Room.u(flow, x, y);
                    bg.setColor(Color.HSVToColor(55, new float[]{u * 300f, 0.8f, 1f}));
                    c.drawRect(room.left + i * cw, room.top + j * ch, room.left + (i + 1) * cw + 1, room.top + (j + 1) * ch + 1, bg);
                }
            }
            stroke.setColor(0x55FFFFFF);
            stroke.setStrokeWidth(Ui.dp(getContext(), 1.5f));
            c.drawRoundRect(room, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), stroke);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(Ui.SUB);
            c.drawText("FRONT · TV / STAGE", room.centerX(), room.top - Ui.dp(getContext(), 6), text);
            text.setColor(Ui.TEXT);

            float r = Ui.dp(getContext(), 11);
            // strips: lines with two ends
            for (Strip st : strips) {
                stroke.setColor(st.enabled ? 0xFF4DD0E1 : 0x664DD0E1);
                stroke.setStrokeWidth(Ui.dp(getContext(), 6));
                c.drawLine(px(st.x1), py(st.y1), px(st.x2), py(st.y2), stroke);
                fill.setColor(0xFF4DD0E1);
                c.drawCircle(px(st.x1), py(st.y1), r * 0.7f, fill);
                c.drawCircle(px(st.x2), py(st.y2), r * 0.7f, fill);
                c.drawText(st.name, (px(st.x1) + px(st.x2)) / 2f, (py(st.y1) + py(st.y2)) / 2f - r, text);
            }
            // Hue lamps: circles (gradient lights as short lines)
            for (HueLamp h : lamps) {
                fill.setColor(h.enabled ? Ui.ACCENT : 0x66FFB443);
                if (h.gradient()) {
                    stroke.setColor(fill.getColor());
                    stroke.setStrokeWidth(Ui.dp(getContext(), 6));
                    c.drawLine(px(h.x), py(h.y), px(h.x2), py(h.y2), stroke);
                    c.drawCircle(px(h.x2), py(h.y2), r * 0.7f, fill);
                }
                c.drawCircle(px(h.x), py(h.y), r, fill);
                c.drawText(h.name, px(h.x), py(h.y) + r + Ui.dp(getContext(), 13), text);
            }
            // DMX lights: squares
            for (DmxLight d : dmx) {
                fill.setColor(d.enabled ? 0xFFB388FF : 0x66B388FF);
                c.drawRoundRect(px(d.x) - r, py(d.y) - r, px(d.x) + r, py(d.y) + r, Ui.dp(getContext(), 4), Ui.dp(getContext(), 4), fill);
                c.drawText(d.name, px(d.x), py(d.y) + r + Ui.dp(getContext(), 13), text);
            }
        }

        private Handle hit(float sx, float sy) {
            float best = Ui.dp(getContext(), 34);
            Handle h = null;
            for (int i = 0; i < dmx.size(); i++) {
                float d = dist(sx, sy, px(dmx.get(i).x), py(dmx.get(i).y));
                if (d < best) {
                    best = d;
                    h = new Handle(2, i, 0);
                }
            }
            for (int i = 0; i < lamps.size(); i++) {
                HueLamp l = lamps.get(i);
                float d = dist(sx, sy, px(l.x), py(l.y));
                if (d < best) {
                    best = d;
                    h = new Handle(0, i, 0);
                }
                if (l.gradient()) {
                    d = dist(sx, sy, px(l.x2), py(l.y2));
                    if (d < best) {
                        best = d;
                        h = new Handle(0, i, 1);
                    }
                }
            }
            for (int i = 0; i < strips.size(); i++) {
                Strip st = strips.get(i);
                float[] cand = {dist(sx, sy, px(st.x1), py(st.y1)), dist(sx, sy, px(st.x2), py(st.y2)),
                        dist(sx, sy, (px(st.x1) + px(st.x2)) / 2f, (py(st.y1) + py(st.y2)) / 2f)};
                for (int part = 0; part < 3; part++) {
                    if (cand[part] < best) {
                        best = cand[part];
                        h = new Handle(1, i, part);
                    }
                }
            }
            return h;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag = hit(e.getX(), e.getY());
                    lastX = e.getX();
                    lastY = e.getY();
                    if (drag != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return drag != null;
                case MotionEvent.ACTION_MOVE:
                    if (drag == null) return false;
                    move(drag, e.getX(), e.getY());
                    lastX = e.getX();
                    lastY = e.getY();
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drag != null) save(drag.kind);
                    drag = null;
                    return true;
                default:
                    return false;
            }
        }

        private void move(Handle h, float sx, float sy) {
            float x = rx(sx), y = ry(sy);
            if (h.kind == 0) {
                HueLamp l = lamps.get(h.index);
                if (h.part == 1) {
                    l.x2 = x;
                    l.y2 = y;
                } else {
                    float dx = l.x2 - l.x, dy = l.y2 - l.y;
                    l.x = x;
                    l.y = y;
                    l.x2 = clamp(x + dx);
                    l.y2 = clamp(y + dy);
                }
            } else if (h.kind == 2) {
                DmxLight d = dmx.get(h.index);
                d.x = x;
                d.y = y;
            } else {
                Strip st = strips.get(h.index);
                if (h.part == 0) {
                    st.x1 = x;
                    st.y1 = y;
                } else if (h.part == 1) {
                    st.x2 = x;
                    st.y2 = y;
                } else {
                    float dx = rx(sx) - rx(lastX), dy = ry(sy) - ry(lastY);
                    st.x1 = clamp(st.x1 + dx);
                    st.y1 = clamp(st.y1 + dy);
                    st.x2 = clamp(st.x2 + dx);
                    st.y2 = clamp(st.y2 + dy);
                }
            }
        }

        private void save(int kind) {
            // places apply live: the running sync takes them over on the next frame
            if (kind == 0) prefs.saveHueLamps(lamps);
            else if (kind == 1) prefs.saveStrips(strips);
            else prefs.saveDmx(dmx);
        }
    }

    private static float dist(float ax, float ay, float bx, float by) {
        float dx = ax - bx, dy = ay - by;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
