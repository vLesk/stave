package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Slurs and hairpins. Like MuseScore's spanners they are tick based: each is stored on the element
 * where it starts as a length in ticks (see {@link ChordRest#slurTicks} and
 * {@link ChordRest#hairpinTicks}), so copies, splits and edits keep them consistent.
 */
public final class Spanners {
    private Spanners() {
    }

    /** One slur or hairpin, resolved to positions. Ticks are counted from the start of the score. */
    public static final class Span {
        public int staff, voice, kind;
        /** Start element and its position. */
        public ChordRest start;
        public int measure, tick, startTick;
        /** Slur: start of its last chord. Hairpin: where it ends (end of its last note). */
        public int endTick;
        /** Slur: its last chord, if it exists. */
        public ChordRest end;
        public int endMeasure, endTickInMeasure;
    }

    /** {measure, tick} of a tick counted from the start of the score (clamped to the end of the score). */
    public static int[] posOf(Score s, int global) {
        int t = global;
        for (int m = 0; m < s.measures.size(); m++) {
            int len = s.measures.get(m).ticks();
            if (t < len) return new int[]{m, Math.max(0, t)};
            t -= len;
        }
        int last = s.measures.size() - 1;
        return new int[]{last, s.measures.get(last).ticks()};
    }

    /** All slurs (kind 0) and hairpins (kind CRESCENDO / DIMINUENDO) of the score, in score order. */
    public static List<Span> all(Score s) {
        List<Span> out = new ArrayList<Span>();
        int base = 0;
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            for (int st = 0; st < mm.staves.size(); st++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = mm.staves.get(st).voices[v];
                    int[] starts = voice.starts();
                    for (int i = 0; i < voice.items.size(); i++) {
                        ChordRest cr = voice.items.get(i);
                        if (cr.slurTicks > 0 && cr instanceof Chord) out.add(span(s, cr, 0, cr.slurTicks, m, st, v, base, starts[i]));
                        if (cr.hairpin != 0 && cr.hairpinTicks > 0) out.add(span(s, cr, cr.hairpin, cr.hairpinTicks, m, st, v, base, starts[i]));
                    }
                }
            }
            base += mm.ticks();
        }
        return out;
    }

    private static Span span(Score s, ChordRest cr, int kind, int len, int m, int st, int v, int base, int tick) {
        Span sp = new Span();
        sp.kind = kind;
        sp.start = cr;
        sp.staff = st;
        sp.voice = v;
        sp.measure = m;
        sp.tick = tick;
        sp.startTick = base + tick;
        sp.endTick = sp.startTick + len;
        int[] e = posOf(s, sp.endTick);
        sp.endMeasure = e[0];
        sp.endTickInMeasure = e[1];
        if (kind == 0) {
            ChordRest end = ScoreEdit.elementAt(s, new Pos(e[0], st, v, e[1]));
            sp.end = end instanceof Chord ? end : null;
        }
        return sp;
    }
}
