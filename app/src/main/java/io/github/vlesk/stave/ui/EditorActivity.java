package io.github.vlesk.stave.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import io.github.vlesk.stave.R;
import io.github.vlesk.stave.audio.Player;
import io.github.vlesk.stave.audio.Synth;
import io.github.vlesk.stave.edit.EditorState;
import io.github.vlesk.stave.engrave.Layout;
import io.github.vlesk.stave.engrave.Sym;
import io.github.vlesk.stave.io.MscxReader;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.ScoreStore;
import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Dynamics;
import io.github.vlesk.stave.model.Instruments;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.Tuplet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The score editor: score canvas, note-input toolbar, piano keyboard, palettes and playback. */
public final class EditorActivity extends Activity implements ScoreView.Listener {
    public static final String EXTRA_ID = "score_id";
    private static final int REQ_EXPORT = 10;

    private Theme t;
    private ScoreStore store;
    private String scoreId;
    private EditorState st;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Synth synth;
    private Player player;
    private int savedRevision = 0;
    private int pendingExportFormat = -1;

    // views
    private FrameLayout root;
    private ScoreView view;
    private PianoView piano;
    private View pianoBox;
    private TextView titleView, statusView;
    private ImageButton undoBtn, redoBtn, playBtn, chordBtn, kbdBtn;
    private LinearLayout lyricBar;
    private LinearLayout fab;
    private android.widget.ImageView fabIcon;
    private TextView fabLabel;
    private FrameLayout content;
    private HorizontalScrollView floatingBar;
    private LinearLayout floatingRow, inputDock;
    private View tipCard;
    private Ui.Snackbar snack;
    private final TextView[] durBtns = new TextView[6];
    private static final int[] DUR_TYPES = {Duration.N32, Duration.N16, Duration.EIGHTH, Duration.QUARTER, Duration.HALF, Duration.WHOLE};
    private TextView dotBtn, tripletBtn, slurBtn, crescBtn, dimBtn;
    private final TextView[] voiceBtns = new TextView[4];
    private FrameLayout sheetHost;
    private PaletteView palette;
    private boolean pianoVisible = true;
    private Object backCallback;
    private boolean backRegistered;

    private final Runnable autosave = new Runnable() {
        @Override
        public void run() {
            save(false);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t = new Theme(this);
        store = new ScoreStore(getFilesDir());
        synth = new Synth();
        player = new Player(this);
        buildUi();
        Intent in = getIntent();
        String id = in.getStringExtra(EXTRA_ID);
        if (b != null && b.getString(EXTRA_ID) != null) id = b.getString(EXTRA_ID);
        if (id != null) {
            openId(id);
        } else {
            Uri uri = in.getData();
            if (uri == null && Intent.ACTION_SEND.equals(in.getAction())) uri = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) importUri(uri);
            else finish();
        }
    }

    private void openId(String id) {
        try {
            Score s = store.load(id);
            scoreId = id;
            bindScore(s);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void importUri(final Uri uri) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream ins = getContentResolver().openInputStream(uri);
                    byte[] data = Mscz.readAll(ins);
                    ins.close();
                    String name = uri.getLastPathSegment();
                    final MscxReader.Result r = Mscz.load(data, name);
                    final String id = store.newId();
                    store.save(id, r.score, Exporter.thumbnail(EditorActivity.this, r.score, 360));
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            scoreId = id;
                            bindScore(r.score);
                            showWarnings(r.warnings);
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(EditorActivity.this, getString(R.string.import_failed, String.valueOf(e.getMessage())), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                }
            }
        });
    }

    private void showWarnings(List<String> w) {
        if (w == null || w.isEmpty()) return;
        List<String> uniq = new ArrayList<String>();
        for (String s : w) if (!uniq.contains(s)) uniq.add(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, uniq.size()); i++) sb.append("• ").append(uniq.get(i)).append('\n');
        if (uniq.size() > 8) sb.append("…");
        new AlertDialog.Builder(this).setTitle(R.string.import_warnings).setMessage(sb.toString().trim())
                .setPositiveButton(R.string.ok, null).show();
    }

    private void bindScore(Score s) {
        st = new EditorState(s);
        savedRevision = st.revision;
        view.bind(st, this);
        piano.centerOn(Clef.middlePitch(s.clefAt(0, 0)) + 2);
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (scoreId != null) out.putString(EXTRA_ID, scoreId);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player.isPlaying()) player.stop();
        handler.removeCallbacks(autosave);
        save(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synth.release();
        io.shutdown();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        // rebuild the toolbars for the new width (one or two rows) and re-flow the score
        boolean input = st != null && st.inputMode;
        // keep the same place in the score (and the zoom) across the new layout
        ScoreView.Anchor anchor = st == null || view == null ? null : view.anchor(st.inputMode && st.cursor != null ? st.cursor : st.selPos);
        if (paletteOpen()) closePalette();
        lyricInputOn = false;
        lyricBarListener = null;
        buildUi();
        if (st != null) {
            view.bind(st, this);
            if (input) setInputMode(true);
            refresh();
            view.restoreAnchor(anchor);
        }
    }

    // ------------------------------------------------------------------ UI construction

    private boolean wide() {
        return getResources().getConfiguration().screenWidthDp >= 720;
    }

    private void buildUi() {
        Configuration cfg = getResources().getConfiguration();
        boolean landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean tablet = cfg.smallestScreenWidthDp >= 600;
        root = new FrameLayout(this);
        root.setBackgroundColor(t.surface);
        LinearLayout col = Ui.column(this);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // top app bar: navigation, title + live status line, undo/redo, play, overflow
        LinearLayout top = Ui.row(this);
        top.setPadding(t.dp(4), 0, t.dp(4), 0);
        top.setBackgroundColor(t.surface);
        top.setMinimumHeight(t.dp(landscape && !tablet ? 52 : 64));
        top.addView(Ui.icon(this, t, R.drawable.ic_arrow_back, getString(R.string.navigate_up), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        LinearLayout titles = Ui.column(this);
        titles.setPadding(t.dp(4), 0, t.dp(4), 0);
        titleView = Ui.title(this, t, "");
        titleView.setTextSize(20);
        statusView = Ui.text(this, "", 12, t.onSurfaceVariant, false);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        titles.addView(titleView);
        titles.addView(statusView);
        titles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                propertiesDialog();
            }
        });
        top.addView(titles, Ui.lin(0, -2, 1f));
        undoBtn = Ui.icon(this, t, R.drawable.ic_undo, getString(R.string.undo), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoRedo(false);
                refresh();
            }
        });
        redoBtn = Ui.icon(this, t, R.drawable.ic_redo, getString(R.string.redo), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoRedo(true);
                refresh();
            }
        });
        playBtn = Ui.icon(this, t, R.drawable.ic_play_arrow, getString(R.string.play), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        final ImageButton more = Ui.icon(this, t, R.drawable.ic_more_vert, getString(R.string.more_options), null);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(more);
            }
        });
        top.addView(undoBtn);
        top.addView(redoBtn);
        top.addView(playBtn);
        top.addView(more);
        col.addView(top, Ui.lin(-1, -2));

        // score area: score canvas + floating contextual toolbar + extended FAB + snackbars
        content = new FrameLayout(this);
        view = new ScoreView(this, t);
        content.addView(view, new FrameLayout.LayoutParams(-1, -1));
        view.setScrollWatcher(new ScoreView.ScrollWatcher() {
            @Override
            public void scrolled(float dy) {
                if (Math.abs(dy) > t.dp(2)) setFabExtended(dy < 0);
            }
        });
        buildTip();
        buildFloatingToolbar();
        buildFab();
        snack = new Ui.Snackbar(content, t);
        col.addView(content, Ui.lin(-1, 0, 1f));

        // docked note-input toolbar (only in note input mode)
        inputDock = Ui.column(this);
        inputDock.setBackgroundColor(t.surfaceContainer);
        LinearLayout rowA = Ui.row(this);
        LinearLayout rowB = Ui.row(this);
        rowA.setPadding(t.dp(8), t.dp(6), t.dp(8), t.dp(2));
        rowB.setPadding(t.dp(4), 0, t.dp(4), 0);
        buildDurationRow(rowA);
        buildInputTools(rowB);
        if (wide()) {
            LinearLayout both = Ui.row(this);
            both.addView(rowA);
            both.addView(rowB);
            inputDock.addView(scroll(both));
        } else {
            inputDock.addView(scroll(rowA));
            inputDock.addView(scroll(rowB));
        }
        LinearLayout pianoRow = Ui.row(this);
        pianoRow.addView(Ui.icon(this, t, R.drawable.ic_chevron_left, getString(R.string.octave_down), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                piano.shiftOctave(-1);
            }
        }), new LinearLayout.LayoutParams(t.dp(40), -1));
        piano = new PianoView(this, t);
        piano.setListener(new PianoView.Listener() {
            @Override
            public void onKey(int pitch, boolean withHeldKeys) {
                pianoKey(pitch, withHeldKeys);
            }
        });
        pianoRow.addView(piano, Ui.lin(0, -1, 1f));
        pianoRow.addView(Ui.icon(this, t, R.drawable.ic_chevron_right, getString(R.string.octave_up), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                piano.shiftOctave(1);
            }
        }), new LinearLayout.LayoutParams(t.dp(40), -1));
        int ph = tablet ? 180 : (landscape ? 104 : 140);
        pianoBox = pianoRow;
        inputDock.addView(pianoRow, Ui.lin(-1, t.dp(ph)));
        inputDock.setVisibility(View.GONE);
        col.addView(inputDock, Ui.lin(-1, -2));

        buildLyricBar();

        // palette sheet host (overlay)
        sheetHost = new FrameLayout(this);
        sheetHost.setVisibility(View.GONE);
        root.addView(sheetHost, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        // system bar appearance from the shared helper; bottom insets go to the dock when it is
        // shown, otherwise the floating elements are lifted above the navigation bar
        Ui.edgeToEdge(this, t, top, null, col);
        getWindow().getDecorView().setOnApplyWindowInsetsListener(insetsListener(top, col));
    }

    private int navInset, imeInset;

    private static final int LYRIC_BAR_DP = 56;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener lyricBarListener;

    /**
     * The bar shown while typing lyrics. It is docked directly above the keyboard, where the thumbs
     * are, and is tinted so it reads as its own mode. It holds previous/next note, a hyphen key
     * and an underscore key for extender lines (phone keyboards bury both), and a Done button.
     */
    private void buildLyricBar() {
        lyricBar = Ui.row(this);
        lyricBar.setBackgroundColor(t.primaryContainer);
        lyricBar.setElevation(t.dp(8));
        lyricBar.setPadding(t.dp(8), 0, t.dp(8), 0);
        android.widget.ImageView mode = new android.widget.ImageView(this);
        mode.setImageResource(R.drawable.ic_text_fields);
        mode.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimaryContainer));
        mode.setContentDescription("Lyric entry");
        mode.setPadding(t.dp(4), 0, t.dp(8), 0);
        lyricBar.addView(mode, Ui.lin(t.dp(36), t.dp(24)));
        if (getResources().getConfiguration().screenWidthDp < 360) mode.setVisibility(View.GONE);
        ImageButton prev = Ui.icon(this, t, R.drawable.ic_chevron_left, "Previous note", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.lyricPrevious();
                refresh();
            }
        });
        prev.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimaryContainer));
        lyricBar.addView(prev);
        TextView hyphen = Ui.text(this, "-", 26, t.onPrimaryContainer, true);
        hyphen.setGravity(Gravity.CENTER);
        hyphen.setBackground(Ui.rippleRound(t, android.graphics.Color.TRANSPARENT, 24));
        hyphen.setClickable(true);
        hyphen.setContentDescription("Hyphen, then next note");
        if (Build.VERSION.SDK_INT >= 26) hyphen.setTooltipText("Hyphen");
        hyphen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.lyricType("-");
                refresh();
            }
        });
        lyricBar.addView(hyphen, Ui.lin(t.dp(48), t.dp(48)));
        TextView underscore = Ui.text(this, "_", 24, t.onPrimaryContainer, true);
        underscore.setGravity(Gravity.CENTER);
        underscore.setBackground(Ui.rippleRound(t, android.graphics.Color.TRANSPARENT, 24));
        underscore.setClickable(true);
        underscore.setContentDescription("Extender line (melisma), then next note");
        if (Build.VERSION.SDK_INT >= 26) underscore.setTooltipText("Extender line (melisma)");
        underscore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.lyricType("_");
                refresh();
            }
        });
        lyricBar.addView(underscore, Ui.lin(t.dp(48), t.dp(48)));
        ImageButton next = Ui.icon(this, t, R.drawable.ic_chevron_right, "Next note", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.lyricNext();
                refresh();
            }
        });
        next.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimaryContainer));
        lyricBar.addView(next);
        lyricBar.addView(new View(this), Ui.lin(0, 1, 1f));
        // a standard Done button: tick and label on a filled pill
        LinearLayout done = Ui.row(this);
        done.setBackground(Ui.rippleRound(t, t.primary, 20));
        done.setPadding(t.dp(14), 0, t.dp(18), 0);
        done.setClickable(true);
        done.setContentDescription(getString(R.string.done) + ", leave lyric entry");
        android.widget.ImageView tick = new android.widget.ImageView(this);
        tick.setImageResource(R.drawable.ic_check);
        tick.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimary));
        done.addView(tick, Ui.lin(t.dp(20), t.dp(20)));
        TextView doneLabel = Ui.text(this, getString(R.string.done), 15, t.onPrimary, true);
        doneLabel.setPadding(t.dp(8), 0, 0, 0);
        done.addView(doneLabel);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                st.endLyric();
                refresh();
            }
        });
        lyricBar.addView(done, Ui.lin(-2, t.dp(40)));
        lyricBar.setVisibility(View.GONE);
        root.addView(lyricBar, Ui.frame(-1, t.dp(LYRIC_BAR_DP), Gravity.BOTTOM));
    }

    /**
     * Docks the lyric bar right above the on-screen keyboard (or the bottom edge when there is none)
     * and tells the score how much of its bottom is covered, so it can keep the lyric in view.
     */
    private void updateLyricBar() {
        if (lyricBar == null || view == null) return;
        boolean on = st != null && st.lyricEdit;
        lyricBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            view.setLyricObstruction(0);
            return;
        }
        android.graphics.Rect frame = new android.graphics.Rect();
        root.getWindowVisibleDisplayFrame(frame); // excludes the keyboard
        int[] loc = new int[2];
        root.getLocationOnScreen(loc);
        int keyboard = Math.max(0, loc[1] + root.getHeight() - frame.bottom);
        // the keyboard's height as reported by the window insets (needs adjustResize in the manifest),
        // with the visible-frame measurement as a second source; the bar sits above the highest of them
        int margin = Math.max(Math.max(navInset, imeInset), keyboard);
        View col = root.getChildAt(0);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lyricBar.getLayoutParams();
        if (lp.bottomMargin != margin || lp.leftMargin != col.getPaddingLeft() || lp.rightMargin != col.getPaddingRight()) {
            lp.setMargins(col.getPaddingLeft(), 0, col.getPaddingRight(), margin);
            lyricBar.setLayoutParams(lp);
        }
        view.setLyricObstruction(t.dp(LYRIC_BAR_DP) + margin);
    }

    @SuppressWarnings("deprecation")
    private View.OnApplyWindowInsetsListener insetsListener(final View top, final View col) {
        final int topBase = top.getPaddingTop();
        return new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                int left = in.getSystemWindowInsetLeft(), right = in.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                    left = Math.max(left, in.getDisplayCutout().getSafeInsetLeft());
                    right = Math.max(right, in.getDisplayCutout().getSafeInsetRight());
                }
                top.setPadding(top.getPaddingLeft(), topBase + in.getSystemWindowInsetTop(), top.getPaddingRight(), top.getPaddingBottom());
                col.setPadding(left, 0, right, 0);
                navInset = in.getSystemWindowInsetBottom();
                imeInset = Build.VERSION.SDK_INT >= 30 ? in.getInsets(android.view.WindowInsets.Type.ime()).bottom : 0;
                applyBottomInset();
                updateLyricBar();
                return v.onApplyWindowInsets(in);
            }
        };
    }

    /** The navigation-bar inset pads whichever surface touches the bottom edge. */
    private void applyBottomInset() {
        boolean dock = inputDock.getVisibility() == View.VISIBLE;
        inputDock.setPadding(0, 0, 0, dock ? navInset : 0);
        content.setPadding(0, 0, 0, 0);
        int base = dock ? 0 : navInset;
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) fab.getLayoutParams();
        fl.bottomMargin = t.dp(16) + base;
        fab.setLayoutParams(fl);
        FrameLayout.LayoutParams tl = (FrameLayout.LayoutParams) floatingBar.getLayoutParams();
        tl.bottomMargin = t.dp(16) + base;
        floatingBar.setLayoutParams(tl);
        boolean barShown = floatingBar.getVisibility() == View.VISIBLE;
        snack.setBottomMargin(base + (barShown || fab.getVisibility() == View.VISIBLE ? t.dp(72) : 0));
    }

    private HorizontalScrollView scroll(View v) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(false);
        hs.addView(v);
        return hs;
    }

    // ------------------------------------------------------------------ extended FAB

    private void buildFab() {
        fab = Ui.row(this);
        fab.setBackground(Ui.rippleRound(t, t.primaryContainer, 16));
        fab.setElevation(t.dp(6));
        fab.setPadding(t.dp(16), 0, t.dp(16), 0);
        fab.setClickable(true);
        fab.setFocusable(true);
        fabIcon = new android.widget.ImageView(this);
        fabIcon.setImageResource(R.drawable.ic_edit);
        fabIcon.setImageTintList(android.content.res.ColorStateList.valueOf(t.onPrimaryContainer));
        fab.addView(fabIcon, Ui.lin(t.dp(24), t.dp(24)));
        fabLabel = Ui.text(this, getString(R.string.write), 16, t.onPrimaryContainer, true);
        fabLabel.setPadding(t.dp(12), 0, t.dp(4), 0);
        fab.addView(fabLabel);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setInputMode(!st.inputMode);
            }
        });
        FrameLayout.LayoutParams flp = Ui.frame(-2, t.dp(56), Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, t.dp(16), t.dp(16));
        content.addView(fab, flp);
    }

    private boolean fabExtended = true;

    /** Extended FAB collapses to an icon while scrolling down and expands again when scrolling up. */
    private void setFabExtended(boolean extended) {
        if (fab == null) return;
        if (floatingBar != null && floatingBar.getVisibility() == View.VISIBLE) extended = false;
        if (fabExtended == extended) return;
        fabExtended = extended;
        fabLabel.setVisibility(extended ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ first-run tip

    private void buildTip() {
        final android.content.SharedPreferences prefs = getSharedPreferences("ui", MODE_PRIVATE);
        if (prefs.getBoolean("tip_editor_seen", false)) return;
        final LinearLayout card = Ui.column(this);
        card.setBackground(Ui.round(t.surfaceContainerHigh, t.dp(16)));
        card.setElevation(t.dp(2));
        card.setPadding(t.dp(16), t.dp(14), t.dp(8), t.dp(4));
        TextView title = Ui.text(this, getString(R.string.tip_title), 16, t.onSurface, true);
        card.addView(title);
        TextView body = Ui.text(this, getString(R.string.tip_body), 14, t.onSurfaceVariant, false);
        body.setPadding(0, t.dp(4), t.dp(8), 0);
        card.addView(body);
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.spacer(this));
        actions.addView(Ui.button(this, t, getString(R.string.got_it), 2, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean("tip_editor_seen", true).apply();
                content.removeView(card);
            }
        }));
        card.addView(actions);
        FrameLayout.LayoutParams lp = Ui.frame(Math.min(t.dp(560), getResources().getDisplayMetrics().widthPixels - t.dp(32)), -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = t.dp(8);
        content.addView(card, lp);
        tipCard = card;
    }

    // ------------------------------------------------------------------ floating contextual toolbar

    private void buildFloatingToolbar() {
        floatingBar = new HorizontalScrollView(this);
        floatingBar.setHorizontalScrollBarEnabled(false);
        floatingBar.setBackground(Ui.round(t.surfaceContainerHigh, t.dp(28)));
        floatingBar.setElevation(t.dp(6));
        floatingRow = Ui.row(this);
        floatingRow.setPadding(t.dp(6), t.dp(4), t.dp(6), t.dp(4));
        floatingBar.addView(floatingRow);
        FrameLayout.LayoutParams lp = Ui.frame(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(t.dp(16), 0, t.dp(16) + t.dp(56) + t.dp(12), t.dp(16));
        floatingBar.setVisibility(View.GONE);
        content.addView(floatingBar, lp);
    }

    /** Rebuilds the floating toolbar for the current kind of selection. */
    private void updateFloatingToolbar() {
        boolean show = !st.inputMode && !st.lyricEdit && st.hasSelection() && !paletteOpen();
        floatingBar.setVisibility(show ? View.VISIBLE : View.GONE);
        // the FAB pairs with the floating toolbar as a compact (icon-only) FAB
        fabExtended = !show;
        fabLabel.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) return;
        floatingRow.removeAllViews();
        final ChordRest cr = st.selectedChordRest();
        if (st.selKind != EditorState.SEL_RANGE && cr != null) {
            // split button: current duration, opens the duration group
            final TextView dur = Ui.glyphButton(this, t, Sym.str(durGlyph(cr.type)) + (cr.dots > 0 ? Sym.str(Sym.metAugmentationDot) : ""),
                    true, 20, getString(R.string.change_duration), null);
            dur.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    durationPopup(dur, cr);
                }
            });
            dur.setLayoutParams(new LinearLayout.LayoutParams(t.dp(56), t.dp(48)));
            dur.setBackground(Ui.rippleRound(t, t.primaryContainer, 24));
            dur.setTextColor(t.onPrimaryContainer);
            floatingRow.addView(dur);
        }
        if (st.selKind == EditorState.SEL_RANGE || cr instanceof Chord) {
            floatingRow.addView(Ui.icon(this, t, R.drawable.ic_keyboard_arrow_up, getString(R.string.up), transposeClick(1)));
            floatingRow.addView(Ui.icon(this, t, R.drawable.ic_keyboard_arrow_down, getString(R.string.down), transposeClick(-1)));
        }
        if (st.selKind == EditorState.SEL_NOTE && st.selectedNote() != null) {
            final TextView acc = Ui.glyphButton(this, t, Sym.str(Sym.accidentalSharp), true, 20,
                    getString(R.string.accidentals), null);
            acc.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    accidentalPopup(acc);
                }
            });
            floatingRow.addView(acc);
            floatingRow.addView(Ui.glyphButton(this, t, "⁀", false, 24, getString(R.string.tie), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!st.toggleTie()) snack.show(getString(R.string.tie_needs_same_pitch));
                    refresh();
                }
            }));
        }
        if (st.selKind == EditorState.SEL_RANGE) {
            floatingRow.addView(Ui.icon(this, t, R.drawable.ic_content_copy, getString(R.string.copy), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st.copy()) snack.show(getString(R.string.copied));
                }
            }));
            floatingRow.addView(Ui.icon(this, t, R.drawable.ic_content_paste, getString(R.string.paste), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pasteWithUndo();
                }
            }));
        }
        floatingRow.addView(Ui.icon(this, t, R.drawable.ic_delete, getString(R.string.delete_selection), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteWithUndo();
            }
        }));
        floatingRow.addView(Ui.icon(this, t, R.drawable.ic_palette, getString(R.string.palettes), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPalette();
            }
        }));
        final ImageButton moreBtn = Ui.icon(this, t, R.drawable.ic_more_vert, getString(R.string.more_actions), null);
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectionMenu(moreBtn);
            }
        });
        floatingRow.addView(moreBtn);
    }

    private static int durGlyph(int type) {
        switch (type) {
            case Duration.WHOLE:
                return Sym.metNoteWhole;
            case Duration.HALF:
                return Sym.metNoteHalfUp;
            case Duration.QUARTER:
                return Sym.metNoteQuarterUp;
            case Duration.EIGHTH:
                return Sym.metNote8thUp;
            case Duration.N16:
                return Sym.metNote16thUp;
            default:
                return Sym.metNote32ndUp;
        }
    }

    private void durationPopup(View anchor, final ChordRest cr) {
        List<Ui.PopupItem> items = new ArrayList<Ui.PopupItem>();
        for (final int type : DUR_TYPES) {
            items.add(new Ui.PopupItem(Sym.str(durGlyph(type)), true, Duration.label(type, 0), cr.type == type && cr.dots == 0, new Runnable() {
                @Override
                public void run() {
                    st.dots = 0;
                    st.setDuration(type);
                    refresh();
                }
            }));
        }
        items.add(new Ui.PopupItem(Sym.str(Sym.metAugmentationDot), true, getString(R.string.dot), cr.dots > 0, new Runnable() {
            @Override
            public void run() {
                st.durType = cr.type;
                st.toggleDot();
                refresh();
            }
        }));
        Ui.popupGroup(anchor, t, items);
    }

    private void accidentalPopup(View anchor) {
        int[] acc = {Sym.accidentalDoubleFlat, Sym.accidentalFlat, Sym.accidentalNatural, Sym.accidentalSharp, Sym.accidentalDoubleSharp};
        int[] desc = {R.string.double_flat, R.string.flat, R.string.natural, R.string.sharp, R.string.double_sharp};
        Note n = st.selectedNote();
        int cur = n == null ? 99 : Pitch.alter(n.tpc);
        List<Ui.PopupItem> items = new ArrayList<Ui.PopupItem>();
        for (int i = 0; i < acc.length; i++) {
            final int alter = i - 2;
            items.add(new Ui.PopupItem(Sym.str(acc[i]), true, getString(desc[i]), cur == alter, new Runnable() {
                @Override
                public void run() {
                    st.applyAccidental(alter);
                    previewSelected();
                    refresh();
                }
            }));
        }
        items.add(new Ui.PopupItem("⇆", false, getString(R.string.respell), false, new Runnable() {
            @Override
            public void run() {
                st.respell();
                refresh();
            }
        }));
        Ui.popupGroup(anchor, t, items);
    }

    private void selectionMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        boolean chord = st.selectedChordRest() instanceof Chord;
        if (chord) m.add(0, 1, 0, R.string.lyrics);
        m.add(0, 2, 0, R.string.chord_symbol);
        m.add(0, 3, 0, R.string.staff_text);
        if (chord) {
            m.add(0, 4, 0, R.string.octave_up);
            m.add(0, 5, 0, R.string.octave_down);
        }
        m.add(0, 6, 0, R.string.copy);
        if (st.hasClipboard()) m.add(0, 7, 0, R.string.paste);
        m.add(0, 8, 0, R.string.insert_measure);
        m.add(0, 9, 0, R.string.delete_measures);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        startLyricEntry();
                        break;
                    case 2:
                        textDialog(getString(R.string.chord_symbol), 3);
                        break;
                    case 3:
                        textDialog(getString(R.string.staff_text), 2);
                        break;
                    case 4:
                        st.transpose(12);
                        previewSelected();
                        break;
                    case 5:
                        st.transpose(-12);
                        previewSelected();
                        break;
                    case 6:
                        if (st.copy()) snack.show(getString(R.string.copied));
                        break;
                    case 7:
                        pasteWithUndo();
                        break;
                    case 8:
                        st.insertMeasureBefore();
                        break;
                    case 9:
                        int before = st.score.measures.size();
                        st.deleteSelectedMeasures();
                        if (st.score.measures.size() < before) undoSnack(getString(R.string.measures_deleted));
                        break;
                    default:
                        return false;
                }
                refresh();
                return true;
            }
        });
        pm.show();
    }

    /** Deletes and offers Undo in a snackbar (instead of asking for confirmation). */
    private void deleteWithUndo() {
        if (st == null || !st.hasSelection()) return;
        int rev = st.revision;
        st.deleteSelection();
        refresh();
        if (st.revision != rev) undoSnack(getString(R.string.deleted));
    }

    private void pasteWithUndo() {
        if (st == null || !st.hasClipboard()) {
            snack.show(getString(R.string.nothing_to_paste));
            return;
        }
        st.paste();
        refresh();
        undoSnack(getString(R.string.pasted));
    }

    private void undoSnack(String msg) {
        snack.show(msg, getString(R.string.undo), new Runnable() {
            @Override
            public void run() {
                if (st.canUndo()) st.undo();
                refresh();
            }
        });
    }

    // ------------------------------------------------------------------ docked note-input toolbar

    private void buildDurationRow(LinearLayout row) {
        int[] glyphs = {Sym.metNote32ndUp, Sym.metNote16thUp, Sym.metNote8thUp, Sym.metNoteQuarterUp, Sym.metNoteHalfUp, Sym.metNoteWhole};
        LinearLayout group = Ui.buttonGroup(this, t);
        for (int i = 0; i < DUR_TYPES.length; i++) {
            final int type = DUR_TYPES[i];
            durBtns[i] = Ui.glyphButton(this, t, Sym.str(glyphs[i]), true, 22, Duration.label(type, 0), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st == null) return;
                    st.setDuration(type);
                    refresh();
                }
            });
            durBtns[i].setLayoutParams(new LinearLayout.LayoutParams(t.dp(48), t.dp(44)));
            group.addView(durBtns[i]);
        }
        row.addView(group);
        View gap = new View(this);
        row.addView(gap, Ui.lin(t.dp(8), 1));
        LinearLayout mods = Ui.buttonGroup(this, t);
        dotBtn = Ui.glyphButton(this, t, Sym.str(Sym.metAugmentationDot), true, 22, getString(R.string.dot), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.toggleDot();
                refresh();
            }
        });
        dotBtn.setLayoutParams(new LinearLayout.LayoutParams(t.dp(48), t.dp(44)));
        mods.addView(dotBtn);
        tripletBtn = Ui.glyphButton(this, t, Sym.str(Sym.tuplet3), true, 22, getString(R.string.triplet), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.setTuplet(st.triplet ? 0 : st.tupletActual);
                refresh();
            }
        });
        // long press: duplet, triplet, quadruplet, quintuplet, sextuplet
        tripletBtn.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (st == null) return false;
                tupletPopup(v);
                return true;
            }
        });
        tripletBtn.setLayoutParams(new LinearLayout.LayoutParams(t.dp(48), t.dp(44)));
        mods.addView(tripletBtn);
        row.addView(mods);
    }

    /** Slur (0) or hairpin: in note input it opens or closes; on a selection it adds or extends. */
    void lineAction(int kind) {
        if (st == null) return;
        boolean ok = kind == 0 ? st.slur() : st.hairpin(kind);
        if (!ok) {
            snack.show(st.inputMode ? "Enter a note first" : getString(R.string.nothing_selected));
            return;
        }
        refresh();
    }

    private int graceKind = Chord.ACCIACCATURA, graceType = Duration.EIGHTH;

    void addGrace(int kind, int type) {
        if (st == null) return;
        graceKind = kind;
        graceType = type;
        if (!st.addGrace(kind, type)) {
            snack.show(getString(R.string.nothing_selected));
            return;
        }
        refresh();
    }

    private void gracePopup(View anchor) {
        List<Ui.PopupItem> items = new ArrayList<Ui.PopupItem>();
        String[] labels = {"acc.", "app.", "app. 16th"};
        String[] desc = {"Acciaccatura (short, slashed)", "Appoggiatura", "Appoggiatura, 16th"};
        final int[][] kinds = {{Chord.ACCIACCATURA, Duration.EIGHTH}, {Chord.APPOGGIATURA, Duration.EIGHTH}, {Chord.APPOGGIATURA, Duration.N16}};
        for (int i = 0; i < kinds.length; i++) {
            final int[] k = kinds[i];
            items.add(new Ui.PopupItem(labels[i], false, desc[i], graceKind == k[0] && graceType == k[1], new Runnable() {
                @Override
                public void run() {
                    addGrace(k[0], k[1]);
                }
            }));
        }
        Ui.popupGroup(anchor, t, items);
    }

    private void tupletPopup(View anchor) {
        List<Ui.PopupItem> items = new ArrayList<Ui.PopupItem>();
        for (final int n : Tuplet.OFFERED) {
            String desc = Tuplet.name(n) + " (" + n + ":" + Tuplet.normalFor(n) + ")";
            items.add(new Ui.PopupItem(Sym.str(Sym.tuplet0 + n), true, desc, st.triplet && st.tupletActual == n, new Runnable() {
                @Override
                public void run() {
                    st.setTuplet(st.triplet && st.tupletActual == n ? 0 : n);
                    refresh();
                }
            }));
        }
        Ui.popupGroup(anchor, t, items);
    }

    private void buildInputTools(LinearLayout row) {
        row.addView(Ui.labeled(this, t, Ui.glyphButton(this, t, Sym.str(Sym.restQuarter), true, 20, getString(R.string.rest), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.enterRest();
                refresh();
                followCursor();
            }
        }), getString(R.string.rest)));
        row.addView(Ui.labeled(this, t, Ui.glyphButton(this, t, "⁀", false, 24, getString(R.string.tie), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                if (!st.enterTie()) snack.show(getString(R.string.tie_needs_note));
                refresh();
                followCursor();
            }
        }), getString(R.string.tie)));
        // grace note: added before the note just entered (or the selected one); its pitch comes next
        TextView grace = Ui.glyphButton(this, t, Sym.str(Sym.metNote8thUp), true, 18, "Grace note", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addGrace(graceKind, graceType);
            }
        });
        grace.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                gracePopup(v);
                return true;
            }
        });
        row.addView(Ui.labeled(this, t, grace, "Grace"));
        // slur and hairpins follow the notes entered after them until tapped again (MuseScore S, <, >)
        slurBtn = Ui.glyphButton(this, t, "⌒", false, 24, "Slur", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lineAction(0);
            }
        });
        row.addView(Ui.labeled(this, t, slurBtn, "Slur"));
        crescBtn = Ui.glyphButton(this, t, "<", false, 22, "Crescendo hairpin", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lineAction(ChordRest.CRESCENDO);
            }
        });
        row.addView(Ui.labeled(this, t, crescBtn, "cresc."));
        dimBtn = Ui.glyphButton(this, t, ">", false, 22, "Diminuendo hairpin", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                lineAction(ChordRest.DIMINUENDO);
            }
        });
        row.addView(Ui.labeled(this, t, dimBtn, "dim."));
        int[] acc = {Sym.accidentalFlat, Sym.accidentalNatural, Sym.accidentalSharp};
        int[] accDesc = {R.string.flat, R.string.natural, R.string.sharp};
        String[] accLabel = {"♭", "♮", "♯"};
        for (int i = 0; i < acc.length; i++) {
            final int alter = i - 1;
            row.addView(Ui.labeled(this, t, Ui.glyphButton(this, t, Sym.str(acc[i]), true, 20, getString(accDesc[i]), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st == null) return;
                    if (st.selectedNote() == null) {
                        snack.show(getString(R.string.nothing_selected));
                        return;
                    }
                    st.applyAccidental(alter);
                    previewSelected();
                    refresh();
                }
            }), getString(accDesc[i])));
        }
        // the piano spells by key; respell gets the other spellings (B natural / C flat, C sharp / D flat)
        row.addView(Ui.labeled(this, t, Ui.glyphButton(this, t, "⇆", false, 20, getString(R.string.respell), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                if (!st.respell()) {
                    snack.show(getString(R.string.nothing_selected));
                    return;
                }
                previewSelected();
                refresh();
            }
        }), "Respell"));
        chordBtn = Ui.icon(this, t, R.drawable.ic_add_box, getString(R.string.chord_mode), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.chordMode = !st.chordMode;
                refresh();
            }
        });
        row.addView(Ui.labeled(this, t, chordBtn, getString(R.string.chord_short)));
        // voices as a compact connected group
        LinearLayout voices = Ui.buttonGroup(this, t);
        for (int i = 0; i < 4; i++) {
            final int v = i;
            voiceBtns[i] = Ui.glyphButton(this, t, String.valueOf(i + 1), false, 15, getString(R.string.voice, i + 1), new View.OnClickListener() {
                @Override
                public void onClick(View vv) {
                    if (st == null) return;
                    st.setVoice(v);
                    refresh();
                }
            });
            voiceBtns[i].setLayoutParams(new LinearLayout.LayoutParams(t.dp(34), t.dp(36)));
            voices.addView(voiceBtns[i]);
        }
        LinearLayout vcol = Ui.column(this);
        vcol.setGravity(Gravity.CENTER_HORIZONTAL);
        vcol.setPadding(t.dp(6), t.dp(4), t.dp(6), t.dp(4));
        vcol.addView(voices);
        TextView vl = Ui.text(this, getString(R.string.voice_short), 11, t.onSurfaceVariant, true);
        vl.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        vcol.addView(vl);
        row.addView(vcol);
        row.addView(Ui.labeled(this, t, Ui.icon(this, t, R.drawable.ic_backspace, getString(R.string.delete_selection), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteWithUndo();
            }
        }), getString(R.string.delete)));
        kbdBtn = Ui.icon(this, t, R.drawable.ic_piano, getString(R.string.keyboard), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pianoVisible = !pianoVisible;
                refresh();
            }
        });
        row.addView(Ui.labeled(this, t, kbdBtn, getString(R.string.keys_short)));
        row.addView(Ui.labeled(this, t, Ui.icon(this, t, R.drawable.ic_palette, getString(R.string.palettes), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPalette();
            }
        }), getString(R.string.palettes)));
        row.addView(Ui.labeled(this, t, Ui.icon(this, t, R.drawable.ic_text_fields, getString(R.string.lyrics), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLyricEntry();
            }
        }), getString(R.string.lyrics)));
    }

    private View.OnClickListener transposeClick(final int semis) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (st == null) return;
                st.transpose(semis);
                previewSelected();
                refresh();
            }
        };
    }

    // ------------------------------------------------------------------ state -> UI

    private void refresh() {
        if (st == null) return;
        if (st.lyricEdit && st.selKind != EditorState.SEL_CHORDREST) st.endLyric();
        syncLyricInput();
        view.refresh();
        titleView.setText(st.score.title);
        statusView.setText(statusText());
        undoBtn.setEnabled(st.canUndo());
        undoBtn.setAlpha(st.canUndo() ? 1f : 0.38f);
        redoBtn.setEnabled(st.canRedo());
        redoBtn.setAlpha(st.canRedo() ? 1f : 0.38f);
        for (int i = 0; i < durBtns.length; i++) Ui.setGroupSelected(t, durBtns[i], st.durType == DUR_TYPES[i]);
        Ui.setGroupSelected(t, dotBtn, st.dots > 0);
        dotBtn.setText(st.dots == 2 ? Sym.str(Sym.metAugmentationDot) + Sym.str(Sym.metAugmentationDot) : Sym.str(Sym.metAugmentationDot));
        dotBtn.setContentDescription(st.dots == 2 ? "Double dot" : getString(R.string.dot));
        Ui.setGroupSelected(t, tripletBtn, st.triplet);
        Ui.setGroupSelected(t, slurBtn, st.slurOpen);
        Ui.setGroupSelected(t, crescBtn, st.hairpinOpen == ChordRest.CRESCENDO);
        Ui.setGroupSelected(t, dimBtn, st.hairpinOpen == ChordRest.DIMINUENDO);
        tripletBtn.setText(Sym.str(Sym.tuplet0 + st.tupletActual));
        tripletBtn.setContentDescription(Tuplet.name(st.tupletActual) + " (long press for other tuplets)");
        for (int i = 0; i < 4; i++) {
            Ui.setGroupSelected(t, voiceBtns[i], st.voice == i);
            if (st.voice != i) voiceBtns[i].setTextColor(t.voice[i]);
        }
        Ui.setToggled(t, chordBtn, st.chordMode);
        Ui.setToggled(t, kbdBtn, pianoVisible);
        boolean dockWas = inputDock.getVisibility() == View.VISIBLE;
        boolean dockOn = st.inputMode && !st.lyricEdit;
        inputDock.setVisibility(dockOn ? View.VISIBLE : View.GONE);
        pianoBox.setVisibility(pianoVisible ? View.VISIBLE : View.GONE);
        // lyric entry mode: the lower menus close; the lyric bar above the keyboard takes over
        fab.setVisibility(st.lyricEdit ? View.GONE : View.VISIBLE);
        if (dockWas != dockOn) {
            if (dockOn) {
                inputDock.setTranslationY(t.dp(80));
                inputDock.animate().translationY(0).setDuration(200).start();
            }
        }
        fabIcon.setImageResource(st.inputMode ? R.drawable.ic_check : R.drawable.ic_edit);
        fabLabel.setText(st.inputMode ? R.string.done : R.string.write);
        String fabDesc = getString(st.inputMode ? R.string.exit_note_input : R.string.note_input);
        fab.setContentDescription(fabDesc);
        if (Build.VERSION.SDK_INT >= 26) fab.setTooltipText(fabDesc);
        if (st.inputMode && tipCard != null) {
            getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("tip_editor_seen", true).apply();
            content.removeView(tipCard);
            tipCard = null;
        }
        updateFloatingToolbar();
        applyBottomInset();
        updateLyricBar();
        if (st.lyricEdit) view.ensureLyricVisible();
        Note n = st.selectedNote();
        piano.setHighlight(n != null ? n.pitch : -1);
        if (st.revision != savedRevision) {
            handler.removeCallbacks(autosave);
            handler.postDelayed(autosave, 1500);
        }
        updateBackCallback();
    }

    private String statusText() {
        if (st.graceAwaitingPitch) return "Grace note \u00b7 tap its pitch on the piano (or ↑ ↓)";
        if (st.lyricEdit) return "Lyric entry \u00b7 Space or - : next note \u00b7 _ : extender line";
        StringBuilder sb = new StringBuilder();
        if (st.inputMode && st.cursor != null) {
            sb.append(getString(R.string.note_input)).append(" · ");
            sb.append("m. ").append(st.score.measureNumber(Math.min(st.cursor.measure, st.score.measures.size() - 1)));
            sb.append(" · ").append(getString(R.string.voice, st.voice + 1));
            sb.append(" · ").append(Duration.label(st.durType, st.dots));
            if (st.slurOpen) sb.append(" · slur");
            if (st.hairpinOpen != 0) sb.append(st.hairpinOpen == ChordRest.CRESCENDO ? " · cresc." : " · dim.");
            if (st.triplet) sb.append(" · ").append(Tuplet.name(st.tupletActual)).append(" of ").append(Duration.label(st.tripletUnit >= 0 ? st.tripletUnit : st.durType, 0).toLowerCase(java.util.Locale.ROOT)).append("s");
            return sb.toString();
        }
        if (st.selKind == EditorState.SEL_RANGE) {
            sb.append("m. ").append(st.score.measureNumber(st.rangeM1));
            if (st.rangeM2 != st.rangeM1) sb.append("–").append(st.score.measureNumber(st.rangeM2));
            Part p = st.score.partOfStaff(st.rangeS1);
            sb.append(" · ").append(p.name);
            return sb.toString();
        }
        ChordRest cr = st.selectedChordRest();
        if (cr != null) {
            Note n = st.selectedNote();
            if (n != null && st.selKind == EditorState.SEL_NOTE) sb.append(Pitch.name(n.pitch, n.tpc)).append(" · ");
            else if (cr instanceof Chord) sb.append(((Chord) cr).notes.size()).append("-note chord · ");
            else sb.append(getString(R.string.rest)).append(" · ");
            sb.append(Duration.label(cr.type, cr.dots)).append(" · m. ").append(st.score.measureNumber(st.selPos.measure));
            sb.append(" · ").append(getString(R.string.voice, st.selPos.voice + 1));
            return sb.toString();
        }
        return getString(R.string.hint_select);
    }

    private void setInputMode(boolean on) {
        if (st == null) return;
        if (on) {
            st.startInput();
            if (st.cursor != null) {
                int clef = st.score.clefAt(st.cursor.staff, st.cursor.measure);
                Note n = st.selectedNote();
                piano.centerOn(n != null ? n.pitch : Clef.middlePitch(clef) + 2);
            }
        } else {
            st.stopInput();
        }
        refresh();
        followCursor();
    }

    private void followCursor() {
        if (st == null) return;
        if (st.inputMode && st.cursor != null) view.ensureVisible(st.cursor.measure, st.cursor.staff, st.cursor.tick, true);
        else if (st.selPos != null) view.ensureVisible(st.selPos.measure, st.selPos.staff, st.selPos.tick, true);
    }

    private void toast(String s) {
        if (snack != null) snack.show(s);
        else Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ input actions

    private void pianoKey(int pitch, boolean withHeldKeys) {
        if (st == null) return;
        if (!st.inputMode) {
            // outside note input the keyboard re-pitches the selected note (or just plays)
            synth.play(pitch);
            return;
        }
        int p = st.enterPianoKey(pitch, withHeldKeys);
        synth.play(p);
        refresh();
        followCursor();
    }

    private void move(int dir) {
        if (st == null) return;
        st.moveCursor(dir);
        previewSelected();
        refresh();
        followCursor();
    }

    private void previewSelected() {
        Note n = st.selectedNote();
        if (n != null) synth.play(n.pitch);
    }

    @Override
    public void onSelectionChanged() {
        if (st.inputMode && st.selPos != null) st.setCursor(st.selPos);
        previewSelected();
        refresh();
    }

    @Override
    public void onStaffNote(Pos pos, int line) {
        int pitch = view.pitchForLine(pos.measure, pos.staff, line);
        ChordRest at = ScoreEdit.elementAt(st.score, pos);
        if (st.chordMode && at instanceof Chord) {
            st.selectNote(pos, -1);
            st.addPitchToSelectedChord(pitch);
        } else {
            st.setCursor(pos);
            st.enterPitch(pitch, Integer.MIN_VALUE);
        }
        synth.play(pitch);
        refresh();
        followCursor();
    }

    @Override
    public void onLyricChanged() {
        refresh();
    }

    @Override
    public void onLongPress() {
        refresh();
    }

    // ------------------------------------------------------------------ playback

    private void togglePlay() {
        if (st == null) return;
        if (player.isPlaying()) {
            player.stop();
            return;
        }
        int m = 0, tick = 0;
        if (st.selKind == EditorState.SEL_RANGE) m = st.rangeM1;
        else if (st.selPos != null) {
            m = st.selPos.measure;
            tick = st.selPos.tick;
        } else if (st.inputMode && st.cursor != null) {
            m = st.cursor.measure;
            tick = st.cursor.tick;
        }
        playBtn.setImageResource(R.drawable.ic_pause);
        playBtn.setContentDescription(getString(R.string.pause));
        player.play(st.score, m, tick, new Player.Listener() {
            @Override
            public void onPosition(int measure, int t2) {
                view.setPlayPosition(measure, t2);
                view.ensureVisible(measure, 0, t2, true);
            }

            @Override
            public void onStopped() {
                playBtn.setImageResource(R.drawable.ic_play_arrow);
                playBtn.setContentDescription(getString(R.string.play));
                view.setPlayPosition(-1, 0);
            }

            @Override
            public void onError(String msg) {
                toast(getString(R.string.playback_error));
            }
        });
    }

    // ------------------------------------------------------------------ saving / export

    private void save(final boolean announce) {
        if (st == null || scoreId == null || st.revision == savedRevision || io.isShutdown()) return;
        final Score copy = st.score.copy();
        final int rev = st.revision;
        final String id = scoreId;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    store.save(id, copy, Exporter.thumbnail(EditorActivity.this, copy, 360));
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            savedRevision = Math.max(savedRevision, rev);
                            if (announce) toast(getString(R.string.saved));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    private void exportTo(int format) {
        pendingExportFormat = format;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Exporter.MIME[format]);
        i.putExtra(Intent.EXTRA_TITLE, Exporter.fileName(st.score, format));
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast(getString(R.string.export_failed, e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXPORT || res != RESULT_OK || data == null || data.getData() == null || st == null) return;
        final Uri uri = data.getData();
        final int fmt = pendingExportFormat;
        final Score copy = st.score.copy();
        io.execute(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                    Exporter.write(EditorActivity.this, copy, fmt, out);
                    out.close();
                    msg = getString(R.string.exported, Exporter.fileName(copy, fmt));
                } catch (Exception e) {
                    msg = getString(R.string.export_failed, String.valueOf(e.getMessage()));
                }
                final String m = msg;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        toast(m);
                    }
                });
            }
        });
    }

    private void share(final int format) {
        final Score copy = st.score.copy();
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = ShareProvider.shareDir(EditorActivity.this);
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    final File f = new File(dir, Exporter.fileName(copy, format));
                    FileOutputStream out = new FileOutputStream(f);
                    Exporter.write(EditorActivity.this, copy, format, out);
                    out.close();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            Uri uri = ShareProvider.uriFor(f);
                            Intent send = new Intent(Intent.ACTION_SEND);
                            send.setType(Exporter.MIME[format]);
                            send.putExtra(Intent.EXTRA_STREAM, uri);
                            send.putExtra(Intent.EXTRA_SUBJECT, copy.title);
                            send.setClipData(ClipData.newRawUri(f.getName(), uri));
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivity(Intent.createChooser(send, getString(R.string.share)));
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            toast(getString(R.string.export_failed, String.valueOf(e.getMessage())));
                        }
                    });
                }
            }
        });
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(View anchor) {
        if (st == null) return;
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, R.string.score_properties);
        m.add(0, 2, 0, R.string.instruments);
        m.add(0, 3, 0, R.string.mixer);
        m.add(0, 4, 0, R.string.add_measures);
        m.add(0, 5, 0, R.string.insert_measure);
        m.add(0, 6, 0, R.string.delete_measures);
        m.add(0, 7, 0, R.string.select_all);
        Menu exp = m.addSubMenu(0, 8, 0, R.string.export);
        exp.add(1, 100 + Exporter.PDF, 0, R.string.export_pdf);
        exp.add(1, 100 + Exporter.MUSICXML, 0, R.string.export_musicxml);
        exp.add(1, 100 + Exporter.MIDI, 0, R.string.export_midi);
        exp.add(1, 100 + Exporter.MSCZ, 0, R.string.export_mscz);
        Menu sh = m.addSubMenu(0, 9, 0, R.string.share);
        sh.add(2, 200 + Exporter.PDF, 0, R.string.export_pdf);
        sh.add(2, 200 + Exporter.MUSICXML, 0, R.string.export_musicxml);
        sh.add(2, 200 + Exporter.MIDI, 0, R.string.export_midi);
        sh.add(2, 200 + Exporter.MSCZ, 0, R.string.export_mscz);
        m.add(0, 10, 0, R.string.zoom_in);
        m.add(0, 11, 0, R.string.zoom_out);
        m.add(0, 12, 0, R.string.shortcuts);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id >= 200) share(id - 200);
                else if (id >= 100) exportTo(id - 100);
                else switch (id) {
                        case 1:
                            propertiesDialog();
                            break;
                        case 2:
                            instrumentsDialog();
                            break;
                        case 3:
                            mixerDialog();
                            break;
                        case 4:
                            addMeasuresDialog();
                            break;
                        case 5:
                            st.insertMeasureBefore();
                            refresh();
                            break;
                        case 6:
                            st.deleteSelectedMeasures();
                            refresh();
                            break;
                        case 7:
                            st.selectAll();
                            refresh();
                            break;
                        case 10:
                            view.setZoom(view.getZoom() * 1.25f);
                            break;
                        case 11:
                            view.setZoom(view.getZoom() / 1.25f);
                            break;
                        case 12:
                            shortcutsDialog();
                            break;
                        default:
                            return false;
                    }
                return true;
            }
        });
        pm.show();
    }

    // ------------------------------------------------------------------ palette sheet

    private void openPalette() {
        if (st == null) return;
        sheetHost.removeAllViews();
        View scrim = new View(this);
        scrim.setBackgroundColor(0x52000000);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePalette();
            }
        });
        sheetHost.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout sheet = Ui.column(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(t.surfaceContainer);
        float r = t.dp(28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setElevation(t.dp(8));
        sheet.setClickable(true);
        // drag handle
        View handle = new View(this);
        handle.setBackground(Ui.round(t.outline, t.dp(2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(t.dp(32), t.dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = t.dp(16);
        hlp.bottomMargin = t.dp(8);
        sheet.addView(handle, hlp);
        LinearLayout header = Ui.row(this);
        header.setPadding(t.dp(24), 0, t.dp(8), 0);
        TextView title = Ui.text(this, getString(R.string.palettes), 22, t.onSurface, false);
        header.addView(title, Ui.lin(0, -2, 1f));
        header.addView(Ui.icon(this, t, R.drawable.ic_close, getString(R.string.done), new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePalette();
            }
        }));
        sheet.addView(header);
        palette = new PaletteView(this, t);
        palette.setCategories(Palettes.build(this));
        palette.setOnApplied(new Runnable() {
            @Override
            public void run() {
                refresh();
            }
        });
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        sheet.addView(palette, Ui.lin(-1, h));
        int nav = 0;
        if (Build.VERSION.SDK_INT >= 23 && root.getRootWindowInsets() != null) nav = root.getRootWindowInsets().getSystemWindowInsetBottom();
        sheet.setPadding(0, 0, 0, nav);
        int maxW = t.dp(720);
        int w = Math.min(maxW, getResources().getDisplayMetrics().widthPixels);
        sheetHost.addView(sheet, Ui.frame(w, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        sheetHost.setVisibility(View.VISIBLE);
        floatingBar.setVisibility(View.GONE);
        sheet.setTranslationY(h);
        sheet.animate().translationY(0).setDuration(250).start();
        updateBackCallback();
    }

    private void closePalette() {
        sheetHost.setVisibility(View.GONE);
        sheetHost.removeAllViews();
        palette = null;
        refresh();
    }

    boolean paletteOpen() {
        return sheetHost.getVisibility() == View.VISIBLE;
    }

    /** Palette contents; actions operate on the editor's selection. */
    static final class Palettes {
        static List<PaletteView.Category> build(final EditorActivity a) {
            final EditorState st = a.st;
            List<PaletteView.Category> cats = new ArrayList<PaletteView.Category>();
            PaletteView.Category clefs = new PaletteView.Category("Clefs");
            int[] ct = {Clef.TREBLE, Clef.BASS, Clef.ALTO, Clef.TENOR, Clef.TREBLE_8VB, Clef.TREBLE_8VA, Clef.TREBLE_15MA, Clef.BASS_8VB,
                    Clef.BASS_8VA, Clef.BASS_15MB, Clef.BASS_15MA, Clef.SOPRANO, Clef.MEZZO, Clef.BARITONE_C, Clef.PERC};
            for (int i = 0; i < ct.length; i++) {
                final int clef = ct[i];
                clefs.add(new PaletteView.Cell(Sym.str(Layout.clefGlyph(clef)), true, Clef.label(clef), Clef.label(clef) + " clef", new Runnable() {
                    @Override
                    public void run() {
                        if (!a.requireSelection()) return;
                        st.setClef(clef);
                    }
                }));
            }
            cats.add(clefs);

            PaletteView.Category keys = new PaletteView.Category("Key signatures");
            for (int k = -7; k <= 7; k++) {
                final int key = k;
                String g;
                if (k == 0) g = Sym.str(Sym.accidentalNatural);
                else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.abs(k); i++) sb.append(Sym.str(k > 0 ? Sym.accidentalSharp : Sym.accidentalFlat));
                    g = sb.toString();
                }
                String name = Pitch.keyName(k).replace(" major", "");
                keys.add(new PaletteView.Cell(g, true, name + " / " + minorName(k), Pitch.keyName(k), new Runnable() {
                    @Override
                    public void run() {
                        st.setKeySig(key);
                    }
                }).scale(0.7f));
            }
            cats.add(keys);

            PaletteView.Category times = new PaletteView.Category("Time signatures");
            int[][] ts = {{2, 4}, {3, 4}, {4, 4}, {5, 4}, {6, 4}, {2, 2}, {3, 8}, {6, 8}, {9, 8}, {12, 8}, {5, 8}, {7, 8}};
            for (final int[] tsi : ts) {
                times.add(new PaletteView.Cell(tsi[0] + "/" + tsi[1], false, "", tsi[0] + "/" + tsi[1] + " time", new Runnable() {
                    @Override
                    public void run() {
                        st.setTimeSig(tsi[0], tsi[1], false);
                    }
                }).scale(1.3f));
            }
            times.add(new PaletteView.Cell(Sym.str(Sym.timeSigCommon), true, "Common", "Common time", new Runnable() {
                @Override
                public void run() {
                    st.setTimeSig(4, 4, true);
                }
            }));
            times.add(new PaletteView.Cell(Sym.str(Sym.timeSigCutCommon), true, "Cut", "Cut time", new Runnable() {
                @Override
                public void run() {
                    st.setTimeSig(2, 2, true);
                }
            }));
            times.add(new PaletteView.Cell("n/d", false, "Custom…", "Custom time signature", new Runnable() {
                @Override
                public void run() {
                    a.customTimeSigDialog();
                }
            }));
            cats.add(times);

            PaletteView.Category accs = new PaletteView.Category("Accidentals");
            int[] ag = {Sym.accidentalDoubleFlat, Sym.accidentalFlat, Sym.accidentalNatural, Sym.accidentalSharp, Sym.accidentalDoubleSharp};
            String[] an = {"Double flat", "Flat", "Natural", "Sharp", "Double sharp"};
            for (int i = 0; i < ag.length; i++) {
                final int alter = i - 2;
                accs.add(new PaletteView.Cell(Sym.str(ag[i]), true, an[i], an[i], new Runnable() {
                    @Override
                    public void run() {
                        if (st.selectedNote() == null) {
                            a.toast(a.getString(R.string.nothing_selected));
                            return;
                        }
                        st.applyAccidental(alter);
                    }
                }));
            }
            PaletteView.Category lines = new PaletteView.Category("Lines");
            lines.add(new PaletteView.Cell("⌒", false, "Slur", "Slur to the next note (again: one note longer)", new Runnable() {
                @Override
                public void run() {
                    a.lineAction(0);
                }
            }));
            lines.add(new PaletteView.Cell("<", false, "cresc.", "Crescendo hairpin (again: one note longer)", new Runnable() {
                @Override
                public void run() {
                    a.lineAction(ChordRest.CRESCENDO);
                }
            }));
            lines.add(new PaletteView.Cell(">", false, "dim.", "Diminuendo hairpin (again: one note longer)", new Runnable() {
                @Override
                public void run() {
                    a.lineAction(ChordRest.DIMINUENDO);
                }
            }));
            lines.add(new PaletteView.Cell("✕", false, "Remove", "Remove the slur and hairpin starting on the selection", new Runnable() {
                @Override
                public void run() {
                    if (!a.st.removeLines()) a.toast("No slur or hairpin starts here");
                }
            }));
            cats.add(lines);
            PaletteView.Category graces = new PaletteView.Category("Grace notes");
            String[] gl = {"acc.", "app.", "app. 16th"};
            String[] gd = {"Acciaccatura (short, slashed): before the selected note", "Appoggiatura: before the selected note",
                    "Appoggiatura, 16th: before the selected note"};
            final int[][] gk = {{Chord.ACCIACCATURA, Duration.EIGHTH}, {Chord.APPOGGIATURA, Duration.EIGHTH}, {Chord.APPOGGIATURA, Duration.N16}};
            for (int i = 0; i < gk.length; i++) {
                final int[] k = gk[i];
                graces.add(new PaletteView.Cell(gl[i], false, gl[i], gd[i], new Runnable() {
                    @Override
                    public void run() {
                        a.addGrace(k[0], k[1]);
                    }
                }));
            }
            cats.add(graces);
            accs.add(new PaletteView.Cell("⇆", false, "Respell", a.getString(R.string.respell), new Runnable() {
                @Override
                public void run() {
                    if (!a.st.respell()) a.toast(a.getString(R.string.nothing_selected));
                }
            }));
            cats.add(accs);

            PaletteView.Category arts = new PaletteView.Category("Articulations");
            for (int i = 0; i < Articulation.ALL.length; i++) {
                final String id = Articulation.ALL[i];
                String sym = Articulation.smufl(id, true);
                arts.add(new PaletteView.Cell(Sym.str(Sym.byName(sym)), true, Articulation.LABELS[i], Articulation.LABELS[i], new Runnable() {
                    @Override
                    public void run() {
                        if (!a.requireSelection()) return;
                        st.toggleArticulation(id);
                    }
                }));
            }
            cats.add(arts);

            PaletteView.Category dyn = new PaletteView.Category("Dynamics");
            for (final String d : Dynamics.ALL) {
                dyn.add(new PaletteView.Cell(Sym.str(Sym.byName(Dynamics.smufl(d))), true, "", "Dynamic " + d, new Runnable() {
                    @Override
                    public void run() {
                        if (!a.requireSelection()) return;
                        st.setDynamic(d);
                    }
                }));
            }
            cats.add(dyn);

            PaletteView.Category bars = new PaletteView.Category("Barlines & repeats");
            bars.add(new PaletteView.Cell("‖", false, "Double", "Double barline", barline(st, Measure.BAR_DOUBLE)));
            bars.add(new PaletteView.Cell("│┃", false, "Final", "Final barline", barline(st, Measure.BAR_FINAL)));
            bars.add(new PaletteView.Cell("┆", false, "Dashed", "Dashed barline", barline(st, Measure.BAR_DASHED)));
            bars.add(new PaletteView.Cell("┃│:", false, "Start repeat", "Start repeat", new Runnable() {
                @Override
                public void run() {
                    st.toggleStartRepeat();
                }
            }));
            bars.add(new PaletteView.Cell(":│┃", false, "End repeat", "End repeat", barline(st, Measure.BAR_END_REPEAT)));
            bars.add(new PaletteView.Cell("│", false, "Normal", "Normal barline", barline(st, Measure.BAR_NORMAL)));
            cats.add(bars);

            PaletteView.Category text = new PaletteView.Category("Text");
            text.add(new PaletteView.Cell(Sym.str(Sym.metNoteQuarterUp) + "=", true, "Tempo", a.getString(R.string.tempo_marking), new Runnable() {
                @Override
                public void run() {
                    a.tempoDialog();
                }
            }).scale(0.8f));
            text.add(new PaletteView.Cell("A", false, "Rehearsal", a.getString(R.string.rehearsal_mark), new Runnable() {
                @Override
                public void run() {
                    a.textDialog(a.getString(R.string.rehearsal_mark), 1);
                }
            }));
            text.add(new PaletteView.Cell("Abc", false, "Staff text", a.getString(R.string.staff_text), new Runnable() {
                @Override
                public void run() {
                    a.textDialog(a.getString(R.string.staff_text), 2);
                }
            }));
            text.add(new PaletteView.Cell("C⁷", false, "Chord", a.getString(R.string.chord_symbol), new Runnable() {
                @Override
                public void run() {
                    a.textDialog(a.getString(R.string.chord_symbol), 3);
                }
            }));
            text.add(new PaletteView.Cell("la", false, "Lyrics", a.getString(R.string.lyrics), new Runnable() {
                @Override
                public void run() {
                    a.startLyricEntry();
                }
            }));
            cats.add(text);

            PaletteView.Category meas = new PaletteView.Category("Measures");
            meas.add(new PaletteView.Cell("+1", false, "Append", "Append one measure", new Runnable() {
                @Override
                public void run() {
                    st.appendMeasures(1);
                }
            }));
            meas.add(new PaletteView.Cell("+4", false, "Append", "Append four measures", new Runnable() {
                @Override
                public void run() {
                    st.appendMeasures(4);
                }
            }));
            meas.add(new PaletteView.Cell("⇤+", false, "Insert", a.getString(R.string.insert_measure), new Runnable() {
                @Override
                public void run() {
                    st.insertMeasureBefore();
                }
            }));
            meas.add(new PaletteView.Cell("−", false, "Delete", a.getString(R.string.delete_measures), new Runnable() {
                @Override
                public void run() {
                    st.deleteSelectedMeasures();
                }
            }));
            cats.add(meas);
            return cats;
        }

        private static Runnable barline(final EditorState st, final int type) {
            return new Runnable() {
                @Override
                public void run() {
                    st.setBarline(type);
                }
            };
        }

        private static String minorName(int k) {
            String[] names = {"a♭", "e♭", "b♭", "f", "c", "g", "d", "a", "e", "b", "f♯", "c♯", "g♯", "d♯", "a♯"};
            return names[k + 7];
        }
    }

    boolean requireSelection() {
        if (st.hasSelection() || (st.inputMode && st.cursor != null)) return true;
        toast(getString(R.string.nothing_selected));
        return false;
    }

    // ------------------------------------------------------------------ dialogs

    private LinearLayout dialogBody() {
        LinearLayout l = Ui.column(this);
        l.setPadding(t.dp(24), t.dp(8), t.dp(24), 0);
        return l;
    }

    private void propertiesDialog() {
        if (st == null) return;
        LinearLayout body = dialogBody();
        final EditText title = labeled(body, getString(R.string.title), st.score.title);
        final EditText sub = labeled(body, getString(R.string.subtitle), st.score.subtitle);
        final EditText comp = labeled(body, getString(R.string.composer), st.score.composer);
        final EditText lyr = labeled(body, getString(R.string.lyricist), st.score.lyricist);
        final EditText copy = labeled(body, getString(R.string.copyright), st.score.copyright);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this).setTitle(R.string.score_properties).setView(sv)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        st.checkpoint();
                        st.score.title = title.getText().toString().trim().isEmpty() ? "Untitled score" : title.getText().toString().trim();
                        st.score.subtitle = sub.getText().toString().trim();
                        st.score.composer = comp.getText().toString().trim();
                        st.score.lyricist = lyr.getText().toString().trim();
                        st.score.copyright = copy.getText().toString().trim();
                        st.externalChange();
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private EditText labeled(LinearLayout body, String label, String value) {
        TextView l = Ui.text(this, label, 12, t.primary, true);
        l.setPadding(0, t.dp(8), 0, 0);
        body.addView(l);
        EditText e = Ui.input(this, t, label, value);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        body.addView(e, Ui.lin(-1, -2));
        return e;
    }

    void textDialog(String title, final int kind) {
        if (!requireSelection()) return;
        LinearLayout body = dialogBody();
        String cur = null;
        ChordRest cr = st.selectedChordRest();
        if (kind == 1) cur = st.score.measures.get(st.selectionMeasure()).rehearsal;
        else if (kind == 2 && cr != null) cur = cr.staffText;
        else if (kind == 3 && cr != null) cur = cr.chordSymbol;
        final EditText e = Ui.input(this, t, title, cur == null ? "" : cur);
        if (kind == 3) e.setHint("e.g. Cmaj7, F#m7b5, Bb/D");
        body.addView(e, Ui.lin(-1, -2));
        final AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        String v = e.getText().toString();
                        if (kind == 1) st.setRehearsal(v);
                        else if (kind == 2) st.setStaffText(v);
                        else st.setChordSymbol(v);
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).create();
        showWithKeyboard(d, e);
    }

    /**
     * Enters lyric entry mode: the lower menus close and typing happens on the score, starting at
     * the selected chord (or the first chord). The on-screen keyboard opens unless a physical
     * keyboard is attached.
     */
    void startLyricEntry() {
        if (st == null) return;
        Pos p = st.lyricStartPos();
        if (p == null) {
            toast("There are no notes to add lyrics to");
            return;
        }
        if (paletteOpen()) closePalette();
        if (st.inputMode) st.stopInput();
        st.beginLyric(p);
        refresh();
    }

    /** Undo/redo that keeps lyric entry mode going on the same chord. */
    private void undoRedo(boolean redo) {
        boolean lyric = st.lyricEdit;
        Pos at = st.selPos;
        if (redo) st.redo();
        else st.undo();
        if (lyric) st.beginLyric(at);
    }

    /** True if a hardware keyboard is attached, in which case the on-screen keyboard stays closed. */
    private boolean hasPhysicalKeyboard() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d != null && !d.isVirtual()
                    && (d.getSources() & android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
                    && d.getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) return true;
        }
        return false;
    }

    /** Keys while typing a lyric. Returns false for shortcuts (Ctrl/Alt/Meta), which keep their meaning. */
    private boolean lyricKey(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MINUS && e.isCtrlPressed()) {
            // MuseScore: Ctrl+- and Ctrl+Shift+_ type a hyphen or underscore inside the syllable, with no jump
            st.lyricInsertLiteral(e.isShiftPressed() ? '_' : '-');
            return true;
        }
        if (e.isCtrlPressed() || e.isAltPressed() || e.isMetaPressed()) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
                st.endLyric();
                return true;
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                st.lyricNext();
                return true;
            case KeyEvent.KEYCODE_DEL:
                st.lyricBackspace();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true; // arrows do nothing while typing, so they don't move the note selection
            default:
                int uc = e.getUnicodeChar();
                if (uc >= 32 && !Character.isISOControl(uc)) {
                    st.lyricType(new String(Character.toChars(uc)));
                    return true;
                }
                return false;
        }
    }

    private boolean lyricInputOn;

    /** Shows the on-screen keyboard while a lyric is being typed, and hides it afterwards. */
    private void syncLyricInput() {
        if (st == null || view == null) return;
        boolean on = st.lyricEdit;
        if (on && !lyricInputOn) {
            lyricInputOn = true;
            // the bar follows the keyboard as it opens, closes or changes height
            lyricBarListener = new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    updateLyricBar();
                }
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(lyricBarListener);
            view.startLyricInput(!hasPhysicalKeyboard());
        } else if (!on && lyricInputOn) {
            lyricInputOn = false;
            if (lyricBarListener != null) root.getViewTreeObserver().removeOnGlobalLayoutListener(lyricBarListener);
            lyricBarListener = null;
            view.stopLyricInput();
        }
    }

    private void showWithKeyboard(AlertDialog d, EditText e) {
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
        e.setSelection(e.getText().length());
    }

    void tempoDialog() {
        if (st == null) return;
        final int m = st.selectionMeasure();
        Measure mm = st.score.measures.get(m);
        LinearLayout body = dialogBody();
        final EditText txt = labeled(body, "Text (optional)", mm.tempoText == null ? "" : mm.tempoText);
        txt.setHint("Allegro, Andante…");
        final EditText bpm = labeled(body, Sym.str(Sym.metNoteQuarterUp) + " BPM", String.valueOf(mm.tempo > 0 ? mm.tempo : st.score.tempoAt(m)));
        bpm.setInputType(InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle(getString(R.string.tempo_marking) + " · m. " + st.score.measureNumber(m)).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        int v;
                        try {
                            v = Integer.parseInt(bpm.getText().toString().trim());
                        } catch (NumberFormatException e) {
                            v = 0;
                        }
                        st.setTempo(m, v, txt.getText().toString());
                        refresh();
                    }
                })
                .setNeutralButton(R.string.remove, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        st.setTempo(m, 0, null);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    void customTimeSigDialog() {
        LinearLayout body = Ui.row(this);
        body.setGravity(Gravity.CENTER);
        final NumberPicker n = new NumberPicker(this);
        n.setMinValue(1);
        n.setMaxValue(32);
        n.setValue(st.score.measures.get(st.selectionMeasure()).timeN);
        final NumberPicker d = new NumberPicker(this);
        final String[] dens = {"1", "2", "4", "8", "16", "32"};
        d.setDisplayedValues(dens);
        d.setMinValue(0);
        d.setMaxValue(dens.length - 1);
        d.setValue(2);
        body.addView(n);
        body.addView(Ui.text(this, " / ", 24, t.onSurface, false));
        body.addView(d);
        new AlertDialog.Builder(this).setTitle(R.string.time_signature).setView(body)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        st.setTimeSig(n.getValue(), Integer.parseInt(dens[d.getValue()]), false);
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void addMeasuresDialog() {
        LinearLayout body = dialogBody();
        final NumberPicker np = new NumberPicker(this);
        np.setMinValue(1);
        np.setMaxValue(64);
        np.setValue(4);
        body.addView(np);
        new AlertDialog.Builder(this).setTitle(R.string.add_measures).setView(body)
                .setPositiveButton(R.string.add, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        st.appendMeasures(np.getValue());
                        refresh();
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void instrumentsDialog() {
        final LinearLayout body = dialogBody();
        final AlertDialog[] holder = new AlertDialog[1];
        Runnable[] rebuild = new Runnable[1];
        rebuild[0] = new Runnable() {
            @Override
            public void run() {
                body.removeAllViews();
                final Score s = st.score;
                for (int i = 0; i < s.parts.size(); i++) {
                    final int idx = i;
                    final Part p = s.parts.get(i);
                    LinearLayout row = Ui.row(EditorActivity.this);
                    TextView name = Ui.text(EditorActivity.this, p.name, 16, t.onSurface, false);
                    row.addView(name, Ui.lin(0, -2, 1f));
                    final Runnable self = this;
                    row.addView(Ui.icon(EditorActivity.this, t, R.drawable.ic_keyboard_arrow_up, getString(R.string.move_up), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (idx == 0) return;
                            st.checkpoint();
                            s.movePart(idx, idx - 1);
                            st.externalChange();
                            refresh();
                            self.run();
                        }
                    }));
                    row.addView(Ui.icon(EditorActivity.this, t, R.drawable.ic_keyboard_arrow_down, getString(R.string.move_down), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (idx >= s.parts.size() - 1) return;
                            st.checkpoint();
                            s.movePart(idx, idx + 1);
                            st.externalChange();
                            refresh();
                            self.run();
                        }
                    }));
                    row.addView(Ui.icon(EditorActivity.this, t, R.drawable.ic_delete, getString(R.string.remove), new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (s.parts.size() <= 1) return;
                            st.checkpoint();
                            s.removePart(p);
                            st.clearSelection();
                            if (st.cursor != null) st.cursor = null;
                            st.stopInput();
                            st.externalChange();
                            refresh();
                            self.run();
                        }
                    }));
                    body.addView(row);
                }
                final Runnable self = this;
                TextView add = Ui.button(EditorActivity.this, t, getString(R.string.add_instrument), 1, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        pickInstrument(new InstrumentPicked() {
                            @Override
                            public void picked(Instruments.Def def) {
                                st.checkpoint();
                                st.score.addPart(def.create());
                                st.externalChange();
                                refresh();
                                self.run();
                            }
                        });
                    }
                });
                LinearLayout.LayoutParams lp = Ui.lin(-2, -2);
                lp.topMargin = t.dp(12);
                body.addView(add, lp);
            }
        };
        rebuild[0].run();
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        holder[0] = new AlertDialog.Builder(this).setTitle(R.string.instruments).setView(sv).setPositiveButton(R.string.done, null).show();
    }

    interface InstrumentPicked {
        void picked(Instruments.Def def);
    }

    void pickInstrument(final InstrumentPicked cb) {
        InstrumentPicker.pickOne(this, t, new InstrumentPicker.Picked() {
            @Override
            public void picked(List<Instruments.Def> defs) {
                cb.picked(defs.get(0));
            }
        });
    }

    private void mixerDialog() {
        LinearLayout body = dialogBody();
        TextView speedLabel = Ui.text(this, getString(R.string.tempo_scale) + ": " + Math.round(player.getTempoScale() * 100) + "%", 14, t.onSurface, true);
        body.addView(speedLabel);
        final TextView sl = speedLabel;
        SeekBar speed = new SeekBar(this);
        speed.setMax(150);
        speed.setProgress((int) Math.round(player.getTempoScale() * 100) - 50);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                player.setTempoScale((p + 50) / 100.0);
                sl.setText(getString(R.string.tempo_scale) + ": " + (p + 50) + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        body.addView(speed);
        final CheckBox preview = new CheckBox(this);
        preview.setText("Play notes while editing");
        preview.setChecked(synth.enabled);
        preview.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                synth.enabled = c;
            }
        });
        body.addView(preview);
        for (final Part p : st.score.parts) {
            TextView name = Ui.text(this, p.name, 14, t.onSurface, true);
            name.setPadding(0, t.dp(12), 0, 0);
            body.addView(name);
            LinearLayout row = Ui.row(this);
            SeekBar vol = new SeekBar(this);
            vol.setMax(127);
            vol.setProgress(p.volume);
            vol.setContentDescription(p.name + " " + getString(R.string.volume));
            vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int pr, boolean u) {
                    if (u) p.volume = pr;
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    st.checkpoint();
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    st.externalChange();
                }
            });
            row.addView(vol, Ui.lin(0, -2, 1f));
            CheckBox mute = new CheckBox(this);
            mute.setText(R.string.mute);
            mute.setChecked(p.muted);
            mute.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                    st.checkpoint();
                    p.muted = c;
                    st.externalChange();
                }
            });
            row.addView(mute);
            body.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        new AlertDialog.Builder(this).setTitle(R.string.mixer).setView(sv).setPositiveButton(R.string.done, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                refresh();
            }
        }).show();
    }

    private void shortcutsDialog() {
        String s = "N — note input on/off\n"
                + "A–G — enter note (Shift: add to chord)\n"
                + "1–7 — 64th … whole (as in MuseScore), . — dot\n"
                + "S — slur,  < > — crescendo / diminuendo (in note input: until pressed again)\n"
                + "0 — rest,  + — tie,  T — triplet,  Ctrl+2–6 — duplet … sextuplet,  . — dot (twice: double dot)\n"
                + "↑ ↓ — semitone,  Ctrl+↑ ↓ — octave,  J — respell (C♯ ⇄ D♭)\n"
                + "← → — previous / next\n"
                + "Del / Backspace — delete\n"
                + "Ctrl+Alt+1–4 — voice\n"
                + "Ctrl+Z / Ctrl+Y — undo / redo\n"
                + "Ctrl+C / Ctrl+V — copy / paste,  Ctrl+A — select all\n"
                + "Space — play / pause,  Ctrl+S — save,  Esc — leave input / deselect\n"
                + "Ctrl+= / Ctrl+- — zoom";
        new AlertDialog.Builder(this).setTitle(R.string.shortcuts).setMessage(s).setPositiveButton(R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ hardware keyboard (MuseScore shortcuts)

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (st == null) return super.onKeyDown(keyCode, e);
        if (st.lyricEdit && lyricKey(keyCode, e)) {
            refresh();
            return true;
        }
        boolean ctrl = e.isCtrlPressed(), shift = e.isShiftPressed(), alt = e.isAltPressed();
        boolean handled = true;
        if (ctrl && alt && keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4) {
            st.setVoice(keyCode - KeyEvent.KEYCODE_1);
        } else if (ctrl && !alt && keyCode >= KeyEvent.KEYCODE_2 && keyCode <= KeyEvent.KEYCODE_6) {
            // MuseScore: Ctrl+2 ... Ctrl+9 for tuplets
            int n = 2 + (keyCode - KeyEvent.KEYCODE_2);
            st.setTuplet(st.triplet && st.tupletActual == n ? 0 : n);
        } else if (ctrl) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_Z:
                    undoRedo(shift);
                    break;
                case KeyEvent.KEYCODE_Y:
                    undoRedo(true);
                    break;
                case KeyEvent.KEYCODE_C:
                    st.copy();
                    break;
                case KeyEvent.KEYCODE_V:
                    st.paste();
                    break;
                case KeyEvent.KEYCODE_A:
                    st.selectAll();
                    break;
                case KeyEvent.KEYCODE_S:
                    save(true);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    st.transpose(12);
                    previewSelected();
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    st.transpose(-12);
                    previewSelected();
                    break;
                case KeyEvent.KEYCODE_EQUALS:
                case KeyEvent.KEYCODE_PLUS:
                    view.setZoom(view.getZoom() * 1.25f);
                    break;
                case KeyEvent.KEYCODE_MINUS:
                    view.setZoom(view.getZoom() / 1.25f);
                    break;
                default:
                    handled = false;
            }
        } else {
            if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_G) {
                // KEYCODE_A..G are consecutive: A B C D E F G -> steps 5 6 0 1 2 3 4
                int[] steps = {5, 6, 0, 1, 2, 3, 4};
                int step = steps[keyCode - KeyEvent.KEYCODE_A];
                if (!st.inputMode) setInputMode(true);
                int p = st.enterLetter(step, shift);
                if (p >= 0) synth.play(p);
                followCursor();
            } else if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_7) {
                st.setDuration(Duration.N64 - (keyCode - KeyEvent.KEYCODE_1));
            } else {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_N:
                        setInputMode(!st.inputMode);
                        break;
                    case KeyEvent.KEYCODE_PERIOD:
                        if (shift) lineAction(ChordRest.DIMINUENDO); // '>' on most layouts
                        else st.toggleDot();
                        break;
                    case KeyEvent.KEYCODE_0:
                        if (st.inputMode) st.enterRest();
                        else st.deleteSelection();
                        followCursor();
                        break;
                    case KeyEvent.KEYCODE_PLUS:
                    case KeyEvent.KEYCODE_EQUALS:
                        if (st.inputMode) st.enterTie();
                        else st.toggleTie();
                        followCursor();
                        break;
                    case KeyEvent.KEYCODE_T:
                        st.setTuplet(st.triplet ? 0 : st.tupletActual);
                        break;
                    case KeyEvent.KEYCODE_J:
                        st.respell();
                        break;
                    case KeyEvent.KEYCODE_S:
                        lineAction(0);
                        break;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        st.transpose(1);
                        previewSelected();
                        break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        st.transpose(-1);
                        previewSelected();
                        break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        move(-1);
                        break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        move(1);
                        break;
                    case KeyEvent.KEYCODE_DEL:
                    case KeyEvent.KEYCODE_FORWARD_DEL:
                        st.deleteSelection();
                        break;
                    case KeyEvent.KEYCODE_SPACE:
                        togglePlay();
                        break;
                    case KeyEvent.KEYCODE_ESCAPE:
                        if (st.inputMode) setInputMode(false);
                        else st.clearSelection();
                        break;
                    default:
                        // < and > (MuseScore): crescendo and diminuendo hairpins, whatever the layout's keys
                        int uc = e.getUnicodeChar();
                        if (uc == '<') lineAction(ChordRest.CRESCENDO);
                        else if (uc == '>') lineAction(ChordRest.DIMINUENDO);
                        else handled = false;
                }
            }
        }
        if (handled) {
            refresh();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // ------------------------------------------------------------------ back navigation (predictive back aware)

    private boolean wantsBack() {
        return st != null && (paletteOpen() || st.lyricEdit || st.inputMode || st.hasSelection());
    }

    private void handleBack() {
        if (paletteOpen()) closePalette();
        else if (st != null && st.lyricEdit) {
            st.endLyric();
            refresh();
        } else if (st != null && st.inputMode) setInputMode(false);
        else if (st != null && st.hasSelection()) {
            st.clearSelection();
            refresh();
        }
    }

    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return;
        boolean want = wantsBack();
        if (want == backRegistered) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        if (backCallback == null) {
            backCallback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    handleBack();
                    updateBackCallback();
                }
            };
        }
        if (want) d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) backCallback);
        else d.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
        backRegistered = want;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (wantsBack()) handleBack();
        else super.onBackPressed();
    }
}
