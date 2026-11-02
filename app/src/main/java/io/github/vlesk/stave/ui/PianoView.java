package io.github.vlesk.stave.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * On-screen piano keyboard for pitch entry (the touch replacement for typing note letters,
 * modelled on MuseScore 4's piano keyboard panel). Multi-touch is supported so chords can be
 * played; the visible range is shifted by octave buttons or by dragging the label strip.
 */
public final class PianoView extends View {
    public interface Listener {
        /**
         * A key was pressed. withHeldKeys is true when other keys are still held down, so the keys
         * pressed together make one chord.
         */
        void onKey(int pitch, boolean withHeldKeys);
    }

    private static final boolean[] BLACK = {false, true, false, true, false, false, true, false, true, false, true, false};
    private static final int[] WHITE_PCS = {0, 2, 4, 5, 7, 9, 11};

    private final Theme theme;
    private Listener listener;
    /** MIDI pitch of the leftmost white key. */
    private int firstWhite = 48;
    private final SparseIntArray pressed = new SparseIntArray();
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG), black = new Paint(Paint.ANTI_ALIAS_FLAG),
            border = new Paint(Paint.ANTI_ALIAS_FLAG), down = new Paint(Paint.ANTI_ALIAS_FLAG), label = new Paint(Paint.ANTI_ALIAS_FLAG),
            strip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float stripDragX = -1;
    private int stripStartWhite;
    private int highlight = -1;

    public PianoView(Context c, Theme t) {
        super(c);
        theme = t;
        white.setColor(t.dark ? 0xFFE9E9EE : 0xFFFFFFFF);
        black.setColor(t.dark ? 0xFF0E0E12 : 0xFF23232A);
        border.setColor(t.outline);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(1, t.dp(1)));
        down.setColor(t.primary);
        label.setColor(0xFF66666E);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(t.dp(11));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        strip.setColor(t.surfaceContainerHigh);
        setContentDescription("Piano keyboard");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private float whiteW() {
        return Math.max(theme.dp(34), getWidth() / 22f);
    }

    private float stripH() {
        return theme.dp(14);
    }

    private int whiteCount() {
        return (int) Math.ceil(getWidth() / whiteW());
    }

    /** Centres the keyboard on a pitch (e.g. middle of the current clef). */
    private int pendingCenter = -1;

    public void centerOn(int pitch) {
        if (getWidth() == 0) {
            // not laid out yet (the keyboard starts hidden): apply once it gets a size
            pendingCenter = pitch;
            return;
        }
        int whites = whiteCount();
        int idx = whiteIndex(pitch) - whites / 2;
        setFirstWhiteIndex(idx);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && pendingCenter >= 0) {
            int p = pendingCenter;
            pendingCenter = -1;
            centerOn(p);
        }
    }

    public void shiftOctave(int dir) {
        setFirstWhiteIndex(whiteIndex(firstWhite) + dir * 7);
    }

    public void setHighlight(int pitch) {
        highlight = pitch;
        invalidate();
    }

    private static int whiteIndex(int pitch) {
        int oct = io.github.vlesk.stave.model.MathUtil.floorDiv(pitch, 12);
        int pc = pitch - oct * 12;
        int i = 0;
        for (int k = 0; k < 7; k++) if (WHITE_PCS[k] <= pc) i = k;
        return oct * 7 + i;
    }

    private static int whitePitch(int index) {
        int oct = io.github.vlesk.stave.model.MathUtil.floorDiv(index, 7);
        return oct * 12 + WHITE_PCS[index - oct * 7];
    }

    private void setFirstWhiteIndex(int idx) {
        int max = whiteIndex(108) - whiteCount() + 1;
        idx = Math.max(whiteIndex(21), Math.min(max, idx));
        firstWhite = whitePitch(idx);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float ww = whiteW(), top = stripH(), h = getHeight();
        c.drawRect(0, 0, getWidth(), top, strip);
        int first = whiteIndex(firstWhite);
        int n = whiteCount() + 1;
        for (int i = 0; i < n; i++) {
            int p = whitePitch(first + i);
            float x = i * ww;
            r.set(x, top, x + ww, h);
            c.drawRect(r, isDown(p) ? down : white);
            if (p == highlight && !isDown(p)) {
                Paint hp = new Paint();
                hp.setColor(Theme.alpha(theme.primary, 0.25f));
                c.drawRect(r, hp);
            }
            c.drawLine(x, top, x, h, border);
            if (p % 12 == 0) {
                label.setColor(isDown(p) ? theme.onPrimary : 0xFF66666E);
                c.drawText("C" + (p / 12 - 1), x + ww / 2, h - theme.dp(8), label);
                // octave marker on the strip
                label.setColor(theme.onSurfaceVariant);
                c.drawText("C" + (p / 12 - 1), x + ww / 2, top - theme.dp(2), label);
            }
        }
        for (int i = 0; i < n; i++) {
            int p = whitePitch(first + i);
            if (p + 1 <= 108 && BLACK[(p + 1) % 12]) {
                float x = (i + 1) * ww - ww * 0.32f;
                r.set(x, top, x + ww * 0.64f, top + (h - top) * 0.62f);
                c.drawRoundRect(r, theme.dp(3), theme.dp(3), isDown(p + 1) ? down : black);
                if (p + 1 == highlight && !isDown(p + 1)) {
                    Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    hp.setColor(Theme.alpha(theme.primary, 0.6f));
                    c.drawRoundRect(r, theme.dp(3), theme.dp(3), hp);
                }
            }
        }
        c.drawLine(0, top, getWidth(), top, border);
    }

    private boolean isDown(int pitch) {
        for (int i = 0; i < pressed.size(); i++) if (pressed.valueAt(i) == pitch) return true;
        return false;
    }

    private int pitchAt(float x, float y) {
        float ww = whiteW(), top = stripH(), h = getHeight();
        int first = whiteIndex(firstWhite);
        int i = (int) Math.floor(x / ww);
        int p = whitePitch(first + i);
        if (y < top + (h - top) * 0.62f) {
            // black key area: check neighbours
            float local = x - i * ww;
            if (local > ww * 0.68f && p + 1 <= 108 && BLACK[(p + 1) % 12]) return p + 1;
            if (local < ww * 0.32f && BLACK[(p + 11) % 12]) return p - 1;
        }
        return p;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        int id = e.getPointerId(idx);
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                if (y < stripH() + theme.dp(4) && action == MotionEvent.ACTION_DOWN) {
                    stripDragX = x;
                    stripStartWhite = whiteIndex(firstWhite);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                int p = pitchAt(x, y);
                boolean held = pressed.size() > 0;
                pressed.put(id, p);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                if (listener != null) listener.onKey(p, held);
                announceForAccessibility(io.github.vlesk.stave.model.Pitch.name(p, io.github.vlesk.stave.model.Pitch.spell(p, 0)));
                invalidate();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (stripDragX >= 0) {
                    int delta = Math.round((stripDragX - e.getX()) / whiteW());
                    setFirstWhiteIndex(stripStartWhite + delta);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                pressed.delete(id);
                if (action != MotionEvent.ACTION_POINTER_UP) {
                    pressed.clear();
                    stripDragX = -1;
                }
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
