package io.github.vlesk.stave.audio;

import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.Dynamics;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.Pos;
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.Spanners;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Voice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Unrolls the score (including simple repeats) into timed note events and provides the
 * mapping between playback time and score positions for the playback cursor.
 */
public final class Timeline {
    public static final class NoteEvent {
        public int startTick, lengthTicks, pitch, velocity, channel, track;
    }

    /** One played occurrence of a measure. */
    public static final class MeasurePlay {
        public int measure;
        public int startTick;
        public int ticks;
        public double startMs;
        public double msPerTick;
        public int tempo;
    }

    /** How much a hairpin changes the velocity over its length (about one dynamic level). */
    private static final int HAIRPIN_RANGE = 20;

    public final List<NoteEvent> events = new ArrayList<NoteEvent>();
    public final List<MeasurePlay> plays = new ArrayList<MeasurePlay>();
    public int[] partChannel;
    public double totalMs;
    public int totalTicks;

    public static List<Integer> playOrder(Score s) {
        List<Integer> order = new ArrayList<Integer>();
        int repeatStart = 0;
        boolean[] repeated = new boolean[s.measures.size()];
        int i = 0;
        int guard = 0;
        while (i < s.measures.size() && guard++ < 10000) {
            Measure m = s.measures.get(i);
            if (m.startRepeat) repeatStart = i;
            order.add(i);
            if (m.endBarline == Measure.BAR_END_REPEAT && !repeated[i]) {
                repeated[i] = true;
                i = repeatStart;
                continue;
            }
            if (m.endBarline == Measure.BAR_END_REPEAT) repeatStart = i + 1;
            i++;
        }
        return order;
    }

    public static Timeline build(Score s) {
        Timeline tl = new Timeline();
        // channels
        tl.partChannel = new int[s.parts.size()];
        int ch = 0;
        for (int p = 0; p < s.parts.size(); p++) {
            if (s.parts.get(p).drums) {
                tl.partChannel[p] = 9;
            } else {
                if (ch == 9) ch++;
                tl.partChannel[p] = ch % 16 == 9 ? 10 : ch % 16;
                ch++;
            }
        }
        List<Integer> order = playOrder(s);
        int tick = 0;
        double ms = 0;
        int tempo = s.tempoAt(0);
        for (int idx : order) {
            Measure m = s.measures.get(idx);
            if (m.tempo > 0) tempo = m.tempo;
            MeasurePlay mp = new MeasurePlay();
            mp.measure = idx;
            mp.startTick = tick;
            mp.ticks = m.ticks();
            mp.startMs = ms;
            mp.tempo = tempo;
            mp.msPerTick = 60000.0 / tempo / 480.0;
            tl.plays.add(mp);
            tick += mp.ticks;
            ms += mp.ticks * mp.msPerTick;
        }
        tl.totalTicks = tick;
        tl.totalMs = ms;

        int staffCount = s.staffCount();
        // slurs make their notes legato; hairpins swell or fade the velocity over their length
        List<Spanners.Span> spans = Spanners.all(s);
        int[] measureBase = new int[s.measures.size()];
        for (int m = 1; m < measureBase.length; m++) measureBase[m] = measureBase[m - 1] + s.measures.get(m - 1).ticks();
        for (int st = 0; st < staffCount; st++) {
            Part part = s.partOfStaff(st);
            int partIdx = s.parts.indexOf(part);
            if (part.muted) continue;
            int level = 80;
            for (MeasurePlay mp : tl.plays) {
                Measure m = s.measures.get(mp.measure);
                StaffMeasure sm = m.staves.get(st);
                // dynamics from any voice affect the whole staff
                List<int[]> dyn = new ArrayList<int[]>();
                for (Voice v : sm.voices) {
                    int[] stt = v.starts();
                    for (int i = 0; i < v.items.size(); i++) {
                        ChordRest cr = v.items.get(i);
                        if (cr.dynamic != null) dyn.add(new int[]{stt[i], Dynamics.velocity(cr.dynamic), Dynamics.isAccent(cr.dynamic) ? 1 : 0});
                    }
                }
                for (int vi = 0; vi < StaffMeasure.VOICES; vi++) {
                    Voice v = sm.voices[vi];
                    int[] stt = v.starts();
                    for (int i = 0; i < v.items.size(); i++) {
                        ChordRest cr = v.items.get(i);
                        int vel = level;
                        boolean accentDyn = false;
                        for (int[] d : dyn) {
                            if (d[0] <= stt[i]) {
                                if (d[2] == 0) vel = d[1];
                                else if (d[0] == stt[i]) {
                                    vel = d[1];
                                    accentDyn = true;
                                }
                            }
                        }
                        if (!(cr instanceof Chord)) continue;
                        Chord c = (Chord) cr;
                        if (c.articulations.contains(Articulation.ACCENT)) vel += 20;
                        if (c.articulations.contains(Articulation.MARCATO)) vel += 30;
                        int scoreTick = measureBase[mp.measure] + stt[i];
                        boolean slurred = false;
                        for (Spanners.Span sp : spans) {
                            if (sp.staff != st || scoreTick < sp.startTick || scoreTick >= sp.endTick) continue;
                            if (sp.kind == 0) {
                                if (sp.voice == vi) slurred = true;
                            } else {
                                double f = (scoreTick - sp.startTick) / (double) Math.max(1, sp.endTick - sp.startTick);
                                vel += (int) Math.round((sp.kind == ChordRest.CRESCENDO ? 1 : -1) * HAIRPIN_RANGE * f);
                            }
                        }
                        vel = Math.max(1, Math.min(127, vel));
                        double gate = slurred ? 1.0 : 0.95;
                        if (c.articulations.contains(Articulation.STACCATO)) gate = 0.5;
                        if (c.articulations.contains(Articulation.STACCATISSIMO)) gate = 0.3;
                        if (c.articulations.contains(Articulation.TENUTO)) gate = 1.0;
                        int fermataExtra = c.articulations.contains(Articulation.FERMATA) ? c.ticks() : 0;
                        // grace notes take their time from the main note: an acciaccatura a moment
                        // (a 32nd each, at most half), an appoggiatura half of it
                        int steal = 0;
                        if (!c.graces.isEmpty()) {
                            int ng = c.graces.size();
                            boolean acc = c.graces.get(0).graceKind == Chord.ACCIACCATURA;
                            steal = acc ? Math.min(60 * ng, c.ticks() / 2) : c.ticks() / 2;
                            int each = Math.max(1, steal / ng);
                            for (int k = 0; k < ng; k++) {
                                for (Note gn : c.graces.get(k).notes) {
                                    NoteEvent ge = new NoteEvent();
                                    ge.startTick = mp.startTick + stt[i] + k * each;
                                    ge.lengthTicks = Math.max(10, (int) (each * 0.95));
                                    ge.pitch = gn.pitch;
                                    ge.velocity = vel;
                                    ge.channel = tl.partChannel[partIdx];
                                    ge.track = partIdx;
                                    tl.events.add(ge);
                                }
                            }
                        }
                        for (Note n : c.notes) {
                            if (isTieContinuation(s, mp.measure, st, vi, stt[i], n.pitch)) continue;
                            int len = c.ticks() + tiedLength(s, mp.measure, st, vi, stt[i] + c.ticks(), n);
                            NoteEvent e = new NoteEvent();
                            e.startTick = mp.startTick + stt[i] + steal;
                            e.lengthTicks = Math.max(10, (int) ((len - steal) * gate) + fermataExtra);
                            e.pitch = n.pitch;
                            e.velocity = vel;
                            e.channel = tl.partChannel[partIdx];
                            e.track = partIdx;
                            tl.events.add(e);
                        }
                    }
                }
                // running level continues from the last non-accent dynamic of the measure
                for (int[] d : dyn) if (d[2] == 0) level = d[1];
                // a hairpin that ends in this measure without a dynamic after it leaves the level changed
                int mStart = measureBase[mp.measure], mEnd = mStart + mp.ticks;
                for (Spanners.Span sp : spans) {
                    if (sp.kind == 0 || sp.staff != st || sp.endTick <= mStart || sp.endTick > mEnd) continue;
                    if (sp.endMeasure < s.measures.size() && sp.endTickInMeasure < s.measures.get(sp.endMeasure).ticks()) {
                        ChordRest atEnd = ScoreEdit.elementAt(s, new Pos(sp.endMeasure, st, 0, sp.endTickInMeasure));
                        if (atEnd != null && atEnd.dynamic != null) continue;
                    }
                    level = Math.max(1, Math.min(127, level + (sp.kind == ChordRest.CRESCENDO ? HAIRPIN_RANGE : -HAIRPIN_RANGE)));
                }
            }
        }
        Collections.sort(tl.events, new Comparator<NoteEvent>() {
            @Override
            public int compare(NoteEvent a, NoteEvent b) {
                return a.startTick != b.startTick ? (a.startTick < b.startTick ? -1 : 1) : 0;
            }
        });
        return tl;
    }

    /** True if the previous chord in this voice ties into this pitch. */
    private static boolean isTieContinuation(Score s, int measure, int staff, int voice, int tick, int pitch) {
        Voice v = s.measures.get(measure).staves.get(staff).voices[voice];
        int[] st = v.starts();
        int idx = v.indexAt(tick);
        ChordRest prev = null;
        if (idx > 0) prev = v.items.get(idx - 1);
        else if (idx == 0 && measure > 0) {
            Voice pv = s.measures.get(measure - 1).staves.get(staff).voices[voice];
            if (!pv.isEmpty()) prev = pv.items.get(pv.items.size() - 1);
        }
        if (!(prev instanceof Chord)) return false;
        for (Note n : ((Chord) prev).notes) if (n.pitch == pitch && n.tieForward) return true;
        return false;
    }

    /** Extra length contributed by tied continuation notes after a note. */
    private static int tiedLength(Score s, int measure, int staff, int voice, int endTick, Note n) {
        int extra = 0;
        Note cur = n;
        int m = measure, t = endTick;
        int guard = 0;
        while (cur.tieForward && guard++ < 64) {
            while (m < s.measures.size() && t >= s.measures.get(m).ticks()) {
                t -= s.measures.get(m).ticks();
                m++;
            }
            if (m >= s.measures.size()) break;
            Voice v = s.measures.get(m).staves.get(staff).voices[voice];
            int idx = v.indexAt(t);
            if (idx < 0 || !(v.items.get(idx) instanceof Chord)) break;
            Chord c = (Chord) v.items.get(idx);
            Note found = null;
            for (Note o : c.notes) if (o.pitch == cur.pitch) found = o;
            if (found == null) break;
            extra += c.ticks();
            t += c.ticks();
            cur = found;
        }
        return extra;
    }

    /** Playback time in ms at which a score position is first played. */
    public double msAt(int measure, int tick) {
        for (MeasurePlay mp : plays) {
            if (mp.measure == measure) return mp.startMs + tick * mp.msPerTick;
        }
        return 0;
    }

    /** Score position {measure, tick} being played at a time, or null after the end. */
    public int[] positionAt(double ms) {
        for (int i = plays.size() - 1; i >= 0; i--) {
            MeasurePlay mp = plays.get(i);
            if (ms >= mp.startMs) {
                int t = (int) ((ms - mp.startMs) / mp.msPerTick);
                if (t >= mp.ticks) {
                    if (i == plays.size() - 1) return null;
                    t = mp.ticks - 1;
                }
                return new int[]{mp.measure, t};
            }
        }
        return plays.isEmpty() ? null : new int[]{plays.get(0).measure, 0};
    }
}
