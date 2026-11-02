package io.github.vlesk.stave.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * MuseScore's palettes re-imagined as a touch bottom sheet: category chips on top, a grid of
 * large (≥ 64dp) cells below. Tapping a cell applies it to the current selection, exactly like
 * clicking a palette cell in MuseScore.
 */
public final class PaletteView extends LinearLayout {

    public static final class Cell {
        final String glyph, caption, description;
        final boolean music;
        final Runnable action;
        float glyphScale = 1f;
        /** For a music glyph Leland lacks: draw it with the supplementary font. */
        boolean supplement;

        public Cell(String glyph, boolean music, String caption, String description, Runnable action) {
            this.glyph = glyph;
            this.music = music;
            this.caption = caption;
            this.description = description;
            this.action = action;
        }

        public Cell scale(float s) {
            glyphScale = s;
            return this;
        }

        public Cell supplement(boolean on) {
            supplement = on;
            return this;
        }
    }

    public static final class Category {
        final String name;
        final List<Cell> cells = new ArrayList<Cell>();

        public Category(String name) {
            this.name = name;
        }

        public Category add(Cell c) {
            cells.add(c);
            return this;
        }
    }

    private final Theme theme;
    private final List<Category> categories = new ArrayList<Category>();
    private final LinearLayout chips;
    private final FlowLayout grid;
    private int current;
    private Runnable onApplied;

    public PaletteView(Context c, Theme t) {
        super(c);
        theme = t;
        setOrientation(VERTICAL);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(c);
        chips.setPadding(t.dp(12), t.dp(4), t.dp(12), t.dp(8));
        hs.addView(chips);
        addView(hs);
        ScrollView sv = new ScrollView(c);
        grid = new FlowLayout(c);
        grid.setPadding(t.dp(12), 0, t.dp(12), t.dp(12));
        sv.addView(grid);
        addView(sv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public void setOnApplied(Runnable r) {
        onApplied = r;
    }

    public void setCategories(List<Category> cats) {
        categories.clear();
        categories.addAll(cats);
        chips.removeAllViews();
        for (int i = 0; i < cats.size(); i++) {
            final int idx = i;
            TextView chip = Ui.text(getContext(), cats.get(i).name, 14, theme.onSurfaceVariant, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(theme.dp(32));
            chip.setPadding(theme.dp(16), 0, theme.dp(16), 0);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    show(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, theme.dp(40));
            lp.rightMargin = theme.dp(8);
            chips.addView(chip, lp);
        }
        show(Math.min(current, cats.size() - 1));
    }

    public void show(int idx) {
        current = idx;
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean on = i == idx;
            chip.setBackground(on ? Ui.rippleRound(theme, theme.primaryContainer, 8)
                    : Ui.ripple(theme, Ui.roundStroke(0, theme.outlineVariant, theme.dp(1), theme.dp(8)), true));
            chip.setTextColor(on ? theme.onPrimaryContainer : theme.onSurfaceVariant);
        }
        grid.removeAllViews();
        for (final Cell cell : categories.get(idx).cells) {
            CellView cv = new CellView(getContext(), cell);
            cv.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Ui.haptic(v);
                    cell.action.run();
                    if (onApplied != null) onApplied.run();
                }
            });
            grid.addView(cv, new ViewGroup.LayoutParams(theme.dp(76), theme.dp(76)));
        }
    }

    private final class CellView extends View {
        private final Cell cell;
        private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG), cp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Rect bounds = new android.graphics.Rect();

        CellView(Context c, Cell cell) {
            super(c);
            this.cell = cell;
            gp.setTypeface(cell.music ? (cell.supplement ? Theme.supplement(c) : Theme.music(c)) : Theme.textBold(c));
            gp.setTextAlign(Paint.Align.CENTER);
            gp.setColor(theme.onSurface);
            cp.setTextAlign(Paint.Align.CENTER);
            cp.setColor(theme.onSurfaceVariant);
            cp.setTextSize(theme.dp(11));
            cp.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            setBackground(Ui.rippleRound(theme, theme.surfaceContainerHigh, 16));
            setContentDescription(cell.description);
            if (Build.VERSION.SDK_INT >= 26) setTooltipText(cell.description);
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean hasCaption = cell.caption != null && !cell.caption.isEmpty();
            float area = hasCaption ? h * 0.68f : h;
            float size = (cell.music ? theme.dp(30) : theme.dp(18)) * cell.glyphScale;
            gp.setTextSize(size);
            float tw = gp.measureText(cell.glyph);
            if (tw > w * 0.86f) gp.setTextSize(size * w * 0.86f / tw);
            gp.getTextBounds(cell.glyph, 0, cell.glyph.length(), bounds);
            float maxH = area * 0.8f;
            if (bounds.height() > maxH) {
                gp.setTextSize(gp.getTextSize() * maxH / bounds.height());
                gp.getTextBounds(cell.glyph, 0, cell.glyph.length(), bounds);
            }
            float y = (hasCaption ? area * 0.55f + theme.dp(4) : area / 2) - (bounds.top + bounds.bottom) / 2f;
            c.drawText(cell.glyph, w / 2, y, gp);
            if (hasCaption) {
                String cap = cell.caption;
                while (cp.measureText(cap) > w - theme.dp(6) && cap.length() > 3) cap = cap.substring(0, cap.length() - 2) + "…";
                c.drawText(cap, w / 2, h - theme.dp(10), cp);
            }
        }
    }

    /** Wrapping row layout for palette cells. */
    static final class FlowLayout extends ViewGroup {
        FlowLayout(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int width = MeasureSpec.getSize(wSpec);
            int gap = (int) (8 * getResources().getDisplayMetrics().density);
            int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                ViewGroup.LayoutParams lp = ch.getLayoutParams();
                ch.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY));
                if (x + ch.getMeasuredWidth() > width - getPaddingRight() && x > getPaddingLeft()) {
                    x = getPaddingLeft();
                    y += rowH + gap;
                    rowH = 0;
                }
                x += ch.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + rowH + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l;
            int gap = (int) (8 * getResources().getDisplayMetrics().density);
            int x = getPaddingLeft(), y = getPaddingTop(), rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (x + ch.getMeasuredWidth() > width - getPaddingRight() && x > getPaddingLeft()) {
                    x = getPaddingLeft();
                    y += rowH + gap;
                    rowH = 0;
                }
                ch.layout(x, y, x + ch.getMeasuredWidth(), y + ch.getMeasuredHeight());
                x += ch.getMeasuredWidth() + gap;
                rowH = Math.max(rowH, ch.getMeasuredHeight());
            }
        }
    }
}
