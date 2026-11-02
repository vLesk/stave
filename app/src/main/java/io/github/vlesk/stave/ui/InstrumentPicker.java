package io.github.vlesk.stave.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import io.github.vlesk.stave.R;
import io.github.vlesk.stave.model.Instruments;

import java.util.ArrayList;
import java.util.List;

/**
 * MuseScore's instrument list as a dialog: a search field, MuseScore's instrument groups as chips,
 * and a scrolling list. In single mode a tap picks the instrument; in multi mode each tap adds one
 * (the same instrument can be added twice, as for two violins) and the chosen ones show as removable
 * chips above the list.
 */
final class InstrumentPicker {
    interface Picked {
        void picked(List<Instruments.Def> defs);
    }

    private final Activity a;
    private final Theme t;
    private final boolean multi;
    private final List<Instruments.Def> chosen = new ArrayList<Instruments.Def>();
    private final List<Instruments.Def> shown = new ArrayList<Instruments.Def>();
    private String group;
    private String query = "";
    private LinearLayout groupRow, chosenRow;
    private TextView chosenLabel, empty;
    private BaseAdapter adapter;
    private AlertDialog dialog;

    private InstrumentPicker(Activity a, Theme t, boolean multi) {
        this.a = a;
        this.t = t;
        this.multi = multi;
    }

    /** One instrument: the callback gets a single-element list. */
    static void pickOne(Activity a, Theme t, final Picked cb) {
        new InstrumentPicker(a, t, false).show(a.getString(R.string.add_instrument), null, cb);
    }

    /** Several instruments, starting from (and returning) an ordered selection. */
    static void pickMany(Activity a, Theme t, List<String> initialIds, final Picked cb) {
        new InstrumentPicker(a, t, true).show(a.getString(R.string.instruments), initialIds, cb);
    }

    private void show(String title, List<String> initialIds, final Picked cb) {
        if (initialIds != null) {
            for (String id : initialIds) {
                Instruments.Def d = Instruments.byId(id);
                if (d != null) chosen.add(d);
            }
        }
        LinearLayout body = Ui.column(a);
        body.setPadding(t.dp(16), t.dp(4), t.dp(16), 0);

        // search field (Material 3 search bar, as in the library)
        LinearLayout search = Ui.row(a);
        search.setBackground(Ui.round(t.surfaceContainerHigh, t.dp(28)));
        search.setPadding(t.dp(16), 0, t.dp(8), 0);
        ImageView si = new ImageView(a);
        si.setImageResource(R.drawable.ic_search);
        si.setImageTintList(ColorStateList.valueOf(t.onSurfaceVariant));
        si.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        search.addView(si, Ui.lin(t.dp(24), t.dp(24)));
        final EditText q = new EditText(a);
        q.setHint(R.string.search_instruments);
        q.setBackground(null);
        q.setSingleLine(true);
        q.setTextSize(16);
        q.setTextColor(t.onSurface);
        q.setHintTextColor(t.onSurfaceVariant);
        q.setPadding(t.dp(12), 0, t.dp(8), 0);
        q.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        q.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString();
                filter();
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        });
        search.addView(q, Ui.lin(0, t.dp(48), 1f));
        body.addView(search, Ui.lin(-1, -2));

        // groups
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.setHorizontalScrollBarEnabled(false);
        groupRow = Ui.row(a);
        groupRow.setPadding(0, t.dp(8), 0, t.dp(4));
        hs.addView(groupRow);
        addGroupChip(a.getString(R.string.all_instruments), null);
        for (String g : Instruments.groups()) addGroupChip(g, g);
        body.addView(hs);
        markGroups();

        // the selection (multi mode)
        if (multi) {
            chosenLabel = Ui.text(a, "", 12, t.primary, true);
            chosenLabel.setPadding(0, t.dp(4), 0, 0);
            body.addView(chosenLabel);
            HorizontalScrollView cs = new HorizontalScrollView(a);
            cs.setHorizontalScrollBarEnabled(false);
            chosenRow = Ui.row(a);
            chosenRow.setPadding(0, t.dp(4), 0, t.dp(4));
            cs.addView(chosenRow);
            body.addView(cs);
            showChosen();
        }

        // the list
        final ListView list = new ListView(a);
        list.setDivider(null);
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return shown.size();
            }

            @Override
            public Object getItem(int i) {
                return shown.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = row();
                Instruments.Def d = shown.get(i);
                LinearLayout col = (LinearLayout) v;
                ((TextView) col.getChildAt(0)).setText(d.label);
                ((TextView) col.getChildAt(1)).setText(group == null ? d.group : d.shortName);
                int n = 0;
                for (Instruments.Def c : chosen) if (c == d) n++;
                v.setSelected(multi && n > 0);
                ((TextView) col.getChildAt(0)).setTextColor(multi && n > 0 ? t.primary : t.onSurface);
                v.setContentDescription(d.label + ", " + d.group + (multi && n > 0 ? ", " + n + " selected" : ""));
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View v, int i, long id) {
                Instruments.Def d = shown.get(i);
                if (!multi) {
                    dialog.dismiss();
                    List<Instruments.Def> one = new ArrayList<Instruments.Def>();
                    one.add(d);
                    cb.picked(one);
                    return;
                }
                chosen.add(d);
                showChosen();
                adapter.notifyDataSetChanged();
            }
        });
        empty = Ui.text(a, a.getString(R.string.no_instruments_found), 14, t.onSurfaceVariant, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, t.dp(24), 0, t.dp(24));
        empty.setVisibility(View.GONE);
        body.addView(empty, Ui.lin(-1, -2));
        int h = Math.min(t.dp(440), (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5f));
        body.addView(list, Ui.lin(-1, h));
        filter();

        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title).setView(body).setNegativeButton(R.string.cancel, null);
        if (multi) {
            b.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface di, int w) {
                    if (!chosen.isEmpty()) cb.picked(new ArrayList<Instruments.Def>(chosen));
                }
            });
        }
        dialog = b.create();
        // keep the list in place when the keyboard opens for the search field
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();
    }

    private View row() {
        LinearLayout col = Ui.column(a);
        col.setMinimumHeight(t.dp(56));
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(t.dp(8), t.dp(6), t.dp(8), t.dp(6));
        col.addView(Ui.text(a, "", 16, t.onSurface, false));
        col.addView(Ui.text(a, "", 12, t.onSurfaceVariant, false));
        return col;
    }

    private void addGroupChip(String label, final String g) {
        TextView chip = Ui.text(a, label, 14, t.onSurfaceVariant, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(t.dp(16), t.dp(6), t.dp(16), t.dp(6));
        chip.setMinHeight(t.dp(32));
        chip.setTag(g);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                group = g;
                markGroups();
                filter();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(40));
        lp.rightMargin = t.dp(8);
        groupRow.addView(chip, lp);
    }

    private void markGroups() {
        for (int i = 0; i < groupRow.getChildCount(); i++) {
            TextView c = (TextView) groupRow.getChildAt(i);
            Object g = c.getTag();
            boolean on = g == null ? group == null : g.equals(group);
            c.setBackground(on ? Ui.rippleRound(t, t.primaryContainer, 8) : Ui.ripple(t, Ui.roundStroke(0, t.outlineVariant, t.dp(1), t.dp(8)), true));
            c.setTextColor(on ? t.onPrimaryContainer : t.onSurfaceVariant);
            c.setSelected(on);
        }
    }

    private void showChosen() {
        chosenRow.removeAllViews();
        chosenLabel.setText(chosen.isEmpty() ? a.getString(R.string.no_instruments_selected)
                : a.getString(R.string.instruments_selected, chosen.size()));
        for (int i = 0; i < chosen.size(); i++) {
            final int idx = i;
            Instruments.Def d = chosen.get(i);
            TextView chip = Ui.text(a, d.label + "  ✕", 14, t.onPrimaryContainer, true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(t.dp(12), t.dp(6), t.dp(12), t.dp(6));
            chip.setMinHeight(t.dp(32));
            chip.setBackground(Ui.rippleRound(t, t.primaryContainer, 8));
            chip.setContentDescription(a.getString(R.string.remove) + " " + d.label);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chosen.remove(idx);
                    showChosen();
                    adapter.notifyDataSetChanged();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, t.dp(40));
            lp.rightMargin = t.dp(8);
            chosenRow.addView(chip, lp);
        }
    }

    private void filter() {
        shown.clear();
        shown.addAll(Instruments.search(group, query));
        if (adapter != null) adapter.notifyDataSetChanged();
        if (empty != null) empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
