package io.github.vlesk.stave.io;

import io.github.vlesk.stave.model.Articulation;
import io.github.vlesk.stave.model.Chord;
import io.github.vlesk.stave.model.ChordRest;
import io.github.vlesk.stave.model.ChordSymbols;
import io.github.vlesk.stave.model.Clef;
import io.github.vlesk.stave.model.Duration;
import io.github.vlesk.stave.model.Measure;
import io.github.vlesk.stave.model.Note;
import io.github.vlesk.stave.model.Part;
import io.github.vlesk.stave.model.Pitch;
import io.github.vlesk.stave.model.Rest;
import io.github.vlesk.stave.model.Score;
import io.github.vlesk.stave.model.Spanners;
import io.github.vlesk.stave.model.Syllables;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Voice;

import java.util.HashMap;
import java.util.Map;

/** MusicXML 4.0 (partwise) export for exchange with other notation programs. */
public final class MusicXmlWriter {
    private MusicXmlWriter() {}

    private static final String[][] KINDS = {
            {"", "major"}, {"maj", "major"}, {"m", "minor"}, {"min", "minor"}, {"-", "minor"}, {"7", "dominant"},
            {"maj7", "major-seventh"}, {"ma7", "major-seventh"}, {"m7", "minor-seventh"}, {"min7", "minor-seventh"},
            {"dim", "diminished"}, {"o", "diminished"}, {"dim7", "diminished-seventh"}, {"o7", "diminished-seventh"},
            {"aug", "augmented"}, {"+", "augmented"}, {"m7b5", "half-diminished"}, {"ø", "half-diminished"},
            {"sus4", "suspended-fourth"}, {"sus2", "suspended-second"}, {"sus", "suspended-fourth"}, {"6", "major-sixth"},
            {"m6", "minor-sixth"}, {"9", "dominant-ninth"}, {"maj9", "major-ninth"}, {"m9", "minor-ninth"},
            {"11", "dominant-11th"}, {"13", "dominant-13th"}, {"mmaj7", "major-minor"}, {"5", "power"}};

    /** Where slurs end and hairpins stop (MusicXML marks both at the end). */
    private static final class Lines {
        final java.util.Set<ChordRest> slurEnds = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ChordRest, Boolean>());
        final Map<String, Integer> wedgeStops = new HashMap<String, Integer>();

        Lines(Score s) {
            for (Spanners.Span sp : Spanners.all(s)) {
                if (sp.kind == 0) {
                    if (sp.end != null) slurEnds.add(sp.end);
                } else {
                    String k = sp.endMeasure + "/" + sp.staff + "/" + sp.voice + "/" + sp.endTickInMeasure;
                    Integer n = wedgeStops.get(k);
                    wedgeStops.put(k, n == null ? 1 : n + 1);
                }
            }
        }
    }

    private static void wedgeStops(Xml x, Lines lines, int m, int st, int v, int tick, int staffNo, int nst) {
        Integer n = lines.wedgeStops.get(m + "/" + st + "/" + v + "/" + tick);
        if (n == null) return;
        for (int k = 0; k < n; k++) {
            x.open("direction");
            x.open("direction-type");
            x.empty("wedge", "type", "stop", "number", "1");
            x.close();
            if (nst > 1) x.tag("staff", staffNo);
            x.close();
        }
    }

    public static String write(Score s) {
        Lines lines = new Lines(s);
        Xml x = new Xml();
        x.raw("<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" \"http://www.musicxml.org/dtds/partwise.dtd\">\n");
        x.open("score-partwise", "version", "4.0");
        x.open("work");
        x.tag("work-title", s.title);
        x.close();
        x.open("identification");
        if (!s.composer.isEmpty()) x.tag("creator", s.composer, "type", "composer");
        if (!s.lyricist.isEmpty()) x.tag("creator", s.lyricist, "type", "lyricist");
        if (!s.copyright.isEmpty()) x.tag("rights", s.copyright);
        x.open("encoding");
        x.tag("software", "Stave for Android");
        x.close();
        x.close();
        x.open("part-list");
        for (int p = 0; p < s.parts.size(); p++) {
            Part part = s.parts.get(p);
            String id = "P" + (p + 1);
            x.open("score-part", "id", id);
            x.tag("part-name", part.name);
            if (part.shortName != null && !part.shortName.isEmpty()) x.tag("part-abbreviation", part.shortName);
            x.open("score-instrument", "id", id + "-I1");
            x.tag("instrument-name", part.name);
            x.close();
            x.open("midi-instrument", "id", id + "-I1");
            x.tag("midi-channel", part.drums ? 10 : Math.min(16, p + 1 >= 10 ? p + 2 : p + 1));
            x.tag("midi-program", part.program + 1);
            x.tag("volume", String.valueOf(Math.round(part.volume / 127.0 * 100)));
            x.close();
            x.close();
        }
        x.close();

        for (int p = 0; p < s.parts.size(); p++) {
            Part part = s.parts.get(p);
            int first = s.firstStaffOf(part);
            int nst = part.staves.size();
            x.open("part", "id", "P" + (p + 1));
            int prevN = -1, prevD = -1, prevKey = 99;
            for (int m = 0; m < s.measures.size(); m++) {
                Measure mm = s.measures.get(m);
                String num = mm.irregular && m == 0 ? "0" : String.valueOf(s.measureNumber(m));
                if (mm.irregular) x.open("measure", "number", num, "implicit", "yes");
                else x.open("measure", "number", num);
                if (mm.startRepeat) {
                    x.open("barline", "location", "left");
                    x.tag("bar-style", "heavy-light");
                    x.empty("repeat", "direction", "forward");
                    x.close();
                }
                boolean attr = m == 0 || mm.timeN != prevN || mm.timeD != prevD || mm.keyFifths != prevKey;
                boolean clefChange = false;
                for (int si = 0; si < nst; si++) if (mm.staves.get(first + si).clef >= 0) clefChange = true;
                if (attr || clefChange) {
                    x.open("attributes");
                    if (m == 0) x.tag("divisions", Duration.DIVISION);
                    if (m == 0 || mm.keyFifths != prevKey) {
                        x.open("key");
                        x.tag("fifths", mm.keyFifths);
                        x.close();
                    }
                    if (m == 0 || mm.timeN != prevN || mm.timeD != prevD) {
                        if (mm.timeSymbol) x.open("time", "symbol", mm.timeD == 2 ? "cut" : "common");
                        else x.open("time");
                        x.tag("beats", mm.timeN);
                        x.tag("beat-type", mm.timeD);
                        x.close();
                    }
                    if (m == 0 && nst > 1) x.tag("staves", nst);
                    for (int si = 0; si < nst; si++) {
                        int c = m == 0 ? s.clefAt(first + si, 0) : mm.staves.get(first + si).clef;
                        if (c < 0) continue;
                        if (nst > 1) x.open("clef", "number", String.valueOf(si + 1));
                        else x.open("clef");
                        x.tag("sign", Clef.musicXmlSign(c));
                        if (c != Clef.PERC) x.tag("line", Clef.musicXmlLine(c));
                        if (Clef.musicXmlOctave(c) != 0) x.tag("clef-octave-change", Clef.musicXmlOctave(c));
                        x.close();
                    }
                    x.close();
                }
                prevN = mm.timeN;
                prevD = mm.timeD;
                prevKey = mm.keyFifths;
                if (p == 0 && mm.rehearsal != null) {
                    x.open("direction", "placement", "above");
                    x.open("direction-type");
                    x.tag("rehearsal", mm.rehearsal);
                    x.close();
                    x.close();
                }
                if (p == 0 && mm.tempo > 0) {
                    x.open("direction", "placement", "above");
                    if (mm.tempoText != null && !mm.tempoText.isEmpty()) {
                        x.open("direction-type");
                        x.tag("words", mm.tempoText, "font-weight", "bold");
                        x.close();
                    }
                    x.open("direction-type");
                    x.open("metronome");
                    x.tag("beat-unit", "quarter");
                    x.tag("per-minute", mm.tempo);
                    x.close();
                    x.close();
                    x.empty("sound", "tempo", String.valueOf(mm.tempo));
                    x.close();
                }
                int pos = 0;
                for (int si = 0; si < nst; si++) {
                    StaffMeasure sm = mm.staves.get(first + si);
                    for (int v = 0; v < StaffMeasure.VOICES; v++) {
                        Voice voice = sm.voices[v];
                        if (voice.isEmpty()) continue;
                        if (pos > 0) {
                            x.open("backup");
                            x.tag("duration", pos);
                            x.close();
                            pos = 0;
                        }
                        writeVoice(x, s, voice, m, first + si, v, si + 1, nst, si * 4 + v + 1, lines);
                        pos = voice.totalTicks();
                    }
                }
                if (mm.endBarline != Measure.BAR_NORMAL) {
                    x.open("barline", "location", "right");
                    switch (mm.endBarline) {
                        case Measure.BAR_DOUBLE:
                            x.tag("bar-style", "light-light");
                            break;
                        case Measure.BAR_DASHED:
                            x.tag("bar-style", "dashed");
                            break;
                        default:
                            x.tag("bar-style", "light-heavy");
                    }
                    if (mm.endBarline == Measure.BAR_END_REPEAT) x.empty("repeat", "direction", "backward");
                    x.close();
                }
                x.close(); // measure
            }
            x.close(); // part
        }
        x.close();
        return x.toString();
    }

    private static boolean tiedFromPrevious(Score s, int m, int st, int v, int idx, int pitch) {
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

    private static void writeVoice(Xml x, Score s, Voice voice, int m, int st, int v, int staffNo, int nst, int voiceNo, Lines lines) {
        int[] vstarts = voice.starts();
        for (int i = 0; i < voice.items.size(); i++) {
            ChordRest cr = voice.items.get(i);
            wedgeStops(x, lines, m, st, v, vstarts[i], staffNo, nst);
            if (cr.hairpin != 0 && cr.hairpinTicks > 0) {
                x.open("direction", "placement", "below");
                x.open("direction-type");
                x.empty("wedge", "type", cr.hairpin == ChordRest.DIMINUENDO ? "diminuendo" : "crescendo", "number", "1");
                x.close();
                if (nst > 1) x.tag("staff", staffNo);
                x.close();
            }
            if (cr.dynamic != null) {
                x.open("direction", "placement", "below");
                x.open("direction-type");
                x.open("dynamics");
                x.empty(cr.dynamic);
                x.close();
                x.close();
                if (nst > 1) x.tag("staff", staffNo);
                x.close();
            }
            if (cr.staffText != null) {
                x.open("direction", "placement", "above");
                x.open("direction-type");
                x.tag("words", cr.staffText);
                x.close();
                if (nst > 1) x.tag("staff", staffNo);
                x.close();
            }
            if (cr.chordSymbol != null) {
                ChordSymbols.Parsed p = ChordSymbols.parse(cr.chordSymbol);
                if (p != null) {
                    x.open("harmony");
                    x.open("root");
                    x.tag("root-step", Pitch.stepName(Pitch.step(p.rootTpc)));
                    if (Pitch.alter(p.rootTpc) != 0) x.tag("root-alter", Pitch.alter(p.rootTpc));
                    x.close();
                    String kind = "other";
                    for (String[] k : KINDS) if (k[0].equals(p.quality)) kind = k[1];
                    x.tag("kind", kind, "text", p.quality);
                    if (Pitch.validTpc(p.bassTpc)) {
                        x.open("bass");
                        x.tag("bass-step", Pitch.stepName(Pitch.step(p.bassTpc)));
                        if (Pitch.alter(p.bassTpc) != 0) x.tag("bass-alter", Pitch.alter(p.bassTpc));
                        x.close();
                    }
                    if (nst > 1) x.tag("staff", staffNo);
                    x.close();
                }
            }
            boolean tupletStart = cr.tuplet != null && (i == 0 || voice.items.get(i - 1).tuplet != cr.tuplet);
            boolean tupletStop = cr.tuplet != null && (i == voice.items.size() - 1 || voice.items.get(i + 1).tuplet != cr.tuplet);
            if (cr instanceof Rest) {
                Rest r = (Rest) cr;
                x.open("note");
                if (r.measureRest) x.empty("rest", "measure", "yes");
                else x.empty("rest");
                x.tag("duration", r.ticks());
                x.tag("voice", voiceNo);
                if (!r.measureRest) {
                    x.tag("type", Duration.musicXmlName(r.type));
                    for (int d = 0; d < r.dots; d++) x.empty("dot");
                }
                timeMod(x, cr);
                if (nst > 1) x.tag("staff", staffNo);
                if (tupletStart || tupletStop || cr.articulations.contains(Articulation.FERMATA)) {
                    x.open("notations");
                    if (tupletStart) x.empty("tuplet", "type", "start", "bracket", "yes");
                    if (tupletStop) x.empty("tuplet", "type", "stop");
                    if (cr.articulations.contains(Articulation.FERMATA)) x.empty("fermata", "type", "upright");
                    x.close();
                }
                x.close();
                continue;
            }
            Chord c = (Chord) cr;
            for (Chord g : c.graces) {
                for (int ni = 0; ni < g.notes.size(); ni++) {
                    Note n = g.notes.get(ni);
                    x.open("note");
                    if (g.graceKind == Chord.ACCIACCATURA) x.empty("grace", "slash", "yes");
                    else x.empty("grace");
                    if (ni > 0) x.empty("chord");
                    x.open("pitch");
                    x.tag("step", Pitch.stepName(Pitch.step(n.tpc)));
                    if (Pitch.alter(n.tpc) != 0) x.tag("alter", Pitch.alter(n.tpc));
                    x.tag("octave", Pitch.octave(n.pitch, n.tpc));
                    x.close();
                    x.tag("voice", voiceNo);
                    x.tag("type", Duration.musicXmlName(g.type));
                    x.tag("stem", "up");
                    if (nst > 1) x.tag("staff", staffNo);
                    x.close();
                }
            }
            for (int ni = 0; ni < c.notes.size(); ni++) {
                Note n = c.notes.get(ni);
                boolean tieStop = tiedFromPrevious(s, m, st, v, i, n.pitch);
                x.open("note");
                if (ni > 0) x.empty("chord");
                x.open("pitch");
                x.tag("step", Pitch.stepName(Pitch.step(n.tpc)));
                if (Pitch.alter(n.tpc) != 0) x.tag("alter", Pitch.alter(n.tpc));
                x.tag("octave", Pitch.octave(n.pitch, n.tpc));
                x.close();
                x.tag("duration", c.ticks());
                if (tieStop) x.empty("tie", "type", "stop");
                if (n.tieForward) x.empty("tie", "type", "start");
                x.tag("voice", voiceNo);
                x.tag("type", Duration.musicXmlName(c.type));
                for (int d = 0; d < c.dots; d++) x.empty("dot");
                timeMod(x, cr);
                if (nst > 1) x.tag("staff", staffNo);
                boolean arts = false;
                for (String a : c.articulations) {
                    if (!a.equals(Articulation.FERMATA) && Articulation.index(a) >= 0) arts = true;
                }
                boolean fermata = c.articulations.contains(Articulation.FERMATA);
                boolean slurStop = lines.slurEnds.contains(c), slurStart = c.slurTicks > 0;
                if (tieStop || n.tieForward || (ni == 0 && (arts || fermata || tupletStart || tupletStop || slurStart || slurStop))) {
                    x.open("notations");
                    if (tieStop) x.empty("tied", "type", "stop");
                    if (n.tieForward) x.empty("tied", "type", "start");
                    if (ni == 0) {
                        if (tupletStart) x.empty("tuplet", "type", "start", "bracket", "yes");
                        if (tupletStop) x.empty("tuplet", "type", "stop");
                        if (slurStop) x.empty("slur", "type", "stop", "number", "1");
                        if (slurStart) x.empty("slur", "type", "start", "number", "1");
                        if (fermata) x.empty("fermata", "type", "upright");
                        StringBuilder artic = new StringBuilder();
                        StringBuilder orn = new StringBuilder();
                        for (String a : c.articulations) {
                            if (a.equals(Articulation.STACCATO)) artic.append("<staccato/>");
                            else if (a.equals(Articulation.ACCENT)) artic.append("<accent/>");
                            else if (a.equals(Articulation.TENUTO)) artic.append("<tenuto/>");
                            else if (a.equals(Articulation.MARCATO)) artic.append("<strong-accent type=\"up\"/>");
                            else if (a.equals(Articulation.STACCATISSIMO)) artic.append("<staccatissimo/>");
                            else if (a.equals(Articulation.TRILL)) orn.append("<trill-mark/>");
                            else if (a.equals(Articulation.MORDENT)) orn.append("<mordent/>");
                            else if (a.equals(Articulation.TURN)) orn.append("<turn/>");
                        }
                        if (artic.length() > 0) x.tagRaw("articulations", artic.toString());
                        if (orn.length() > 0) x.tagRaw("ornaments", orn.toString());
                    }
                    x.close();
                }
                if (ni == 0 && c.lyric != null && !Syllables.text(c.lyric).isEmpty()) {
                    String txt = Syllables.text(c.lyric);
                    String syl = Syllables.syllabic(s, m, st, v, i);
                    x.open("lyric", "number", "1");
                    x.tag("syllabic", syl);
                    x.tag("text", txt);
                    if (c.melisma > 0) x.empty("extend"); // as MuseScore exports a melisma
                    x.close();
                }
                x.close();
            }
        }
        wedgeStops(x, lines, m, st, v, s.measures.get(m).ticks(), staffNo, nst);
    }

    private static void timeMod(Xml x, ChordRest cr) {
        if (cr.tuplet == null) return;
        x.open("time-modification");
        x.tag("actual-notes", cr.tuplet.actual);
        x.tag("normal-notes", cr.tuplet.normal);
        x.close();
    }
}
