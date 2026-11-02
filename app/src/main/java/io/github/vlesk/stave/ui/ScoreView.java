package io.github.vlesk.stave.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import io.github.vlesk.stave.edit.EditorState;
import io.github.vlesk.stave.engrave.DisplayList;
import io.github.vlesk.stave.engrave.Layout;
import io.github.vlesk.stave.engrave.Sym;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.Voice;

import java.util.HashSet;
import java.util.Set;

/**
 * The score canvas. The score is re-flowed to the screen width (no horizontal scrolling on
 * phones); one-finger drag scrolls, pinch zooms, tap selects, long-press extends a range
 * selection. In note-input mode a touch on a staff shows a ghost note that follows the finger
 * (with a pitch label above the finger, since the finger hides the note) and is entered on release.
 */
public final class ScoreView extends View {

    public interface Listener {
        void onSelectionChanged();

        /** Note input: a note was placed by touching the staff. */
        void onStaffNote(Pos pos, int line);

        void onLongPress();

        /** Inline lyric entry changed the lyric text (not a selection change, so no note preview). */
        void onLyricChanged();
    }

    private final Theme theme;
    private final ScoreRenderer renderer;
    private EditorState state;
    private Listener listener;
    private DisplayList dl;
    private int laidOutRevision = -1;
    private int laidOutWidth = -1;
    private float laidOutZoom = -1;
    private Object laidOutScore;

    private float zoom = 1f;
    private float pinchScale = 1f, pinchFocusY;
    private float scrollYPx;
    private final OverScroller scroller;
    private final GestureDetector gestures;
    private final ScaleGestureDetector scaleGestures;

    // note-input ghost
    private boolean ghostActive;
    private int ghostStaff, ghostMeasure, ghostTick, ghostLine;
    private float ghostFingerX, ghostFingerY;

    // playback
    private final Set<Object> playing = new HashSet<Object>();
    private int playMeasure = -1, playTick;
    private boolean lyricWasActive;
    /** Height in px at the bottom covered by the lyric bar and the keyboard (0 outside lyric entry). */
    private int lyricObstruction;

    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ScoreView(Context c, Theme t) {
        super(c);
        theme = t;
        renderer = new ScoreRenderer(c);
        renderer.ink = t.ink;
        renderer.muted = t.inkMuted;
        renderer.accent = t.primary;
        scroller = new OverScroller(c);
        cursorPaint.setColor(t.cursorFill);
        cursorStroke.setColor(t.primary);
        cursorStroke.setStyle(Paint.Style.STROKE);
        cursorStroke.setStrokeWidth(t.dp(1.5f));
        bubblePaint.setColor(t.primary);
        bubbleText.setColor(t.onPrimary);
        bubbleText.setTextAlign(Paint.Align.CENTER);
        bubbleText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bubbleText.setTextSize(t.dp(18));
        glyphPaint.setTypeface(Theme.music(c));
        setFocusable(true);
        setContentDescription("Score");
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                scroller.forceFinished(true);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                handleLongPress(e.getX(), e.getY());
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollBy(dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                scroller.fling(0, (int) scrollYPx, 0, (int) -vy, 0, 0, 0, maxScroll());
                postInvalidateOnAnimation();
                return true;
            }
        });
        scaleGestures = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                pinchScale = 1f;
                pinchFocusY = d.getFocusY();
                ghostActive = false;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                pinchScale *= d.getScaleFactor();
                float target = zoom * pinchScale;
                if (target < 0.45f) pinchScale = 0.45f / zoom;
                if (target > 3.5f) pinchScale = 3.5f / zoom;
                invalidate();
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector d) {
                float contentY = (scrollYPx + pinchFocusY) / sp();
                zoom = Math.max(0.45f, Math.min(3.5f, zoom * pinchScale));
                pinchScale = 1f;
                relayout(true);
                // keep the content under the fingers roughly in place (systems reflow)
                scrollYPx = contentY * sp() - pinchFocusY;
                clampScroll();
                invalidate();
            }
        });
    }

    public void bind(EditorState s, Listener l) {
        state = s;
        listener = l;
        relayout(true);
    }

    public float getZoom() {
        return zoom;
    }

    public void setZoom(float z) {
        zoom = Math.max(0.45f, Math.min(3.5f, z));
        relayout(true);
        invalidate();
    }

    /** Pixels per staff space. */
    public float sp() {
        return theme.dp(7f) * zoom;
    }

    public DisplayList displayList() {
        return dl;
    }

    public void relayout(boolean force) {
        if (state == null || getWidth() == 0) return;
        if (!force && laidOutRevision == state.revision && laidOutWidth == getWidth() && laidOutZoom == zoom && laidOutScore == state.score) return;
        Layout.Options o = new Layout.Options();
        o.width = getWidth() / sp();
        o.marginX = Math.max(1.0f, theme.dp(12) / sp());
        dl = Layout.layout(state.score, renderer, o);
        laidOutRevision = state.revision;
        laidOutWidth = getWidth();
        laidOutZoom = zoom;
        laidOutScore = state.score;
        clampScroll();
    }

    /** Call after every edit. */
    public void refresh() {
        // While typing lyrics, systems grow and shrink (a lyric row appears) and text reflows. Keep the
        // note being edited at the same place on screen so the score doesn't jump under the finger.
        float anchor = Float.NaN;
        boolean lyric = state != null && (state.lyricEdit || lyricWasActive) && state.selPos != null;
        if (lyric) anchor = staffScreenY(state.selPos);
        relayout(false);
        if (lyric && !Float.isNaN(anchor)) {
            float now = staffScreenY(state.selPos);
            if (!Float.isNaN(now) && now != anchor) {
                scroller.forceFinished(true);
                scrollYPx += now - anchor;
                clampScroll();
            }
        }
        lyricWasActive = state != null && state.lyricEdit;
        invalidate();
    }

    /** Screen y of the top line of the staff at a position in the current layout, or NaN. */
    private float staffScreenY(Pos p) {
        if (dl == null || p == null || p.measure < 0 || p.measure >= dl.measures.size() || dl.measures.get(p.measure) == null) return Float.NaN;
        DisplayList.SystemInfo si = dl.systems.get(dl.measures.get(p.measure).system);
        return si.staffY[Math.max(0, Math.min(p.staff, si.staffY.length - 1))] * sp() - scrollYPx;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout(true);
    }

    private int maxScroll() {
        if (dl == null) return 0;
        return (int) Math.max(0, dl.height * sp() - getHeight() + theme.dp(24) + lyricObstruction);
    }

    private void clampScroll() {
        scrollYPx = Math.max(0, Math.min(maxScroll(), scrollYPx));
    }

    /** Notified about user scrolling (used to collapse the extended FAB, like Material apps do). */
    public interface ScrollWatcher {
        void scrolled(float dy);
    }

    private ScrollWatcher scrollWatcher;

    public void setScrollWatcher(ScrollWatcher w) {
        scrollWatcher = w;
    }

    private void scrollBy(float dy) {
        if (scrollWatcher != null) scrollWatcher.scrolled(dy);
        scrollYPx += dy;
        clampScroll();
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollYPx = scroller.getCurrY();
            clampScroll();
            postInvalidateOnAnimation();
        }
    }

    /** Scrolls so that the given measure/staff is visible (used by input cursor and playback). */
    public void ensureVisible(int measure, int staff, boolean smooth) {
        if (dl == null || measure < 0 || measure >= dl.measures.size() || dl.measures.get(measure) == null) return;
        DisplayList.SystemInfo si = dl.systems.get(dl.measures.get(measure).system);
        float sp = sp();
        float top = (si.staffY[Math.min(staff, si.staffY.length - 1)] - 5) * sp;
        float bottom = (si.staffY[Math.min(staff, si.staffY.length - 1)] + 10) * sp;
        float target = scrollYPx;
        if (top < scrollYPx) target = top;
        else if (bottom > scrollYPx + getHeight()) target = bottom - getHeight();
        if (target != scrollYPx) {
            if (smooth) {
                scroller.forceFinished(true);
                scroller.startScroll(0, (int) scrollYPx, 0, (int) (target - scrollYPx), 250);
                postInvalidateOnAnimation();
            } else {
                scrollYPx = target;
                clampScroll();
                invalidate();
            }
        }
    }

    // ------------------------------------------------------------------ inline lyric entry

    private DisplayList.Hit findLyricHit(float sx, float sy) {
        if (dl == null) return null;
        float tol = Math.max(0.6f, theme.dp(10) / sp());
        for (DisplayList.Hit h : dl.lyricHits) {
            float x1 = Math.max(h.x1, h.x0 + 1.2f);
            if (sx >= h.x0 - tol && sx <= x1 + tol && sy >= h.y0 - tol && sy <= h.y1 + tol) return h;
        }
        return null;
    }

    /** The lyric position of a chord in the current layout, or null if it has no lyric row. */
    private DisplayList.Hit lyricHitFor(ChordRest cr) {
        if (dl == null || cr == null) return null;
        for (DisplayList.Hit h : dl.lyricHits) if (h.cr == cr) return h;
        return null;
    }

    private void drawLyricCaret(Canvas c, float sp) {
        if (state == null || !state.lyricEdit) return;
        DisplayList.Hit h = lyricHitFor(state.selectedChordRest());
        if (h == null) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(theme.voice[state.voice & 3]);
        float x = h.x1 * sp + theme.dp(1);
        c.drawRect(x, h.y0 * sp, x + theme.dp(2), h.y1 * sp, p);
    }

    private InputMethodManager inputMethods() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    /**
     * Starts lyric input. With showKeyboard, opens the on-screen keyboard; without it (a physical
     * keyboard is attached), only takes the focus.
     */
    public void startLyricInput(final boolean showKeyboard) {
        // in touch mode a view only takes the focus (and so the keyboard) if it asks for it
        setFocusableInTouchMode(true);
        requestFocus();
        if (showKeyboard) {
            post(new Runnable() {
                @Override
                public void run() {
                    InputMethodManager imm = inputMethods();
                    if (imm == null || state == null || !state.lyricEdit) return;
                    imm.restartInput(ScoreView.this);
                    imm.showSoftInput(ScoreView.this, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        }
        ensureLyricVisible();
    }

    public void stopLyricInput() {
        InputMethodManager imm = inputMethods();
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    /** Tells the view how much of its bottom is covered by the lyric bar and the keyboard. */
    public void setLyricObstruction(int px) {
        if (px == lyricObstruction) return;
        lyricObstruction = px;
        clampScroll();
        ensureLyricVisible();
    }

    /**
     * Scrolls only as far as needed to bring the lyric being typed into the part of the view that
     * is not covered by the keyboard or the lyric bar. A lyric that is already visible stays put.
     */
    public void ensureLyricVisible() {
        if (dl == null || state == null || !state.lyricEdit) return;
        DisplayList.Hit h = lyricHitFor(state.selectedChordRest());
        if (h == null) return;
        float sp = sp();
        float visibleBottom = getHeight() - lyricObstruction;
        float pad = theme.dp(12);
        float top = h.y0 * sp - scrollYPx;
        float bottom = h.y1 * sp - scrollYPx;
        float target = scrollYPx;
        if (bottom > visibleBottom - pad) target = scrollYPx + (bottom - (visibleBottom - pad));
        else if (top < pad) target = scrollYPx - (pad - top);
        target = Math.max(0, Math.min(maxScroll(), target));
        if (Math.abs(target - scrollYPx) > 0.5f) {
            scroller.forceFinished(true);
            scroller.startScroll(0, (int) scrollYPx, 0, (int) (target - scrollYPx), 200);
            postInvalidateOnAnimation();
        }
    }

    /** After a change to the lyric text: keep it visible, redraw, and tell the editor. */
    private void lyricChanged() {
        invalidate();
        if (listener != null) listener.onLyricChanged();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return state != null && state.lyricEdit;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        if (state == null || !state.lyricEdit) return null;
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        return new LyricInputConnection();
    }

    /** Connects the on-screen keyboard (and its suggestions and composing text) to the lyric. */
    private final class LyricInputConnection extends BaseInputConnection {
        LyricInputConnection() {
            super(ScoreView.this, false);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            if (state == null) return false;
            state.lyricType(text);
            lyricChanged();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            if (state == null) return false;
            state.lyricCompose(text);
            lyricChanged();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            if (state != null) state.lyricFinishComposing();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (state == null) return false;
            for (int i = 0; i < beforeLength; i++) state.lyricBackspace();
            lyricChanged();
            return true;
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            if (state == null) return false;
            state.lyricNext();
            lyricChanged();
            return true;
        }

        @Override
        public CharSequence getTextBeforeCursor(int n, int flags) {
            ChordRest cr = state == null ? null : state.selectedChordRest();
            String t = cr instanceof Chord && cr.lyric != null ? cr.lyric : "";
            return t.substring(Math.max(0, t.length() - n));
        }

        @Override
        public CharSequence getTextAfterCursor(int n, int flags) {
            return "";
        }

        @Override
        public CharSequence getSelectedText(int flags) {
            return null;
        }

        @Override
        public boolean setSelection(int start, int end) {
            return true;
        }

        @Override
        public boolean beginBatchEdit() {
            return true;
        }

        @Override
        public boolean endBatchEdit() {
            return true;
        }

        @Override
        public boolean clearMetaKeyStates(int states) {
            return true;
        }
    }

    // ------------------------------------------------------------------ playback

    public void setPlayPosition(int measure, int tick) {
        playing.clear();
        playMeasure = measure;
        playTick = tick;
        if (measure >= 0 && state != null && measure < state.score.measures.size()) {
            for (int st = 0; st < state.score.staffCount(); st++) {
                for (Voice v : state.score.measures.get(measure).staves.get(st).voices) {
                    int[] starts = v.starts();
                    for (int i = 0; i < v.items.size(); i++) {
                        if (starts[i] <= tick && tick < starts[i + 1] && v.items.get(i) instanceof Chord) playing.add(v.items.get(i));
                    }
                }
            }
        }
        invalidate();
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (state != null && state.inputMode && dl != null) {
            if (e.getPointerCount() > 1 && ghostActive) {
                ghostActive = false;
                invalidate();
            }
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN && startGhost(e.getX(), e.getY())) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (ghostActive) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        updateGhost(e.getX(), e.getY());
                        return true;
                    case MotionEvent.ACTION_UP:
                        ghostActive = false;
                        invalidate();
                        if (listener != null) listener.onStaffNote(new Pos(ghostMeasure, ghostStaff, state.voice, ghostTick), ghostLine);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        ghostActive = false;
                        invalidate();
                        return true;
                    default:
                        return true;
                }
            }
        }
        scaleGestures.onTouchEvent(e);
        if (!scaleGestures.isInProgress()) gestures.onTouchEvent(e);
        return true;
    }

    private float toScoreX(float x) {
        return x / sp();
    }

    private float toScoreY(float y) {
        return (y + scrollYPx) / sp();
    }

    /** Finds the staff under a point: returns {systemIndex, staff} or null. */
    private int[] staffAt(float sx, float sy, float above, float below) {
        for (int si = 0; si < dl.systems.size(); si++) {
            DisplayList.SystemInfo s = dl.systems.get(si);
            for (int st = 0; st < s.staffY.length; st++) {
                float y0 = s.staffY[st] - above, y1 = s.staffY[st] + 4 + below;
                if (sy >= y0 && sy <= y1 && sx >= s.x0 - 1 && sx <= s.x1 + 1) return new int[]{si, st};
            }
        }
        return null;
    }

    private boolean startGhost(float x, float y) {
        float sx = toScoreX(x), sy = toScoreY(y);
        int[] hit = staffAt(sx, sy, 3.0f, 3.0f);
        if (hit == null) return false;
        ghostStaff = hit[1];
        ghostActive = true;
        DisplayList.SystemInfo s = dl.systems.get(hit[0]);
        // measure under finger
        ghostMeasure = s.firstMeasure;
        for (int m = s.firstMeasure; m <= s.lastMeasure; m++) {
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            if (sx >= mi.x) ghostMeasure = m;
        }
        ghostTick = snapTick(ghostMeasure, ghostStaff, sx);
        updateGhost(x, y);
        return true;
    }

    private int snapTick(int m, int staff, float sx) {
        DisplayList.MeasureInfo mi = dl.measures.get(m);
        Voice v = state.score.measures.get(m).staves.get(staff).voices[state.voice];
        int best = 0;
        float bestD = Float.MAX_VALUE;
        // candidate ticks: element starts in the input voice (or bar start), and the cursor
        java.util.List<Integer> cands = new java.util.ArrayList<Integer>();
        if (v.isEmpty()) {
            for (int t : mi.colTicks) cands.add(t);
            cands.add(0);
        } else {
            int[] st = v.starts();
            for (int i = 0; i < v.items.size(); i++) {
                ChordRest cr = v.items.get(i);
                if (cr instanceof io.github.vlesk.stave.model.Rest && ((io.github.vlesk.stave.model.Rest) cr).measureRest) {
                    for (int t : mi.colTicks) cands.add(t);
                    cands.add(0);
                } else cands.add(st[i]);
            }
        }
        if (state.cursor != null && state.cursor.measure == m && state.cursor.staff == staff) cands.add(state.cursor.tick);
        for (int t : cands) {
            float d = Math.abs(dl.xAt(m, t) + 0.6f - sx);
            if (d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    private void updateGhost(float x, float y) {
        ghostFingerX = x;
        ghostFingerY = y;
        DisplayList.MeasureInfo mi = dl.measures.get(ghostMeasure);
        DisplayList.SystemInfo s = dl.systems.get(mi.system);
        float rel = toScoreY(y) - s.staffY[ghostStaff];
        ghostLine = Math.round(rel * 2);
        ghostLine = Math.max(-10, Math.min(18, ghostLine));
        invalidate();
    }

    /** Pitch for a staff line in note input (applies the key signature, like MuseScore's mouse input). */
    public int pitchForLine(int measure, int staff, int line) {
        int clef = state.score.clefAt(staff, measure);
        int dia = Clef.topLineDiatonic(clef) - line;
        int step = io.github.vlesk.stave.model.MathUtil.floorMod(dia, 7);
        int alter = Pitch.keyAlter(state.score.keyAt(measure), step);
        return Pitch.pitchFromDiatonic(dia, alter);
    }

    private DisplayList.Hit findHit(float sx, float sy) {
        float tol = Math.max(0.9f, theme.dp(14) / sp());
        DisplayList.Hit best = null;
        float bestScore = Float.MAX_VALUE;
        DisplayList.Hit measureHit = null;
        for (DisplayList.Hit h : dl.hits) {
            if (h.cr == null) {
                if (sx >= h.x0 && sx <= h.x1 && sy >= h.y0 && sy <= h.y1) measureHit = h;
                continue;
            }
            float dx = sx < h.x0 ? h.x0 - sx : (sx > h.x1 ? sx - h.x1 : 0);
            float dy = sy < h.y0 ? h.y0 - sy : (sy > h.y1 ? sy - h.y1 : 0);
            if (dx > tol || dy > tol) continue;
            float cx = (h.x0 + h.x1) / 2, cy = (h.y0 + h.y1) / 2;
            float score = (float) Math.hypot(sx - cx, sy - cy) + (h.note != null ? 0 : 0.6f) + (dx + dy) * 2;
            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }
        return best != null ? best : measureHit;
    }

    private void handleTap(float x, float y) {
        if (dl == null || state == null) return;
        float sx = toScoreX(x), sy = toScoreY(y);
        DisplayList.Hit lyric = findLyricHit(sx, sy);
        if (lyric == null && state.lyricEdit) {
            // lyric entry mode: a tap on a note moves typing to it, any other tap is ignored
            DisplayList.Hit note = findHit(sx, sy);
            if (note != null && note.cr != null) lyric = note;
            else return;
        }
        if (lyric != null) {
            // tapping a lyric (or, in lyric entry mode, a note) types on that chord, at the end of its lyric
            if (!state.beginLyric(lyric.pos)) return;
            Ui.haptic(this);
            invalidate();
            if (listener != null) listener.onLyricChanged();
            ensureLyricVisible();
            return;
        }
        DisplayList.Hit h = findHit(toScoreX(x), toScoreY(y));
        if (h == null) {
            state.clearSelection();
        } else if (h.cr == null) {
            state.selectMeasure(h.pos.measure, h.pos.staff);
        } else if (h.grace >= 0) {
            state.selectGrace(h.pos, h.grace);
        } else {
            state.selectNote(h.pos, h.noteIdx);
        }
        Ui.haptic(this);
        invalidate();
        if (listener != null) listener.onSelectionChanged();
    }

    private void handleLongPress(float x, float y) {
        if (dl == null || state == null || state.lyricEdit) return;
        DisplayList.Hit h = findHit(toScoreX(x), toScoreY(y));
        if (h == null) return;
        if (state.hasSelection()) state.extendTo(h.pos.measure, h.pos.staff);
        else state.selectMeasure(h.pos.measure, h.pos.staff);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        if (listener != null) {
            listener.onSelectionChanged();
            listener.onLongPress();
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(theme.paper);
        if (dl == null) {
            relayout(true);
            if (dl == null) return;
        }
        float sp = sp();
        c.save();
        if (pinchScale != 1f) {
            c.scale(pinchScale, pinchScale, getWidth() / 2f, pinchFocusY);
        }
        c.translate(0, -scrollYPx);
        float y0 = scrollYPx / sp, y1 = (scrollYPx + getHeight()) / sp;
        if (pinchScale != 1f) {
            y0 -= getHeight() / sp;
            y1 += getHeight() / sp;
        }
        drawRangeSelection(c, sp);
        drawCursor(c, sp);
        Set<Object> sel = new HashSet<Object>();
        Note selNote = null;
        if (state.selKind == EditorState.SEL_NOTE) selNote = state.selectedNote();
        else if (state.selKind == EditorState.SEL_CHORDREST) {
            ChordRest cr = state.selectedChordRest();
            if (cr != null) sel.add(cr);
        } else if (state.selKind == EditorState.SEL_RANGE) {
            for (int m = state.rangeM1; m <= state.rangeM2; m++) {
                for (int st = state.rangeS1; st <= state.rangeS2; st++) {
                    for (Voice v : state.score.measures.get(m).staves.get(st).voices) sel.addAll(v.items);
                }
            }
        }
        renderer.draw(c, dl, sp, y0, y1, ScoreRenderer.setHighlighter(sel, selNote, theme.voice, playing, theme.playback));
        drawPlayLine(c, sp);
        drawLyricCaret(c, sp);
        drawGhost(c, sp);
        c.restore();
    }

    private void drawRangeSelection(Canvas c, float sp) {
        if (state.selKind != EditorState.SEL_RANGE) return;
        Paint p = new Paint();
        p.setColor(Theme.alpha(theme.voice[0], 0.12f));
        Paint st = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.setStyle(Paint.Style.STROKE);
        st.setStrokeWidth(theme.dp(1.5f));
        st.setColor(theme.voice[0]);
        // one rectangle per system segment of the range
        int curSys = -1;
        float x0 = 0, x1 = 0;
        for (int m = state.rangeM1; m <= state.rangeM2 + 1; m++) {
            DisplayList.MeasureInfo mi = m <= state.rangeM2 && m < dl.measures.size() ? dl.measures.get(m) : null;
            if (mi == null || mi.system != curSys) {
                if (curSys >= 0) {
                    DisplayList.SystemInfo s = dl.systems.get(curSys);
                    float ya = s.staffY[state.rangeS1] - 1f, yb = s.staffY[state.rangeS2] + 5f;
                    rect.set(x0 * sp, ya * sp, x1 * sp, yb * sp);
                    c.drawRoundRect(rect, theme.dp(4), theme.dp(4), p);
                    c.drawRoundRect(rect, theme.dp(4), theme.dp(4), st);
                }
                if (mi == null) break;
                curSys = mi.system;
                x0 = mi.x;
            }
            x1 = mi.x + mi.width;
        }
    }

    private void drawCursor(Canvas c, float sp) {
        if (!state.inputMode || state.cursor == null) return;
        Pos p = state.cursor;
        if (p.measure >= dl.measures.size() || dl.measures.get(p.measure) == null) return;
        DisplayList.MeasureInfo mi = dl.measures.get(p.measure);
        DisplayList.SystemInfo s = dl.systems.get(mi.system);
        float x = dl.xAt(p.measure, p.tick);
        ChordRest cr = ScoreEdit.elementCovering(state.score, p);
        if (cr instanceof io.github.vlesk.stave.model.Rest && ((io.github.vlesk.stave.model.Rest) cr).measureRest && p.tick == 0) {
            x = mi.contentX;
        }
        float sy = s.staffY[Math.min(p.staff, s.staffY.length - 1)];
        rect.set((x - 0.6f) * sp, (sy - 1.5f) * sp, (x + Sym.width(Sym.noteheadBlack) + 0.6f) * sp, (sy + 5.5f) * sp);
        cursorPaint.setColor(Theme.alpha(theme.voice[state.voice & 3], 0.18f));
        cursorStroke.setColor(theme.voice[state.voice & 3]);
        c.drawRoundRect(rect, theme.dp(3), theme.dp(3), cursorPaint);
        c.drawRoundRect(rect, theme.dp(3), theme.dp(3), cursorStroke);
    }

    private void drawPlayLine(Canvas c, float sp) {
        if (playMeasure < 0 || playMeasure >= dl.measures.size()) return;
        DisplayList.MeasureInfo mi = dl.measures.get(playMeasure);
        DisplayList.SystemInfo s = dl.systems.get(mi.system);
        float x = dl.playX(playMeasure, playTick);
        Paint p = new Paint();
        p.setColor(Theme.alpha(theme.playback, 0.25f));
        rect.set((x - 0.4f) * sp, (s.staffY[0] - 2f) * sp, (x + 1.6f) * sp, (s.staffY[s.staffY.length - 1] + 6f) * sp);
        c.drawRoundRect(rect, theme.dp(3), theme.dp(3), p);
    }

    private void drawGhost(Canvas c, float sp) {
        if (!ghostActive) return;
        DisplayList.MeasureInfo mi = dl.measures.get(ghostMeasure);
        DisplayList.SystemInfo s = dl.systems.get(mi.system);
        float x = dl.xAt(ghostMeasure, ghostTick);
        ChordRest cr = ScoreEdit.elementAt(state.score, new Pos(ghostMeasure, ghostStaff, state.voice, ghostTick));
        if (cr instanceof io.github.vlesk.stave.model.Rest && ((io.github.vlesk.stave.model.Rest) cr).measureRest) x = mi.contentX;
        float staffY = s.staffY[ghostStaff];
        float ny = staffY + ghostLine * 0.5f;
        int col = theme.voice[state.voice & 3];
        // ledger lines
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setColor(Theme.alpha(col, 0.7f));
        lp.setStrokeWidth(Math.max(1f, Sym.legerLineThickness * sp));
        float hw = Sym.width(Sym.noteheadBlack);
        for (int l = -2; l >= ghostLine; l -= 2) c.drawLine((x - 0.4f) * sp, (staffY + l * 0.5f) * sp, (x + hw + 0.4f) * sp, (staffY + l * 0.5f) * sp, lp);
        for (int l = 10; l <= ghostLine; l += 2) c.drawLine((x - 0.4f) * sp, (staffY + l * 0.5f) * sp, (x + hw + 0.4f) * sp, (staffY + l * 0.5f) * sp, lp);
        glyphPaint.setColor(Theme.alpha(col, 0.75f));
        glyphPaint.setTextSize(4 * sp);
        c.drawText(String.valueOf(Sym.CODE[Sym.noteheadBlack]), x * sp, ny * sp, glyphPaint);
        // pitch label bubble above the finger
        int pitch = pitchForLine(ghostMeasure, ghostStaff, ghostLine);
        int tpc = Pitch.spell(pitch, state.score.keyAt(ghostMeasure));
        String label = Pitch.name(pitch, tpc);
        float bx = ghostFingerX, by = ghostFingerY + scrollYPx - theme.dp(72);
        float w = bubbleText.measureText(label) + theme.dp(24);
        rect.set(bx - w / 2, by - theme.dp(22), bx + w / 2, by + theme.dp(14));
        c.drawRoundRect(rect, theme.dp(18), theme.dp(18), bubblePaint);
        c.drawText(label, bx, by + theme.dp(4), bubbleText);
    }
}
