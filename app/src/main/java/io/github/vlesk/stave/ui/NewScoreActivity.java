package io.github.vlesk.stave.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.vlesk.stave.R;
import io.github.vlesk.stave.io.ScoreStore;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Instruments;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Score;

import java.util.ArrayList;
import java.util.List;

/**
 * "New score" — MuseScore's new-score wizard condensed into one scrollable form with
 * sensible defaults so a score can be started with a single tap on Create.
 */
public final class NewScoreActivity extends Activity {
    private Theme t;
    private EditText title, composer, tempo, measures;
    private final List<String> instrumentIds = new ArrayList<String>();
    private int keyFifths = 0, timeN = 4, timeD = 4, pickupTicks = 480;
    private Switch pickup;
    private LinearLayout templateChips, keyChips, timeChips, pickupChips;
    private TextView instrumentSummary;

    private static final int[][] TIMES = {{4, 4}, {3, 4}, {2, 4}, {6, 8}, {2, 2}, {3, 8}, {9, 8}, {12, 8}, {5, 4}, {7, 8}};
    private static final int[] PICKUPS = {240, 480, 720, 960};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = new Theme(this);
        instrumentIds.add("piano");
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(t.surface);
        LinearLayout col = Ui.column(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = Ui.row(this);
        top.setMinimumHeight(t.dp(64));
        top.setPadding(t.dp(4), 0, t.dp(16), 0);
        top.addView(Ui.icon(this, t, R.drawable.ic_close, getString(R.string.cancel), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        TextView tt = Ui.title(this, t, getString(R.string.new_score));
        tt.setPadding(t.dp(8), 0, 0, 0);
        top.addView(tt, Ui.lin(0, -2, 1f));
        top.addView(Ui.button(this, t, getString(R.string.create), 0, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                create();
            }
        }));
        col.addView(top);

        ScrollView sv = new ScrollView(this);
        LinearLayout form = Ui.column(this);
        form.setPadding(t.dp(16), t.dp(8), t.dp(16), t.dp(32));
        int maxW = t.dp(640);
        sv.addView(form);
        col.addView(sv, Ui.lin(-1, 0, 1f));
        form.setLayoutParams(new FrameLayout.LayoutParams(Math.min(maxW, getResources().getDisplayMetrics().widthPixels), -2, Gravity.CENTER_HORIZONTAL));

        title = field(form, getString(R.string.title), "Untitled score");
        composer = field(form, getString(R.string.composer), "");

        section(form, getString(R.string.template));
        templateChips = chipRow(form);
        for (final String[] tmpl : Instruments.TEMPLATES) {
            addChip(templateChips, tmpl[0], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    instrumentIds.clear();
                    for (int i = 1; i < tmpl.length; i++) instrumentIds.add(tmpl[i]);
                    selectChip(templateChips, v);
                    updateSummary();
                }
            });
        }
        selectChip(templateChips, templateChips.getChildAt(0));
        LinearLayout instRow = Ui.row(this);
        instrumentSummary = Ui.text(this, "", 14, t.onSurfaceVariant, false);
        instRow.addView(instrumentSummary, Ui.lin(0, -2, 1f));
        instRow.addView(Ui.button(this, t, getString(R.string.custom_instruments), 2, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseInstruments();
            }
        }));
        form.addView(instRow);

        section(form, getString(R.string.key_signature));
        keyChips = chipRow(form);
        for (int k = -7; k <= 7; k++) {
            final int key = k;
            View c = addChip(keyChips, Pitch.keyName(k).replace(" major", "") + (k == 0 ? " (no ♯/♭)" : ""), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    keyFifths = key;
                    selectChip(keyChips, v);
                }
            });
            if (k == 0) selectChip(keyChips, c);
        }
        scrollChipsTo(keyChips, 7);

        section(form, getString(R.string.time_signature));
        timeChips = chipRow(form);
        for (final int[] ts : TIMES) {
            View c = addChip(timeChips, ts[0] + "/" + ts[1], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    timeN = ts[0];
                    timeD = ts[1];
                    selectChip(timeChips, v);
                }
            });
            if (ts[0] == 4 && ts[1] == 4) selectChip(timeChips, c);
        }

        LinearLayout nums = Ui.row(this);
        LinearLayout tcol = Ui.column(this);
        tempo = field(tcol, getString(R.string.tempo) + " (♩ = BPM)", "100");
        tempo.setInputType(InputType.TYPE_CLASS_NUMBER);
        LinearLayout mcol = Ui.column(this);
        measures = field(mcol, getString(R.string.measures), "16");
        measures.setInputType(InputType.TYPE_CLASS_NUMBER);
        nums.addView(tcol, Ui.lin(0, -2, 1f));
        View gap = new View(this);
        nums.addView(gap, Ui.lin(t.dp(16), 1));
        nums.addView(mcol, Ui.lin(0, -2, 1f));
        form.addView(nums);

        LinearLayout pr = Ui.row(this);
        pr.setPadding(0, t.dp(16), 0, 0);
        TextView pl = Ui.text(this, getString(R.string.pickup), 16, t.onSurface, false);
        pr.addView(pl, Ui.lin(0, -2, 1f));
        pickup = new Switch(this);
        pickup.setContentDescription(getString(R.string.pickup));
        pr.addView(pickup);
        form.addView(pr);
        pickupChips = chipRow(form);
        String[] pn = {"♪ eighth", "♩ quarter", "♩. dotted quarter", "𝅗𝅥 half"};
        for (int i = 0; i < PICKUPS.length; i++) {
            final int ticks = PICKUPS[i];
            View c = addChip(pickupChips, pn[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickupTicks = ticks;
                    selectChip(pickupChips, v);
                    pickup.setChecked(true);
                }
            });
            if (ticks == 480) selectChip(pickupChips, c);
        }
        ((View) pickupChips.getParent()).setVisibility(View.GONE);
        pickup.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                ((View) pickupChips.getParent()).setVisibility(c ? View.VISIBLE : View.GONE);
            }
        });

        updateSummary();
        setContentView(root);
        Ui.edgeToEdge(this, t, top, sv, col);
    }

    private EditText field(LinearLayout parent, String label, String value) {
        TextView l = Ui.text(this, label, 12, t.primary, true);
        l.setPadding(0, t.dp(12), 0, 0);
        parent.addView(l);
        EditText e = Ui.input(this, t, label, value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setSelectAllOnFocus(true);
        parent.addView(e, Ui.lin(-1, -2));
        return e;
    }

    private void section(LinearLayout form, String s) {
        TextView v = Ui.text(this, s, 14, t.onSurfaceVariant, true);
        v.setPadding(0, t.dp(20), 0, t.dp(8));
        form.addView(v);
    }

    private LinearLayout chipRow(LinearLayout form) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = Ui.row(this);
        hs.addView(row);
        form.addView(hs);
        return row;
    }

    private void scrollChipsTo(final LinearLayout row, final int idx) {
        row.post(new Runnable() {
            @Override
            public void run() {
                View c = row.getChildAt(idx);
                if (c != null) ((HorizontalScrollView) row.getParent()).scrollTo(Math.max(0, c.getLeft() - t.dp(80)), 0);
            }
        });
    }

    private View addChip(LinearLayout row, String label, View.OnClickListener l) {
        TextView chip = Ui.text(this, label, 14, t.onSurfaceVariant, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(t.dp(16), 0, t.dp(16), 0);
        chip.setMinHeight(t.dp(32));
        chip.setOnClickListener(l);
        chip.setBackground(Ui.ripple(t, Ui.roundStroke(0, t.outlineVariant, t.dp(1), t.dp(8)), true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(48));
        lp.rightMargin = t.dp(8);
        chip.setPadding(t.dp(16), t.dp(8), t.dp(16), t.dp(8));
        row.addView(chip, lp);
        return chip;
    }

    private void selectChip(LinearLayout row, View sel) {
        for (int i = 0; i < row.getChildCount(); i++) {
            TextView c = (TextView) row.getChildAt(i);
            boolean on = c == sel;
            c.setBackground(on ? Ui.rippleRound(t, t.primaryContainer, 8) : Ui.ripple(t, Ui.roundStroke(0, t.outlineVariant, t.dp(1), t.dp(8)), true));
            c.setTextColor(on ? t.onPrimaryContainer : t.onSurfaceVariant);
            c.setSelected(on);
        }
    }

    private void updateSummary() {
        StringBuilder sb = new StringBuilder();
        for (String id : instrumentIds) {
            Instruments.Def d = Instruments.byId(id);
            if (d == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(d.label);
        }
        instrumentSummary.setText(sb.toString());
    }

    private void chooseInstruments() {
        InstrumentPicker.pickMany(this, t, instrumentIds, new InstrumentPicker.Picked() {
            @Override
            public void picked(List<Instruments.Def> defs) {
                instrumentIds.clear();
                for (Instruments.Def d : defs) instrumentIds.add(d.id);
                selectChip(templateChips, null);
                updateSummary();
            }
        });
    }

    private int num(EditText e, int def, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(e.getText().toString().trim())));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private void create() {
        List<Part> parts = new ArrayList<Part>();
        for (String id : instrumentIds) {
            Instruments.Def d = Instruments.byId(id);
            if (d != null) parts.add(d.create());
        }
        if (parts.isEmpty()) parts.add(Instruments.byId("piano").create());
        String ti = title.getText().toString().trim();
        int measureTicks = timeN * 1920 / timeD;
        int pk = pickup.isChecked() && pickupTicks < measureTicks ? pickupTicks : 0;
        Score s = Score.create(ti.isEmpty() ? "Untitled score" : ti, composer.getText().toString().trim(), parts,
                timeN, timeD, keyFifths, num(tempo, 100, 20, 400), num(measures, 16, 1, 999), pk);
        ScoreStore store = new ScoreStore(getFilesDir());
        String id = store.newId();
        try {
            store.save(id, s, Exporter.thumbnail(this, s, 360));
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, EditorActivity.class);
        i.putExtra(EditorActivity.EXTRA_ID, id);
        startActivity(i);
        finish();
    }

}
