package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Editing operations on the score model. All operations keep every used voice exactly as long
 * as its measure, mirroring the rules MuseScore applies when entering and deleting notes.
 */
public final class ScoreEdit {
    private ScoreEdit() {}

    // ------------------------------------------------------------------ voice preparation

    /** Replaces a measure rest by beat-aligned regular rests so that it can be edited. */
    public static void expandMeasureRest(Voice v, int measureTicks) {
        if (v.items.size() == 1 && v.items.get(0) instanceof Rest && ((Rest) v.items.get(0)).measureRest) {
            Rest mr = (Rest) v.items.get(0);
            v.items.clear();
            boolean first = true;
            for (int[] d : Duration.decomposeRests(0, measureTicks)) {
                Rest r = new Rest();
                r.type = d[0];
                r.dots = d[1];
                if (first) {
                    r.dynamic = mr.dynamic;
                    r.chordSymbol = mr.chordSymbol;
                    r.staffText = mr.staffText;
                    first = false;
                }
                v.items.add(r);
            }
        }
    }

    /** Makes a voice editable: expands measure rests and fills empty voices 2-4 with rests. */
    public static Voice prepareVoice(Measure m, int staff, int voice) {
        Voice v = m.staves.get(staff).voices[voice];
        if (v.isEmpty()) {
            for (int[] d : Duration.decomposeRests(0, m.ticks())) {
                Rest r = new Rest();
                r.type = d[0];
                r.dots = d[1];
                v.items.add(r);
            }
        } else {
            expandMeasureRest(v, m.ticks());
        }
        return v;
    }

    /** Collapses voices that contain only rests: voice 1 becomes a measure rest, voices 2-4 are removed. */
    public static void normalize(Measure m, int staff) {
        StaffMeasure sm = m.staves.get(staff);
        for (int vi = 0; vi < StaffMeasure.VOICES; vi++) {
            Voice v = sm.voices[vi];
            if (v.isEmpty() || !v.onlyRests()) continue;
            boolean hasTuplet = false;
            for (ChordRest cr : v.items) if (cr.tuplet != null) hasTuplet = true;
            if (vi == 0) {
                if (v.items.size() == 1 && ((Rest) v.items.get(0)).measureRest) continue;
                if (hasTuplet && v.items.size() > 1) continue;
                v.items.clear();
                v.items.add(Rest.measureRest(m.ticks()));
            } else {
                v.items.clear();
            }
        }
    }

    // ------------------------------------------------------------------ core range replacement

    private static int tupletStart(Voice v, Tuplet t) {
        int[] st = v.starts();
        for (int i = 0; i < v.items.size(); i++) if (v.items.get(i).tuplet == t) return st[i];
        return -1;
    }

    private static int tupletEnd(Voice v, Tuplet t) {
        int[] st = v.starts();
        int end = -1;
        for (int i = 0; i < v.items.size(); i++) if (v.items.get(i).tuplet == t) end = st[i + 1];
        return end;
    }

    /** Replaces all members of a tuplet by plain rests spanning the same time. */
    private static void dissolveTuplet(Voice v, Tuplet t) {
        int[] st = v.starts();
        int first = -1, last = -1;
        for (int i = 0; i < v.items.size(); i++) {
            if (v.items.get(i).tuplet == t) {
                if (first < 0) first = i;
                last = i;
            }
        }
        if (first < 0) return;
        int s = st[first], e = st[last + 1];
        for (int i = last; i >= first; i--) v.items.remove(i);
        List<ChordRest> rests = restPieces(s, e - s, null);
        v.items.addAll(first, rests);
    }

    private static List<ChordRest> restPieces(int start, int ticks, Tuplet ctx) {
        List<ChordRest> out = new ArrayList<ChordRest>();
        if (ticks <= 0) return out;
        List<int[]> ds;
        if (ctx != null) ds = Duration.decompose(ticks * ctx.actual / ctx.normal);
        else ds = Duration.decomposeRests(start, ticks);
        for (int[] d : ds) {
            Rest r = new Rest();
            r.type = d[0];
            r.dots = d[1];
            r.tuplet = ctx;
            out.add(r);
        }
        return out;
    }

    /** Splits a chord/rest into pieces of the given length (ties between chord pieces). */
    private static List<ChordRest> headPieces(ChordRest cr, int ticks, Tuplet ctx) {
        List<ChordRest> out = new ArrayList<ChordRest>();
        if (ticks <= 0) return out;
        List<int[]> ds = ctx != null ? Duration.decompose(ticks * ctx.actual / ctx.normal) : Duration.decompose(ticks);
        for (int i = 0; i < ds.size(); i++) {
            ChordRest p = cr.copy();
            if (cr instanceof Rest) ((Rest) p).measureRest = false;
            p.type = ds.get(i)[0];
            p.dots = ds.get(i)[1];
            p.tuplet = ctx;
            if (i > 0) p.clearMarkings();
            if (p instanceof Chord) {
                boolean tie = i < ds.size() - 1;
                for (Note n : ((Chord) p).notes) n.tieForward = tie;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Replaces the time range [from, from+len) of a voice by the given elements, truncating
     * elements that overlap the range. With ctx != null the range lies inside that tuplet.
     */
    public static void replaceRange(Voice v, int from, int len, List<ChordRest> newEls, Tuplet ctx) {
        int to = from + len;
        if (ctx == null) {
            boolean changed = true;
            while (changed) {
                changed = false;
                int[] st = v.starts();
                for (int i = 0; i < v.items.size(); i++) {
                    ChordRest cr = v.items.get(i);
                    if (cr.tuplet != null && st[i + 1] > from && st[i] < to) {
                        int ts = tupletStart(v, cr.tuplet), te = tupletEnd(v, cr.tuplet);
                        // a range covering the whole tuplet simply removes it below
                        if (ts >= from && te <= to) continue;
                        dissolveTuplet(v, cr.tuplet);
                        changed = true;
                        break;
                    }
                }
            }
        }
        int[] st = v.starts();
        List<ChordRest> out = new ArrayList<ChordRest>();
        boolean inserted = false;
        for (int i = 0; i < v.items.size(); i++) {
            ChordRest cr = v.items.get(i);
            int s = st[i], e = st[i + 1];
            if (e <= from || s >= to) {
                if (s >= to && !inserted) {
                    out.addAll(newEls);
                    inserted = true;
                }
                out.add(cr);
                continue;
            }
            Tuplet pieceCtx = (ctx != null && cr.tuplet == ctx) ? ctx : null;
            if (s < from) out.addAll(headPieces(cr, from - s, pieceCtx));
            if (!inserted) {
                out.addAll(newEls);
                inserted = true;
            }
            if (e > to) out.addAll(restPieces(to, e - to, pieceCtx));
        }
        if (!inserted) out.addAll(newEls);
        v.items.clear();
        v.items.addAll(out);
    }

    // ------------------------------------------------------------------ note input

    /** Returns a normalized position (moves to the next measure when at a bar end). */
    public static Pos normalizePos(Score s, Pos p) {
        Pos q = p.copy();
        while (q.measure < s.measures.size() && q.tick >= s.measures.get(q.measure).ticks()) {
            q.tick -= s.measures.get(q.measure).ticks();
            q.measure++;
        }
        return q;
    }

    /**
     * Enters a chord (or a rest when template is null) at a position, overwriting what is there.
     * Long values are split across bar lines with ties. Returns the position after the new element.
     */
    public static Pos enter(Score s, Pos p, Chord template, int type, int dots, boolean triplet) {
        return enter(s, p, template, type, dots, triplet, -1);
    }

    /**
     * Enters a chord (or a rest when template is null). In triplet mode a new triplet is built on
     * tripletUnit, the value that was selected when triplets were turned on (as in Dorico), and the
     * entered value goes inside it: a quarter takes two slots of a triplet of eighths, a dotted 16th
     * three quarters of one. Without a unit the triplet is built on the entered value (a dotted value:
     * on the next larger one).
     */
    public static Pos enter(Score s, Pos p, Chord template, int type, int dots, boolean triplet, int tripletUnit) {
        return enter(s, p, template, type, dots, triplet ? 3 : 0, tripletUnit);
    }

    /** As above for any tuplet: tuplet is the number of notes (3 = triplet, 5 = quintuplet), 0 for none. */
    public static Pos enter(Score s, Pos p, Chord template, int type, int dots, int tuplet, int tripletUnit) {
        if (p.measure >= s.measures.size()) s.appendMeasures(p.measure - s.measures.size() + 1);
        Measure m = s.measures.get(p.measure);
        Voice v = prepareVoice(m, p.staff, p.voice);
        int idx = v.indexCovering(p.tick);
        if (idx < 0) return p;
        ChordRest at = v.items.get(idx);
        int atStart = v.starts()[idx];
        if (at.tuplet == null && !expressible(p.tick - atStart)) {
            // A position no plain value reaches, as one inside another voice's triplet: build
            // that tuplet here (as rests) and enter into it, as the position was tapped there.
            Tuplet mirror = mirrorTuplet(m, p, v);
            if (mirror != null) return enterInTuplet(s, p, v, mirror, template, type, dots);
            int t = p.tick;
            while (t > atStart && !expressible(t - atStart)) t--;
            p = new Pos(p.measure, p.staff, p.voice, t);
        }

        if (at.tuplet != null && !(p.tick == tupletStart(v, at.tuplet) && Duration.ticks(type, dots) >= at.tuplet.spanTicks())) {
            return enterInTuplet(s, p, v, at.tuplet, template, type, dots);
        }

        int actual = tuplet, normal = Tuplet.normalFor(Math.max(2, tuplet));
        if (tuplet > 1 && type < Duration.MAX_TYPE) {
            // A triplet on another unit than the entered value (the unit chosen when triplets were
            // turned on; for a dotted value the next larger one: a dotted 16th triplet is three
            // quarters of a triplet eighth). Lay out that triplet as rests, then enter the value at
            // its start.
            int unit = tripletUnit >= 0 ? tripletUnit : (dots > 0 && type > Duration.WHOLE ? type - 1 : type);
            if ((unit != type || dots > 0) && Tuplet.exact(actual, unit) && Duration.ticks(type, dots) * normal % actual == 0) {
                Tuplet t = new Tuplet(actual, normal, unit);
                int span = t.spanTicks();
                if (p.tick + span <= m.ticks() && t.scale(Duration.ticks(type, dots)) <= span) {
                    List<ChordRest> rests = new ArrayList<ChordRest>();
                    for (int i = 0; i < actual; i++) {
                        Rest r = new Rest();
                        r.type = unit;
                        r.tuplet = t;
                        rests.add(r);
                    }
                    replaceRange(v, p.tick, span, rests, null);
                    return enterInTuplet(s, p, v, t, template, type, dots);
                }
            }
            Tuplet t = new Tuplet(actual, normal, type);
            int span = t.spanTicks();
            if (dots == 0 && Tuplet.exact(actual, type) && p.tick + span <= m.ticks()) {
                List<ChordRest> els = new ArrayList<ChordRest>();
                for (int i = 0; i < actual; i++) {
                    ChordRest cr = (i == 0 && template != null) ? template.copy() : new Rest();
                    cr.type = type;
                    cr.dots = 0;
                    cr.tuplet = t;
                    if (cr instanceof Chord) for (Note n : ((Chord) cr).notes) n.tieForward = false;
                    els.add(cr);
                }
                replaceRange(v, p.tick, span, els, null);
                return advance(s, new Pos(p.measure, p.staff, p.voice, p.tick + t.scale(Duration.baseTicks(type))));
            }
        }

        int remaining = Duration.ticks(type, dots);
        Pos cur = p.copy();
        boolean firstPiece = true;
        List<Chord> pieces = new ArrayList<Chord>();
        while (remaining > 0) {
            if (cur.measure >= s.measures.size()) s.appendMeasures(1);
            Measure mm = s.measures.get(cur.measure);
            Voice vv = prepareVoice(mm, cur.staff, cur.voice);
            int room = mm.ticks() - cur.tick;
            int part = Math.min(remaining, room);
            int[] ex = Duration.exact(part);
            List<int[]> ds = new ArrayList<int[]>();
            if (ex != null) ds.add(ex);
            else ds.addAll(Duration.decompose(part));
            List<ChordRest> els = buildPieces(template, ds, null, !firstPiece);
            for (ChordRest cr : els) if (cr instanceof Chord) pieces.add((Chord) cr);
            replaceRange(vv, cur.tick, part, els, null);
            remaining -= part;
            cur.tick += part;
            firstPiece = false;
            if (cur.tick >= mm.ticks()) {
                cur.measure++;
                cur.tick = 0;
            }
        }
        // tie all generated pieces together; the last one keeps the template's ties
        for (int i = 0; i < pieces.size(); i++) {
            Chord c = pieces.get(i);
            for (int n = 0; n < c.notes.size(); n++) {
                boolean tie = i < pieces.size() - 1 || template.notes.get(n).tieForward;
                c.notes.get(n).tieForward = tie;
            }
        }
        return advance(s, cur);
    }

    /** True if a gap of this many ticks can be filled with plain (non-tuplet) values. */
    static boolean expressible(int ticks) {
        int sum = 0;
        for (int[] d : Duration.decompose(ticks)) sum += Duration.ticks(d[0], d[1]);
        return sum == ticks;
    }

    /**
     * For a position inside a tuplet of another voice of the staff: lays out the same tuplet as
     * rests in voice v, where v has only plain rests, and returns it; otherwise null.
     */
    private static Tuplet mirrorTuplet(Measure m, Pos p, Voice v) {
        StaffMeasure sm = m.staves.get(p.staff);
        for (int ov = 0; ov < StaffMeasure.VOICES; ov++) {
            Voice other = sm.voices[ov];
            if (ov == p.voice || other.isEmpty()) continue;
            int oi = other.indexCovering(p.tick);
            if (oi < 0 || other.items.get(oi).tuplet == null) continue;
            Tuplet src = other.items.get(oi).tuplet;
            int gs = tupletStart(other, src), ge = tupletEnd(other, src);
            Tuplet t = new Tuplet(src.actual, src.normal, src.baseType);
            if (ge - gs != t.spanTicks() || (p.tick - gs) * t.actual % t.normal != 0) continue;
            int[] st = v.starts();
            boolean plainRests = true;
            for (int i = 0; i < v.items.size(); i++) {
                if (st[i + 1] <= gs || st[i] >= ge) continue;
                ChordRest cr = v.items.get(i);
                if (!(cr instanceof Rest) || cr.tuplet != null || !expressible(gs - st[i]) || !expressible(st[i + 1] - Math.max(gs, Math.min(ge, st[i + 1])))) plainRests = false;
            }
            if (!plainRests) continue;
            List<ChordRest> rests = new ArrayList<ChordRest>();
            for (int i = 0; i < t.actual; i++) {
                Rest r = new Rest();
                r.type = t.baseType;
                r.tuplet = t;
                rests.add(r);
            }
            replaceRange(v, gs, t.spanTicks(), rests, null);
            return t;
        }
        return null;
    }

    /** Puts a value inside an existing tuplet at p, scaled by its ratio (and cut at the tuplet's end). */
    private static Pos enterInTuplet(Score s, Pos p, Voice v, Tuplet t, Chord template, int type, int dots) {
        int te = tupletEnd(v, t);
        int d = t.scale(Duration.ticks(type, dots));
        if (d > te - p.tick) d = te - p.tick;
        // keep a value that exists as one note (a dotted one, say) in one piece
        int[] ex = Duration.exact(d * t.actual / t.normal);
        List<int[]> ds = new ArrayList<int[]>();
        if (ex != null) ds.add(ex);
        else ds.addAll(Duration.decompose(d * t.actual / t.normal));
        List<ChordRest> els = buildPieces(template, ds, t, false);
        replaceRange(v, p.tick, d, els, t);
        return advance(s, new Pos(p.measure, p.staff, p.voice, p.tick + d));
    }

    private static List<ChordRest> buildPieces(Chord template, List<int[]> ds, Tuplet t, boolean continuation) {
        List<ChordRest> els = new ArrayList<ChordRest>();
        for (int i = 0; i < ds.size(); i++) {
            ChordRest cr = template != null ? template.copy() : new Rest();
            cr.type = ds.get(i)[0];
            cr.dots = ds.get(i)[1];
            cr.tuplet = t;
            if (i > 0 || continuation) cr.clearMarkings();
            if (cr instanceof Chord) {
                boolean tie = i < ds.size() - 1;
                for (Note n : ((Chord) cr).notes) n.tieForward = tie;
            }
            els.add(cr);
        }
        return els;
    }

    /** Ensures there is a measure for the position (note input continues past the last bar). */
    private static Pos advance(Score s, Pos p) {
        Pos q = normalizePos(s, p);
        if (q.measure >= s.measures.size()) s.appendMeasures(1);
        return q;
    }

    /** Adds a pitch to the chord at the position (turning a rest into a chord). */
    public static Note addToChord(Score s, Pos p, int pitch, int tpc) {
        ChordRest cr = elementAt(s, p);
        if (cr == null) return null;
        Note n = new Note(pitch, tpc);
        if (cr instanceof Chord) {
            for (Note o : ((Chord) cr).notes) if (o.pitch == pitch) return o;
            ((Chord) cr).addNote(n);
            return n;
        }
        Chord c = new Chord();
        c.type = cr.type;
        c.dots = cr.dots;
        c.tuplet = cr.tuplet;
        c.dynamic = cr.dynamic;
        c.chordSymbol = cr.chordSymbol;
        c.staffText = cr.staffText;
        c.addNote(n);
        Voice v = s.measures.get(p.measure).staves.get(p.staff).voices[p.voice];
        int idx = v.items.indexOf(cr);
        if (cr instanceof Rest && ((Rest) cr).measureRest) {
            Measure m = s.measures.get(p.measure);
            int[] ex = Duration.exact(m.ticks());
            if (ex == null) {
                // irregular bar length: split the measure rest first
                expandMeasureRest(v, m.ticks());
                return addToChord(s, p, pitch, tpc);
            }
            c.type = ex[0];
            c.dots = ex[1];
        }
        v.items.set(idx, c);
        return n;
    }

    // ------------------------------------------------------------------ lookup

    public static ChordRest elementAt(Score s, Pos p) {
        if (p.measure < 0 || p.measure >= s.measures.size()) return null;
        Measure m = s.measures.get(p.measure);
        if (p.staff < 0 || p.staff >= m.staves.size()) return null;
        Voice v = m.staves.get(p.staff).voices[p.voice];
        int i = v.indexAt(p.tick);
        return i < 0 ? null : v.items.get(i);
    }

    public static ChordRest elementCovering(Score s, Pos p) {
        if (p.measure < 0 || p.measure >= s.measures.size()) return null;
        Voice v = s.measures.get(p.measure).staves.get(p.staff).voices[p.voice];
        int i = v.indexCovering(p.tick);
        return i < 0 ? null : v.items.get(i);
    }

    /** Position of the element at or before the tick in the same voice. */
    public static Pos snapToElement(Score s, Pos p) {
        Voice v = s.measures.get(p.measure).staves.get(p.staff).voices[p.voice];
        int[] st = v.starts();
        int best = 0;
        for (int i = 0; i < v.items.size(); i++) if (st[i] <= p.tick) best = st[i];
        return new Pos(p.measure, p.staff, p.voice, best);
    }

    /** Next element position in the same staff/voice, crossing measures; null at the end. */
    public static Pos next(Score s, Pos p) {
        ChordRest cr = elementAt(s, p);
        int t = p.tick + (cr != null ? cr.ticks() : 0);
        Pos q = new Pos(p.measure, p.staff, p.voice, t);
        q = normalizePos(s, q);
        while (q.measure < s.measures.size()) {
            Voice v = s.measures.get(q.measure).staves.get(q.staff).voices[q.voice];
            if (!v.isEmpty() && v.indexAt(q.tick) >= 0) return q;
            if (!v.isEmpty()) {
                Pos snapped = snapToElement(s, q);
                if (snapped.measure > p.measure || (snapped.measure == p.measure && snapped.tick > p.tick)) return snapped;
            }
            // an empty voice, or one that ends before the bar does: go on in the next bar
            q.measure++;
            q.tick = 0;
        }
        return null;
    }

    public static Pos prev(Score s, Pos p) {
        Voice v = s.measures.get(p.measure).staves.get(p.staff).voices[p.voice];
        int[] st = v.starts();
        for (int i = v.items.size() - 1; i >= 0; i--) {
            if (st[i] < p.tick) return new Pos(p.measure, p.staff, p.voice, st[i]);
        }
        for (int m = p.measure - 1; m >= 0; m--) {
            Voice pv = s.measures.get(m).staves.get(p.staff).voices[p.voice];
            if (pv.isEmpty()) continue;
            int[] ps = pv.starts();
            return new Pos(m, p.staff, p.voice, ps[pv.items.size() - 1]);
        }
        return null;
    }

    /** The chord following a chord in the same voice (for ties), or null. */
    public static Chord nextChord(Score s, Pos p) {
        Pos q = next(s, p);
        if (q == null) return null;
        ChordRest cr = elementAt(s, q);
        return cr instanceof Chord ? (Chord) cr : null;
    }

    public static Chord prevChordBefore(Score s, Pos p) {
        Pos q = prev(s, p);
        while (q != null) {
            ChordRest cr = elementAt(s, q);
            if (cr instanceof Chord) return (Chord) cr;
            q = prev(s, q);
        }
        return null;
    }

    // ------------------------------------------------------------------ modifications

    /** Deletes a note (or the whole chord, turning it into a rest). */
    public static void deleteNote(Score s, Pos p, int noteIdx) {
        ChordRest cr = elementAt(s, p);
        if (!(cr instanceof Chord)) {
            if (cr instanceof Rest && p.voice > 0) {
                // removing a rest in voices 2-4 is allowed when the voice becomes empty
                normalize(s.measures.get(p.measure), p.staff);
            }
            return;
        }
        Chord c = (Chord) cr;
        if (noteIdx >= 0 && c.notes.size() > 1 && noteIdx < c.notes.size()) {
            c.notes.remove(noteIdx);
            return;
        }
        deleteChordRest(s, p);
    }

    public static void deleteChordRest(Score s, Pos p) {
        ChordRest cr = elementAt(s, p);
        if (cr == null || cr.isRest()) return;
        Rest r = new Rest();
        r.type = cr.type;
        r.dots = cr.dots;
        r.tuplet = cr.tuplet;
        r.dynamic = cr.dynamic;
        r.chordSymbol = cr.chordSymbol;
        r.staffText = cr.staffText;
        Voice v = s.measures.get(p.measure).staves.get(p.staff).voices[p.voice];
        v.items.set(v.items.indexOf(cr), r);
        normalize(s.measures.get(p.measure), p.staff);
    }

    /** Clears all content of a range of staves/measures. */
    public static void clearRange(Score s, int m1, int m2, int s1, int s2) {
        for (int m = m1; m <= m2; m++) {
            Measure mm = s.measures.get(m);
            for (int st = s1; st <= s2; st++) {
                StaffMeasure sm = mm.staves.get(st);
                for (int v = 0; v < StaffMeasure.VOICES; v++) sm.voices[v].items.clear();
                sm.voices[0].items.add(Rest.measureRest(mm.ticks()));
            }
        }
    }

    /** Changes the duration of an existing element, overwriting what follows. */
    public static Pos changeDuration(Score s, Pos p, int type, int dots) {
        ChordRest cr = elementAt(s, p);
        if (cr == null) return p;
        if (cr instanceof Chord) {
            Chord tmpl = ((Chord) cr).copy();
            tmpl.tuplet = null;
            return enter(s, p, tmpl, type, dots, false);
        }
        if (cr instanceof Rest && ((Rest) cr).measureRest) {
            expandMeasureRest(s.measures.get(p.measure).staves.get(p.staff).voices[p.voice], s.measures.get(p.measure).ticks());
        }
        return enter(s, p, null, type, dots, false);
    }

    /** Moves a note by semitones, respelling it in the key (up = sharps, down = flats). */
    public static void transposeNote(Score s, int measure, Note n, int semis) {
        int key = s.keyAt(measure);
        int np = n.pitch + semis;
        if (np < 0 || np > 127) return;
        n.pitch = np;
        n.tpc = spellDirectional(np, key, semis);
    }

    public static int spellDirectional(int pitch, int key, int direction) {
        int def = Pitch.spell(pitch, key);
        int lo = 13 + key, hi = 19 + key;
        if (def >= lo && def <= hi) return def;
        int pc = MathUtil.floorMod(pitch, 12);
        int best = def;
        for (int tpc = Pitch.TPC_MIN; tpc <= Pitch.TPC_MAX; tpc++) {
            if (Pitch.pitchClassOfTpc(tpc) != pc) continue;
            int a = Pitch.alter(tpc);
            if (direction > 0 && a == 1) best = tpc;
            if (direction < 0 && a == -1) best = tpc;
        }
        if (Math.abs(direction) == 12) return def;
        return best;
    }

    /** Applies an accidental to a note, keeping its letter name (E + flat = Eb). */
    public static void setAccidental(Note n, int alter) {
        int step = Pitch.step(n.tpc);
        int oct = Pitch.octave(n.pitch, n.tpc);
        int np = Pitch.pitchFromDiatonic(oct * 7 + step, alter);
        if (np < 0 || np > 127) return;
        n.pitch = np;
        n.tpc = Pitch.tpcFromStepAlter(step, alter);
        n.forceAccidental = false;
    }

    /** Transposes every note in a range by semitones. */
    public static void transposeRange(Score s, int m1, int m2, int s1, int s2, int semis) {
        for (int m = m1; m <= m2; m++) {
            int key = s.keyAt(m);
            for (int st = s1; st <= s2; st++) {
                for (Voice v : s.measures.get(m).staves.get(st).voices) {
                    for (ChordRest cr : v.items) {
                        if (!(cr instanceof Chord)) continue;
                        for (Note n : ((Chord) cr).notes) {
                            int np = n.pitch + semis;
                            if (np < 0 || np > 127) continue;
                            n.pitch = np;
                            n.tpc = Math.abs(semis) % 12 == 0 ? n.tpc : Pitch.spell(np, key);
                        }
                        ((Chord) cr).sortNotes();
                    }
                }
            }
        }
    }

    /** Toggles ties from a chord to the next chord on all matching pitches. */
    public static boolean toggleTie(Score s, Pos p) {
        ChordRest cr = elementAt(s, p);
        if (!(cr instanceof Chord)) return false;
        Chord c = (Chord) cr;
        Chord nx = nextChord(s, p);
        boolean any = false;
        for (Note n : c.notes) if (n.tieForward) any = true;
        if (any) {
            for (Note n : c.notes) n.tieForward = false;
            return true;
        }
        if (nx == null) return false;
        boolean changed = false;
        for (Note n : c.notes) {
            for (Note m : nx.notes) {
                if (m.pitch == n.pitch) {
                    n.tieForward = true;
                    changed = true;
                }
            }
        }
        return changed;
    }

    /** Adjusts all voices of a measure after its length changed (time signature change). */
    public static void refitMeasure(Measure m, int oldTicks) {
        int nt = m.ticks();
        for (StaffMeasure sm : m.staves) {
            for (int vi = 0; vi < StaffMeasure.VOICES; vi++) {
                Voice v = sm.voices[vi];
                if (v.isEmpty()) continue;
                if (v.items.size() == 1 && v.items.get(0) instanceof Rest && ((Rest) v.items.get(0)).measureRest) {
                    ((Rest) v.items.get(0)).measureTicks = nt;
                    continue;
                }
                int total = v.totalTicks();
                if (total > nt) {
                    // drop / truncate elements past the new end
                    int[] st = v.starts();
                    List<ChordRest> out = new ArrayList<ChordRest>();
                    IdentityHashMap<Tuplet, Boolean> cut = new IdentityHashMap<Tuplet, Boolean>();
                    for (int i = 0; i < v.items.size(); i++) {
                        ChordRest cr = v.items.get(i);
                        if (st[i + 1] <= nt) {
                            out.add(cr);
                        } else if (st[i] < nt) {
                            if (cr.tuplet != null) cut.put(cr.tuplet, Boolean.TRUE);
                            else out.addAll(headPieces(cr, nt - st[i], null));
                        }
                    }
                    // remove partially cut tuplets entirely
                    List<ChordRest> clean = new ArrayList<ChordRest>();
                    for (ChordRest cr : out) if (cr.tuplet == null || !cut.containsKey(cr.tuplet)) clean.add(cr);
                    v.items.clear();
                    v.items.addAll(clean);
                    int t2 = v.totalTicks();
                    if (t2 < nt) v.items.addAll(restPieces(t2, nt - t2, null));
                } else if (total < nt) {
                    v.items.addAll(restPieces(total, nt - total, null));
                }
            }
        }
    }

    /**
     * Makes an imported voice exactly as long as its measure. Returns false if the content could
     * not be represented (e.g. nested tuplets) and the voice had to be cleared.
     */
    public static boolean fitVoice(Measure mm, Voice voice, boolean firstVoice) {
        if (voice.isEmpty()) {
            if (firstVoice) voice.items.add(Rest.measureRest(mm.ticks()));
            return true;
        }
        if (voice.items.size() == 1 && voice.items.get(0) instanceof Rest && ((Rest) voice.items.get(0)).measureRest) {
            ((Rest) voice.items.get(0)).measureTicks = mm.ticks();
            return true;
        }
        int total = voice.totalTicks();
        if (total != mm.ticks()) {
            Measure tmp = mm.copyHeader();
            StaffMeasure tsm = new StaffMeasure();
            tsm.voices[0] = voice;
            tmp.staves.add(tsm);
            refitMeasure(tmp, total);
        }
        if (voice.totalTicks() != mm.ticks()) {
            voice.items.clear();
            if (firstVoice) voice.items.add(Rest.measureRest(mm.ticks()));
            return false;
        }
        return true;
    }

    /** Toggles an articulation on a chord. */
    public static void toggleArticulation(ChordRest cr, String id) {
        if (cr == null) return;
        if (cr.articulations.contains(id)) cr.articulations.remove(id);
        else cr.articulations.add(id);
    }

    // ------------------------------------------------------------------ clipboard

    /** A rectangular copy of measures x staves. */
    public static final class Clip {
        public final List<List<StaffMeasure>> measures = new ArrayList<List<StaffMeasure>>();
        public final List<Integer> lengths = new ArrayList<Integer>();

        public int staffCount() {
            return measures.isEmpty() ? 0 : measures.get(0).size();
        }
    }

    public static Clip copyRange(Score s, int m1, int m2, int s1, int s2) {
        Clip c = new Clip();
        for (int m = m1; m <= m2; m++) {
            List<StaffMeasure> row = new ArrayList<StaffMeasure>();
            for (int st = s1; st <= s2; st++) row.add(s.measures.get(m).staves.get(st).copy());
            c.measures.add(row);
            c.lengths.add(s.measures.get(m).ticks());
        }
        return c;
    }

    /** Pastes a clip starting at a measure/staff. Measures with a different length are skipped. */
    public static int paste(Score s, Clip c, int measure, int staff) {
        int pasted = 0;
        for (int i = 0; i < c.measures.size(); i++) {
            int m = measure + i;
            if (m >= s.measures.size()) s.appendMeasures(1);
            Measure mm = s.measures.get(m);
            if (mm.ticks() != c.lengths.get(i)) continue;
            List<StaffMeasure> row = c.measures.get(i);
            for (int j = 0; j < row.size(); j++) {
                int st = staff + j;
                if (st >= s.staffCount()) break;
                StaffMeasure src = row.get(j).copy();
                StaffMeasure dst = mm.staves.get(st);
                for (int v = 0; v < StaffMeasure.VOICES; v++) dst.voices[v] = src.voices[v];
                dst.clef = src.clef;
            }
            pasted++;
        }
        return pasted;
    }
}
