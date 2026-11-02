package io.github.vlesk.stave.engrave;

import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.ChordSymbols;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Dynamics;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.Staff;
import io.github.vlesk.stave.model.Spanners;
import io.github.vlesk.stave.model.Syllables;
import io.github.vlesk.stave.model.Rest;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Tuplet;
import io.github.vlesk.stave.model.Voice;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Engraving: turns a score into a {@link DisplayList}. Systems are reflowed to the available
 * width (the "continuous / fit to screen" view that works best on phones), or paginated for PDF.
 * All coordinates are in staff spaces with y pointing down; staff lines are at y = 0..4.
 */
public final class Layout {

    public static final class Options {
        /** Total width in staff spaces. */
        public float width = 60;
        public float marginX = 1.5f;
        public float marginTop = 2f;
        public boolean showTitle = true;
        /** Page height in staff spaces for paginated output (0 = one continuous page). */
        public float pageHeight;
        public float marginBottom = 3f;
        public float systemGap = 5f;
        /**
         * In the editor: a system that would need squeezing by more than 10% keeps its natural width
         * (marked {@link DisplayList.SystemInfo#wide}) and is scrolled sideways instead. Off for
         * printing and export, where it is squeezed to the page.
         */
        public boolean scrollWideSystems;
        /**
         * Reuse between layouts of the same score (the editor passes one per view): measures and
         * whole systems whose content, neighbours and position did not change are not laid out again.
         */
        public Cache cache;
    }

    /**
     * What a layout leaves for the next one: per measure its preparation and accidentals, per system
     * its staves before vertical placement. Entries are keyed by signatures that cover the content of
     * the measures concerned, the identity of their objects, their neighbours and the layout options,
     * so a hit gives exactly what a fresh layout would. Only the last layout's entries are kept.
     */
    public static final class Cache {
        IdentityHashMap<Measure, PrepEntry> preps = new IdentityHashMap<Measure, PrepEntry>();
        HashMap<Long, SystemEntry> systems = new HashMap<Long, SystemEntry>();
        /** Statistics of the last layout (for tests): measures and systems reused or laid out. */
        public int prepHits, prepMisses, systemHits, systemMisses;
    }

    private static final class PrepEntry {
        long key;
        MPrep prep;
        List<Object[]> accidentals;
    }

    private static final class SystemEntry {
        StaffGen[] gens;
        List<Object[]> recs;
    }

    // --------------------------------------------------------------------------- data

    private static final class Col {
        int tick;
        float accPad;
        float minW;
        float spring;
        float width;
    }

    private static final class MPrep {
        List<Col> cols = new ArrayList<Col>();
        float natural; // without headers
        boolean showTime, showKey;
        int oldKey;
        int[] clefChange;
        float midHeader;
        boolean allRests;
    }

    private static final class ChordGeom {
        Chord chord;
        int voice, tick, measure, staff;
        float x;
        int[] lines;
        float[] noteDx;
        boolean up;
        boolean forcedDir;
        int head;
        float headW;
        float stemX, stemEndY;
        float noteTopY, noteBottomY;
        boolean beamed;
        boolean hasStem;
    }

    private static final class NoteRec {
        int system;
        float x, y, headW;
        boolean tieUp;
        int staff;
    }

    private static final class StaffGen {
        final List<DisplayList.Item> items = new ArrayList<DisplayList.Item>();
        final List<DisplayList.Hit> hits = new ArrayList<DisplayList.Hit>();
        final List<DisplayList.Hit> lyricHits = new ArrayList<DisplayList.Hit>();
        float top = 0, bottom = 4;

        void ext(float y0, float y1) {
            if (y0 < top) top = y0;
            if (y1 > bottom) bottom = y1;
        }
    }

    private final Score s;
    private final TextMetrics tm;
    private final Options o;
    private final DisplayList dl = new DisplayList();
    private final IdentityHashMap<Note, Integer> accidental = new IdentityHashMap<Note, Integer>();
    private final IdentityHashMap<Note, NoteRec> noteRecs = new IdentityHashMap<Note, NoteRec>();
    private final List<MPrep> preps = new ArrayList<MPrep>();
    private final int staffCount;

    private static final float HEAD_W = Sym.width(Sym.noteheadBlack);
    /** Grace notes: size (MuseScore's graceNoteMag is 0.7) and the gap after each one. */
    private static final float GRACE_SCALE = 0.65f, GRACE_GAP = 0.3f;
    /** Gap between the last grace note and its main note (clear of the main note's ledger lines). */
    private static final float GRACE_TO_NOTE = 0.6f;

    // Lyrics, following MuseScore's engraving style defaults (src/engraving/style/styledef.cpp), in staff spaces
    /** Edwin 10pt at the default spatium (lyricsOddFontSize / lyricsEvenFontSize). */
    private static final float LYRIC_SIZE = 2.0f;
    /** Baseline below the top staff line: staff height + lyricsPosBelow (3.0). */
    private static final float LYRIC_BASELINE = 4f + 3.0f;
    /** Ascent of the lyric text, for keeping lyricsMinTopDistance (1.0) to what is above. */
    private static final float LYRIC_ASCENT = 1.5f;
    private static final float LYRIC_MIN_TOP_DISTANCE = 1.0f;
    /** Minimum gap between neighbouring lyrics (lyricsMinDistance). */
    private static final float LYRIC_MIN_DISTANCE = 0.25f;
    /** Dashes: lyricsDashMinLength, lyricsDashMaxLength, lyricsDashPad, lyricsDashMaxDistance, lyricsDashLineThickness. */
    private static final float DASH_MIN = 0.4f, DASH_MAX = 0.6f, DASH_PAD = 0.05f, DASH_MAX_DISTANCE = 16f, DASH_THICKNESS = 0.1f;
    /** Room a hyphenated syllable needs before the next one: a minimum-length dash with its padding. */
    private static final float DASH_SPACE = DASH_MIN + 2 * DASH_PAD;
    /** Dashes sit half the x-height above the baseline (lyricsDashYposRatio 0.5); Edwin's x-height is about 0.466 em. */
    private static final float DASH_RAISE = 0.5f * 0.466f * LYRIC_SIZE;
    /** Extender (melisma) line: lyricsLineThickness, lyricsMelismaPad, lyricsMelismaMinLength. */
    private static final float MELISMA_THICKNESS = 0.1f, MELISMA_PAD = 0.2f, MELISMA_MIN = 1.0f;

    private Layout(Score s, TextMetrics tm, Options o) {
        this.s = s;
        this.tm = tm;
        this.o = o;
        this.staffCount = s.staffCount();
    }

    public static DisplayList layout(Score s, TextMetrics tm, Options o) {
        Layout l = new Layout(s, tm, o);
        l.run();
        return l.dl;
    }

    // --------------------------------------------------------------------------- helpers

    private DisplayList.Item glyph(List<DisplayList.Item> out, int g, float x, float y) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.GLYPH;
        it.glyph = g;
        it.x = x;
        it.y = y;
        it.w = 1f;
        out.add(it);
        return it;
    }

    private DisplayList.Item line(List<DisplayList.Item> out, float x1, float y1, float x2, float y2, float w) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.LINE;
        it.x = x1;
        it.y = y1;
        it.x2 = x2;
        it.y2 = y2;
        it.w = w;
        out.add(it);
        return it;
    }

    private DisplayList.Item text(List<DisplayList.Item> out, String t, int font, float size, float x, float y, int align) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.TEXT;
        it.text = t;
        it.font = font;
        it.w = size;
        it.x = x;
        it.y = y;
        it.align = align;
        out.add(it);
        return it;
    }

    private DisplayList.Item poly(List<DisplayList.Item> out, float[] pts) {
        DisplayList.Item it = new DisplayList.Item();
        it.kind = DisplayList.POLY;
        it.pts = pts;
        out.add(it);
        return it;
    }

    private static float gTop(int g, float y) {
        return y - Sym.BBOX[g][3];
    }

    private static float gBottom(int g, float y) {
        return y - Sym.BBOX[g][1];
    }

    private static int clefGlyph(int clef) {
        switch (clef) {
            case Clef.BASS:
                return Sym.fClef;
            case Clef.BASS_8VB:
                return Sym.fClef8vb;
            case Clef.ALTO:
            case Clef.TENOR:
                return Sym.cClef;
            case Clef.TREBLE_8VB:
                return Sym.gClef8vb;
            case Clef.TREBLE_8VA:
                return Sym.gClef8va;
            case Clef.PERC:
                return Sym.unpitchedPercussionClef1;
            default:
                return Sym.gClef;
        }
    }

    private boolean isDrums(int staff) {
        return s.partOfStaff(staff).drums;
    }

    /** Drum kit display positions (staff line) for General MIDI percussion pitches. */
    private static int drumLine(int pitch) {
        switch (pitch) {
            case 35:
            case 36:
                return 7;
            case 37:
            case 38:
            case 39:
            case 40:
                return 3;
            case 41:
                return 6;
            case 43:
                return 5;
            case 45:
            case 47:
                return 2;
            case 48:
            case 50:
                return 1;
            case 42:
            case 46:
                return -1;
            case 44:
                return 9;
            case 49:
            case 57:
            case 52:
            case 55:
                return -2;
            case 51:
            case 59:
            case 53:
                return 0;
            default:
                return 4;
        }
    }

    private static boolean drumX(int pitch) {
        return pitch == 42 || pitch == 44 || pitch == 46 || pitch == 49 || pitch == 51 || pitch == 52 || pitch == 53
                || pitch == 55 || pitch == 57 || pitch == 59;
    }

    public static int noteLine(int clef, boolean drums, Note n) {
        if (drums && clef == Clef.PERC) return drumLine(n.pitch);
        return Clef.line(clef, n.pitch, n.tpc);
    }

    private static float spring(int dt) {
        return 1.1f + 2.2f * (float) Math.pow(Math.max(dt, 30) / 480.0, 0.6);
    }

    private static int restGlyph(int type) {
        switch (type) {
            case Duration.WHOLE:
                return Sym.restWhole;
            case Duration.HALF:
                return Sym.restHalf;
            case Duration.QUARTER:
                return Sym.restQuarter;
            case Duration.EIGHTH:
                return Sym.rest8th;
            case Duration.N16:
                return Sym.rest16th;
            case Duration.N32:
                return Sym.rest32nd;
            default:
                return Sym.rest64th;
        }
    }

    private static int accGlyph(int alter) {
        switch (alter) {
            case -2:
                return Sym.accidentalDoubleFlat;
            case -1:
                return Sym.accidentalFlat;
            case 1:
                return Sym.accidentalSharp;
            case 2:
                return Sym.accidentalDoubleSharp;
            default:
                return Sym.accidentalNatural;
        }
    }

    private int nonEmptyVoices(StaffMeasure sm) {
        int n = 0;
        for (Voice v : sm.voices) if (!v.isEmpty()) n++;
        return n;
    }

    // --------------------------------------------------------------------------- accidentals

    private boolean tiedFromPrev(int m, int st, int v, int idx, int pitch) {
        Voice voice = s.measures.get(m).staves.get(st).voices[v];
        ChordRest prev = null;
        if (idx > 0) prev = voice.items.get(idx - 1);
        else if (m > 0) {
            Voice pv = s.measures.get(m - 1).staves.get(st).voices[v];
            if (!pv.isEmpty()) prev = pv.items.get(pv.items.size() - 1);
        }
        if (!(prev instanceof Chord)) return false;
        for (Note n : ((Chord) prev).notes) if (n.pitch == pitch && n.tieForward) return true;
        return false;
    }

    private void computeAccidentals() {
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            PrepEntry hit = o.cache == null ? null : o.cache.preps.get(mm);
            if (hit != null && hit.key == prepKey(m)) {
                for (Object[] a : hit.accidentals) accidental.put((Note) a[0], (Integer) a[1]);
                continue;
            }
            accidentalLog = o.cache == null ? null : new ArrayList<Object[]>();
            computeAccidentals(m, mm);
            if (o.cache != null) pendingAccidentals.put(mm, accidentalLog);
            accidentalLog = null;
        }
    }

    private List<Object[]> accidentalLog;
    private final IdentityHashMap<Measure, List<Object[]>> pendingAccidentals = new IdentityHashMap<Measure, List<Object[]>>();

    private void computeAccidentals(int m, Measure mm) {
        {
            for (int st = 0; st < staffCount; st++) {
                if (isDrums(st)) continue;
                // collect (tick, note) in time order across voices
                List<Object[]> evs = new ArrayList<Object[]>();
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = mm.staves.get(st).voices[v];
                    int[] starts = startsOf(voice);
                    for (int i = 0; i < voice.items.size(); i++) {
                        ChordRest cr = voice.items.get(i);
                        if (!(cr instanceof Chord)) continue;
                        for (Note n : ((Chord) cr).notes) {
                            evs.add(new Object[]{starts[i], n, tiedFromPrev(m, st, v, i, n.pitch)});
                        }
                    }
                }
                java.util.Collections.sort(evs, new java.util.Comparator<Object[]>() {
                    @Override
                    public int compare(Object[] a, Object[] b) {
                        return ((Integer) a[0]).compareTo((Integer) b[0]);
                    }
                });
                Map<Integer, Integer> state = new HashMap<Integer, Integer>();
                for (Object[] e : evs) {
                    Note n = (Note) e[1];
                    boolean tied = (Boolean) e[2];
                    int dia = Pitch.diatonic(n.pitch, n.tpc);
                    int step = Pitch.step(n.tpc);
                    int alter = Pitch.alter(n.tpc);
                    Integer cur = state.get(dia);
                    int curAlter = cur != null ? cur : Pitch.keyAlter(mm.keyFifths, step);
                    if (tied) {
                        state.put(dia, alter);
                        continue;
                    }
                    if (alter != curAlter || n.forceAccidental) {
                        accidental.put(n, accGlyph(alter));
                        if (accidentalLog != null) accidentalLog.add(new Object[]{n, accGlyph(alter)});
                    }
                    state.put(dia, alter);
                }
            }
        }
    }

    // --------------------------------------------------------------------------- measure preparation

    private int keySigWidthCount(int key, int oldKey) {
        if (key == 0) return Math.abs(oldKey);
        return Math.abs(key);
    }

    private float keySigWidth(int key, int oldKey) {
        int n = keySigWidthCount(key, oldKey);
        return n == 0 ? 0 : n * 1.0f + 0.6f;
    }

    private float timeSigWidth(Measure m) {
        if (m.timeSymbol) return Sym.width(Sym.timeSigCommon) + 1.0f;
        float wn = digitsWidth(m.timeN), wd = digitsWidth(m.timeD);
        return Math.max(wn, wd) + 1.0f;
    }

    private static float digitsWidth(int v) {
        String str = String.valueOf(v);
        float w = 0;
        for (int i = 0; i < str.length(); i++) w += Sym.width(Sym.timeSig0 + (str.charAt(i) - '0'));
        return w;
    }

    private float clefWidth(int clef) {
        return Sym.width(clefGlyph(clef)) + 0.9f;
    }

    private void prepareMeasures() {
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            long key = o.cache != null ? prepKey(m) : 0;
            PrepEntry hit = o.cache == null ? null : o.cache.preps.get(mm);
            if (hit != null && hit.key == key) {
                preps.add(hit.prep);
                next.preps.put(mm, hit);
                o.cache.prepHits++;
                continue;
            }
            MPrep p = prepareMeasure(m, mm);
            preps.add(p);
            if (o.cache != null) {
                o.cache.prepMisses++;
                PrepEntry e = new PrepEntry();
                e.key = key;
                e.prep = p;
                e.accidentals = pendingAccidentals.get(mm);
                if (e.accidentals == null) e.accidentals = new ArrayList<Object[]>();
                next.preps.put(mm, e);
            }
        }
    }

    private MPrep prepareMeasure(int m, Measure mm) {
        {
            MPrep p = new MPrep();
            Measure prev = m > 0 ? s.measures.get(m - 1) : null;
            p.showTime = prev == null || prev.timeN != mm.timeN || prev.timeD != mm.timeD || prev.timeSymbol != mm.timeSymbol;
            p.showKey = prev == null ? mm.keyFifths != 0 : prev.keyFifths != mm.keyFifths;
            p.oldKey = prev == null ? 0 : prev.keyFifths;
            p.clefChange = new int[staffCount];
            float clefW = 0;
            for (int st = 0; st < staffCount; st++) {
                p.clefChange[st] = m > 0 ? mm.staves.get(st).clef : -1;
                if (p.clefChange[st] >= 0) clefW = Math.max(clefW, clefWidth(p.clefChange[st]) * 0.8f);
            }
            p.midHeader = clefW + (p.showKey ? keySigWidth(mm.keyFifths, p.oldKey) : 0) + (p.showTime ? timeSigWidth(mm) : 0);
            // columns
            TreeSet<Integer> ticks = new TreeSet<Integer>();
            boolean allRests = true;
            for (int st = 0; st < staffCount; st++) {
                for (Voice v : mm.staves.get(st).voices) {
                    int[] starts = startsOf(v);
                    for (int i = 0; i < v.items.size(); i++) {
                        ChordRest cr = v.items.get(i);
                        if (cr instanceof Rest && ((Rest) cr).measureRest) continue;
                        allRests = false;
                        ticks.add(starts[i]);
                    }
                }
            }
            p.allRests = allRests;
            if (ticks.isEmpty()) ticks.add(0);
            Integer[] tk = ticks.toArray(new Integer[0]);
            for (int i = 0; i < tk.length; i++) {
                Col c = new Col();
                c.tick = tk[i];
                int next = i + 1 < tk.length ? tk[i + 1] : mm.ticks();
                c.spring = spring(next - c.tick);
                float right = HEAD_W + 0.5f;
                float acc = 0;
                float lyricW = 0, lyricLead = 0;
                for (int st = 0; st < staffCount; st++) {
                    int clef = clefAt(st, m);
                    for (int vi = 0; vi < StaffMeasure.VOICES; vi++) {
                        Voice v = mm.staves.get(st).voices[vi];
                        int idx = indexAt(v, c.tick);
                        if (idx < 0) continue;
                        ChordRest cr = v.items.get(idx);
                        if (cr.dots > 0) right = Math.max(right, HEAD_W + 0.5f + 0.5f * cr.dots + 0.3f);
                        if (cr instanceof Chord) {
                            Chord ch = (Chord) cr;
                            int accs = 0;
                            boolean second = false;
                            int prevLine = Integer.MIN_VALUE;
                            for (Note n : ch.notes) {
                                if (accidental.containsKey(n)) accs++;
                                int ln = noteLine(clef, isDrums(st), n);
                                if (Math.abs(ln - prevLine) == 1) second = true;
                                prevLine = ln;
                            }
                            if (accs > 0) acc = Math.max(acc, 0.3f + Math.min(accs, 3) * 1.15f);
                            float gw = graceWidth(ch, s.keyAt(m));
                            if (gw > 0) acc = Math.max(acc, (accs > 0 ? 0.3f + Math.min(accs, 3) * 1.15f : 0) + gw);
                            if (second) right += HEAD_W;
                            if (ch.lyric != null && startsOf(v)[idx] == c.tick) {
                                // keep neighbouring lyrics apart by MuseScore's gap (more when a dash has to fit
                                // between them), using where each one really sits: centred, or left-aligned
                                // when it carries a melisma
                                float[] ext = lyricExtent(ch);
                                ChordRest nx = nextInVoice(m, st, vi, idx);
                                float need;
                                if (nx instanceof Chord && nx.lyric != null) {
                                    float gap = Syllables.hyphenated(ch.lyric) ? DASH_SPACE : LYRIC_MIN_DISTANCE;
                                    need = ext[1] - lyricExtent(nx)[0] + gap;
                                } else {
                                    need = ext[1] + LYRIC_MIN_DISTANCE;
                                }
                                // the last note of a bar: the next one follows the bar's end and start padding
                                if (idx + 1 >= v.items.size()) need -= 1.5f;
                                lyricW = Math.max(lyricW, need);
                                if (c.tick == 0) lyricLead = Math.max(lyricLead, -ext[0] - 1.0f);
                            }
                        }
                        if (cr.chordSymbol != null) {
                            lyricW = Math.max(lyricW, tm.width(ChordSymbols.display(cr.chordSymbol), TextMetrics.FONT_TEXT, 2.2f) + 0.8f);
                        }
                    }
                }
                // a long first syllable of a bar may need room before its note
                c.accPad = Math.max(acc, lyricLead);
                c.minW = Math.max(right, lyricW);
                c.width = c.accPad + Math.max(c.spring, c.minW);
                p.cols.add(c);
            }
            float w = 1.1f;
            for (Col c : p.cols) w += c.width;
            w += 0.4f;
            if (allRests) w = Math.max(w, 6.5f);
            if (mm.startRepeat) w += 1.3f;
            if (mm.endBarline == Measure.BAR_END_REPEAT) w += 1.3f;
            p.natural = w;
            return p;
        }
    }

    // --------------------------------------------------------------------------- main

    private float namesWidth(boolean first) {
        if (s.parts.size() == 1 && s.parts.get(0).staves.size() == 1 && !first) return 0;
        float w = 0;
        for (Part p : s.parts) {
            String n = first ? p.name : p.shortName;
            if (n == null || n.isEmpty()) continue;
            w = Math.max(w, tm.width(n, TextMetrics.FONT_TEXT, 2.0f));
        }
        if (s.parts.size() == 1 && s.parts.get(0).staves.size() == 1) {
            // single-staff scores: no instrument names, like MuseScore's default for solo parts
            return 0;
        }
        return w > 0 ? w + 1.0f : 0;
    }

    private float bracketWidth() {
        float w = 0;
        if (s.parts.size() > 1) w += 1.2f;
        for (Part p : s.parts) if (p.staves.size() > 1) {
            w += 1.6f;
            break;
        }
        return w;
    }

    private float systemHeaderWidth(int m) {
        float clefW = 0;
        for (int st = 0; st < staffCount; st++) clefW = Math.max(clefW, clefWidth(clefAt(st, m)));
        Measure mm = s.measures.get(m);
        float w = 0.6f + clefW + keySigWidth(mm.keyFifths, 0) + (mm.keyFifths == 0 ? 0 : 0);
        if (preps.get(m).showTime) w += timeSigWidth(mm);
        return w;
    }

    /** Slurs and hairpins of the score, resolved once per layout. */
    private List<Spanners.Span> spans;

    /** Clef of every staff in every measure, and where each measure starts: looked up per note, so precomputed. */
    private int[][] clefs;
    private int[] measureStart;

    // --------------------------------------------------------------------------- signatures (layout cache)

    private static long mix(long h, long v) {
        h ^= v;
        h *= 0x100000001b3L;
        return h ^ (h >>> 29);
    }

    private static long mixS(long h, String str) {
        return mix(h, str == null ? 0x5bd1e995L : ((long) str.hashCode() << 16) ^ str.length());
    }

    /**
     * Everything about a measure that layout reads, and the identity of its objects (the display list
     * points to them). Keep in step with the model: ModelTests checks the fields of these classes.
     */
    static long measureSig(Measure mm) {
        long h = 0xcbf29ce484222325L;
        h = mix(h, System.identityHashCode(mm));
        h = mix(h, mm.timeN);
        h = mix(h, mm.timeD);
        h = mix(h, mm.timeSymbol ? 1 : 0);
        h = mix(h, mm.keyFifths);
        h = mix(h, mm.startRepeat ? 1 : 0);
        h = mix(h, mm.endBarline);
        h = mix(h, mm.tempo);
        h = mixS(h, mm.tempoText);
        h = mixS(h, mm.rehearsal);
        h = mix(h, mm.actualTicks);
        h = mix(h, mm.irregular ? 1 : 0);
        for (StaffMeasure sm : mm.staves) {
            h = mix(h, System.identityHashCode(sm));
            h = mix(h, sm.clef);
            for (Voice v : sm.voices) {
                h = mix(h, System.identityHashCode(v));
                h = mix(h, v.items.size());
                for (ChordRest cr : v.items) h = chordRestSig(h, cr);
            }
        }
        return h;
    }

    private static long chordRestSig(long h, ChordRest cr) {
        h = mix(h, System.identityHashCode(cr));
        h = mix(h, cr.type);
        h = mix(h, cr.dots);
        if (cr.tuplet != null) {
            h = mix(h, System.identityHashCode(cr.tuplet));
            h = mix(h, cr.tuplet.actual);
            h = mix(h, cr.tuplet.normal);
            h = mix(h, cr.tuplet.baseType);
        }
        h = mixS(h, cr.dynamic);
        h = mixS(h, cr.chordSymbol);
        h = mixS(h, cr.lyric);
        h = mix(h, cr.melisma);
        for (String a : cr.articulations) h = mixS(h, a);
        h = mixS(h, cr.staffText);
        h = mix(h, cr.slurTicks);
        h = mix(h, cr.hairpin);
        h = mix(h, cr.hairpinTicks);
        if (cr instanceof Rest) {
            h = mix(h, ((Rest) cr).measureRest ? 1 : 0);
            h = mix(h, ((Rest) cr).measureTicks);
        } else {
            Chord c = (Chord) cr;
            h = mix(h, c.stemDir);
            h = mix(h, c.graceKind);
            for (Note n : c.notes) {
                h = mix(h, System.identityHashCode(n));
                h = mix(h, n.pitch);
                h = mix(h, n.tpc);
                h = mix(h, n.tieForward ? 1 : 0);
                h = mix(h, n.forceAccidental ? 1 : 0);
            }
            h = mix(h, c.graces.size());
            for (Chord gr : c.graces) h = chordRestSig(h, gr);
        }
        return h;
    }

    private long sigAt(int m) {
        return m >= 0 && m < sig.length ? sig[m] : 0x9e3779b97f4a7c15L;
    }

    /** Key for a measure's preparation: it reads its neighbours (ties, key and time changes, lyrics) and the clefs. */
    private long prepKey(int m) {
        long h = mix(globalSig, sigAt(m - 1));
        h = mix(h, sigAt(m));
        h = mix(h, sigAt(m + 1));
        for (int st = 0; st < staffCount; st++) h = mix(h, clefAt(st, m) * 31L + (m > 0 ? clefAt(st, m - 1) : -1));
        return h;
    }

    /** Element start ticks per voice, computed once per layout (the score does not change during it). */
    private final IdentityHashMap<Voice, int[]> startsCache = new IdentityHashMap<Voice, int[]>();

    private int[] startsOf(Voice v) {
        int[] st = startsCache.get(v);
        if (st == null) startsCache.put(v, st = v.starts());
        return st;
    }

    /** Index of the element starting exactly at tick (binary search on the cached starts), or -1. */
    private int indexAt(Voice v, int tick) {
        int i = java.util.Arrays.binarySearch(startsOf(v), 0, v.items.size(), tick);
        return i >= 0 ? i : -1;
    }

    private int clefAt(int st, int m) {
        int[] c = clefs[st];
        return c[Math.max(0, Math.min(m, c.length - 1))];
    }

    private int globalTick(int m, int tick) {
        return (m < measureStart.length ? measureStart[m] : measureStart[measureStart.length - 1]) + tick;
    }

    /** Signature of each measure (content and object identity), and of everything global. */
    private long[] sig;
    private long globalSig;
    private Cache next;

    private void precompute() {
        int mc = s.measures.size();
        sig = new long[mc];
        for (int m = 0; m < mc; m++) sig[m] = measureSig(s.measures.get(m));
        long g = 0xcbf29ce484222325L;
        g = mix(g, System.identityHashCode(tm));
        g = mix(g, Float.floatToIntBits(o.width));
        g = mix(g, Float.floatToIntBits(o.marginX));
        g = mix(g, Float.floatToIntBits(o.pageHeight));
        g = mix(g, o.scrollWideSystems ? 1 : 0);
        g = mix(g, staffCount);
        for (Part p : s.parts) {
            g = mix(g, System.identityHashCode(p));
            g = mixS(g, p.name);
            g = mixS(g, p.shortName);
            g = mix(g, p.drums ? 1 : 0);
            g = mix(g, p.staves.size());
            for (Staff st : p.staves) g = mix(g, st.defaultClef);
        }
        globalSig = g;
        if (o.cache != null) {
            next = new Cache();
            o.cache.prepHits = o.cache.prepMisses = o.cache.systemHits = o.cache.systemMisses = 0;
        }
        clefs = new int[staffCount][Math.max(1, mc)];
        for (int st = 0; st < staffCount; st++) {
            int cur = s.staff(st).defaultClef;
            for (int m = 0; m < mc; m++) {
                int c = s.measures.get(m).staves.get(st).clef;
                if (c >= 0) cur = c;
                clefs[st][m] = cur;
            }
        }
        measureStart = new int[mc + 1];
        for (int m = 0; m < mc; m++) measureStart[m + 1] = measureStart[m] + s.measures.get(m).ticks();
    }

    private void run() {
        precompute();
        spans = Spanners.all(s);
        computeAccidentals();
        prepareMeasures();
        dl.width = o.width;
        float y = o.marginTop;
        if (o.showTitle) y = titleBlock(y);

        int m = 0;
        int sysIdx = 0;
        int page = 0;
        while (m < s.measures.size()) {
            boolean first = sysIdx == 0;
            float left = o.marginX + namesWidth(first) + bracketWidth();
            float header = systemHeaderWidth(m);
            float avail = o.width - o.marginX - left - header;
            int end = m;
            float used = preps.get(m).natural;
            while (end + 1 < s.measures.size()) {
                MPrep np = preps.get(end + 1);
                float add = np.natural + np.midHeader;
                if (used + add > avail) break;
                used += add;
                end++;
            }
            boolean last = end == s.measures.size() - 1;
            float stretchTotal = 0;
            for (int i = m; i <= end; i++) for (Col c : preps.get(i).cols) stretchTotal += c.spring;
            float extra = avail - used;
            float ratio = 0;
            if (extra > 0 && stretchTotal > 0 && !(last && used < avail * 0.5f)) ratio = extra / stretchTotal;
            if (extra < 0 && stretchTotal > 0) ratio = Math.max(-0.5f, extra / stretchTotal);
            float sysY = layoutSystem(sysIdx, m, end, left, header, ratio, y);
            DisplayList.SystemInfo si = dl.systems.get(dl.systems.size() - 1);
            if (o.pageHeight > 0) {
                float h = si.bottom - si.top;
                float pageBottom = (page + 1) * o.pageHeight - o.marginBottom;
                if (si.bottom > pageBottom && si.top > page * o.pageHeight + o.marginTop + 1) {
                    // move system to the next page
                    page++;
                    float target = page * o.pageHeight + o.marginTop - (si.top - si.y);
                    float dy = target - si.y;
                    shiftSystem(sysIdx, dy);
                    sysY = si.bottom;
                }
            }
            y = sysY + o.systemGap;
            m = end + 1;
            sysIdx++;
        }
        drawTies();
        drawSlurs();
        drawLyricLines();
        if (o.cache != null) {
            o.cache.preps = next.preps;
            o.cache.systems = next.systems;
        }
        if (o.pageHeight > 0) dl.height = (page + 1) * o.pageHeight;
        else dl.height = y + 2;
    }

    private int systemItemStart = 0, systemHitStart = 0, systemLyricHitStart = 0;

    private void shiftSystem(int sysIdx, float dy) {
        DisplayList.SystemInfo si = dl.systems.get(sysIdx);
        si.y += dy;
        si.top += dy;
        si.bottom += dy;
        for (int i = 0; i < si.staffY.length; i++) si.staffY[i] += dy;
        for (int i = systemItemStart; i < dl.items.size(); i++) {
            DisplayList.Item it = dl.items.get(i);
            it.y += dy;
            it.y2 += dy;
            if (it.pts != null) for (int k = 1; k < it.pts.length; k += 2) it.pts[k] += dy;
        }
        for (int i = systemHitStart; i < dl.hits.size(); i++) {
            DisplayList.Hit h = dl.hits.get(i);
            h.y0 += dy;
            h.y1 += dy;
        }
        for (int i = systemLyricHitStart; i < dl.lyricHits.size(); i++) {
            DisplayList.Hit h = dl.lyricHits.get(i);
            h.y0 += dy;
            h.y1 += dy;
            h.baseline += dy;
        }
        for (NoteRec r : noteRecs.values()) if (r.system == sysIdx) r.y += dy;
    }

    private float titleBlock(float y) {
        List<DisplayList.Item> out = dl.items;
        float cx = o.width / 2;
        boolean any = false;
        if (s.title != null && !s.title.isEmpty()) {
            y += 4.2f;
            text(out, s.title, TextMetrics.FONT_TEXT, 4.4f, cx, y, DisplayList.ALIGN_CENTER);
            any = true;
        }
        if (s.subtitle != null && !s.subtitle.isEmpty()) {
            y += 2.8f;
            text(out, s.subtitle, TextMetrics.FONT_TEXT, 2.6f, cx, y, DisplayList.ALIGN_CENTER);
            any = true;
        }
        boolean comp = s.composer != null && !s.composer.isEmpty();
        boolean lyr = s.lyricist != null && !s.lyricist.isEmpty();
        if (comp || lyr) {
            y += 3.0f;
            if (comp) text(out, s.composer, TextMetrics.FONT_TEXT, 2.0f, o.width - o.marginX, y, DisplayList.ALIGN_RIGHT);
            if (lyr) text(out, s.lyricist, TextMetrics.FONT_TEXT, 2.0f, o.marginX, y, DisplayList.ALIGN_LEFT);
            any = true;
        }
        return any ? y + 3.5f : y;
    }

    // --------------------------------------------------------------------------- system

    private float layoutSystem(int sysIdx, int m0, int m1, float left, float header, float ratio, float y) {
        systemItemStart = dl.items.size();
        systemHitStart = dl.hits.size();
        systemLyricHitStart = dl.lyricHits.size();
        DisplayList.SystemInfo si = new DisplayList.SystemInfo();
        si.firstMeasure = m0;
        si.lastMeasure = m1;
        si.x0 = left;
        si.x1 = o.width - o.marginX;
        // horizontal positions; if the measures still do not fit (one very busy bar on a phone), a
        // second pass squeezes every column of the system uniformly so nothing runs off the page
        float x = left + header;
        float[] mx = new float[m1 - m0 + 1];
        float squeeze = 1f;
        for (int pass = 0; pass < 2; pass++) {
        x = left + header;
        float colSum = 0;
        for (int m = m0; m <= m1; m++) {
            MPrep p = preps.get(m);
            Measure mm = s.measures.get(m);
            DisplayList.MeasureInfo mi = new DisplayList.MeasureInfo();
            mi.system = sysIdx;
            float mid = m == m0 ? 0 : p.midHeader;
            mi.x = x;
            float cx = x + mid + 1.1f + (mm.startRepeat ? 1.3f : 0);
            mi.contentX = cx;
            mi.colTicks = new int[p.cols.size()];
            mi.colX = new float[p.cols.size()];
            for (int i = 0; i < p.cols.size(); i++) {
                Col c = p.cols.get(i);
                float w = c.accPad + Math.max(c.spring, c.minW) + c.spring * ratio;
                w = Math.max(w, c.accPad + c.minW * 0.85f);
                colSum += w;
                mi.colTicks[i] = c.tick;
                mi.colX[i] = cx + c.accPad * squeeze;
                cx += w * squeeze;
            }
            float width = (cx + 0.4f + (mm.endBarline == Measure.BAR_END_REPEAT ? 1.3f : 0)) - x;
            if (p.allRests && squeeze == 1f) {
                // measure-rest bars keep a minimum width and stretch like the others
                float natural = p.natural + mid;
                float springSum = 0;
                for (Col c : p.cols) springSum += c.spring;
                width = Math.max(width, natural + springSum * Math.max(ratio, 0));
            }
            mi.width = width;
            mi.ticks = mm.ticks();
            while (dl.measures.size() <= m) dl.measures.add(null);
            dl.measures.set(m, mi);
            mx[m - m0] = x;
            x += width;
        }
        float limit = o.width - o.marginX;
        if (x <= limit + 0.01f || colSum <= 0) break;
        // the fixed parts (header, bar padding) stay; the columns share what is left
        float fixed = x - (left + header) - colSum;
        squeeze = Math.max(0.3f, (limit - left - header - fixed) / colSum);
        if (o.scrollWideSystems && squeeze < 0.9f) {
            si.wide = true;
            break;
        }
        }
        // the last measure ends exactly at the system end when justified
        if (ratio > 0) {
            DisplayList.MeasureInfo last = dl.measures.get(m1);
            float end = last.x + last.width;
            if (Math.abs(end - si.x1) < 3f) last.width = si.x1 - last.x;
        }
        DisplayList.MeasureInfo lastMi = dl.measures.get(m1);
        si.x1 = lastMi.x + lastMi.width;

        // generate staves, or reuse them from the last layout when nothing they depend on changed
        StaffGen[] gens = new StaffGen[staffCount];
        long key = o.cache != null ? systemKey(sysIdx, m0, m1, left, header, ratio, si) : 0;
        SystemEntry hit = o.cache == null ? null : o.cache.systems.get(key);
        if (hit != null) {
            for (int st = 0; st < staffCount; st++) gens[st] = cloneGen(hit.gens[st]);
            for (Object[] r : hit.recs) {
                NoteRec c = cloneRec((NoteRec) r[1]);
                c.system = sysIdx;
                pendingRecs.put((Note) r[0], c);
            }
            next.systems.put(key, hit);
            o.cache.systemHits++;
        } else {
            for (int st = 0; st < staffCount; st++) {
                gens[st] = new StaffGen();
                genStaff(gens[st], sysIdx, st, m0, m1, left, si);
            }
            if (o.cache != null) {
                o.cache.systemMisses++;
                SystemEntry e = new SystemEntry();
                e.gens = new StaffGen[staffCount];
                for (int st = 0; st < staffCount; st++) e.gens[st] = cloneGen(gens[st]);
                e.recs = new ArrayList<Object[]>();
                for (Map.Entry<Note, NoteRec> r : pendingRecs.entrySet()) e.recs.add(new Object[]{r.getKey(), cloneRec(r.getValue())});
                next.systems.put(key, e);
            }
        }
        // vertical placement
        si.staffY = new float[staffCount];
        float cur = y + Math.max(0, -gens[0].top);
        if (sysIdx == 0 && hasTempoOrRehearsal(m0, m1)) cur += 0.5f;
        for (int st = 0; st < staffCount; st++) {
            if (st > 0) {
                boolean samePart = s.partOfStaff(st) == s.partOfStaff(st - 1);
                float minDist = samePart ? 6.5f : 7.5f;
                float need = (gens[st - 1].bottom - 4) + (-gens[st].top) + 1.2f;
                cur = si.staffY[st - 1] + 4 + Math.max(minDist - 4, need);
            }
            si.staffY[st] = cur;
        }
        si.y = si.staffY[0];
        si.top = si.staffY[0] + gens[0].top;
        si.bottom = si.staffY[staffCount - 1] + gens[staffCount - 1].bottom;
        // copy items with offset
        for (int st = 0; st < staffCount; st++) {
            float oy = si.staffY[st];
            for (DisplayList.Item it : gens[st].items) {
                it.y += oy;
                it.y2 += oy;
                if (it.pts != null) for (int k = 1; k < it.pts.length; k += 2) it.pts[k] += oy;
                dl.items.add(it);
            }
            for (DisplayList.Hit h : gens[st].hits) {
                h.y0 += oy;
                h.y1 += oy;
                dl.hits.add(h);
            }
            for (DisplayList.Hit h : gens[st].lyricHits) {
                h.y0 += oy;
                h.y1 += oy;
                h.baseline += oy;
                dl.lyricHits.add(h);
            }
        }
        // translate note records of this system
        for (Map.Entry<Note, NoteRec> e : pendingRecs.entrySet()) {
            NoteRec r = e.getValue();
            r.y += si.staffY[r.staff];
            noteRecs.put(e.getKey(), r);
        }
        pendingRecs.clear();
        systemDecorations(sysIdx, si, m0, m1, left);
        dl.systems.add(si);
        return si.bottom;
    }

    /** Everything a system's staves depend on: its measures and their neighbours, position, clefs, lines through it. */
    private long systemKey(int sysIdx, int m0, int m1, float left, float header, float ratio, DisplayList.SystemInfo si) {
        long h = mix(globalSig, m1 - m0);
        h = mix(h, sysIdx == 0 ? 1 : 0);
        h = mix(h, Float.floatToIntBits(left));
        h = mix(h, Float.floatToIntBits(header));
        h = mix(h, Float.floatToIntBits(ratio));
        h = mix(h, Float.floatToIntBits(si.x0));
        h = mix(h, Float.floatToIntBits(si.x1));
        h = mix(h, si.wide ? 1 : 0);
        h = mix(h, s.measureNumber(m0));
        for (int m = m0 - 1; m <= m1 + 1; m++) h = mix(h, sigAt(m));
        for (int st = 0; st < staffCount; st++) h = mix(h, clefAt(st, m0));
        for (int m = m0; m <= m1; m++) {
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            h = mix(h, Float.floatToIntBits(mi.x));
            h = mix(h, Float.floatToIntBits(mi.width));
        }
        int sysStart = globalTick(m0, 0), sysEnd = globalTick(m1, s.measures.get(m1).ticks());
        for (Spanners.Span sp : spans) {
            if (sp.kind == 0 || sp.startTick >= sysEnd || sp.endTick <= sysStart) continue;
            h = mix(h, sp.kind * 1000003L + sp.staff * 31 + sp.voice);
            h = mix(h, sp.startTick - sysStart);
            h = mix(h, sp.endTick - sysStart);
        }
        return h;
    }

    private static DisplayList.Item cloneItem(DisplayList.Item a) {
        DisplayList.Item b = new DisplayList.Item();
        b.kind = a.kind;
        b.x = a.x;
        b.y = a.y;
        b.x2 = a.x2;
        b.y2 = a.y2;
        b.w = a.w;
        b.glyph = a.glyph;
        b.text = a.text;
        b.font = a.font;
        b.align = a.align;
        b.pts = a.pts == null ? null : a.pts.clone();
        b.color = a.color;
        b.owner = a.owner;
        b.note = a.note;
        b.voice = a.voice;
        return b;
    }

    private static DisplayList.Hit cloneHit(DisplayList.Hit a) {
        DisplayList.Hit b = new DisplayList.Hit();
        b.x0 = a.x0;
        b.y0 = a.y0;
        b.x1 = a.x1;
        b.y1 = a.y1;
        b.pos = a.pos == null ? null : a.pos.copy();
        b.cr = a.cr;
        b.note = a.note;
        b.noteIdx = a.noteIdx;
        b.baseline = a.baseline;
        b.grace = a.grace;
        return b;
    }

    private static StaffGen cloneGen(StaffGen a) {
        StaffGen b = new StaffGen();
        for (DisplayList.Item it : a.items) b.items.add(cloneItem(it));
        for (DisplayList.Hit h : a.hits) b.hits.add(cloneHit(h));
        for (DisplayList.Hit h : a.lyricHits) b.lyricHits.add(cloneHit(h));
        b.top = a.top;
        b.bottom = a.bottom;
        return b;
    }

    private static NoteRec cloneRec(NoteRec a) {
        NoteRec b = new NoteRec();
        b.system = a.system;
        b.x = a.x;
        b.y = a.y;
        b.headW = a.headW;
        b.tieUp = a.tieUp;
        b.staff = a.staff;
        return b;
    }

    private boolean hasTempoOrRehearsal(int m0, int m1) {
        for (int m = m0; m <= m1; m++) {
            if (s.measures.get(m).tempo > 0 || s.measures.get(m).rehearsal != null) return true;
        }
        return false;
    }

    private final IdentityHashMap<Note, NoteRec> pendingRecs = new IdentityHashMap<Note, NoteRec>();

    // --------------------------------------------------------------------------- staff generation

    private void drawKeySig(StaffGen g, int clef, int key, int oldKey, float x) {
        if (clef == Clef.PERC) return;
        if (key == 0 && oldKey != 0) {
            int[] lines = Clef.keySigLines(clef, oldKey > 0);
            for (int i = 0; i < Math.abs(oldKey); i++) {
                glyph(g.items, Sym.accidentalNatural, x + i * 1.0f, lines[i] * 0.5f);
            }
            return;
        }
        int[] lines = Clef.keySigLines(clef, key > 0);
        int g2 = key > 0 ? Sym.accidentalSharp : Sym.accidentalFlat;
        for (int i = 0; i < Math.abs(key); i++) {
            glyph(g.items, g2, x + i * 1.0f, lines[i] * 0.5f);
            g.ext(lines[i] * 0.5f - 1.5f, lines[i] * 0.5f + 1);
        }
    }

    private void drawTimeSig(StaffGen g, Measure mm, float x, float width) {
        if (mm.timeSymbol) {
            int gl = mm.timeD == 2 ? Sym.timeSigCutCommon : Sym.timeSigCommon;
            glyph(g.items, gl, x, 2f);
            return;
        }
        drawDigits(g, String.valueOf(mm.timeN), x, width, 1f);
        drawDigits(g, String.valueOf(mm.timeD), x, width, 3f);
    }

    private void drawDigits(StaffGen g, String str, float x, float width, float y) {
        float w = 0;
        for (int i = 0; i < str.length(); i++) w += Sym.width(Sym.timeSig0 + (str.charAt(i) - '0'));
        float cx = x + (width - 1.0f - w) / 2;
        for (int i = 0; i < str.length(); i++) {
            int gl = Sym.timeSig0 + (str.charAt(i) - '0');
            glyph(g.items, gl, cx, y);
            cx += Sym.width(gl);
        }
    }

    private void genStaff(StaffGen g, int sysIdx, int st, int m0, int m1, float left, DisplayList.SystemInfo si) {
        // staff lines
        for (int i = 0; i < 5; i++) {
            DisplayList.Item it = line(g.items, si.x0, i, si.x1, i, Sym.staffLineThickness);
            it.color = DisplayList.C_INK;
        }
        // system header
        float hx = left + 0.6f;
        int clef = clefAt(st, m0);
        glyph(g.items, clefGlyph(clef), hx, Clef.glyphLine(clef) * 0.5f);
        g.ext(-1.5f, 5.5f);
        hx += clefWidth(clef);
        Measure first = s.measures.get(m0);
        float maxClef = 0;
        for (int k = 0; k < staffCount; k++) maxClef = Math.max(maxClef, clefWidth(clefAt(k, m0)));
        hx = left + 0.6f + maxClef;
        if (first.keyFifths != 0) {
            drawKeySig(g, clef, first.keyFifths, 0, hx);
            hx += keySigWidth(first.keyFifths, 0);
        }
        if (preps.get(m0).showTime) drawTimeSig(g, first, hx, timeSigWidth(first));

        for (int m = m0; m <= m1; m++) {
            Measure mm = s.measures.get(m);
            MPrep p = preps.get(m);
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            if (m > m0) {
                float x = mi.x + 0.4f;
                if (p.clefChange[st] >= 0) {
                    int c = p.clefChange[st];
                    DisplayList.Item it = glyph(g.items, clefGlyph(c), x, Clef.glyphLine(c) * 0.5f);
                    it.w = 0.8f;
                }
                float clefW = 0;
                for (int k = 0; k < staffCount; k++) if (p.clefChange[k] >= 0) clefW = Math.max(clefW, clefWidth(p.clefChange[k]) * 0.8f);
                x += clefW;
                if (p.showKey) {
                    drawKeySig(g, clefAt(st, m), mm.keyFifths, p.oldKey, x);
                    x += keySigWidth(mm.keyFifths, p.oldKey);
                }
                if (p.showTime) drawTimeSig(g, mm, x, timeSigWidth(mm));
            }
            genMeasureContent(g, sysIdx, st, m, mi);
        }
        genMarkings(g, sysIdx, st, m0, m1);
    }

    private void genMeasureContent(StaffGen g, int sysIdx, int st, int m, DisplayList.MeasureInfo mi) {
        Measure mm = s.measures.get(m);
        StaffMeasure sm = mm.staves.get(st);
        int clef = clefAt(st, m);
        boolean drums = isDrums(st);
        int voicesUsed = nonEmptyVoices(sm);
        for (int v = 0; v < StaffMeasure.VOICES; v++) {
            Voice voice = sm.voices[v];
            if (voice.isEmpty()) continue;
            int[] starts = startsOf(voice);
            List<ChordGeom> geoms = new ArrayList<ChordGeom>();
            ChordGeom[] byIdx = new ChordGeom[voice.items.size()];
            for (int i = 0; i < voice.items.size(); i++) {
                ChordRest cr = voice.items.get(i);
                if (cr instanceof Chord) {
                    ChordGeom cg = chordGeom((Chord) cr, clef, drums, dl.xAt(m, starts[i]), voicesUsed > 1, v);
                    cg.voice = v;
                    cg.tick = starts[i];
                    cg.measure = m;
                    cg.staff = st;
                    byIdx[i] = cg;
                    geoms.add(cg);
                }
            }
            // beams
            List<List<Integer>> groups = beamGroups(voice, starts, mm);
            for (List<Integer> grp : groups) {
                layoutBeam(g, grp, byIdx, voice, v);
            }
            for (int i = 0; i < voice.items.size(); i++) {
                ChordRest cr = voice.items.get(i);
                Pos pos = new Pos(m, st, v, starts[i]);
                if (cr instanceof Chord) {
                    emitChord(g, byIdx[i], pos, sysIdx);
                    if (!((Chord) cr).graces.isEmpty()) emitGraces(g, (Chord) cr, clef, drums, dl.xAt(m, starts[i]), pos, s.keyAt(m));
                } else {
                    emitRest(g, (Rest) cr, pos, mi, voicesUsed > 1, v);
                }
            }
            emitTuplets(g, voice, starts, byIdx, m, v);
        }
    }

    // --------------------------------------------------------------------------- grace notes

    /** The accidental a grace note shows (its own alteration if the key does not give it), or -1. */
    private static int graceAccidental(Note n, int key) {
        int alt = Pitch.alter(n.tpc);
        if (alt == Pitch.keyAlter(key, Pitch.step(n.tpc))) return -1;
        return Sym.accidentalDoubleFlat + Math.max(-2, Math.min(2, alt)) + 2;
    }

    /** Room the grace notes of a chord need before it (and before its accidentals). */
    private float graceWidth(Chord c, int key) {
        if (c.graces.isEmpty()) return 0;
        float w = GRACE_TO_NOTE;
        for (Chord gr : c.graces) {
            float acc = 0;
            for (Note n : gr.notes) {
                int ag = graceAccidental(n, key);
                if (ag >= 0) acc = Math.max(acc, Sym.width(ag) * GRACE_SCALE + 0.1f);
            }
            w += HEAD_W * GRACE_SCALE + GRACE_GAP + acc;
        }
        return w;
    }

    /**
     * Grace notes before a chord, small and stems up, with a slash through an acciaccatura's stem,
     * their own accidentals and ledger lines, placed right to left from the main chord's accidentals.
     */
    private void emitGraces(StaffGen g, Chord c, int clef, boolean drums, float x, Pos pos, int key) {
        int accs = 0;
        for (Note n : c.notes) if (accidental.containsKey(n)) accs++;
        float cur = x - (accs > 0 ? 0.3f + Math.min(accs, 3) * 1.15f : 0) - GRACE_TO_NOTE;
        float hw = HEAD_W * GRACE_SCALE;
        for (int k = c.graces.size() - 1; k >= 0; k--) {
            Chord gr = c.graces.get(k);
            float hx = cur - hw;
            float left = hx;
            int minL = Integer.MAX_VALUE, maxL = Integer.MIN_VALUE;
            for (Note n : gr.notes) {
                int ln = noteLine(clef, drums, n);
                minL = Math.min(minL, ln);
                maxL = Math.max(maxL, ln);
                DisplayList.Item it = glyph(g.items, Sym.noteheadBlack, hx, ln * 0.5f);
                it.w = GRACE_SCALE;
                it.owner = c;
                it.note = n;
                it.voice = pos.voice;
                int ag = graceAccidental(n, key);
                if (ag >= 0) {
                    float aw = Sym.width(ag) * GRACE_SCALE;
                    DisplayList.Item a = glyph(g.items, ag, hx - aw - 0.1f, ln * 0.5f);
                    a.w = GRACE_SCALE;
                    a.owner = c;
                    a.note = n;
                    left = Math.min(left, hx - aw - 0.1f);
                }
                DisplayList.Hit h = new DisplayList.Hit();
                h.x0 = hx - 0.15f;
                h.x1 = hx + hw + 0.15f;
                h.y0 = ln * 0.5f - 0.6f;
                h.y1 = ln * 0.5f + 0.6f;
                h.pos = pos;
                h.cr = c;
                h.note = n;
                h.grace = k;
                g.hits.add(h);
            }
            for (int l = -2; l >= minL; l -= 2) line(g.items, hx - 0.2f, l * 0.5f, hx + hw + 0.2f, l * 0.5f, Sym.legerLineThickness * 0.8f).owner = c;
            for (int l = 10; l <= maxL; l += 2) line(g.items, hx - 0.2f, l * 0.5f, hx + hw + 0.2f, l * 0.5f, Sym.legerLineThickness * 0.8f).owner = c;
            float sx = hx + hw - Sym.stemThickness / 2;
            float top = minL * 0.5f - 2.4f;
            line(g.items, sx, maxL * 0.5f - 0.1f, sx, top, Sym.stemThickness).owner = c;
            DisplayList.Item f = glyph(g.items, gr.type >= Duration.N16 ? Sym.flag16thUp : Sym.flag8thUp, sx - Sym.stemThickness / 2, top);
            f.w = GRACE_SCALE;
            f.owner = c;
            if (gr.graceKind == Chord.ACCIACCATURA) line(g.items, sx - 0.55f, top + 1.45f, sx + 0.75f, top + 0.5f, 0.12f).owner = c;
            g.ext(top - 0.5f, maxL * 0.5f + 0.6f);
            cur = left - GRACE_GAP;
        }
    }

    // --------------------------------------------------------------------------- chords

    private ChordGeom chordGeom(Chord c, int clef, boolean drums, float x, boolean multiVoice, int voice) {
        ChordGeom cg = new ChordGeom();
        cg.chord = c;
        cg.x = x;
        int n = c.notes.size();
        cg.lines = new int[n];
        for (int i = 0; i < n; i++) cg.lines[i] = noteLine(clef, drums, c.notes.get(i));
        int minLine = Integer.MAX_VALUE, maxLine = Integer.MIN_VALUE;
        for (int l : cg.lines) {
            minLine = Math.min(minLine, l);
            maxLine = Math.max(maxLine, l);
        }
        if (c.stemDir != Chord.STEM_AUTO) {
            cg.up = c.stemDir == Chord.STEM_UP;
            cg.forcedDir = true;
        } else if (multiVoice) {
            cg.up = voice % 2 == 0;
            cg.forcedDir = true;
        } else {
            cg.up = (maxLine - 4) > (4 - minLine);
        }
        if (c.type == Duration.WHOLE) cg.head = Sym.noteheadWhole;
        else if (c.type == Duration.HALF) cg.head = Sym.noteheadHalf;
        else cg.head = Sym.noteheadBlack;
        cg.headW = Sym.width(cg.head);
        cg.hasStem = c.type >= Duration.HALF;
        cg.noteTopY = minLine * 0.5f;
        cg.noteBottomY = maxLine * 0.5f;
        computeNoteOffsets(cg);
        defaultStem(cg);
        return cg;
    }

    private void computeNoteOffsets(ChordGeom cg) {
        int n = cg.lines.length;
        cg.noteDx = new float[n];
        // notes sorted by pitch ascending -> lines descending (mostly). Work on index order by line.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        final int[] lines = cg.lines;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return lines[b] - lines[a]; // bottom (largest line) first
            }
        });
        if (cg.up) {
            boolean prevShifted = false;
            int prevLine = Integer.MIN_VALUE;
            for (int k = 0; k < n; k++) {
                int i = order[k];
                boolean shift = k > 0 && Math.abs(prevLine - lines[i]) == 1 && !prevShifted;
                cg.noteDx[i] = shift ? cg.headW - 0.05f : 0;
                prevShifted = shift;
                prevLine = lines[i];
            }
        } else {
            boolean prevShifted = false;
            int prevLine = Integer.MIN_VALUE;
            for (int k = n - 1; k >= 0; k--) {
                int i = order[k];
                boolean shift = k < n - 1 && Math.abs(prevLine - lines[i]) == 1 && !prevShifted;
                cg.noteDx[i] = shift ? -(cg.headW - 0.05f) : 0;
                prevShifted = shift;
                prevLine = lines[i];
            }
        }
    }

    private void defaultStem(ChordGeom cg) {
        float len = 3.5f;
        int flags = Math.max(0, cg.chord.type - Duration.EIGHTH);
        if (cg.chord.type >= Duration.N16) len += 0.5f * flags;
        if (cg.up) {
            cg.stemX = cg.x + Sym.STEM_UP_SE[Sym.noteheadBlack][0] - Sym.stemThickness / 2;
            if (cg.head == Sym.noteheadHalf) cg.stemX = cg.x + Sym.STEM_UP_SE[Sym.noteheadHalf][0] - Sym.stemThickness / 2;
            cg.stemEndY = Math.min(cg.noteTopY - len, 2f);
        } else {
            cg.stemX = cg.x + Sym.stemThickness / 2;
            cg.stemEndY = Math.max(cg.noteBottomY + len, 2f);
        }
    }

    private List<List<Integer>> beamGroups(Voice voice, int[] starts, Measure mm) {
        List<List<Integer>> groups = new ArrayList<List<Integer>>();
        boolean compound = mm.timeD == 8 && mm.timeN % 3 == 0;
        List<Integer> cur = new ArrayList<Integer>();
        int curGroup = -1;
        Tuplet curTuplet = null;
        // does the measure contain 16ths or shorter (affects grouping in simple meters)
        boolean shortNotes = false;
        for (ChordRest cr : voice.items) if (cr instanceof Chord && cr.type >= Duration.N16) shortNotes = true;
        int groupLen;
        if (compound) groupLen = 720;
        else if (mm.timeD == 4 && mm.timeN == 4 && !shortNotes) groupLen = 960;
        else if (mm.timeD == 2) groupLen = 960;
        else if (mm.timeD == 8) groupLen = shortNotes ? 240 : 720;
        else if (mm.timeD == 16) groupLen = 240;
        else groupLen = 480;
        if (mm.timeD == 8 && mm.timeN == 3) groupLen = 720;
        for (int i = 0; i < voice.items.size(); i++) {
            ChordRest cr = voice.items.get(i);
            boolean beamable = cr instanceof Chord && cr.type >= Duration.EIGHTH;
            int grp = cr.tuplet != null ? -2 : starts[i] / groupLen;
            boolean sameTuplet = cr.tuplet == curTuplet;
            if (!beamable || (!cur.isEmpty() && (grp != curGroup || !sameTuplet))) {
                if (cur.size() > 1) groups.add(cur);
                cur = new ArrayList<Integer>();
            }
            if (beamable) {
                if (cur.isEmpty()) {
                    curGroup = grp;
                    curTuplet = cr.tuplet;
                }
                cur.add(i);
            }
        }
        if (cur.size() > 1) groups.add(cur);
        return groups;
    }

    private void layoutBeam(StaffGen g, List<Integer> grp, ChordGeom[] byIdx, Voice voice, int v) {
        ChordGeom first = byIdx[grp.get(0)];
        ChordGeom last = byIdx[grp.get(grp.size() - 1)];
        boolean up;
        if (first.forcedDir) {
            up = first.up;
        } else {
            float sum = 0;
            int cnt = 0;
            int minL = Integer.MAX_VALUE, maxL = Integer.MIN_VALUE;
            for (int idx : grp) {
                for (int l : byIdx[idx].lines) {
                    sum += l;
                    cnt++;
                    minL = Math.min(minL, l);
                    maxL = Math.max(maxL, l);
                }
            }
            up = (maxL - 4) > (4 - minL);
        }
        for (int idx : grp) {
            ChordGeom cg = byIdx[idx];
            if (cg.up != up) {
                cg.up = up;
                computeNoteOffsets(cg);
            }
            defaultStem(cg);
            cg.beamed = true;
        }
        int maxLevel = 1;
        for (int idx : grp) maxLevel = Math.max(maxLevel, byIdx[idx].chord.type - Duration.EIGHTH + 1);
        float minStem = 2.75f + (maxLevel - 1) * 0.75f;
        float x0 = first.stemX, x1 = last.stemX;
        float y0 = up ? first.noteTopY - 3.5f : first.noteBottomY + 3.5f;
        float y1 = up ? last.noteTopY - 3.5f : last.noteBottomY + 3.5f;
        float dx = Math.max(0.01f, x1 - x0);
        float slope = (y1 - y0) / dx;
        float maxRise = Math.min(1.0f, dx * 0.15f);
        if (Math.abs(y1 - y0) > maxRise) slope = Math.signum(y1 - y0) * maxRise / dx;
        // flat beam if inner notes are more extreme than the ends
        boolean innerExtreme = false;
        for (int k = 1; k < grp.size() - 1; k++) {
            ChordGeom cg = byIdx[grp.get(k)];
            if (up ? cg.noteTopY < Math.min(first.noteTopY, last.noteTopY) : cg.noteBottomY > Math.max(first.noteBottomY, last.noteBottomY)) innerExtreme = true;
        }
        if (innerExtreme) slope = 0;
        float base = y0;
        // ensure minimum stem lengths
        for (int idx : grp) {
            ChordGeom cg = byIdx[idx];
            float by = base + slope * (cg.stemX - x0);
            if (up) {
                float limit = cg.noteTopY - minStem;
                if (by > limit) base -= (by - limit);
            } else {
                float limit = cg.noteBottomY + minStem;
                if (by < limit) base += (limit - by);
            }
        }
        for (int idx : grp) {
            ChordGeom cg = byIdx[idx];
            cg.stemEndY = base + slope * (cg.stemX - x0);
        }
        // beam polygons
        float t = Sym.beamThickness;
        float dist = Sym.beamThickness + Sym.beamSpacing;
        ChordRest owner = first.chord;
        for (int level = 1; level <= maxLevel; level++) {
            float off = (level - 1) * dist * (up ? 1 : -1);
            int k = 0;
            while (k < grp.size()) {
                ChordGeom a = byIdx[grp.get(k)];
                if (a.chord.type - Duration.EIGHTH + 1 < level) {
                    k++;
                    continue;
                }
                int e = k;
                while (e + 1 < grp.size() && byIdx[grp.get(e + 1)].chord.type - Duration.EIGHTH + 1 >= level) e++;
                float xa, xb;
                if (e > k) {
                    xa = a.stemX;
                    xb = byIdx[grp.get(e)].stemX;
                } else {
                    // hook
                    boolean right = k == 0;
                    if (right) {
                        xa = a.stemX;
                        xb = a.stemX + 1.1f;
                    } else {
                        xa = a.stemX - 1.1f;
                        xb = a.stemX;
                    }
                }
                float ya = base + slope * (xa - x0) + off;
                float yb = base + slope * (xb - x0) + off;
                float sa = xa - Sym.stemThickness / 2, sb = xb + Sym.stemThickness / 2;
                float[] pts;
                if (up) pts = new float[]{sa, ya, sb, yb, sb, yb + t, sa, ya + t};
                else pts = new float[]{sa, ya - t, sb, yb - t, sb, yb, sa, ya};
                DisplayList.Item it = poly(g.items, pts);
                it.owner = owner;
                it.voice = v;
                g.ext(Math.min(ya, yb) - t, Math.max(ya, yb) + t);
                k = e + 1;
            }
        }
    }

    private void emitChord(StaffGen g, ChordGeom cg, Pos pos, int sysIdx) {
        Chord c = cg.chord;
        boolean drums = isDrums(pos.staff) && clefAt(pos.staff, pos.measure) == Clef.PERC;
        float minX = cg.x, maxX = cg.x + cg.headW;
        for (int i = 0; i < c.notes.size(); i++) {
            minX = Math.min(minX, cg.x + cg.noteDx[i]);
            maxX = Math.max(maxX, cg.x + cg.noteDx[i] + cg.headW);
        }
        // ledger lines
        int minLine = Integer.MAX_VALUE, maxLine = Integer.MIN_VALUE;
        for (int l : cg.lines) {
            minLine = Math.min(minLine, l);
            maxLine = Math.max(maxLine, l);
        }
        float lx0 = minX - Sym.legerLineExtension, lx1 = maxX + Sym.legerLineExtension;
        for (int l = -2; l >= minLine; l -= 2) {
            DisplayList.Item it = line(g.items, lx0, l * 0.5f, lx1, l * 0.5f, Sym.legerLineThickness);
            it.owner = c;
        }
        for (int l = 10; l <= maxLine; l += 2) {
            DisplayList.Item it = line(g.items, lx0, l * 0.5f, lx1, l * 0.5f, Sym.legerLineThickness);
            it.owner = c;
        }
        // noteheads, accidentals, dots
        List<int[]> accPlaced = new ArrayList<int[]>();
        Integer[] order = new Integer[c.notes.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        final int[] lines = cg.lines;
        java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return lines[a] - lines[b]; // top first
            }
        });
        float dotX = maxX + 0.35f;
        java.util.Set<Integer> dotLines = new java.util.HashSet<Integer>();
        for (int k = 0; k < order.length; k++) {
            int i = order[k];
            Note n = c.notes.get(i);
            float nx = cg.x + cg.noteDx[i];
            float ny = cg.lines[i] * 0.5f;
            int head = cg.head;
            if (drums && drumX(n.pitch)) head = Sym.noteheadXBlack;
            DisplayList.Item hi = glyph(g.items, head, nx, ny);
            hi.owner = c;
            hi.note = n;
            hi.voice = pos.voice;
            Integer acc = accidental.get(n);
            if (acc != null) {
                int col = 0;
                boolean clash = true;
                while (clash) {
                    clash = false;
                    for (int[] pl : accPlaced) {
                        if (pl[1] == col && Math.abs(pl[0] - cg.lines[i]) < 6) {
                            clash = true;
                            col++;
                            break;
                        }
                    }
                }
                accPlaced.add(new int[]{cg.lines[i], col});
                float ax = minX - 0.2f - Sym.width(acc) - col * 1.15f;
                DisplayList.Item ai = glyph(g.items, acc, ax, ny);
                ai.owner = c;
                ai.note = n;
                ai.voice = pos.voice;
            }
            for (int d = 0; d < c.dots; d++) {
                int dl2 = cg.lines[i];
                if (dl2 % 2 == 0) dl2 += (cg.up || pos.voice % 2 == 0) ? -1 : 1;
                if (dotLines.contains(dl2 * 10 + d)) continue;
                dotLines.add(dl2 * 10 + d);
                DisplayList.Item di = glyph(g.items, Sym.augmentationDot, dotX + d * 0.5f, dl2 * 0.5f);
                di.owner = c;
                di.voice = pos.voice;
            }
            g.ext(ny - 1, ny + 1);
            // hit + tie record
            DisplayList.Hit h = new DisplayList.Hit();
            h.x0 = nx - 0.5f;
            h.x1 = nx + cg.headW + 0.5f;
            h.y0 = ny - 0.6f;
            h.y1 = ny + 0.6f;
            h.pos = pos;
            h.cr = c;
            h.note = n;
            h.noteIdx = i;
            g.hits.add(h);
            NoteRec r = new NoteRec();
            r.system = sysIdx;
            r.x = nx;
            r.y = ny;
            r.headW = cg.headW;
            r.staff = pos.staff;
            if (c.notes.size() == 1) r.tieUp = !cg.up;
            else r.tieUp = k < (order.length + 1) / 2;
            if (cg.forcedDir) r.tieUp = cg.up;
            pendingRecs.put(n, r);
        }
        // chord-level hit (stem area) for easier tapping
        DisplayList.Hit ch = new DisplayList.Hit();
        ch.x0 = minX - 0.6f;
        ch.x1 = maxX + 0.6f;
        ch.y0 = Math.min(cg.noteTopY, cg.hasStem ? cg.stemEndY : cg.noteTopY) - 0.3f;
        ch.y1 = Math.max(cg.noteBottomY, cg.hasStem ? cg.stemEndY : cg.noteBottomY) + 0.3f;
        ch.pos = pos;
        ch.cr = c;
        g.hits.add(ch);
        // stem
        if (cg.hasStem) {
            float yNote = cg.up ? cg.noteBottomY - Sym.STEM_UP_SE[Sym.noteheadBlack][1] : cg.noteTopY + 0.16f;
            DisplayList.Item si2 = line(g.items, cg.stemX, yNote, cg.stemX, cg.stemEndY, Sym.stemThickness);
            si2.owner = c;
            si2.voice = pos.voice;
            g.ext(Math.min(yNote, cg.stemEndY), Math.max(yNote, cg.stemEndY));
            if (!cg.beamed && c.type >= Duration.EIGHTH) {
                int fl = flagGlyph(c.type, cg.up);
                DisplayList.Item fi = glyph(g.items, fl, cg.stemX - Sym.stemThickness / 2, cg.stemEndY);
                fi.owner = c;
                fi.voice = pos.voice;
            }
        }
        // articulations
        float aboveY = Math.min(cg.noteTopY, cg.hasStem && cg.up ? cg.stemEndY : cg.noteTopY) - 1.0f;
        float belowY = Math.max(cg.noteBottomY, cg.hasStem && !cg.up ? cg.stemEndY : cg.noteBottomY) + 1.0f;
        float cx = cg.x + cg.headW / 2;
        float nearTop = cg.noteTopY - 1.0f, nearBottom = cg.noteBottomY + 1.0f;
        for (String a : c.articulations) {
            boolean always = Articulation.alwaysAbove(a);
            boolean above = always || !cg.up;
            String name = Articulation.smufl(a, above);
            int gl = name == null ? -1 : Sym.byName(name);
            if (gl < 0) continue;
            float gy;
            if (always) {
                gy = Math.min(-1.2f, aboveY - 0.2f);
                aboveY = gy - 1.4f;
            } else if (above) {
                gy = nearTop;
                // avoid staff lines inside the staff
                if (gy > -0.5f && gy < 4.5f && Math.abs(gy - Math.round(gy)) < 0.1f) gy -= 0.5f;
                nearTop = gy - 1.0f;
            } else {
                gy = nearBottom;
                if (gy > -0.5f && gy < 4.5f && Math.abs(gy - Math.round(gy)) < 0.1f) gy += 0.5f;
                gy += 0.6f;
                nearBottom = gy + 0.6f;
            }
            DisplayList.Item ai = glyph(g.items, gl, cx - Sym.width(gl) / 2, gy);
            ai.owner = c;
            g.ext(gTop(gl, gy), gBottom(gl, gy));
        }
    }

    private static int flagGlyph(int type, boolean up) {
        int k = Math.min(3, type - Duration.EIGHTH);
        return up ? Sym.flag8thUp + k : Sym.flag8thDown + k;
    }

    private void emitRest(StaffGen g, Rest r, Pos pos, DisplayList.MeasureInfo mi, boolean multiVoice, int v) {
        int gl;
        float x, y;
        float voiceOff = multiVoice ? (v % 2 == 0 ? -2f : 2f) : 0;
        if (r.measureRest) {
            gl = Sym.restWhole;
            if (r.measureTicks >= 3840) gl = Sym.restDoubleWhole;
            float end = mi.x + mi.width;
            x = (mi.contentX - 0.8f + end) / 2 - Sym.width(gl) / 2;
            y = 1f + voiceOff;
        } else {
            gl = restGlyph(r.type);
            x = dl.xAt(pos.measure, pos.tick);
            y = (r.type == Duration.WHOLE ? 1f : 2f) + voiceOff;
        }
        DisplayList.Item it = glyph(g.items, gl, x, y);
        it.owner = r;
        it.voice = v;
        g.ext(gTop(gl, y), gBottom(gl, y));
        if (multiVoice && v % 2 == 1 && !r.measureRest) it.color = DisplayList.C_INK;
        for (int d = 0; d < r.dots; d++) {
            glyph(g.items, Sym.augmentationDot, x + Sym.width(gl) + 0.3f + d * 0.5f, 1.5f).owner = r;
        }
        DisplayList.Hit h = new DisplayList.Hit();
        h.x0 = x - 0.5f;
        h.x1 = x + Sym.width(gl) + 0.5f;
        h.y0 = Math.min(gTop(gl, y), y - 1) - 0.3f;
        h.y1 = Math.max(gBottom(gl, y), y + 1) + 0.3f;
        h.pos = pos;
        h.cr = r;
        g.hits.add(h);
        if (r.articulations.contains(Articulation.FERMATA)) {
            int fg = Sym.fermataAbove;
            glyph(g.items, fg, x + Sym.width(gl) / 2 - Sym.width(fg) / 2, -1.2f).owner = r;
            g.ext(-3, 4);
        }
    }

    private void emitTuplets(StaffGen g, Voice voice, int[] starts, ChordGeom[] byIdx, int m, int v) {
        int i = 0;
        while (i < voice.items.size()) {
            Tuplet t = voice.items.get(i).tuplet;
            if (t == null) {
                i++;
                continue;
            }
            int j = i;
            while (j + 1 < voice.items.size() && voice.items.get(j + 1).tuplet == t) j++;
            float x0 = dl.xAt(m, starts[i]);
            float x1 = dl.xAt(m, starts[j]) + HEAD_W;
            boolean allBeamed = true;
            int upVotes = 0;
            float top = 0, bottom = 4;
            for (int k = i; k <= j; k++) {
                ChordGeom cg = byIdx[k];
                if (cg == null) {
                    allBeamed = false;
                    continue;
                }
                if (!cg.beamed) allBeamed = false;
                if (cg.up) upVotes++;
                else upVotes--;
                top = Math.min(top, Math.min(cg.noteTopY, cg.hasStem ? cg.stemEndY : cg.noteTopY));
                bottom = Math.max(bottom, Math.max(cg.noteBottomY, cg.hasStem ? cg.stemEndY : cg.noteBottomY));
            }
            boolean above = upVotes >= 0;
            if (v % 2 == 1 && nonEmptyVoices(s.measures.get(m).staves.get(byIdxStaff(byIdx))) > 1) above = false;
            float y = above ? top - 1.3f : bottom + 1.3f;
            String num = String.valueOf(t.actual);
            float numW = 0;
            for (int k = 0; k < num.length(); k++) numW += Sym.width(Sym.tuplet0 + (num.charAt(k) - '0'));
            float cx = (x0 + x1) / 2 - numW / 2;
            float ny = y + 0.6f;
            for (int k = 0; k < num.length(); k++) {
                int gl = Sym.tuplet0 + (num.charAt(k) - '0');
                DisplayList.Item it = glyph(g.items, gl, cx, ny);
                it.owner = voice.items.get(i);
                cx += Sym.width(gl);
            }
            if (!allBeamed) {
                float hook = above ? 0.6f : -0.6f;
                float gap0 = (x0 + x1) / 2 - numW / 2 - 0.3f, gap1 = (x0 + x1) / 2 + numW / 2 + 0.3f;
                float w = Sym.tupletBracketThickness;
                line(g.items, x0 - 0.2f, y + hook, x0 - 0.2f, y, w);
                line(g.items, x0 - 0.2f, y, gap0, y, w);
                line(g.items, gap1, y, x1 + 0.2f, y, w);
                line(g.items, x1 + 0.2f, y, x1 + 0.2f, y + hook, w);
            }
            g.ext(y - 1, y + 1);
            i = j + 1;
        }
    }

    private int byIdxStaff(ChordGeom[] byIdx) {
        for (ChordGeom cg : byIdx) if (cg != null) return cg.staff;
        return 0;
    }

    // --------------------------------------------------------------------------- markings

    private void genMarkings(StaffGen g, int sysIdx, int st, int m0, int m1) {
        boolean hasDyn = false, hasLyrics = false, hasChords = false, hasText = false;
        for (int m = m0; m <= m1; m++) {
            for (Voice v : s.measures.get(m).staves.get(st).voices) {
                for (ChordRest cr : v.items) {
                    if (cr.dynamic != null || cr.hairpin != 0) hasDyn = true;
                    if (cr.lyric != null) hasLyrics = true;
                    if (cr.chordSymbol != null) hasChords = true;
                    if (cr.staffText != null) hasText = true;
                }
            }
        }
        int sysStart = globalTick(m0, 0), sysEnd = globalTick(m1, s.measures.get(m1).ticks());
        for (Spanners.Span sp : spans) {
            if (sp.kind != 0 && sp.staff == st && sp.startTick < sysEnd && sp.endTick > sysStart) hasDyn = true;
        }
        float dynY = Math.max(6.2f, g.bottom + 2.2f);
        float lyrY = hasDyn ? dynY + 3.0f : Math.max(LYRIC_BASELINE, g.bottom + LYRIC_MIN_TOP_DISTANCE + LYRIC_ASCENT);
        float chordY = Math.min(-2.0f, g.top - 1.0f);
        float textY = hasChords ? chordY - 2.8f : chordY;
        for (Spanners.Span sp : spans) {
            if (sp.kind != 0 && sp.staff == st && sp.startTick < sysEnd && sp.endTick > sysStart) hairpin(g, sp, m0, m1, sysStart, sysEnd, dynY);
        }
        for (int m = m0; m <= m1; m++) {
            for (int v = 0; v < StaffMeasure.VOICES; v++) {
                Voice voice = s.measures.get(m).staves.get(st).voices[v];
                int[] starts = startsOf(voice);
                for (int i = 0; i < voice.items.size(); i++) {
                    ChordRest cr = voice.items.get(i);
                    float x = dl.xAt(m, starts[i]);
                    if (cr instanceof Rest && ((Rest) cr).measureRest) x = dl.measures.get(m).contentX;
                    if (cr.dynamic != null) {
                        String name = Dynamics.smufl(cr.dynamic);
                        int gl = name == null ? -1 : Sym.byName(name);
                        if (gl >= 0) {
                            DisplayList.Item it = glyph(g.items, gl, x - 0.3f, dynY);
                            it.owner = cr;
                            g.ext(gTop(gl, dynY), gBottom(gl, dynY));
                            hitBox(g, it, x - 0.3f, dynY - 1.5f, x + Sym.width(gl), dynY + 0.8f, new Pos(m, st, v, starts[i]), cr);
                        }
                    }
                    if (cr.lyric != null && cr instanceof Chord) {
                        // MuseScore: centred on the notehead, or left-aligned when it carries a melisma;
                        // leading and trailing punctuation hangs outside (lyricsAlignVerseNumber). The
                        // hyphen marker and the melisma are drawn as dashes / an extender line later.
                        String shown = Syllables.text(cr.lyric);
                        float w = tm.width(shown, TextMetrics.FONT_TEXT, LYRIC_SIZE);
                        float lx = x + lyricExtent(cr)[0];
                        DisplayList.Item it = text(g.items, shown, TextMetrics.FONT_TEXT, LYRIC_SIZE, lx, lyrY, DisplayList.ALIGN_LEFT);
                        it.owner = cr;
                        g.ext(lyrY - 1.6f, lyrY + 0.6f);
                        DisplayList.Hit lh = new DisplayList.Hit();
                        lh.x0 = lx;
                        lh.x1 = lx + w;
                        lh.y0 = lyrY - 1.6f;
                        lh.y1 = lyrY + 0.6f;
                        lh.baseline = lyrY;
                        lh.pos = new Pos(m, st, v, starts[i]);
                        lh.cr = cr;
                        g.lyricHits.add(lh);
                    }
                    if (cr.chordSymbol != null) {
                        DisplayList.Item it = text(g.items, ChordSymbols.display(cr.chordSymbol), TextMetrics.FONT_TEXT, 2.2f, x, chordY, DisplayList.ALIGN_LEFT);
                        it.owner = cr;
                        g.ext(chordY - 2.0f, chordY);
                    }
                    if (cr.staffText != null) {
                        DisplayList.Item it = text(g.items, cr.staffText, TextMetrics.FONT_ITALIC, 2.0f, x, textY, DisplayList.ALIGN_LEFT);
                        it.owner = cr;
                        g.ext(textY - 1.8f, textY);
                    }
                }
            }
        }
        if (st == 0) {
            // tempo marks and rehearsal marks above the top staff
            float ty = Math.min(-2.5f, g.top - 1.4f);
            boolean any = false;
            for (int m = m0; m <= m1; m++) {
                Measure mm = s.measures.get(m);
                DisplayList.MeasureInfo mi = dl.measures.get(m);
                float x = m == m0 ? dl.measures.get(m).contentX - 1.0f : mi.x + 0.3f;
                if (mm.rehearsal != null) {
                    float w = tm.width(mm.rehearsal, TextMetrics.FONT_BOLD, 2.4f);
                    float rx = mi.x - 0.2f;
                    DisplayList.Item box = new DisplayList.Item();
                    box.kind = DisplayList.RECT_OUTLINE;
                    box.x = rx - 0.5f;
                    box.y = ty - 2.4f;
                    box.x2 = rx + w + 0.5f;
                    box.y2 = ty + 0.6f;
                    box.w = 0.15f;
                    g.items.add(box);
                    text(g.items, mm.rehearsal, TextMetrics.FONT_BOLD, 2.4f, rx, ty, DisplayList.ALIGN_LEFT);
                    x = rx + w + 1.6f;
                    any = true;
                }
                if (mm.tempo > 0) {
                    float tx = x;
                    if (mm.tempoText != null && !mm.tempoText.isEmpty()) {
                        text(g.items, mm.tempoText, TextMetrics.FONT_BOLD, 2.2f, tx, ty, DisplayList.ALIGN_LEFT);
                        tx += tm.width(mm.tempoText, TextMetrics.FONT_BOLD, 2.2f) + 0.8f;
                    }
                    DisplayList.Item ng = glyph(g.items, Sym.metNoteQuarterUp, tx, ty - 0.2f);
                    ng.w = 0.8f;
                    tx += Sym.width(Sym.metNoteQuarterUp) * 0.8f + 0.4f;
                    text(g.items, "= " + mm.tempo, TextMetrics.FONT_TEXT, 2.2f, tx, ty, DisplayList.ALIGN_LEFT);
                    any = true;
                }
            }
            if (any) g.ext(ty - 3.2f, 4);
            // measure number at the start of each system except the first
            if (m0 > 0) {
                DisplayList.MeasureInfo mi = dl.measures.get(m0);
                float ny = Math.min(-1.2f, g.top - 0.4f);
                if (any) ny = -1.4f;
                text(g.items, String.valueOf(s.measureNumber(m0)), TextMetrics.FONT_ITALIC, 1.6f, mi.x - 0.2f - (mi.contentX - mi.x) * 0 + 0.0f, ny, DisplayList.ALIGN_LEFT).color = DisplayList.C_MUTED;
            }
        }
        // measure hit regions (tap on empty space selects the measure)
        for (int m = m0; m <= m1; m++) {
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            DisplayList.Hit h = new DisplayList.Hit();
            h.x0 = mi.x;
            h.x1 = mi.x + mi.width;
            h.y0 = -0.5f;
            h.y1 = 4.5f;
            h.pos = new Pos(m, st, 0, -1);
            g.hits.add(0, h);
        }
    }

    private void hitBox(StaffGen g, DisplayList.Item it, float x0, float y0, float x1, float y1, Pos p, ChordRest cr) {
        DisplayList.Hit h = new DisplayList.Hit();
        h.x0 = x0;
        h.y0 = y0;
        h.x1 = x1;
        h.y1 = y1;
        h.pos = p;
        h.cr = cr;
        g.hits.add(h);
    }

    // --------------------------------------------------------------------------- system decorations

    private void systemDecorations(int sysIdx, DisplayList.SystemInfo si, int m0, int m1, float left) {
        List<DisplayList.Item> out = dl.items;
        float topY = si.staffY[0], botY = si.staffY[staffCount - 1] + 4;
        float bw = Sym.thinBarlineThickness;
        if (staffCount > 1) line(out, si.x0, topY, si.x0, botY, bw);
        // brackets and braces
        float bx = si.x0;
        boolean anyBrace = false;
        for (Part p : s.parts) if (p.staves.size() > 1) anyBrace = true;
        if (anyBrace) {
            for (Part p : s.parts) {
                if (p.staves.size() < 2) continue;
                int f = s.firstStaffOf(p);
                float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                brace(out, bx - 0.4f, y0, y1);
            }
            bx -= 1.6f;
        }
        if (s.parts.size() > 1) {
            float x = bx - 0.6f;
            line(out, x, topY - 0.5f, x, botY + 0.5f, Sym.bracketThickness);
            glyph(out, Sym.bracketTop, x - Sym.bracketThickness / 2, topY - 0.5f);
            glyph(out, Sym.bracketBottom, x - Sym.bracketThickness / 2, botY + 0.5f);
            bx -= 1.2f;
        }
        // instrument names
        boolean names = !(s.parts.size() == 1 && s.parts.get(0).staves.size() == 1);
        if (names) {
            for (Part p : s.parts) {
                String n = sysIdx == 0 ? p.name : p.shortName;
                if (n == null || n.isEmpty()) continue;
                int f = s.firstStaffOf(p);
                float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                text(out, n, TextMetrics.FONT_TEXT, 2.0f, bx - 0.6f, (y0 + y1) / 2 + 0.7f, DisplayList.ALIGN_RIGHT);
            }
        }
        // bar lines
        for (int m = m0; m <= m1; m++) {
            Measure mm = s.measures.get(m);
            DisplayList.MeasureInfo mi = dl.measures.get(m);
            float x = mi.x + mi.width;
            if (mm.startRepeat) {
                float sx = m == m0 ? mi.contentX - 1.1f - 1.3f + 0.2f : mi.x + (m == m0 ? 0 : preps.get(m).midHeader) + 0.1f;
                for (Part p : s.parts) {
                    int f = s.firstStaffOf(p);
                    float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                    float tw = Sym.thickBarlineThickness;
                    line(out, sx + tw / 2, y0, sx + tw / 2, y1, tw);
                    float tx = sx + tw + Sym.thinThickBarlineSeparation + bw / 2;
                    line(out, tx, y0, tx, y1, bw);
                    for (int k = 0; k < p.staves.size(); k++) {
                        float sy = si.staffY[f + k];
                        glyph(out, Sym.repeatDot, tx + Sym.repeatBarlineDotSeparation, sy + 1.5f);
                        glyph(out, Sym.repeatDot, tx + Sym.repeatBarlineDotSeparation, sy + 2.5f);
                    }
                }
            }
            for (Part p : s.parts) {
                int f = s.firstStaffOf(p);
                float y0 = si.staffY[f], y1 = si.staffY[f + p.staves.size() - 1] + 4;
                switch (mm.endBarline) {
                    case Measure.BAR_DOUBLE: {
                        line(out, x - bw / 2, y0, x - bw / 2, y1, bw);
                        float x2 = x - bw - Sym.barlineSeparation - bw / 2;
                        line(out, x2, y0, x2, y1, bw);
                        break;
                    }
                    case Measure.BAR_FINAL:
                    case Measure.BAR_END_REPEAT: {
                        float tw = Sym.thickBarlineThickness;
                        line(out, x - tw / 2, y0, x - tw / 2, y1, tw);
                        float x2 = x - tw - Sym.thinThickBarlineSeparation - bw / 2;
                        line(out, x2, y0, x2, y1, bw);
                        if (mm.endBarline == Measure.BAR_END_REPEAT) {
                            float dx = x2 - Sym.repeatBarlineDotSeparation - Sym.width(Sym.repeatDot);
                            for (int k = 0; k < p.staves.size(); k++) {
                                float sy = si.staffY[f + k];
                                glyph(out, Sym.repeatDot, dx, sy + 1.5f);
                                glyph(out, Sym.repeatDot, dx, sy + 2.5f);
                            }
                        }
                        break;
                    }
                    case Measure.BAR_DASHED: {
                        for (float yy = y0; yy < y1; yy += 1.0f) line(out, x - bw / 2, yy, x - bw / 2, Math.min(y1, yy + 0.55f), bw);
                        break;
                    }
                    default:
                        line(out, x - bw / 2, y0, x - bw / 2, y1, bw);
                }
            }
        }
    }

    private void brace(List<DisplayList.Item> out, float x, float y0, float y1) {
        // procedural brace: two mirrored S-curves with variable thickness
        int n = 24;
        float h = y1 - y0;
        float w = 1.1f;
        float[] pts = new float[(n + 1) * 4 + 4];
        int k = 0;
        // outer edge top->bottom, inner edge bottom->top
        float[] ox = new float[n + 1], oy = new float[n + 1], ix = new float[n + 1];
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float yy = y0 + t * h;
            // shape: x offset peaks at the middle tip
            float u = Math.abs(t - 0.5f) * 2; // 1 at ends, 0 at middle
            float curve = (float) (Math.pow(u, 0.6) * w) - w; // middle is leftmost
            float thick = 0.12f + 0.35f * (float) Math.sin(Math.PI * Math.min(1, u) );
            ox[i] = x + curve * 0.9f + (1 - u) * 0f;
            oy[i] = yy;
            ix[i] = ox[i] + thick;
        }
        for (int i = 0; i <= n; i++) {
            pts[k++] = ox[i];
            pts[k++] = oy[i];
        }
        for (int i = n; i >= 0; i--) {
            pts[k++] = ix[i];
            pts[k++] = oy[i];
        }
        float[] p2 = new float[k];
        System.arraycopy(pts, 0, p2, 0, k);
        poly(out, p2);
    }

    // --------------------------------------------------------------------------- hairpins and slurs

    /** MuseScore's hairpinHeight and hairpinContHeight, and its line width. */
    private static final float HAIRPIN_H = 1.2f, HAIRPIN_CONT_H = 0.6f, HAIRPIN_W = 0.12f;

    /** x of a tick counted from the start of the score, within the measures m0..m1 of this system. */
    private float xOfGlobal(int global, int m0, int m1) {
        int[] mt = Spanners.posOf(s, global);
        if (mt[0] < m0) return dl.measures.get(m0).contentX;
        if (mt[0] > m1) {
            DisplayList.MeasureInfo last = dl.measures.get(m1);
            return last.x + last.width;
        }
        if (mt[1] >= s.measures.get(mt[0]).ticks()) {
            DisplayList.MeasureInfo mi = dl.measures.get(mt[0]);
            return mi.x + mi.width;
        }
        return dl.xAt(mt[0], mt[1]);
    }

    /** A dynamic mark at a tick of a staff (any voice), or null. */
    private String dynamicAt(int st, int global) {
        int[] mt = Spanners.posOf(s, global);
        if (mt[1] >= s.measures.get(mt[0]).ticks()) return null;
        for (Voice v : s.measures.get(mt[0]).staves.get(st).voices) {
            int i = indexAt(v, mt[1]);
            if (i >= 0 && v.items.get(i).dynamic != null) return v.items.get(i).dynamic;
        }
        return null;
    }

    /**
     * A crescendo or diminuendo hairpin on the dynamics line, as MuseScore draws it: closed at its
     * narrow end, 1.2 sp open at the wide end, clear of dynamic marks at either end. Across a line
     * break, the part on the next system starts (or ends) half open.
     */
    private void hairpin(StaffGen g, Spanners.Span sp, int m0, int m1, int sysStart, int sysEnd, float dynY) {
        boolean before = sp.startTick < sysStart, after = sp.endTick > sysEnd;
        float x0, x1;
        if (before) {
            x0 = dl.measures.get(m0).contentX - 0.8f;
        } else {
            x0 = xOfGlobal(sp.startTick, m0, m1) - 0.2f;
            String d = dynamicAt(sp.staff, sp.startTick);
            if (d != null && Dynamics.smufl(d) != null) x0 += Sym.width(Sym.byName(Dynamics.smufl(d))) + 0.5f;
        }
        if (after) {
            DisplayList.MeasureInfo last = dl.measures.get(m1);
            x1 = last.x + last.width - 0.5f;
        } else {
            x1 = xOfGlobal(sp.endTick, m0, m1) - 0.7f;
            if (dynamicAt(sp.staff, sp.endTick) != null) x1 = xOfGlobal(sp.endTick, m0, m1) - 0.8f;
        }
        if (x1 - x0 < 1.5f) x1 = x0 + 1.5f;
        boolean cresc = sp.kind == ChordRest.CRESCENDO;
        float o0 = cresc ? (before ? HAIRPIN_CONT_H : 0) : HAIRPIN_H;
        float o1 = cresc ? HAIRPIN_H : (after ? HAIRPIN_CONT_H : 0);
        float y = dynY - 0.6f;
        line(g.items, x0, y - o0 / 2, x1, y - o1 / 2, HAIRPIN_W).owner = sp.start;
        line(g.items, x0, y + o0 / 2, x1, y + o1 / 2, HAIRPIN_W).owner = sp.start;
        g.ext(y - HAIRPIN_H, y + HAIRPIN_H);
    }

    /**
     * Slurs, drawn once every system is placed (like ties): from notehead to notehead on the side
     * away from the stems, reaching to a stem end when the last note's stem is on that side, and
     * arched over the notes in between. Across a line break, a part on each system.
     */
    private void drawSlurs() {
        for (Spanners.Span sp : spans) {
            if (sp.kind != 0 || sp.end == null) continue;
            Chord a = (Chord) sp.start, b = (Chord) sp.end;
            NoteRec at = noteRecs.get(a.top()), ab = noteRecs.get(a.bottom()), bt = noteRecs.get(b.top()), bb = noteRecs.get(b.bottom());
            if (at == null || ab == null || bt == null || bb == null) continue;
            boolean up = at.tieUp;
            float x1 = at.x + at.headW / 2, x2 = bt.x + bt.headW / 2;
            float y1 = up ? at.y - 0.9f : ab.y + 0.9f;
            boolean endStem = bt.tieUp != up;
            float y2 = up ? (endStem ? bt.y - 3.3f : bt.y - 0.9f) : (endStem ? bb.y + 3.3f : bb.y + 0.9f);
            // the notes in between (and their stems) the slur must clear
            List<float[]> between = new ArrayList<float[]>();
            int base = globalTick(sp.measure, 0);
            for (int m = sp.measure; m <= sp.endMeasure && m < s.measures.size(); m++) {
                Voice voice = s.measures.get(m).staves.get(sp.staff).voices[sp.voice];
                int[] starts = startsOf(voice);
                for (int i = 0; i < voice.items.size(); i++) {
                    int gt = base + starts[i];
                    if (gt <= sp.startTick || gt >= sp.endTick || !(voice.items.get(i) instanceof Chord)) continue;
                    Chord c = (Chord) voice.items.get(i);
                    NoteRec r = noteRecs.get(up ? c.top() : c.bottom());
                    if (r == null) continue;
                    float edge = up ? (r.tieUp ? r.y - 0.9f : r.y - 3.4f) : (r.tieUp ? r.y + 3.4f : r.y + 0.9f);
                    between.add(new float[]{r.system, r.x + r.headW / 2, edge});
                }
                base += s.measures.get(m).ticks();
            }
            if (at.system == bt.system) {
                slurCurve(x1, y1, x2, y2, up, between, at.system, a);
            } else {
                DisplayList.SystemInfo sa = dl.systems.get(at.system), sb = dl.systems.get(bt.system);
                slurCurve(x1, y1, sa.x1 - 0.3f, y1, up, between, at.system, a);
                float yb = up ? bt.y - 0.9f - 1.0f : bb.y + 0.9f + 1.0f;
                slurCurve(dl.measures.get(sb.firstMeasure).contentX - 1.0f, yb, x2, y2, up, between, bt.system, a);
            }
        }
    }

    private void slurCurve(float x1, float y1, float x2, float y2, boolean up, List<float[]> between, int system, ChordRest owner) {
        if (x2 - x1 < 1.0f) x2 = x1 + 1.0f;
        float len = x2 - x1;
        float h = Math.min(2.0f, 0.6f + 0.06f * len);
        for (float[] b : between) {
            if ((int) b[0] != system || b[1] <= x1 || b[1] >= x2) continue;
            float t = (b[1] - x1) / len;
            float lineY = y1 + (y2 - y1) * t;
            float need = up ? lineY - (b[2] - 0.5f) : (b[2] + 0.5f) - lineY;
            float bulge = 3 * t * (1 - t); // height of the curve at t for unit h (control points at 4/3 h)
            if (need > 0) h = Math.max(h, need / Math.max(0.35f, bulge));
        }
        h = Math.min(h, 5f) * (up ? -1 : 1);
        float th = 0.2f * (up ? -1 : 1);
        int n = 20;
        float[] pts = new float[(n + 1) * 4];
        int k = 0;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            pts[k++] = bez(x1, x1 + len * 0.25f, x2 - len * 0.25f, x2, t);
            pts[k++] = bez(y1, y1 + h * 1.33f, y2 + h * 1.33f, y2, t);
        }
        for (int i = n; i >= 0; i--) {
            float t = i / (float) n;
            pts[k++] = bez(x1, x1 + len * 0.25f, x2 - len * 0.25f, x2, t);
            pts[k++] = bez(y1, y1 + (h - th) * 1.33f, y2 + (h - th) * 1.33f, y2, t);
        }
        DisplayList.Item it = poly(dl.items, pts);
        it.owner = owner;
    }

    // --------------------------------------------------------------------------- lyrics

    /** The next chord or rest in a voice, continuing into the next bar. */
    private ChordRest nextInVoice(int m, int st, int v, int idx) {
        Voice voice = s.measures.get(m).staves.get(st).voices[v];
        if (idx + 1 < voice.items.size()) return voice.items.get(idx + 1);
        if (m + 1 >= s.measures.size()) return null;
        Voice nv = s.measures.get(m + 1).staves.get(st).voices[v];
        return nv.items.isEmpty() ? null : nv.items.get(0);
    }

    /** True if the syllable carries an extender line (a hyphenated one continues with dashes instead). */
    private static boolean hasMelisma(ChordRest cr) {
        return cr.melisma > 0 && !Syllables.hyphenated(cr.lyric);
    }

    /**
     * Where a chord's lyric sits relative to the left edge of its notehead: {left, right}. As in
     * MuseScore, it is centred on the notehead, or left-aligned with it when it carries a melisma
     * (lyricsMelismaAlign); only the letters count, leading and trailing punctuation hangs outside.
     */
    private float[] lyricExtent(ChordRest cr) {
        String t = Syllables.text(cr.lyric);
        float w = tm.width(t, TextMetrics.FONT_TEXT, LYRIC_SIZE);
        int first = -1, last = -1;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetter(t.charAt(i))) {
                if (first < 0) first = i;
                last = i;
            }
        }
        float lead = first > 0 ? tm.width(t.substring(0, first), TextMetrics.FONT_TEXT, LYRIC_SIZE) : 0;
        float trail = first >= 0 && last < t.length() - 1 ? tm.width(t.substring(last + 1), TextMetrics.FONT_TEXT, LYRIC_SIZE) : 0;
        float left = hasMelisma(cr) ? -lead : HEAD_W / 2 - w / 2 - (lead - trail) / 2;
        return new float[]{left, left + w};
    }

    /**
     * Dashes between a hyphenated syllable and the next one, as MuseScore lays them out
     * (LyricsLayout::layoutDashes): the line runs from just after the first syllable to just before
     * the next, and its dashes are centred in it. Across a line break the line ends at the end of
     * the system and starts again before the next syllable.
     */
    private void drawLyricLines() {
        Map<ChordRest, DisplayList.Hit> at = new IdentityHashMap<ChordRest, DisplayList.Hit>();
        for (DisplayList.Hit h : dl.lyricHits) at.put(h.cr, h);
        for (DisplayList.Hit h : dl.lyricHits) {
            if (hasMelisma(h.cr)) {
                melisma(h);
                continue;
            }
            if (!Syllables.hyphenated(h.cr.lyric)) continue;
            Voice voice = s.measures.get(h.pos.measure).staves.get(h.pos.staff).voices[h.pos.voice];
            ChordRest next = Syllables.next(s, h.pos.measure, h.pos.staff, h.pos.voice, indexAt(voice, h.pos.tick));
            DisplayList.Hit n = next == null ? null : at.get(next);
            if (n == null) continue;
            int sa = dl.measures.get(h.pos.measure).system, sb = dl.measures.get(n.pos.measure).system;
            if (sa == sb) {
                dashes(h.x1 + DASH_PAD, n.x0 - DASH_PAD, h.baseline - DASH_RAISE, false);
            } else {
                DisplayList.SystemInfo a = dl.systems.get(sa), b = dl.systems.get(sb);
                dashes(h.x1 + DASH_PAD, a.x1 - 0.5f, h.baseline - DASH_RAISE, false);
                dashes(dl.measures.get(b.firstMeasure).contentX - 0.5f, n.x0 - DASH_PAD, n.baseline - DASH_RAISE, true);
            }
        }
    }

    /**
     * The extender line of a melisma, as MuseScore lays it out (layoutMelismaLine): on the baseline,
     * from just after the syllable to the right edge of the last notehead it is held over. A line
     * shorter than the minimum length is left out, except for a one-note melisma, which is drawn at
     * the minimum length (MuseScore shows it that way while the line is being typed). Across a line
     * break it runs to the end of the system and continues on the next one.
     */
    private void melisma(DisplayList.Hit h) {
        int st = h.pos.staff, v = h.pos.voice;
        Voice voice = s.measures.get(h.pos.measure).staves.get(st).voices[v];
        int[] end = Syllables.melismaEnd(s, h.pos.measure, st, v, indexAt(voice, h.pos.tick));
        int endTick = startsOf(s.measures.get(end[0]).staves.get(st).voices[v])[end[1]];
        float endX = dl.xAt(end[0], endTick) + HEAD_W;
        boolean oneNote = h.cr.melisma == 1;
        int sa = dl.measures.get(h.pos.measure).system, sb = dl.measures.get(end[0]).system;
        float y = h.baseline - MELISMA_THICKNESS / 2;
        if (sa == sb) {
            extender(h.x1 + MELISMA_PAD, endX, y, oneNote);
        } else {
            DisplayList.SystemInfo a = dl.systems.get(sa), b = dl.systems.get(sb);
            extender(h.x1 + MELISMA_PAD, a.x1 - 0.5f, y, false);
            float yb = b.staffY[st] + (y - a.staffY[st]);
            extender(dl.measures.get(b.firstMeasure).contentX - 0.3f, endX, yb, false);
        }
    }

    private void extender(float x0, float x1, float y, boolean force) {
        if (x1 - x0 < MELISMA_MIN - 0.05f) {
            if (!force) return;
            x1 = x0 + MELISMA_MIN;
        }
        line(dl.items, x0, y, x1, y, MELISMA_THICKNESS);
    }

    private void dashes(float x0, float x1, float y, boolean firstOnSystem) {
        float len = x1 - x0;
        // first and last gaps are half a gap (lyricsDashFirstAndLastGapAreHalf); always at least one dash (lyricsDashForce)
        int count = len > DASH_MAX_DISTANCE ? (int) Math.ceil(len / DASH_MAX_DISTANCE) : 1;
        if (len < DASH_MIN) {
            float diff = DASH_MIN - len;
            if (firstOnSystem) {
                x0 -= diff;
            } else {
                x0 -= diff / 2;
                x1 += diff / 2;
            }
            len = x1 - x0;
        }
        float dw = Math.min(len, DASH_MAX);
        float dist = len / count;
        float xd = 0;
        for (int i = 0; i < count; i++) {
            xd += i == 0 ? dist / 2 : dist;
            line(dl.items, x0 + xd - dw / 2, y, x0 + xd + dw / 2, y, DASH_THICKNESS);
        }
    }

    // --------------------------------------------------------------------------- ties

    private void drawTies() {
        for (int m = 0; m < s.measures.size(); m++) {
            for (int st = 0; st < staffCount; st++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = s.measures.get(m).staves.get(st).voices[v];
                    int[] starts = startsOf(voice);
                    for (int i = 0; i < voice.items.size(); i++) {
                        ChordRest cr = voice.items.get(i);
                        if (!(cr instanceof Chord)) continue;
                        Chord nextChord = nextChordAfter(m, st, v, starts[i] + cr.ticks());
                        for (Note n : ((Chord) cr).notes) {
                            if (!n.tieForward) continue;
                            NoteRec a = noteRecs.get(n);
                            if (a == null) continue;
                            Note target = null;
                            if (nextChord != null) for (Note t : nextChord.notes) if (t.pitch == n.pitch) target = t;
                            NoteRec b = target != null ? noteRecs.get(target) : null;
                            float x1 = a.x + a.headW + 0.15f;
                            float y = a.y + (a.tieUp ? -0.45f : 0.45f);
                            if (b != null && b.system == a.system) {
                                tie(x1, b.x - 0.15f, y, a.tieUp, cr);
                            } else {
                                DisplayList.SystemInfo si = dl.systems.get(a.system);
                                tie(x1, si.x1 - 0.3f, y, a.tieUp, cr);
                                if (b != null) {
                                    DisplayList.SystemInfo sb = dl.systems.get(b.system);
                                    DisplayList.MeasureInfo mi = dl.measures.get(sb.firstMeasure);
                                    tie(mi.contentX - 1.4f, b.x - 0.15f, b.y + (a.tieUp ? -0.45f : 0.45f), a.tieUp, cr);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Chord nextChordAfter(int m, int st, int v, int tick) {
        int mm = m, t = tick;
        while (mm < s.measures.size() && t >= s.measures.get(mm).ticks()) {
            t -= s.measures.get(mm).ticks();
            mm++;
        }
        if (mm >= s.measures.size()) return null;
        Voice voice = s.measures.get(mm).staves.get(st).voices[v];
        int idx = indexAt(voice, t);
        if (idx < 0) return null;
        ChordRest cr = voice.items.get(idx);
        return cr instanceof Chord ? (Chord) cr : null;
    }

    private void tie(float x1, float x2, float y, boolean up, ChordRest owner) {
        if (x2 - x1 < 0.6f) x2 = x1 + 0.6f;
        float len = x2 - x1;
        float h = Math.min(1.3f, 0.3f + 0.08f * len) * (up ? -1 : 1);
        float th = Sym.tieMidpointThickness * (up ? -1 : 1);
        int n = 16;
        float[] pts = new float[(n + 1) * 4];
        int k = 0;
        for (int i = 0; i <= n; i++) {
            float t = i / (float) n;
            float bx = bez(x1, x1 + len * 0.25f, x2 - len * 0.25f, x2, t);
            float by = bez(y, y + h * 1.33f, y + h * 1.33f, y, t);
            pts[k++] = bx;
            pts[k++] = by;
        }
        for (int i = n; i >= 0; i--) {
            float t = i / (float) n;
            float bx = bez(x1, x1 + len * 0.25f, x2 - len * 0.25f, x2, t);
            float by = bez(y, y + (h - th) * 1.33f, y + (h - th) * 1.33f, y, t);
            pts[k++] = bx;
            pts[k++] = by;
        }
        DisplayList.Item it = poly(dl.items, pts);
        it.owner = owner;
    }

    private static float bez(float p0, float p1, float p2, float p3, float t) {
        float u = 1 - t;
        return u * u * u * p0 + 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t * p3;
    }
}
