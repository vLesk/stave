package io.github.vlesk.stave.engrave;

import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Output of the layout engine: drawing primitives in staff-space units (y down), plus
 * hit-test regions and geometry needed for cursors. Rendering backends only need to
 * draw these primitives.
 */
public final class DisplayList {
    public static final int GLYPH = 0, LINE = 1, POLY = 2, TEXT = 3, RECT = 4, RECT_OUTLINE = 5;
    public static final int ALIGN_LEFT = 0, ALIGN_CENTER = 1, ALIGN_RIGHT = 2;
    /** Colour roles (resolved by the renderer's theme). */
    public static final int C_INK = 0, C_MUTED = 1, C_ACCENT = 2, C_FRAME = 3;

    public static final class Item {
        public int kind;
        public float x, y, x2, y2;
        /** Line width for LINE / outline, glyph scale for GLYPH, em size for TEXT. */
        public float w;
        public int glyph;
        public String text;
        public int font;
        public int align;
        public float[] pts;
        public int color;
        /** Model element this item belongs to (for selection / playback highlighting). */
        public ChordRest owner;
        public Note note;
        public int voice;

        /**
         * Whether any part of this item can reach the vertical band [y0, y1] (staff spaces),
         * allowing {@code margin} for glyph and text extents around their anchor point.
         * Uses the full vertical extent of lines, rectangles and polygons, so tall items such
         * as system barlines, brackets and braces stay visible while their top or bottom is
         * scrolled off screen.
         */
        public boolean intersects(float y0, float y1, float margin) {
            float top, bottom;
            switch (kind) {
                case LINE:
                case RECT:
                case RECT_OUTLINE:
                    top = Math.min(y, y2);
                    bottom = Math.max(y, y2);
                    break;
                case POLY:
                    top = Float.POSITIVE_INFINITY;
                    bottom = Float.NEGATIVE_INFINITY;
                    for (int k = 1; k < pts.length; k += 2) {
                        top = Math.min(top, pts[k]);
                        bottom = Math.max(bottom, pts[k]);
                    }
                    break;
                default:
                    top = bottom = y;
                    break;
            }
            return bottom >= y0 - margin && top <= y1 + margin;
        }
    }

    /** A tappable element. */
    public static final class Hit {
        public float x0, y0, x1, y1;
        public Pos pos;
        public ChordRest cr;
        public Note note;
        public int noteIdx = -1;
        /** Text baseline (lyric hits only). */
        public float baseline;
        /** Index of the grace chord of cr this hit is on, or -1. */
        public int grace = -1;
    }

    public static final class SystemInfo {
        public float y, x0, x1;
        public float[] staffY;
        public int firstMeasure, lastMeasure;
        public float top, bottom;
    }

    public static final class MeasureInfo {
        public int system;
        public float x, width;
        /** Length of the measure in ticks. */
        public int ticks;
        /** First content x (after any clef/key/time signature at the measure start). */
        public float contentX;
        public int[] colTicks;
        public float[] colX;
    }

    public final List<Item> items = new ArrayList<Item>();
    public final List<Hit> hits = new ArrayList<Hit>();
    /** Where each chord's lyric is drawn (for tapping to edit and for the lyric caret). */
    public final List<Hit> lyricHits = new ArrayList<Hit>();
    public final List<SystemInfo> systems = new ArrayList<SystemInfo>();
    public final List<MeasureInfo> measures = new ArrayList<MeasureInfo>();
    public float width, height;

    Item add(int kind) {
        Item it = new Item();
        it.kind = kind;
        items.add(it);
        return it;
    }

    /**
     * X position of the playback cursor at a tick of a measure. Unlike {@link #xAt}, it runs
     * across the whole measure: from the first column to the first column of the next measure
     * on the same system (or the barline, at the end of a system), so the cursor moves on
     * continuously instead of stalling after the last note column and jumping to the next bar.
     */
    public float playX(int measure, int tick) {
        MeasureInfo mi = measures.get(measure);
        if (mi.ticks <= 0) return xAt(measure, tick);
        float endX = mi.x + mi.width;
        if (measure + 1 < measures.size()) {
            MeasureInfo next = measures.get(measure + 1);
            if (next != null && next.system == mi.system) endX = xAt(measure + 1, 0);
        }
        // knots: (tick, x) pairs, strictly increasing in tick
        float[] kt = new float[mi.colTicks.length + 2];
        float[] kx = new float[kt.length];
        int n = 0;
        kt[n] = 0;
        kx[n++] = xAt(measure, 0);
        for (int i = 0; i < mi.colTicks.length; i++) {
            if (mi.colTicks[i] <= kt[n - 1] || mi.colTicks[i] >= mi.ticks) continue;
            kt[n] = mi.colTicks[i];
            kx[n++] = mi.colX[i];
        }
        kt[n] = mi.ticks;
        kx[n++] = endX;
        if (tick <= 0) return kx[0];
        if (tick >= mi.ticks) return kx[n - 1];
        for (int i = 1; i < n; i++) {
            if (tick <= kt[i]) {
                float f = (tick - kt[i - 1]) / (kt[i] - kt[i - 1]);
                return kx[i - 1] + f * (kx[i] - kx[i - 1]);
            }
        }
        return kx[n - 1];
    }

    /** X position of a tick in a measure (interpolating between columns). */
    public float xAt(int measure, int tick) {
        MeasureInfo mi = measures.get(measure);
        if (mi.colTicks.length == 0) return mi.contentX;
        for (int i = 0; i < mi.colTicks.length; i++) {
            if (mi.colTicks[i] == tick) return mi.colX[i];
            if (mi.colTicks[i] > tick) {
                if (i == 0) return mi.colX[0];
                float f = (tick - mi.colTicks[i - 1]) / (float) (mi.colTicks[i] - mi.colTicks[i - 1]);
                return mi.colX[i - 1] + f * (mi.colX[i] - mi.colX[i - 1]);
            }
        }
        return mi.colX[mi.colX.length - 1];
    }
}
