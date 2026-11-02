package io.github.vlesk.stave.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

import io.github.vlesk.stave.engrave.DisplayList;
import io.github.vlesk.stave.engrave.Sym;
import io.github.vlesk.stave.engrave.TextMetrics;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Note;

import java.util.Set;

/** Draws a {@link DisplayList} on an Android Canvas. Shared by the editor view, thumbnails and PDF export. */
public final class ScoreRenderer implements TextMetrics {
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint measurePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface music, supplement, text, italic, bold;
    private final Path path = new Path();
    private final char[] one = new char[1];

    public int ink = 0xFF111111, muted = 0xFF777777, accent = 0xFF1A62D6;

    public ScoreRenderer(Context c) {
        music = Theme.music(c);
        supplement = Theme.supplement(c);
        text = Theme.text(c);
        italic = Theme.textItalic(c);
        bold = Theme.textBold(c);
        glyphPaint.setTypeface(music);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.BUTT);
        fillPaint.setStyle(Paint.Style.FILL);
    }

    private Typeface font(int f) {
        switch (f) {
            case FONT_ITALIC:
                return italic;
            case FONT_BOLD:
                return bold;
            case FONT_MUSIC:
                return music;
            default:
                return text;
        }
    }

    @Override
    public float width(String s, int font, float sizeSp) {
        measurePaint.setTypeface(font(font));
        measurePaint.setTextSize(100f);
        return measurePaint.measureText(s) / 100f * sizeSp;
    }

    /** Colour lookup for highlighted items. */
    public interface Highlighter {
        /** Returns a colour for the item or 0 for default ink. */
        int colorFor(DisplayList.Item it);
    }

    /**
     * Draws the items that reach into [y0, y1] (in staff spaces); sp is pixels per staff space.
     */
    public void draw(Canvas c, DisplayList dl, float sp, float y0, float y1, Highlighter hl) {
        for (int i = 0; i < dl.items.size(); i++) {
            DisplayList.Item it = dl.items.get(i);
            if (!it.intersects(y0, y1, 12)) continue;
            int col = hl != null ? hl.colorFor(it) : 0;
            if (col == 0) col = it.color == DisplayList.C_MUTED ? muted : (it.color == DisplayList.C_ACCENT ? accent : ink);
            switch (it.kind) {
                case DisplayList.GLYPH:
                    glyphPaint.setColor(col);
                    glyphPaint.setTypeface(Sym.SUPPLEMENT[it.glyph] ? supplement : music);
                    glyphPaint.setTextSize(4 * sp * it.w);
                    one[0] = Sym.CODE[it.glyph];
                    c.drawText(one, 0, 1, it.x * sp, it.y * sp, glyphPaint);
                    break;
                case DisplayList.LINE:
                    linePaint.setColor(col);
                    linePaint.setStrokeWidth(Math.max(1f, it.w * sp));
                    c.drawLine(it.x * sp, it.y * sp, it.x2 * sp, it.y2 * sp, linePaint);
                    break;
                case DisplayList.POLY:
                    fillPaint.setColor(col);
                    path.rewind();
                    path.moveTo(it.pts[0] * sp, it.pts[1] * sp);
                    for (int k = 2; k < it.pts.length; k += 2) path.lineTo(it.pts[k] * sp, it.pts[k + 1] * sp);
                    path.close();
                    c.drawPath(path, fillPaint);
                    break;
                case DisplayList.TEXT:
                    textPaint.setColor(col);
                    textPaint.setTypeface(font(it.font));
                    textPaint.setTextSize(it.w * sp);
                    textPaint.setTextAlign(it.align == DisplayList.ALIGN_CENTER ? Paint.Align.CENTER
                            : (it.align == DisplayList.ALIGN_RIGHT ? Paint.Align.RIGHT : Paint.Align.LEFT));
                    c.drawText(it.text, it.x * sp, it.y * sp, textPaint);
                    break;
                case DisplayList.RECT:
                    fillPaint.setColor(col);
                    c.drawRect(it.x * sp, it.y * sp, it.x2 * sp, it.y2 * sp, fillPaint);
                    break;
                case DisplayList.RECT_OUTLINE:
                    linePaint.setColor(col);
                    linePaint.setStrokeWidth(Math.max(1f, it.w * sp));
                    c.drawRect(it.x * sp, it.y * sp, it.x2 * sp, it.y2 * sp, linePaint);
                    break;
                default:
                    break;
            }
        }
    }

    /** Highlighter for a set of selected / playing elements. */
    public static Highlighter setHighlighter(final Set<Object> selected, final Note selectedNote, final int[] voiceColors,
                                             final Set<Object> playing, final int playColor) {
        return new Highlighter() {
            @Override
            public int colorFor(DisplayList.Item it) {
                if (playing != null && it.owner != null && playing.contains(it.owner)) return playColor;
                if (selectedNote != null) {
                    if (it.note == selectedNote) return voiceColors[it.voice & 3];
                    return 0;
                }
                if (selected != null && it.owner != null && selected.contains(it.owner)) return voiceColors[voiceOf(it) & 3];
                return 0;
            }
        };
    }

    private static int voiceOf(DisplayList.Item it) {
        return it.voice;
    }

    public static boolean owns(DisplayList.Item it, ChordRest cr) {
        return it.owner == cr;
    }
}
