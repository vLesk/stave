package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** One or more notes sharing a stem. Notes are kept sorted from lowest to highest. */
public final class Chord extends ChordRest {
    public final List<Note> notes = new ArrayList<Note>();
    public static final int STEM_AUTO = 0, STEM_UP = 1, STEM_DOWN = 2;
    /** Explicit stem direction (MuseScore's StemDirection), STEM_AUTO by default. */
    public int stemDir;
    /** Kinds of grace chord: an acciaccatura (slashed, short) or an appoggiatura. */
    public static final int GRACE_NONE = 0, ACCIACCATURA = 1, APPOGGIATURA = 2;
    /** For a grace chord, its kind; GRACE_NONE for an ordinary chord. Its type is eighth or 16th. */
    public int graceKind;
    /** Grace chords before this chord, left to right (MuseScore keeps them on the main chord). */
    public final List<Chord> graces = new ArrayList<Chord>();

    @Override
    public boolean isRest() {
        return false;
    }

    public void addNote(Note n) {
        for (Note o : notes) if (o.pitch == n.pitch) return;
        int i = 0;
        while (i < notes.size() && notes.get(i).pitch < n.pitch) i++;
        notes.add(i, n);
    }

    public void sortNotes() {
        for (int i = 1; i < notes.size(); i++) {
            Note n = notes.get(i);
            int j = i - 1;
            while (j >= 0 && notes.get(j).pitch > n.pitch) {
                notes.set(j + 1, notes.get(j));
                j--;
            }
            notes.set(j + 1, n);
        }
    }

    public Note top() {
        return notes.get(notes.size() - 1);
    }

    public Note bottom() {
        return notes.get(0);
    }

    @Override
    public Chord copy() {
        Chord c = new Chord();
        copyInto(c);
        c.stemDir = stemDir;
        c.graceKind = graceKind;
        for (Note n : notes) c.notes.add(n.copy());
        for (Chord g : graces) c.graces.add(g.copy());
        return c;
    }

    /** Ornaments, lyrics and the like, and grace notes: what only the first piece of a split note keeps. */
    @Override
    public void clearMarkings() {
        super.clearMarkings();
        graces.clear();
    }

    /** A grace chord of the given kind and type with one note. */
    public static Chord grace(int kind, int type, Note n) {
        Chord g = new Chord();
        g.graceKind = kind;
        g.type = type;
        if (n != null) g.notes.add(n);
        return g;
    }
}
