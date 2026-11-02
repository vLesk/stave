package io.github.vlesk.stave.edit;

import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.Rest;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.Spanners;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Syllables;
import io.github.vlesk.stave.model.Voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Editing session state: the score, undo history, selection and note-input state.
 * This is the touch-friendly port of MuseScore's note input and selection model; it has no
 * Android dependencies so it can be unit-tested on the JVM.
 */
public final class EditorState {
    public static final int SEL_NONE = 0, SEL_CHORDREST = 1, SEL_NOTE = 2, SEL_RANGE = 3;
    private static final int MAX_UNDO = 80;

    public Score score;
    private final List<Score> undo = new ArrayList<Score>();
    private final List<Score> redo = new ArrayList<Score>();
    /** Increments on every content change (used for autosave and re-layout). */
    public int revision;

    // selection
    public int selKind = SEL_NONE;
    public Pos selPos;
    public int selNote = -1;
    public int rangeM1, rangeM2, rangeS1, rangeS2;

    // note input
    public boolean inputMode;
    public Pos cursor;
    public int durType = Duration.QUARTER;
    public int dots;
    public boolean triplet;
    /** Index of the selected grace chord of the selected chord, or -1. */
    public int selGrace = -1;
    /** A grace note was just added: the next piano key or letter gives its pitch. */
    public boolean graceAwaitingPitch;
    /** Notes in the tuplet entered while {@link #triplet} is on: 3 for triplets, 5 for quintuplets, ... */
    public int tupletActual = 3;
    /** The value a new triplet is built on: the duration selected when triplets were turned on (-1: the entered value). */
    public int tripletUnit = -1;
    public int voice;
    /** When on, piano keys add notes to the chord just entered instead of advancing. */
    public boolean chordMode;
    private Pos lastEntered;

    private ScoreEdit.Clip clipboard;

    public EditorState(Score s) {
        score = s;
    }

    // ------------------------------------------------------------------ history

    /** Call before every modification. */
    public void checkpoint() {
        undo.add(score.copy());
        if (undo.size() > MAX_UNDO) undo.remove(0);
        redo.clear();
    }

    private void changed() {
        revision++;
        validateSelection();
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        endLyric();
        if (undo.isEmpty()) return;
        redo.add(score);
        score = undo.remove(undo.size() - 1);
        dropEmptyLyrics(score);
        changed();
    }

    public void redo() {
        endLyric();
        if (redo.isEmpty()) return;
        undo.add(score);
        score = redo.remove(redo.size() - 1);
        dropEmptyLyrics(score);
        changed();
    }

    // ------------------------------------------------------------------ selection

    public void clearSelection() {
        selGrace = -1;
        graceAwaitingPitch = false;
        selKind = SEL_NONE;
        selPos = null;
        selNote = -1;
    }

    public void selectNote(Pos p, int noteIdx) {
        selGrace = -1;
        graceAwaitingPitch = false;
        selKind = noteIdx >= 0 ? SEL_NOTE : SEL_CHORDREST;
        selPos = p.copy();
        selNote = noteIdx;
        if (inputMode) {
            cursor = p.copy();
            voice = p.voice;
        }
    }

    public void selectMeasure(int m, int staff) {
        selGrace = -1;
        graceAwaitingPitch = false;
        selKind = SEL_RANGE;
        rangeM1 = rangeM2 = m;
        rangeS1 = rangeS2 = staff;
        selPos = new Pos(m, staff, 0, 0);
    }

    /** Extends the current selection to a range ending at the measure/staff (long-press). */
    public void extendTo(int m, int staff) {
        selGrace = -1;
        graceAwaitingPitch = false;
        int am, as;
        if (selKind == SEL_RANGE) {
            am = rangeM1;
            as = rangeS1;
            if (m < rangeM1) am = rangeM2;
            if (staff < rangeS1) as = rangeS2;
        } else if (selPos != null) {
            am = selPos.measure;
            as = selPos.staff;
        } else {
            am = m;
            as = staff;
        }
        selKind = SEL_RANGE;
        rangeM1 = Math.min(am, m);
        rangeM2 = Math.max(am, m);
        rangeS1 = Math.min(as, staff);
        rangeS2 = Math.max(as, staff);
        selPos = new Pos(rangeM1, rangeS1, 0, 0);
    }

    public void selectAll() {
        selGrace = -1;
        graceAwaitingPitch = false;
        selKind = SEL_RANGE;
        rangeM1 = 0;
        rangeM2 = score.measures.size() - 1;
        rangeS1 = 0;
        rangeS2 = score.staffCount() - 1;
        selPos = new Pos(0, 0, 0, 0);
    }

    public ChordRest selectedChordRest() {
        if ((selKind == SEL_CHORDREST || selKind == SEL_NOTE) && selPos != null) return ScoreEdit.elementAt(score, selPos);
        return null;
    }

    public Note selectedNote() {
        Chord gr = selectedGrace();
        if (gr != null) return gr.top();
        ChordRest cr = selectedChordRest();
        if (cr instanceof Chord) {
            Chord c = (Chord) cr;
            if (selNote >= 0 && selNote < c.notes.size()) return c.notes.get(selNote);
            return c.top();
        }
        return null;
    }

    public boolean hasSelection() {
        return selKind != SEL_NONE;
    }

    private void validateSelection() {
        if (selKind == SEL_RANGE) {
            int mc = score.measures.size() - 1, sc = score.staffCount() - 1;
            rangeM1 = Math.min(rangeM1, mc);
            rangeM2 = Math.min(rangeM2, mc);
            rangeS1 = Math.min(rangeS1, sc);
            rangeS2 = Math.min(rangeS2, sc);
            return;
        }
        if (selPos == null) return;
        if (selPos.measure >= score.measures.size() || selPos.staff >= score.staffCount()) {
            clearSelection();
            return;
        }
        ChordRest cr = ScoreEdit.elementAt(score, selPos);
        if (cr == null) {
            Voice v = score.measures.get(selPos.measure).staves.get(selPos.staff).voices[selPos.voice];
            if (v.isEmpty()) {
                clearSelection();
                return;
            }
            selPos = ScoreEdit.snapToElement(score, selPos);
            cr = ScoreEdit.elementAt(score, selPos);
        }
        if (cr instanceof Chord) {
            if (selNote >= ((Chord) cr).notes.size()) selNote = ((Chord) cr).notes.size() - 1;
        } else if (selKind == SEL_NOTE) {
            selKind = SEL_CHORDREST;
            selNote = -1;
        }
    }

    // ------------------------------------------------------------------ note input

    public void startInput() {
        inputMode = true;
        if (selPos != null && selKind != SEL_RANGE) {
            cursor = selPos.copy();
            voice = selPos.voice;
        } else if (selKind == SEL_RANGE) {
            cursor = new Pos(rangeM1, rangeS1, voice, 0);
        } else if (cursor == null) {
            cursor = new Pos(0, 0, voice, 0);
        }
        cursor.voice = voice;
        clampCursor();
        lastEntered = null;
    }

    public void stopInput() {
        inputMode = false;
        chordMode = false;
        slurOpen = false;
        hairpinOpen = 0;
    }

    private void clampCursor() {
        if (cursor.measure >= score.measures.size()) cursor.measure = score.measures.size() - 1;
        if (cursor.staff >= score.staffCount()) cursor.staff = score.staffCount() - 1;
        if (cursor.tick >= score.measures.get(cursor.measure).ticks()) cursor.tick = 0;
    }

    public void setVoice(int v) {
        voice = v;
        if (cursor != null) {
            cursor.voice = v;
            // voices 2-4 may be empty: keep the cursor tick
        }
    }

    public int keyAtCursor() {
        return score.keyAt(cursor != null ? cursor.measure : 0);
    }

    /** Clef at the input cursor (for choosing the octave of new notes). */
    public int clefAtCursor() {
        return cursor == null ? Clef.TREBLE : score.clefAt(cursor.staff, cursor.measure);
    }

    /** Enters a pitch at the cursor (or adds it to the current chord in chord mode). Returns the pitch. */
    public int enterPitch(int pitch, int forcedAlter) {
        if (graceAwaitingPitch && selectedGrace() != null) return setGracePitch(pitch, forcedAlter);
        if (cursor == null) startInput();
        pitch = Math.max(0, Math.min(127, pitch));
        int key = score.keyAt(cursor.measure);
        int tpc = Pitch.spell(pitch, key);
        if (forcedAlter != Integer.MIN_VALUE) {
            int t = Pitch.spellWithAlter(pitch, forcedAlter);
            if (Pitch.validTpc(t)) tpc = t;
        }
        checkpoint();
        if (chordMode && lastEntered != null && ScoreEdit.elementAt(score, lastEntered) instanceof Chord) {
            ScoreEdit.addToChord(score, lastEntered, pitch, tpc);
            selectEnteredNote(lastEntered, pitch);
            changed();
            return pitch;
        }
        Chord c = new Chord();
        c.addNote(new Note(pitch, tpc));
        Pos at = cursor.copy();
        Pos after = ScoreEdit.enter(score, at, c, durType, dots, triplet ? tupletActual : 0, tripletUnit);
        lastEntered = at;
        followLines(at);
        cursor = after;
        cursor.voice = voice;
        selectEnteredNote(at, pitch);
        changed();
        return pitch;
    }

    /**
     * A key of the on-screen piano. Keys pressed while others are still held join the chord just
     * entered, so a chord is played rather than built with the chord-mode toggle.
     */
    public int enterPianoKey(int pitch, boolean withHeldKeys) {
        boolean was = chordMode;
        if (withHeldKeys && lastEntered != null) chordMode = true;
        try {
            return enterPitch(pitch, Integer.MIN_VALUE);
        } finally {
            chordMode = was;
        }
    }

    private void selectEnteredNote(Pos at, int pitch) {
        selGrace = -1;
        graceAwaitingPitch = false;
        ChordRest cr = ScoreEdit.elementAt(score, at);
        int idx = -1;
        if (cr instanceof Chord) {
            List<Note> ns = ((Chord) cr).notes;
            for (int i = 0; i < ns.size(); i++) if (ns.get(i).pitch == pitch) idx = i;
        }
        selKind = idx >= 0 ? SEL_NOTE : SEL_CHORDREST;
        selPos = at.copy();
        selNote = idx;
    }

    /** Adds a pitch to the chord at the selection (MuseScore: Shift+letter / "add to chord"). */
    public void addPitchToSelectedChord(int pitch) {
        Pos p = selPos != null ? selPos : lastEntered;
        if (p == null) return;
        int key = score.keyAt(p.measure);
        checkpoint();
        ScoreEdit.addToChord(score, p, pitch, Pitch.spell(pitch, key));
        selectEnteredNote(p, pitch);
        changed();
    }

    public void enterRest() {
        selGrace = -1;
        graceAwaitingPitch = false;
        if (cursor == null) startInput();
        checkpoint();
        Pos at = cursor.copy();
        cursor = ScoreEdit.enter(score, at, null, durType, dots, triplet ? tupletActual : 0, tripletUnit);
        cursor.voice = voice;
        lastEntered = null;
        followLines(at);
        selKind = SEL_CHORDREST;
        selPos = at;
        selNote = -1;
        changed();
    }

    /** Enters a tied copy of the previous chord at the cursor (MuseScore "+" in note input). */
    public boolean enterTie() {
        selGrace = -1;
        graceAwaitingPitch = false;
        if (cursor == null) return false;
        Pos prev = lastEntered;
        if (prev == null) prev = ScoreEdit.prev(score, cursor);
        ChordRest pcr = prev != null ? ScoreEdit.elementAt(score, prev) : null;
        if (!(pcr instanceof Chord)) return false;
        checkpoint();
        Chord tmpl = ((Chord) pcr).copy();
        tmpl.clearMarkings();
        tmpl.tuplet = null;
        for (Note n : tmpl.notes) n.tieForward = false;
        for (Note n : ((Chord) pcr).notes) n.tieForward = true;
        Pos at = cursor.copy();
        cursor = ScoreEdit.enter(score, at, tmpl, durType, dots, triplet ? tupletActual : 0, tripletUnit);
        cursor.voice = voice;
        lastEntered = at;
        followLines(at);
        selKind = SEL_CHORDREST;
        selPos = at;
        selNote = -1;
        changed();
        return true;
    }

    /** Letter-name entry from a hardware keyboard: picks the octave closest to the previous note. */
    public int enterLetter(int step, boolean addToChord) {
        int key = score.keyAt(cursor != null ? cursor.measure : 0);
        int ref = referencePitch();
        int alter = Pitch.keyAlter(key, step);
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int oct = 0; oct <= 9; oct++) {
            int p = Pitch.pitchFromDiatonic(oct * 7 + step, alter);
            if (p < 0 || p > 127) continue;
            int d = Math.abs(p - ref);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        if (best < 0) return -1;
        if (addToChord) {
            addPitchToSelectedChord(best);
            return best;
        }
        return enterPitch(best, Integer.MIN_VALUE);
    }

    private int referencePitch() {
        Note n = selectedNote();
        if (n != null) return n.pitch;
        if (cursor != null) {
            Chord c = ScoreEdit.prevChordBefore(score, cursor);
            if (c != null) return c.top().pitch;
            return Clef.middlePitch(score.clefAt(cursor.staff, cursor.measure));
        }
        return 71;
    }

    public void moveCursor(int dir) {
        selGrace = -1;
        graceAwaitingPitch = false;
        if (inputMode && cursor != null) {
            Pos snap = ScoreEdit.snapToElement(score, cursor);
            Voice v = score.measures.get(snap.measure).staves.get(snap.staff).voices[snap.voice];
            Pos np;
            if (v.isEmpty()) {
                np = new Pos(Math.max(0, Math.min(score.measures.size() - 1, snap.measure + dir)), snap.staff, snap.voice, 0);
            } else {
                np = dir > 0 ? ScoreEdit.next(score, snap) : ScoreEdit.prev(score, snap);
                if (dir < 0 && snap.tick < cursor.tick) np = snap;
            }
            if (np != null) cursor = np;
            lastEntered = null;
            ChordRest cr = ScoreEdit.elementAt(score, cursor);
            if (cr != null) {
                selKind = cr instanceof Chord ? SEL_NOTE : SEL_CHORDREST;
                selPos = cursor.copy();
                selNote = cr instanceof Chord ? ((Chord) cr).notes.size() - 1 : -1;
            }
            return;
        }
        if (selPos == null) return;
        Pos np = dir > 0 ? ScoreEdit.next(score, selPos) : ScoreEdit.prev(score, selPos);
        if (np == null) return;
        ChordRest cr = ScoreEdit.elementAt(score, np);
        selKind = cr instanceof Chord ? SEL_NOTE : SEL_CHORDREST;
        selPos = np;
        selNote = cr instanceof Chord ? ((Chord) cr).notes.size() - 1 : -1;
    }

    /** Moves the input cursor to a tapped position. */
    public void setCursor(Pos p) {
        cursor = p.copy();
        voice = p.voice;
        lastEntered = null;
    }

    // ------------------------------------------------------------------ duration

    /** Turns triplets on or off. The selected value becomes the triplet's unit (triplet eighths, say). */
    public void setTriplet(boolean on) {
        setTuplet(on ? 3 : 0);
    }

    /** Turns tuplets on (2 = duplet ... 6 = sextuplet) or off (0); the selected value becomes the unit. */
    public void setTuplet(int actual) {
        triplet = actual > 1;
        if (triplet) tupletActual = actual;
        tripletUnit = triplet ? durType : -1;
    }

    public void setDuration(int type) {
        durType = type;
        if (type == Duration.MAX_TYPE) dots = 0;
        if (!inputMode) applyDurationToSelection();
    }

    /** The dot button: no dot, one dot, two dots (a double dot is not offered on 64ths). */
    public void toggleDot() {
        dots = (dots + 1) % 3;
        if (durType == Duration.MAX_TYPE) dots = 0;
        if (dots == 2 && durType >= Duration.N64) dots = 0;
        if (!inputMode) applyDurationToSelection();
    }

    private void applyDurationToSelection() {
        if (selKind != SEL_CHORDREST && selKind != SEL_NOTE) return;
        ChordRest cr = selectedChordRest();
        if (cr == null) return;
        checkpoint();
        ScoreEdit.changeDuration(score, selPos, durType, dots);
        changed();
    }

    // ------------------------------------------------------------------ modifications on selection

    public void deleteSelection() {
        Chord gr = selectedGrace();
        if (gr != null) {
            checkpoint();
            ((Chord) selectedChordRest()).graces.remove(gr);
            selGrace = -1;
            graceAwaitingPitch = false;
            changed();
            return;
        }
        if (selKind == SEL_RANGE) {
            checkpoint();
            ScoreEdit.clearRange(score, rangeM1, rangeM2, rangeS1, rangeS2);
            changed();
            return;
        }
        if (selPos == null) return;
        ChordRest cr = selectedChordRest();
        if (cr == null) return;
        checkpoint();
        if (selKind == SEL_NOTE && cr instanceof Chord && ((Chord) cr).notes.size() > 1) {
            ScoreEdit.deleteNote(score, selPos, selNote);
            selNote = Math.max(0, selNote - 1);
        } else {
            ScoreEdit.deleteChordRest(score, selPos);
            if (selPos.voice > 0 && score.measures.get(selPos.measure).staves.get(selPos.staff).voices[selPos.voice].isEmpty()) {
                clearSelection();
            }
        }
        if (inputMode && selPos != null) {
            cursor = selPos.copy();
            lastEntered = null;
        }
        changed();
    }

    /** Semitone (or octave with 12) transposition of the selected note(s). */
    public void transpose(int semis) {
        if (selKind == SEL_RANGE) {
            checkpoint();
            ScoreEdit.transposeRange(score, rangeM1, rangeM2, rangeS1, rangeS2, semis);
            changed();
            return;
        }
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord)) return;
        checkpoint();
        Chord c = (Chord) cr;
        Chord gr = selectedGrace();
        if (gr != null) {
            for (Note n : gr.notes) ScoreEdit.transposeNote(score, selPos.measure, n, semis);
            gr.sortNotes();
            graceAwaitingPitch = false;
            changed();
            return;
        }
        if (selKind == SEL_NOTE) {
            Note n = selectedNote();
            int target = n.pitch + semis;
            for (Note o : c.notes) if (o != n && o.pitch == target) return;
            ScoreEdit.transposeNote(score, selPos.measure, n, semis);
            c.sortNotes();
            selNote = c.notes.indexOf(n);
        } else {
            for (Note n : c.notes) ScoreEdit.transposeNote(score, selPos.measure, n, semis);
            c.sortNotes();
        }
        changed();
    }

    // ------------------------------------------------------------------ slurs and hairpins

    /** Note input: a slur (S) or hairpin (&lt; &gt;) is open and follows the entered notes, as in MuseScore. */
    public boolean slurOpen;
    public int hairpinOpen;
    private Pos slurStartPos, hairpinStartPos;

    private int globalTick(Pos p) {
        return Syllables.globalTick(score, p.measure, p.tick);
    }

    private Pos posAt(int global, Pos like) {
        int[] mt = Spanners.posOf(score, global);
        return new Pos(mt[0], like.staff, like.voice, mt[1]);
    }

    private void followLines(Pos at) {
        ChordRest e = ScoreEdit.elementAt(score, at);
        if (e == null) return;
        if (slurOpen && slurStartPos != null && e instanceof Chord) {
            ChordRest st = ScoreEdit.elementAt(score, slurStartPos);
            int d = globalTick(at) - globalTick(slurStartPos);
            if (st instanceof Chord && d > 0) st.slurTicks = d;
        }
        if (hairpinOpen != 0 && hairpinStartPos != null) {
            ChordRest st = ScoreEdit.elementAt(score, hairpinStartPos);
            int d = globalTick(at) + e.ticks() - globalTick(hairpinStartPos);
            if (st != null && d > 0) {
                st.hairpin = hairpinOpen;
                st.hairpinTicks = d;
            }
        }
    }

    /** First and last chord (or element, with anyElement) of the selected range, in its first staff. */
    private Pos[] rangeEnds(boolean anyElement) {
        Pos first = null, last = null;
        for (int m = rangeM1; m <= rangeM2; m++) {
            Voice v = score.measures.get(m).staves.get(rangeS1).voices[0];
            int[] st = v.starts();
            for (int i = 0; i < v.items.size(); i++) {
                if (!anyElement && !(v.items.get(i) instanceof Chord)) continue;
                Pos p = new Pos(m, rangeS1, 0, st[i]);
                if (first == null) first = p;
                last = p;
            }
        }
        return new Pos[]{first, last};
    }

    /**
     * S (MuseScore): in note input, starts a slur on the note just entered that follows the next
     * notes, or ends it; on a selected note, adds a slur to the next note, or extends it by one note;
     * on a range, a slur over the range.
     */
    public boolean slur() {
        if (inputMode) {
            if (slurOpen) {
                slurOpen = false;
                return true;
            }
            Pos at = lastEntered != null ? lastEntered : selPos;
            if (at == null || !(ScoreEdit.elementAt(score, at) instanceof Chord)) return false;
            slurStartPos = at.copy();
            slurOpen = true;
            return true;
        }
        if (selKind == SEL_RANGE) {
            Pos[] ends = rangeEnds(false);
            if (ends[0] == null || ends[1] == null || globalTick(ends[1]) <= globalTick(ends[0])) return false;
            checkpoint();
            ScoreEdit.elementAt(score, ends[0]).slurTicks = globalTick(ends[1]) - globalTick(ends[0]);
            changed();
            return true;
        }
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord)) return false;
        Pos end = cr.slurTicks > 0 ? posAt(globalTick(selPos) + cr.slurTicks, selPos) : selPos;
        Pos nx = lyricNextChordPos(end);
        if (nx == null) return false;
        checkpoint();
        cr.slurTicks = globalTick(nx) - globalTick(selPos);
        changed();
        return true;
    }

    /**
     * &lt; and &gt; (MuseScore): a crescendo or diminuendo hairpin. In note input it starts on the
     * element just entered and follows the next ones until pressed again; on a selected note it
     * reaches over the next one, and again extends it by one; on a range it covers the range.
     */
    public boolean hairpin(int kind) {
        if (inputMode) {
            if (hairpinOpen == kind) {
                hairpinOpen = 0;
                return true;
            }
            Pos at = lastEntered != null ? lastEntered : selPos;
            ChordRest cr = at == null ? null : ScoreEdit.elementAt(score, at);
            if (cr == null) return false;
            checkpoint();
            cr.hairpin = kind;
            cr.hairpinTicks = cr.ticks();
            hairpinStartPos = at.copy();
            hairpinOpen = kind;
            changed();
            return true;
        }
        if (selKind == SEL_RANGE) {
            Pos[] ends = rangeEnds(true);
            if (ends[0] == null) return false;
            checkpoint();
            ChordRest first = ScoreEdit.elementAt(score, ends[0]);
            first.hairpin = kind;
            first.hairpinTicks = Syllables.globalTick(score, rangeM2, score.measures.get(rangeM2).ticks()) - globalTick(ends[0]);
            changed();
            return true;
        }
        ChordRest cr = selectedChordRest();
        if (cr == null) return false;
        int start = globalTick(selPos);
        int end = cr.hairpin == kind && cr.hairpinTicks > 0 ? start + cr.hairpinTicks : start + cr.ticks();
        Pos np = posAt(end, selPos);
        ChordRest nx = ScoreEdit.elementAt(score, np);
        if (nx == null) return false;
        checkpoint();
        cr.hairpin = kind;
        cr.hairpinTicks = end + nx.ticks() - start;
        changed();
        return true;
    }

    /** Removes the slurs and hairpins that start on the selection. */
    public boolean removeLines() {
        List<ChordRest> ts = targets();
        boolean any = false;
        for (ChordRest cr : ts) if (cr.slurTicks > 0 || cr.hairpin != 0) any = true;
        if (!any) return false;
        checkpoint();
        for (ChordRest cr : ts) {
            cr.slurTicks = 0;
            cr.hairpin = 0;
            cr.hairpinTicks = 0;
        }
        changed();
        return true;
    }

    // ------------------------------------------------------------------ grace notes

    /** The selected grace chord, or null. */
    public Chord selectedGrace() {
        if (selGrace < 0 || selKind != SEL_NOTE) return null;
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord) || selGrace >= ((Chord) cr).graces.size()) return null;
        return ((Chord) cr).graces.get(selGrace);
    }

    /** Selects a grace chord of the chord at p (a tap on it in the score). */
    public void selectGrace(Pos p, int index) {
        selKind = SEL_NOTE;
        selPos = p.copy();
        selNote = -1;
        selGrace = index;
        graceAwaitingPitch = false;
    }

    /**
     * Adds a grace note before the selected chord (in note input: the note just entered), at the
     * pitch of the selected note, and selects it; the next piano key or letter sets its pitch, as
     * in MuseScore, where grace notes are added to an existing note. Kind is ACCIACCATURA or
     * APPOGGIATURA; type is EIGHTH or N16.
     */
    public boolean addGrace(int kind, int type) {
        Pos at = selPos != null && selKind != SEL_RANGE ? selPos : lastEntered;
        ChordRest cr = at == null ? null : ScoreEdit.elementAt(score, at);
        if (!(cr instanceof Chord)) return false;
        Chord c = (Chord) cr;
        Note main = selectedGrace() == null ? selectedNote() : null;
        if (main == null) main = c.top();
        checkpoint();
        c.graces.add(Chord.grace(kind, type, new Note(main.pitch, main.tpc)));
        selKind = SEL_NOTE;
        selPos = at.copy();
        selNote = -1;
        selGrace = c.graces.size() - 1;
        graceAwaitingPitch = true;
        changed();
        return true;
    }

    private int setGracePitch(int pitch, int forcedAlter) {
        Chord gr = selectedGrace();
        int key = score.keyAt(selPos.measure);
        int tpc = Pitch.spell(pitch, key);
        if (forcedAlter != Integer.MIN_VALUE) {
            int t = Pitch.spellWithAlter(pitch, forcedAlter);
            if (Pitch.validTpc(t)) tpc = t;
        }
        checkpoint();
        gr.notes.clear();
        gr.notes.add(new Note(pitch, tpc));
        graceAwaitingPitch = false;
        changed();
        return pitch;
    }

    /** Respells the selected note (or every note of the selected chord) enharmonically, as MuseScore's J. */
    public boolean respell() {
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord) || selKind == SEL_RANGE) return false;
        checkpoint();
        Note sel = selectedNote();
        Chord gr = selectedGrace();
        for (Note n : (gr != null ? gr : (Chord) cr).notes) {
            if (sel != null && n != sel) continue;
            n.tpc = Pitch.nextSpelling(n.tpc);
            n.forceAccidental = false;
        }
        ((Chord) cr).sortNotes();
        changed();
        return true;
    }

    public void applyAccidental(int alter) {
        Note n = selectedNote();
        if (selKind == SEL_RANGE || n == null) return;
        checkpoint();
        ScoreEdit.setAccidental(n, alter);
        ((Chord) selectedChordRest()).sortNotes();
        changed();
    }

    public boolean toggleTie() {
        if (selPos == null) return false;
        checkpoint();
        boolean ok = ScoreEdit.toggleTie(score, selPos);
        if (!ok) {
            undo.remove(undo.size() - 1);
            return false;
        }
        changed();
        return true;
    }

    public void toggleArticulation(String id) {
        List<ChordRest> targets = targets();
        if (targets.isEmpty()) return;
        checkpoint();
        boolean allHave = true;
        for (ChordRest cr : targets) if (!cr.articulations.contains(id)) allHave = false;
        for (ChordRest cr : targets) {
            if (cr instanceof Rest && !id.equals(io.github.vlesk.stave.model.Articulation.FERMATA)) continue;
            if (allHave) cr.articulations.remove(id);
            else if (!cr.articulations.contains(id)) cr.articulations.add(id);
        }
        changed();
    }

    public void setDynamic(String d) {
        ChordRest cr = firstTarget();
        if (cr == null) return;
        checkpoint();
        cr.dynamic = d != null && d.equals(cr.dynamic) ? null : d;
        changed();
    }

    public void setLyric(String text) {
        ChordRest cr = selectedChordRest();
        if (!(cr instanceof Chord)) return;
        checkpoint();
        cr.lyric = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void setChordSymbol(String text) {
        ChordRest cr = firstTarget();
        if (cr == null) return;
        checkpoint();
        cr.chordSymbol = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void setStaffText(String text) {
        ChordRest cr = firstTarget();
        if (cr == null) return;
        checkpoint();
        cr.staffText = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    /**
     * Undo snapshots are taken while a syllable is being typed, so they can hold its empty
     * placeholder; restored scores must not keep it (it would reserve an empty lyric line).
     */
    private static void dropEmptyLyrics(Score s) {
        for (Measure m : s.measures) {
            for (StaffMeasure sm : m.staves) {
                for (Voice v : sm.voices) {
                    for (ChordRest cr : v.items) {
                        if (cr.lyric != null && cr.lyric.trim().isEmpty()) {
                            cr.lyric = null;
                            cr.melisma = 0;
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ inline lyric entry

    /**
     * True while lyrics are typed on the score. The text being typed is the selected chord's own
     * lyric, so layout and drawing need no separate draft.
     */
    public boolean lyricEdit;
    /** Trailing characters of the lyric that an input method is still composing. */
    private int lyricComposing;
    /** The next change to a lyric first takes an undo step (one step per syllable). */
    private boolean lyricUndoPending;

    private Chord lyricChord() {
        ChordRest cr = selectedChordRest();
        return cr instanceof Chord ? (Chord) cr : null;
    }

    private void lyricBeforeChange() {
        if (lyricUndoPending) {
            checkpoint();
            lyricUndoPending = false;
        }
    }

    /** Drops an empty syllable, so no empty lyric is kept. */
    private void lyricFinish(Chord c) {
        lyricComposing = 0;
        if (c == null || c.lyric == null) return;
        String t = c.lyric.trim();
        c.lyric = t.isEmpty() ? null : t;
        if (c.lyric == null) c.melisma = 0;
    }

    /** Removes the text an input method is still composing. */
    private void lyricDropComposing() {
        Chord c = lyricChord();
        if (c != null && c.lyric != null && lyricComposing > 0) {
            c.lyric = c.lyric.substring(0, Math.max(0, c.lyric.length() - lyricComposing));
        }
        lyricComposing = 0;
    }

    /**
     * Where lyric entry starts: the selected chord, else the first chord of the selected range or
     * staff, else the first chord of the score. Null if the score has no chord at all.
     */
    public Pos lyricStartPos() {
        ChordRest cr = selectedChordRest();
        if (cr instanceof Chord) return selPos.copy();
        int m = 0, staff = 0;
        if (selKind == SEL_RANGE) {
            m = rangeM1;
            staff = rangeS1;
        } else if (selPos != null) {
            m = selPos.measure;
            staff = selPos.staff;
        }
        Pos p = new Pos(m, staff, 0, 0);
        while (p != null) {
            if (ScoreEdit.elementAt(score, p) instanceof Chord) return p;
            p = ScoreEdit.next(score, p);
        }
        return null;
    }

    /** Starts typing the lyric of the chord at p. An existing lyric is continued at its end. */
    public boolean beginLyric(Pos p) {
        selGrace = -1;
        graceAwaitingPitch = false;
        if (p == null) return false;
        ChordRest cr = ScoreEdit.elementAt(score, p);
        if (!(cr instanceof Chord)) return false;
        if (lyricEdit) lyricFinish(lyricChord());
        selKind = SEL_CHORDREST;
        selPos = p.copy();
        selNote = -1;
        if (cr.lyric == null) cr.lyric = "";
        lyricEdit = true;
        lyricComposing = 0;
        lyricUndoPending = true;
        changed();
        return true;
    }

    /** Ends the current syllable and moves to the next chord. At the last chord nothing happens. */
    public boolean lyricNext() {
        return lyricMove(true);
    }

    /** Ends the current syllable and moves back to the previous chord. */
    public boolean lyricPrevious() {
        return lyricMove(false);
    }

    /**
     * Moves lyric entry to the next or previous chord. At the first or last chord nothing changes:
     * lyric entry is only left on purpose (see {@link #endLyric()}), never by running out of notes.
     */
    private boolean lyricMove(boolean forward) {
        selGrace = -1;
        graceAwaitingPitch = false;
        if (!lyricEdit) return false;
        Pos p = selPos;
        ChordRest target = null;
        while (p != null && target == null) {
            p = forward ? ScoreEdit.next(score, p) : ScoreEdit.prev(score, p);
            if (p != null && ScoreEdit.elementAt(score, p) instanceof Chord) target = ScoreEdit.elementAt(score, p);
        }
        if (target == null) return false;
        lyricFinish(lyricChord());
        selKind = SEL_CHORDREST;
        selPos = p;
        selNote = -1;
        if (target.lyric == null) target.lyric = "";
        lyricUndoPending = true;
        changed();
        return true;
    }

    /**
     * Types text into the lyric, as in MuseScore: a space or newline ends the syllable and moves to
     * the next note; a hyphen after a syllable is added to it and also moves on (so "hel-lo" fills
     * two notes); an underscore holds the syllable over the next note with an extender line
     * (melisma). On an empty syllable, a hyphen or underscore extends the previous syllable over
     * this note instead.
     */
    public void lyricType(CharSequence text) {
        if (!lyricEdit || text == null) return;
        lyricDropComposing();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\n') lyricMove(true);
            else if (ch == '_') lyricUnderscore();
            else if (ch == '-') lyricHyphen();
            else if (!Character.isISOControl(ch)) lyricAppend(ch);
        }
    }

    private void lyricAppend(char ch) {
        Chord c = lyricChord();
        if (c == null) return;
        lyricBeforeChange();
        String base = c.lyric == null ? "" : c.lyric;
        if (base.isEmpty()) lyricClaim();
        c.lyric = base + ch;
        changed();
    }

    /** A new syllable on this note ends any melisma that was holding it (MuseScore does the same). */
    private void lyricClaim() {
        Pos from = lyricPreviousPos(selPos);
        if (from == null) return;
        ChordRest f = ScoreEdit.elementAt(score, from);
        int upToHere = lyricChordsBetween(from, selPos);
        if (f.melisma >= upToHere) f.melisma = Math.max(1, upToHere - 1);
    }

    /** "-": after a syllable, hyphenate it and move on; on an empty one, carry the previous hyphen over this note. */
    private void lyricHyphen() {
        Chord c = lyricChord();
        if (c == null) return;
        String base = c.lyric == null ? "" : c.lyric;
        if (!base.isEmpty()) {
            lyricBeforeChange();
            c.lyric = base + '-';
            c.melisma = 0; // a hyphenated syllable continues with dashes, not an extender line
            changed();
            lyricMove(true);
            return;
        }
        Pos from = lyricPreviousPos(selPos);
        if (from == null) {
            lyricInsertLiteral('-');
            return;
        }
        if (lyricNextChordPos(selPos) == null) return;
        lyricBeforeChange();
        ChordRest f = ScoreEdit.elementAt(score, from);
        if (!Syllables.hyphenated(f.lyric)) f.lyric = f.lyric + "-";
        f.melisma = 0;
        c.lyric = null;
        changed();
        lyricMove(true);
    }

    /**
     * "_": hold the syllable over this note and move to the next one, as MuseScore's addMelisma. On
     * the syllable's own note it starts a one-note melisma; on an empty syllable it extends the
     * previous syllable up to this note. The held syllable loses its hyphen (a word ends there).
     */
    private void lyricUnderscore() {
        Chord c = lyricChord();
        if (c == null) return;
        lyricBeforeChange();
        boolean empty = c.lyric == null || c.lyric.trim().isEmpty();
        Pos from = empty ? lyricPreviousPos(selPos) : selPos.copy();
        Pos next = lyricNextChordPos(selPos);
        if (from != null) {
            ChordRest f = ScoreEdit.elementAt(score, from);
            if (Syllables.hyphenated(f.lyric)) f.lyric = Syllables.text(f.lyric);
            if (!empty) {
                if (next != null) f.melisma = Math.max(f.melisma, 1);
            } else {
                f.melisma = lyricChordsBetween(from, selPos);
            }
        }
        if (next == null) {
            changed();
            return;
        }
        if (empty) c.lyric = null;
        changed();
        lyricMove(true);
    }

    /** The nearest earlier chord in the voice with a (non-empty) lyric. */
    private Pos lyricPreviousPos(Pos p) {
        Pos q = p;
        while ((q = ScoreEdit.prev(score, q)) != null) {
            ChordRest cr = ScoreEdit.elementAt(score, q);
            if (cr instanceof Chord && cr.lyric != null && !cr.lyric.trim().isEmpty()) return q;
        }
        return null;
    }

    /** The next chord in the voice (rests skipped), or null. */
    private Pos lyricNextChordPos(Pos p) {
        Pos q = p;
        while ((q = ScoreEdit.next(score, q)) != null) {
            if (ScoreEdit.elementAt(score, q) instanceof Chord) return q;
        }
        return null;
    }

    /** Number of chords from a to b, both included (rests not counted). */
    private int lyricChordsBetween(Pos a, Pos b) {
        int n = 0;
        Pos q = a;
        while (q != null) {
            if (ScoreEdit.elementAt(score, q) instanceof Chord) n++;
            if (q.measure == b.measure && q.tick == b.tick) break;
            if (q.measure > b.measure || (q.measure == b.measure && q.tick > b.tick)) break;
            Pos nq = ScoreEdit.next(score, q);
            if (nq != null && (nq.measure < q.measure || (nq.measure == q.measure && nq.tick <= q.tick))) break;
            q = nq;
        }
        return n;
    }

    /** Types a character as plain text (a literal hyphen, say), without moving to the next note. */
    public void lyricInsertLiteral(char ch) {
        Chord c = lyricChord();
        if (!lyricEdit || c == null) return;
        lyricDropComposing();
        lyricBeforeChange();
        c.lyric = (c.lyric == null ? "" : c.lyric) + ch;
        changed();
    }

    /** Shows the text an input method is composing (replaces the previous composing text). */
    public void lyricCompose(CharSequence text) {
        Chord c = lyricChord();
        if (!lyricEdit || c == null) return;
        String t = text == null ? "" : text.toString();
        if (t.indexOf(' ') >= 0 || t.indexOf('\n') >= 0 || t.indexOf('-') >= 0 || t.indexOf('_') >= 0) {
            lyricType(t);
            return;
        }
        lyricBeforeChange();
        lyricDropComposing();
        c.lyric = (c.lyric == null ? "" : c.lyric) + t;
        lyricComposing = t.length();
        changed();
    }

    /** The input method has finished its composing text; it stays in the lyric. */
    public void lyricFinishComposing() {
        lyricComposing = 0;
    }

    /** Deletes the last character of the syllable. At the start of a syllable, moves back to the previous one. */
    public void lyricBackspace() {
        Chord c = lyricChord();
        if (!lyricEdit || c == null) return;
        if (lyricComposing > 0) {
            lyricBeforeChange();
            lyricDropComposing();
            changed();
            return;
        }
        String base = c.lyric == null ? "" : c.lyric;
        if (!base.isEmpty()) {
            lyricBeforeChange();
            c.lyric = base.substring(0, base.length() - 1);
            changed();
        } else {
            lyricMove(false);
        }
    }

    /** Ends lyric entry, keeping what was typed. */
    public void endLyric() {
        if (!lyricEdit) return;
        lyricDropComposing();
        lyricFinish(lyricChord());
        lyricEdit = false;
        lyricUndoPending = false;
        changed();
    }

    /** Moves the selection to the next chord (used to type lyrics syllable by syllable). */
    public boolean selectNextChord() {
        selGrace = -1;
        graceAwaitingPitch = false;
        if (selPos == null) return false;
        Pos p = ScoreEdit.next(score, selPos);
        while (p != null) {
            ChordRest cr = ScoreEdit.elementAt(score, p);
            if (cr instanceof Chord) {
                selKind = SEL_CHORDREST;
                selPos = p;
                selNote = -1;
                return true;
            }
            p = ScoreEdit.next(score, p);
        }
        return false;
    }

    private ChordRest firstTarget() {
        if (selKind == SEL_RANGE) {
            Voice v = score.measures.get(rangeM1).staves.get(rangeS1).voices[0];
            return v.items.isEmpty() ? null : v.items.get(0);
        }
        return selectedChordRest();
    }

    private List<ChordRest> targets() {
        List<ChordRest> out = new ArrayList<ChordRest>();
        if (selKind == SEL_RANGE) {
            for (int m = rangeM1; m <= rangeM2; m++) {
                for (int st = rangeS1; st <= rangeS2; st++) {
                    for (Voice v : score.measures.get(m).staves.get(st).voices) {
                        for (ChordRest cr : v.items) if (cr instanceof Chord) out.add(cr);
                    }
                }
            }
        } else {
            ChordRest cr = selectedChordRest();
            if (cr != null) out.add(cr);
        }
        return out;
    }

    /** Measure index the selection (or cursor) starts at; 0 if none. */
    public int selectionMeasure() {
        if (selKind == SEL_RANGE) return rangeM1;
        if (selPos != null) return selPos.measure;
        if (cursor != null) return cursor.measure;
        return 0;
    }

    public int selectionStaff() {
        if (selKind == SEL_RANGE) return rangeS1;
        if (selPos != null) return selPos.staff;
        if (cursor != null) return cursor.staff;
        return 0;
    }

    // ------------------------------------------------------------------ measure-level

    public void setClef(int clef) {
        int m = selectionMeasure();
        int s1 = selectionStaff(), s2 = selKind == SEL_RANGE ? rangeS2 : s1;
        checkpoint();
        for (int st = s1; st <= s2; st++) {
            if (m == 0) {
                score.staff(st).defaultClef = clef;
                score.measures.get(0).staves.get(st).clef = -1;
            } else {
                score.measures.get(m).staves.get(st).clef = clef == score.clefAt(st, m - 1) ? -1 : clef;
            }
        }
        changed();
    }

    public void setKeySig(int fifths) {
        checkpoint();
        score.setKeySig(selectionMeasure(), fifths);
        changed();
    }

    public void setTimeSig(int n, int d, boolean symbol) {
        checkpoint();
        score.setTimeSig(selectionMeasure(), n, d, symbol);
        changed();
    }

    public void setBarline(int type) {
        int m = selKind == SEL_RANGE ? rangeM2 : selectionMeasure();
        checkpoint();
        Measure mm = score.measures.get(m);
        mm.endBarline = mm.endBarline == type ? Measure.BAR_NORMAL : type;
        changed();
    }

    public void toggleStartRepeat() {
        int m = selectionMeasure();
        checkpoint();
        score.measures.get(m).startRepeat = !score.measures.get(m).startRepeat;
        changed();
    }

    public void setTempo(int m, int bpm, String text) {
        checkpoint();
        Measure mm = score.measures.get(m);
        mm.tempo = Math.max(0, Math.min(400, bpm));
        mm.tempoText = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void setRehearsal(String text) {
        int m = selectionMeasure();
        checkpoint();
        score.measures.get(m).rehearsal = text == null || text.trim().isEmpty() ? null : text.trim();
        changed();
    }

    public void insertMeasureBefore() {
        int m = selectionMeasure();
        checkpoint();
        score.insertMeasure(m);
        changed();
    }

    public void appendMeasures(int n) {
        checkpoint();
        score.appendMeasures(n);
        changed();
    }

    public void deleteSelectedMeasures() {
        int m1 = selKind == SEL_RANGE ? rangeM1 : selectionMeasure();
        int m2 = selKind == SEL_RANGE ? rangeM2 : m1;
        if (score.measures.size() <= 1) return;
        checkpoint();
        score.deleteMeasures(m1, m2);
        clearSelection();
        if (cursor != null) clampCursor();
        changed();
    }

    // ------------------------------------------------------------------ clipboard

    public boolean copy() {
        if (selKind == SEL_RANGE) {
            clipboard = ScoreEdit.copyRange(score, rangeM1, rangeM2, rangeS1, rangeS2);
            return true;
        }
        if (selPos != null) {
            clipboard = ScoreEdit.copyRange(score, selPos.measure, selPos.measure, selPos.staff, selPos.staff);
            return true;
        }
        return false;
    }

    public boolean hasClipboard() {
        return clipboard != null;
    }

    public boolean paste() {
        if (clipboard == null) return false;
        int m = selectionMeasure(), st = selectionStaff();
        checkpoint();
        int n = ScoreEdit.paste(score, clipboard, m, st);
        selKind = SEL_RANGE;
        rangeM1 = m;
        rangeM2 = Math.min(score.measures.size() - 1, m + clipboard.measures.size() - 1);
        rangeS1 = st;
        rangeS2 = Math.min(score.staffCount() - 1, st + clipboard.staffCount() - 1);
        changed();
        return n > 0;
    }

    /** Applies a structural change made directly to the score (instruments, properties). */
    public void externalChange() {
        changed();
    }

    /** Fills empty voice selection helper: ensures selection points to an existing element. */
    public static boolean voiceHasContent(StaffMeasure sm, int v) {
        return !sm.voices[v].isEmpty();
    }
}
