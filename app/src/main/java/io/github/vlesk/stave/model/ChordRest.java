package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/** Common base of chords and rests: a duration plus attached markings. */
public abstract class ChordRest {
    public int type = Duration.QUARTER;
    public int dots;
    public Tuplet tuplet;
    /** Dynamic marking placed at this position, e.g. "mf" (null = none). */
    public String dynamic;
    /** Chord symbol text placed above the staff, e.g. "Cmaj7" (null = none). */
    public String chordSymbol;
    /** Lyric syllable for verse 1 (null = none). */
    public String lyric;
    /**
     * Melisma (extender line, entered with "_" as in MuseScore): the number of notes the syllable is
     * held over, counting its own. 0 = none; 1 = only its own note (MuseScore's one-note melisma, drawn
     * as a short line); n = the line runs to the (n-1)-th following chord in the voice (rests skipped).
     */
    public int melisma;
    /** Articulation ids, see {@link Articulation}. */
    public final List<String> articulations = new ArrayList<String>();
    /** Free staff text above this position (expression / technique text). */
    public String staffText;
    /**
     * Slur starting here: ticks from this element to the start of the slur's last chord, in the
     * same staff and voice (MuseScore's tick2 - tick); 0 = no slur.
     */
    public int slurTicks;
    /** Hairpin starting here: CRESCENDO or DIMINUENDO (0 = none), and its length in ticks. */
    public int hairpin, hairpinTicks;
    public static final int CRESCENDO = 1, DIMINUENDO = 2;

    public int ticks() {
        int t = Duration.ticks(type, dots);
        return tuplet != null ? tuplet.scale(t) : t;
    }

    public abstract boolean isRest();

    public abstract ChordRest copy();

    protected void copyInto(ChordRest o) {
        o.type = type;
        o.dots = dots;
        o.tuplet = tuplet;
        o.dynamic = dynamic;
        o.chordSymbol = chordSymbol;
        o.lyric = lyric;
        o.melisma = melisma;
        o.articulations.addAll(articulations);
        o.staffText = staffText;
        o.slurTicks = slurTicks;
        o.hairpin = hairpin;
        o.hairpinTicks = hairpinTicks;
    }

    public boolean hasMarkings() {
        return dynamic != null || chordSymbol != null || lyric != null || !articulations.isEmpty() || staffText != null
                || slurTicks > 0 || hairpin != 0;
    }

    public void clearMarkings() {
        dynamic = null;
        chordSymbol = null;
        lyric = null;
        melisma = 0;
        staffText = null;
        slurTicks = 0;
        hairpin = 0;
        hairpinTicks = 0;
        articulations.clear();
    }
}
