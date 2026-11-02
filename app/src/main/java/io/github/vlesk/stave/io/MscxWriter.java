package io.github.vlesk.stave.io;

import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.ChordSymbols;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Dynamics;
import io.github.vlesk.stave.model.Instruments;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Rest;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.Spanners;
import io.github.vlesk.stave.model.Syllables;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Tuplet;
import io.github.vlesk.stave.model.Voice;

import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * Writes scores in the MuseScore 4 .mscx format (version 4.20) so that they open in
 * MuseScore Studio on the desktop.
 */
public final class MscxWriter {
    private MscxWriter() {}

    public static final String VERSION = "4.20";

    /** Key for a tie target: identifies the note a tie arrives at. */
    private static String key(int measure, int staff, int voice, int tick, int pitch) {
        return measure + ":" + staff + ":" + voice + ":" + tick + ":" + pitch;
    }

    public static String write(Score s) {
        Xml x = new Xml();
        x.open("museScore", "version", VERSION);
        x.tag("programVersion", "4.2.0");
        x.tag("programRevision", "");
        x.open("Score");
        x.tag("Division", Duration.DIVISION);
        x.open("Style");
        x.tag("spatium", "1.75");
        x.close();
        x.tag("showInvisible", 1);
        x.tag("showUnprintable", 1);
        x.tag("showFrames", 1);
        x.tag("showMargins", 0);
        meta(x, "arranger", "");
        meta(x, "composer", s.composer);
        meta(x, "copyright", s.copyright);
        meta(x, "lyricist", s.lyricist);
        meta(x, "movementNumber", "");
        meta(x, "movementTitle", "");
        meta(x, "source", "");
        meta(x, "subtitle", s.subtitle);
        meta(x, "translator", "");
        meta(x, "workNumber", "");
        meta(x, "workTitle", s.title);

        int staffId = 1;
        for (int pi = 0; pi < s.parts.size(); pi++) {
            Part p = s.parts.get(pi);
            x.open("Part", "id", String.valueOf(pi + 1));
            for (int si = 0; si < p.staves.size(); si++) {
                x.open("Staff", "id", String.valueOf(staffId + si));
                if (p.drums) {
                    x.open("StaffType", "group", "percussion");
                    x.tag("name", "perc5Line");
                } else {
                    x.open("StaffType", "group", "pitched");
                    x.tag("name", "stdNormal");
                }
                x.close();
                if (si == 0 && p.staves.size() > 1) {
                    x.empty("bracket", "type", "1", "span", String.valueOf(p.staves.size()), "col", "0", "visible", "1");
                }
                if (si < p.staves.size() - 1) x.tag("barLineSpan", 1);
                if (p.staves.get(si).defaultClef != Clef.TREBLE) {
                    x.tag("defaultClef", Clef.mscxName(p.staves.get(si).defaultClef));
                }
                x.close();
            }
            x.tag("trackName", p.name);
            x.open("Instrument", "id", p.instrumentId != null ? p.instrumentId : "piano");
            x.tag("longName", p.name);
            x.tag("shortName", p.shortName != null ? p.shortName : "");
            x.tag("trackName", p.name);
            Instruments.Def def = Instruments.byId(p.instrumentId);
            if (def != null) x.tag("instrumentId", def.musicXmlId);
            if (p.drums) x.tag("useDrumset", 1);
            for (int si = 0; si < p.staves.size(); si++) {
                int c = p.staves.get(si).defaultClef;
                if (si == 0) {
                    if (c != Clef.TREBLE) x.tag("clef", Clef.mscxName(c));
                } else {
                    x.tag("clef", Clef.mscxName(c), "staff", String.valueOf(si + 1));
                }
            }
            x.open("Channel");
            if (p.drums) x.empty("controller", "ctrl", "0", "value", "1");
            x.empty("program", "value", String.valueOf(p.program));
            x.empty("controller", "ctrl", "7", "value", String.valueOf(p.volume));
            x.empty("controller", "ctrl", "10", "value", String.valueOf(p.pan));
            if (p.muted) x.tag("mute", 1);
            x.close();
            x.close(); // Instrument
            x.close(); // Part
            staffId += p.staves.size();
        }

        // Pre-compute tie targets so that the arriving note can write its <prev> location.
        Map<String, int[]> tieArrivals = new HashMap<String, int[]>();
        Map<String, List<int[]>> spanEnds = spanEnds(s);
        for (int m = 0; m < s.measures.size(); m++) {
            for (int st = 0; st < s.staffCount(); st++) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    Voice voice = s.measures.get(m).staves.get(st).voices[v];
                    int[] starts = voice.starts();
                    for (int i = 0; i < voice.items.size(); i++) {
                        ChordRest cr = voice.items.get(i);
                        if (!(cr instanceof Chord)) continue;
                        Chord c = (Chord) cr;
                        int[] target = tieTarget(s, m, st, v, starts[i] + c.ticks());
                        if (target == null) continue;
                        Chord nc = (Chord) s.measures.get(target[0]).staves.get(st).voices[v]
                                .items.get(s.measures.get(target[0]).staves.get(st).voices[v].indexAt(target[1]));
                        for (int ni = 0; ni < c.notes.size(); ni++) {
                            Note n = c.notes.get(ni);
                            if (!n.tieForward) continue;
                            for (int nj = 0; nj < nc.notes.size(); nj++) {
                                if (nc.notes.get(nj).pitch == n.pitch) {
                                    tieArrivals.put(key(target[0], st, v, target[1], n.pitch),
                                            new int[]{m - target[0], starts[i] - target[1], ni - nj});
                                }
                            }
                        }
                    }
                }
            }
        }

        staffId = 1;
        for (int st = 0; st < s.staffCount(); st++) {
            x.open("Staff", "id", String.valueOf(st + 1));
            if (st == 0 && (!isBlank(s.title) || !isBlank(s.composer) || !isBlank(s.subtitle) || !isBlank(s.lyricist))) {
                x.open("VBox");
                x.tag("height", 10);
                textEl(x, "title", s.title);
                textEl(x, "subtitle", s.subtitle);
                textEl(x, "composer", s.composer);
                textEl(x, "lyricist", s.lyricist);
                x.close();
            }
            int prevN = -1, prevD = -1, prevKey = Integer.MIN_VALUE;
            for (int m = 0; m < s.measures.size(); m++) {
                Measure mm = s.measures.get(m);
                StaffMeasure sm = mm.staves.get(st);
                if (mm.actualTicks > 0 && mm.actualTicks != mm.nominalTicks()) {
                    x.open("Measure", "len", Xml.ticksFraction(mm.actualTicks));
                } else {
                    x.open("Measure");
                }
                if (mm.irregular) x.tag("irregular", 1);
                if (mm.startRepeat) x.empty("startRepeat");
                if (mm.endBarline == Measure.BAR_END_REPEAT) x.tag("endRepeat", 2);
                int lastVoice = 0;
                for (int v = 0; v < StaffMeasure.VOICES; v++) if (!sm.voices[v].isEmpty()) lastVoice = v;
                for (int v = 0; v <= lastVoice; v++) {
                    Voice voice = sm.voices[v];
                    x.open("voice");
                    if (v == 0) {
                        int clef = m == 0 ? s.clefAt(st, 0) : sm.clef;
                        if (clef >= 0) {
                            x.open("Clef");
                            x.tag("concertClefType", Clef.mscxName(clef));
                            x.tag("transposingClefType", Clef.mscxName(clef));
                            x.close();
                        }
                        if (mm.keyFifths != prevKey) {
                            if (m > 0 || mm.keyFifths != 0) {
                                x.open("KeySig");
                                x.tag("concertKey", mm.keyFifths);
                                x.close();
                            }
                        }
                        if (mm.timeN != prevN || mm.timeD != prevD) {
                            x.open("TimeSig");
                            if (mm.timeSymbol && mm.timeN == 4 && mm.timeD == 4) x.tag("subtype", 1);
                            else if (mm.timeSymbol && mm.timeN == 2 && mm.timeD == 2) x.tag("subtype", 2);
                            x.tag("sigN", mm.timeN);
                            x.tag("sigD", mm.timeD);
                            x.close();
                        }
                        if (st == 0 && mm.rehearsal != null) {
                            x.open("RehearsalMark");
                            x.tag("text", mm.rehearsal);
                            x.close();
                        }
                        if (st == 0 && mm.tempo > 0) {
                            x.open("Tempo");
                            x.tag("tempo", formatDouble(mm.tempo / 60.0));
                            x.tag("followText", 1);
                            String txt = (mm.tempoText != null && !mm.tempoText.isEmpty() ? Xml.esc(mm.tempoText) + " " : "")
                                    + "<sym>metNoteQuarterUp</sym> = " + mm.tempo;
                            x.tagRaw("text", txt);
                            x.close();
                        }
                    }
                    writeVoice(x, s, voice, m, st, v, tieArrivals, spanEnds);
                    if (v == 0 && mm.endBarline != Measure.BAR_NORMAL && mm.endBarline != Measure.BAR_END_REPEAT) {
                        x.open("BarLine");
                        x.tag("subtype", Measure.BAR_MSCX[mm.endBarline]);
                        x.close();
                    }
                    x.close(); // voice
                }
                x.close(); // Measure
                prevN = mm.timeN;
                prevD = mm.timeD;
                prevKey = mm.keyFifths;
            }
            x.close(); // Staff
        }
        x.close(); // Score
        x.close(); // museScore
        return x.toString();
    }

    /** Returns {measure, tick} of the next element after a chord ending at endTick if it is a chord. */
    private static int[] tieTarget(Score s, int m, int st, int v, int endTick) {
        int mm = m, t = endTick;
        while (mm < s.measures.size() && t >= s.measures.get(mm).ticks()) {
            t -= s.measures.get(mm).ticks();
            mm++;
        }
        if (mm >= s.measures.size()) return null;
        Voice voice = s.measures.get(mm).staves.get(st).voices[v];
        int idx = voice.indexAt(t);
        if (idx < 0 || !(voice.items.get(idx) instanceof Chord)) return null;
        return new int[]{mm, t};
    }

    private static String spanKey(int m, int st, int v, int tick) {
        return m + "/" + st + "/" + v + "/" + tick;
    }

    /** Where slurs and hairpins end: MuseScore writes a &lt;Spanner&gt; with &lt;prev&gt; there. */
    private static Map<String, List<int[]>> spanEnds(Score s) {
        Map<String, List<int[]>> ends = new HashMap<String, List<int[]>>();
        for (Spanners.Span sp : Spanners.all(s)) {
            String k = spanKey(sp.endMeasure, sp.staff, sp.voice, sp.endTickInMeasure);
            List<int[]> l = ends.get(k);
            if (l == null) ends.put(k, l = new ArrayList<int[]>());
            l.add(new int[]{sp.kind, sp.measure, sp.tick});
        }
        return ends;
    }

    private static void writeSpanEnds(Xml x, Map<String, List<int[]>> spanEnds, int m, int st, int v, int tick) {
        List<int[]> l = spanEnds.get(spanKey(m, st, v, tick));
        if (l == null) return;
        for (int[] e : l) {
            x.open("Spanner", "type", e[0] == 0 ? "Slur" : "HairPin");
            x.open("prev");
            location(x, e[1] - m, e[2] - tick, 0);
            x.close();
            x.close();
        }
    }

    private static void writeVoice(Xml x, Score s, Voice voice, int m, int st, int v, Map<String, int[]> tieArrivals,
                                   Map<String, List<int[]>> spanEnds) {
        int[] starts = voice.starts();
        Tuplet open = null;
        for (int i = 0; i < voice.items.size(); i++) {
            ChordRest cr = voice.items.get(i);
            if (open != null && cr.tuplet != open) {
                x.empty("endTuplet");
                open = null;
            }
            writeSpanEnds(x, spanEnds, m, st, v, starts[i]);
            // slurs and hairpins starting here, as MuseScore writes them: <next><location> to their end
            if (cr.hairpin != 0 && cr.hairpinTicks > 0) {
                int[] e = Spanners.posOf(s, Syllables.globalTick(s, m, starts[i]) + cr.hairpinTicks);
                x.open("Spanner", "type", "HairPin");
                x.open("HairPin");
                x.tag("subtype", cr.hairpin == ChordRest.DIMINUENDO ? 1 : 0);
                x.close();
                x.open("next");
                location(x, e[0] - m, e[1] - starts[i], 0);
                x.close();
                x.close();
            }
            if (cr instanceof Chord && cr.slurTicks > 0) {
                int[] e = Spanners.posOf(s, Syllables.globalTick(s, m, starts[i]) + cr.slurTicks);
                x.open("Spanner", "type", "Slur");
                x.empty("Slur");
                x.open("next");
                location(x, e[0] - m, e[1] - starts[i], 0);
                x.close();
                x.close();
            }
            if (cr.tuplet != null && cr.tuplet != open) {
                open = cr.tuplet;
                x.open("Tuplet");
                x.tag("normalNotes", open.normal);
                x.tag("actualNotes", open.actual);
                x.tag("baseNote", Duration.xmlName(open.baseType));
                x.open("Number");
                x.tag("style", "tuplet");
                x.tag("text", String.valueOf(open.actual));
                x.close();
                x.close();
            }
            if (cr.dynamic != null) {
                x.open("Dynamic");
                x.tag("subtype", cr.dynamic);
                x.tag("velocity", Dynamics.velocity(cr.dynamic));
                x.close();
            }
            if (cr.chordSymbol != null) {
                ChordSymbols.Parsed p = ChordSymbols.parse(cr.chordSymbol);
                x.open("Harmony");
                if (p != null) {
                    x.tag("root", p.rootTpc);
                    if (p.quality != null && !p.quality.isEmpty()) x.tag("name", p.quality);
                    if (Pitch.validTpc(p.bassTpc)) x.tag("base", p.bassTpc);
                } else {
                    x.tag("name", cr.chordSymbol);
                }
                x.close();
            }
            if (cr.staffText != null) {
                x.open("StaffText");
                x.tag("text", cr.staffText);
                x.close();
            }
            if (cr.articulations.contains(Articulation.FERMATA)) {
                x.open("Fermata");
                x.tag("subtype", "fermataAbove");
                x.close();
            }
            if (cr instanceof Rest) {
                Rest r = (Rest) cr;
                x.open("Rest");
                if (r.measureRest) {
                    x.tag("durationType", "measure");
                    x.tag("duration", Xml.ticksFraction(r.measureTicks));
                } else {
                    if (r.dots > 0) x.tag("dots", r.dots);
                    x.tag("durationType", Duration.xmlName(r.type));
                }
                x.close();
            } else {
                Chord c = (Chord) cr;
                for (Chord g : c.graces) {
                    x.open("Chord");
                    x.tag("durationType", Duration.xmlName(g.type));
                    x.empty(g.graceKind == Chord.ACCIACCATURA ? "acciaccatura"
                            : g.type == Duration.N16 ? "grace16" : g.type == Duration.N32 ? "grace32" : g.type == Duration.QUARTER ? "grace4" : "appoggiatura");
                    for (Note gn : g.notes) {
                        x.open("Note");
                        x.tag("pitch", gn.pitch);
                        x.tag("tpc", gn.tpc);
                        x.close();
                    }
                    x.close();
                }
                x.open("Chord");
                if (c.dots > 0) x.tag("dots", c.dots);
                x.tag("durationType", Duration.xmlName(c.type));
                if (c.stemDir != Chord.STEM_AUTO) x.tag("StemDirection", c.stemDir == Chord.STEM_UP ? "up" : "down");
                if (c.lyric != null && !Syllables.text(c.lyric).isEmpty()) {
                    // as MuseScore writes it: <syllabic> (unless single) before the text, no hyphen in the text
                    x.open("Lyrics");
                    String syl = Syllables.syllabic(s, m, st, v, i);
                    if (!syl.equals("single")) x.tag("syllabic", syl);
                    if (c.melisma > 0) x.tag("ticks_f", Syllables.fraction(Syllables.melismaTicks(s, m, st, v, i)));
                    x.tag("text", Syllables.text(c.lyric));
                    x.close();
                }
                for (String a : c.articulations) {
                    String sub = Articulation.mscxSubtype(a);
                    if (sub == null) continue;
                    if (Articulation.FERMATA.equals(a)) continue; // written as a <Fermata> segment element
                    x.open("Articulation");
                    x.tag("subtype", sub);
                    x.close();
                }
                int[] tgt = tieTarget(s, m, st, v, starts[i] + c.ticks());
                for (int ni = 0; ni < c.notes.size(); ni++) {
                    Note n = c.notes.get(ni);
                    x.open("Note");
                    int[] arr = tieArrivals.get(key(m, st, v, starts[i], n.pitch));
                    if (arr != null) {
                        x.open("Spanner", "type", "Tie");
                        x.open("prev");
                        location(x, arr[0], arr[1], arr[2]);
                        x.close();
                        x.close();
                    }
                    if (n.tieForward && tgt != null) {
                        Voice tv = s.measures.get(tgt[0]).staves.get(st).voices[v];
                        Chord nc = (Chord) tv.items.get(tv.indexAt(tgt[1]));
                        int nj = -1;
                        for (int k = 0; k < nc.notes.size(); k++) if (nc.notes.get(k).pitch == n.pitch) nj = k;
                        if (nj >= 0) {
                            x.open("Spanner", "type", "Tie");
                            x.open("Tie");
                            x.close();
                            x.open("next");
                            location(x, tgt[0] - m, tgt[1] - starts[i], nj - ni);
                            x.close();
                            x.close();
                        }
                    }
                    x.tag("pitch", n.pitch);
                    x.tag("tpc", n.tpc);
                    x.close();
                }
                x.close();
            }
        }
        if (open != null) x.empty("endTuplet");
        writeSpanEnds(x, spanEnds, m, st, v, s.measures.get(m).ticks());
    }

    private static void location(Xml x, int measures, int ticks, int notes) {
        x.open("location");
        if (measures != 0) x.tag("measures", measures);
        if (ticks != 0) x.tag("fractions", Xml.ticksFraction(ticks));
        if (notes != 0) x.tag("notes", notes);
        x.close();
    }

    private static void meta(Xml x, String name, String value) {
        x.tag("metaTag", value == null ? "" : value, "name", name);
    }

    private static void textEl(Xml x, String style, String text) {
        if (isBlank(text)) return;
        x.open("Text");
        x.tag("style", style);
        x.tag("text", text);
        x.close();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String formatDouble(double d) {
        String s = String.valueOf(Math.round(d * 1000000.0) / 1000000.0);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }
}
