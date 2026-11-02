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
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.Staff;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Syllables;
import io.github.vlesk.stave.model.Tuplet;
import io.github.vlesk.stave.model.Voice;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Reads MuseScore .mscx files (MuseScore 3.x, 4.x and 5.x). Elements the editor does not
 * support (lines, slurs, graphics, layout breaks, ...) are skipped; notes, rests, tuplets,
 * ties, signatures, clefs, tempo, dynamics, articulations, lyrics and chord symbols are kept.
 */
public final class MscxReader {
    private MscxReader() {}

    /** Collected warnings for unsupported content (shown to the user after import). */
    public static final class Result {
        public Score score;
        public final List<String> warnings = new ArrayList<String>();
        /** Melisma lengths read from the file (MuseScore ticks), resolved once the score is complete. */
        final java.util.Map<ChordRest, Integer> melismaTicks = new java.util.IdentityHashMap<ChordRest, Integer>();
        /** MusicXML lyrics with an &lt;extend/&gt;, resolved once the score is complete. */
        final java.util.Set<ChordRest> melismaExtends = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<ChordRest, Boolean>());
        /** Slurs and hairpins read from the file: {element, kind, measure, tick, end measure, end tick}. */
        final List<Object[]> spans = new ArrayList<Object[]>();
    }

    private static final class TieRef {
        Note note;
        int measure, tick, voice, staff, noteIdx;
        int dMeasure, dTick, dNote;
        boolean hasNext;
    }

    public static Result read(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(new ByteArrayInputStream(data));
        Element root = doc.getDocumentElement();
        if (!root.getTagName().equals("museScore")) throw new IllegalArgumentException("Not a MuseScore file");
        String version = root.getAttribute("version");
        double ver = 0;
        try {
            ver = Double.parseDouble(version);
        } catch (Exception ignored) {
        }
        if (ver > 0 && ver < 3.0) throw new IllegalArgumentException("MuseScore " + version + " files are not supported. Re-save the file in MuseScore 3 or newer.");
        Element se = Xml.child(root, "Score");
        if (se == null) throw new IllegalArgumentException("No score found in file");
        Result res = new Result();
        Score s = new Score();
        s.title = "";
        res.score = s;

        for (Element mt : Xml.children(se, "metaTag")) {
            String n = mt.getAttribute("name");
            String v = mt.getTextContent().trim();
            if (n.equals("workTitle") && !v.isEmpty()) s.title = v;
            else if (n.equals("composer")) s.composer = v;
            else if (n.equals("lyricist")) s.lyricist = v;
            else if (n.equals("copyright")) s.copyright = v;
            else if (n.equals("subtitle")) s.subtitle = v;
        }

        // ---- parts
        List<Element> staffElements = new ArrayList<Element>();
        for (Element pe : Xml.children(se, "Part")) {
            Element ie = Xml.child(pe, "Instrument");
            String longName = Xml.text(ie, "longName");
            Element label = Xml.child(ie, "InstrumentLabel");
            if (longName == null && label != null) longName = Xml.text(label, "longName");
            if (longName != null) longName = Xml.plainText(Xml.child(ie, "longName") != null ? Xml.child(ie, "longName") : Xml.child(label, "longName"));
            String shortName = Xml.text(ie, "shortName");
            if (shortName == null && label != null) shortName = Xml.text(label, "shortName");
            String trackName = Xml.text(pe, "trackName");
            if (trackName == null) trackName = Xml.text(ie, "trackName");
            String id = ie != null ? ie.getAttribute("id") : "";
            int program = 0;
            Element ch = Xml.child(ie, "Channel");
            if (ch != null) {
                Element pr = Xml.child(ch, "program");
                if (pr != null) {
                    try {
                        program = Integer.parseInt(pr.getAttribute("value"));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String name = longName != null && !longName.isEmpty() ? longName : (trackName != null ? trackName : "Instrument");
            Part p = new Part(name, shortName != null ? shortName : "", id.isEmpty() ? "piano" : id, program);
            Instruments.Def def = Instruments.byId(id);
            p.drums = "1".equals(Xml.text(ie, "useDrumset")) || (def != null && def.drums);
            // instrument clefs (clef / clef staff="n")
            Map<Integer, Integer> instClefs = new HashMap<Integer, Integer>();
            for (Element ce : Xml.children(ie, "clef")) {
                String sa = ce.getAttribute("staff");
                int idx = sa == null || sa.isEmpty() ? 1 : Integer.parseInt(sa.trim());
                instClefs.put(idx, Clef.fromMscx(ce.getTextContent()));
            }
            List<Element> pst = Xml.children(pe, "Staff");
            for (int i = 0; i < pst.size(); i++) {
                Element ste = pst.get(i);
                int clef = Clef.TREBLE;
                if (instClefs.containsKey(i + 1)) clef = instClefs.get(i + 1);
                String dc = Xml.text(ste, "defaultClef");
                if (dc != null) clef = Clef.fromMscx(dc);
                Element stt = Xml.child(ste, "StaffType");
                if (stt != null && "percussion".equals(stt.getAttribute("group"))) {
                    clef = Clef.PERC;
                    p.drums = true;
                }
                if (stt != null && "tablature".equals(stt.getAttribute("group"))) {
                    res.warnings.add("Tablature staff of " + name + " is shown as standard notation.");
                    clef = Clef.TREBLE_8VB;
                }
                p.staves.add(new Staff(clef));
            }
            if (p.staves.isEmpty()) p.staves.add(new Staff(Clef.TREBLE));
            s.parts.add(p);
        }
        int staffCount = s.staffCount();
        for (Element e : Xml.children(se, "Staff")) staffElements.add(e);
        if (staffCount == 0 || staffElements.isEmpty()) throw new IllegalArgumentException("The score has no staves");

        // ---- measures: first pass over staff 1 to build measure headers
        List<TieRef> ties = new ArrayList<TieRef>();
        Map<String, Note> eidNotes = new HashMap<String, Note>();
        List<String[]> eidTies = new ArrayList<String[]>();
        int curN = 4, curD = 4, curKey = 0;
        boolean curSym = false;
        for (int st = 0; st < Math.min(staffCount, staffElements.size()); st++) {
            Element ste = staffElements.get(st);
            int m = 0;
            int stN = 4, stD = 4, stKey = 0;
            for (Element me : Xml.children(ste)) {
                String tag = me.getTagName();
                if (tag.equals("VBox") || tag.equals("TBox")) {
                    if (st == 0) readFrameText(me, s);
                    continue;
                }
                if (!tag.equals("Measure")) continue;
                Measure mm;
                if (st == 0) {
                    mm = new Measure();
                    for (int i = 0; i < staffCount; i++) mm.staves.add(new StaffMeasure());
                    s.measures.add(mm);
                } else {
                    if (m >= s.measures.size()) break;
                    mm = s.measures.get(m);
                }
                String len = me.getAttribute("len");
                // signatures appear inside the first voice
                List<Element> voices = Xml.children(me, "voice");
                if (st == 0) {
                    mm.timeN = curN;
                    mm.timeD = curD;
                    mm.timeSymbol = curSym;
                    mm.keyFifths = curKey;
                    for (Element ve : voices) {
                        for (Element el : Xml.children(ve)) {
                            String t = el.getTagName();
                            if (t.equals("TimeSig")) {
                                curN = Xml.intText(el, "sigN", 4);
                                curD = Xml.intText(el, "sigD", 4);
                                int sub = Xml.intText(el, "subtype", 0);
                                curSym = sub == 1 || sub == 2;
                                mm.timeN = curN;
                                mm.timeD = curD;
                                mm.timeSymbol = curSym;
                            } else if (t.equals("KeySig")) {
                                curKey = readKey(el, curKey);
                                mm.keyFifths = curKey;
                            } else if (t.equals("Tempo")) {
                                double bps = Xml.doubleText(el, "tempo", 2.0);
                                mm.tempo = (int) Math.round(bps * 60.0);
                                String txt = Xml.plainText(Xml.child(el, "text"));
                                if (txt != null) {
                                    int cut = txt.indexOf('♩');
                                    if (cut < 0) cut = txt.indexOf('=');
                                    String pre = cut >= 0 ? txt.substring(0, cut).trim() : txt.trim();
                                    if (!pre.isEmpty() && !pre.matches("[0-9 =]+")) mm.tempoText = pre;
                                }
                            } else if (t.equals("RehearsalMark")) {
                                mm.rehearsal = Xml.plainText(Xml.child(el, "text"));
                            }
                        }
                    }
                    if (!len.isEmpty()) {
                        int lt = Xml.fractionTicks(len);
                        if (lt > 0 && lt != mm.nominalTicks()) mm.actualTicks = lt;
                    }
                    if ("1".equals(Xml.text(me, "irregular"))) mm.irregular = true;
                    if (Xml.child(me, "startRepeat") != null) mm.startRepeat = true;
                    if (Xml.child(me, "endRepeat") != null) mm.endBarline = Measure.BAR_END_REPEAT;
                }
                StaffMeasure sm = mm.staves.get(st);
                for (int v = 0; v < voices.size() && v < StaffMeasure.VOICES; v++) {
                    readVoice(voices.get(v), s, mm, m, st, v, sm, ties, eidNotes, eidTies, res);
                }
                m++;
            }
        }
        if (s.measures.isEmpty()) throw new IllegalArgumentException("The score has no measures");

        // ---- make every voice fit its measure
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            for (int st = 0; st < staffCount; st++) {
                StaffMeasure sm = mm.staves.get(st);
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    if (!ScoreEdit.fitVoice(mm, sm.voices[v], v == 0)) {
                        res.warnings.add("Measure " + s.measureNumber(m) + ": a rhythm (e.g. nested tuplet) could not be imported.");
                    }
                }
            }
        }

        // ---- resolve ties
        for (TieRef t : ties) {
            if (t.hasNext) t.note.tieForward = true;
        }
        for (String[] et : eidTies) {
            Note start = eidNotes.get(et[0]);
            if (start != null) start.tieForward = true;
        }
        if (s.title == null || s.title.isEmpty()) s.title = "Untitled score";
        Syllables.resolveMelismaTicks(s, res.melismaTicks);
        resolveSpans(s, res.spans);
        return res;
    }

    private static int readKey(Element el, int def) {
        String k = Xml.text(el, "concertKey");
        if (k == null) k = Xml.text(el, "accidental");
        if (k == null) k = Xml.text(el, "actualKey");
        if (k == null) {
            Element ke = Xml.child(el, "key");
            if (ke != null) k = ke.getTextContent().trim();
        }
        if (k == null) return def;
        try {
            int v = Integer.parseInt(k.trim());
            return Math.max(-7, Math.min(7, v));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void readFrameText(Element frame, Score s) {
        for (Element t : Xml.children(frame, "Text")) {
            String style = Xml.text(t, "style");
            String txt = Xml.plainText(Xml.child(t, "text"));
            if (style == null || txt == null) continue;
            style = style.toLowerCase();
            if (style.equals("title") && (s.title == null || s.title.isEmpty() || s.title.equals("Untitled score"))) s.title = txt;
            else if (style.equals("subtitle") && s.subtitle.isEmpty()) s.subtitle = txt;
            else if (style.equals("composer") && s.composer.isEmpty()) s.composer = txt;
            else if ((style.equals("lyricist") || style.equals("poet")) && s.lyricist.isEmpty()) s.lyricist = txt;
        }
    }

    /** Slur and hairpin lengths, once every measure (and so every measure length) is known. */
    static void resolveSpans(Score s, List<Object[]> spans) {
        for (Object[] sp : spans) {
            ChordRest cr = (ChordRest) sp[0];
            int kind = (Integer) sp[1];
            int start = Syllables.globalTick(s, (Integer) sp[2], (Integer) sp[3]);
            int em = Math.min((Integer) sp[4], s.measures.size());
            int end = em >= s.measures.size() ? Syllables.globalTick(s, s.measures.size() - 1, s.measures.get(s.measures.size() - 1).ticks())
                    : Syllables.globalTick(s, em, (Integer) sp[5]);
            if (end <= start) continue;
            if (kind == 0) {
                if (cr instanceof Chord) cr.slurTicks = end - start;
            } else {
                cr.hairpin = kind;
                cr.hairpinTicks = end - start;
            }
        }
    }

    /** A grace chord: kind from its tag (acciaccatura, appoggiatura, grace4/16/32), notes as in a chord. */
    private static Chord readGrace(Element el, int keyFifths) {
        int kind = Xml.child(el, "acciaccatura") != null ? Chord.ACCIACCATURA : Chord.APPOGGIATURA;
        int type = Duration.typeFromXml(Xml.text(el, "durationType"));
        if (Xml.child(el, "grace16") != null) type = Duration.N16;
        else if (Xml.child(el, "grace32") != null) type = Duration.N32;
        else if (Xml.child(el, "grace4") != null) type = Duration.QUARTER;
        Chord g = Chord.grace(kind, type < 0 ? Duration.EIGHTH : type, null);
        for (Element ne : Xml.children(el, "Note")) {
            int pitch = Xml.intText(ne, "pitch", 60);
            int tpc = Xml.intText(ne, "tpc", -100);
            if (!Pitch.validTpc(tpc) || Pitch.pitchClassOfTpc(tpc) != MathMod.mod(pitch, 12)) tpc = Pitch.spell(pitch, keyFifths);
            g.addNote(new Note(pitch, tpc));
        }
        return g;
    }

    private static boolean isGrace(Element chord) {
        for (Element c : Xml.children(chord)) {
            String t = c.getTagName();
            if (t.equals("acciaccatura") || t.equals("appoggiatura") || t.startsWith("grace")) return true;
        }
        return false;
    }

    private static void readVoice(Element ve, Score s, Measure mm, int m, int st, int v, StaffMeasure sm,
                                  List<TieRef> ties, Map<String, Note> eidNotes, List<String[]> eidTies, Result res) {
        Voice voice = sm.voices[v];
        int tick = 0;
        Tuplet curTuplet = null;
        List<Chord> pendingGraces = new ArrayList<Chord>();
        List<int[]> pendingSpans = new ArrayList<int[]>();
        Map<String, Tuplet> tupletsById = new HashMap<String, Tuplet>();
        String pendingDynamic = null, pendingHarmony = null, pendingText = null;
        boolean pendingFermata = false;
        for (Element el : Xml.children(ve)) {
            String t = el.getTagName();
            if (t.equals("Clef")) {
                String ct = Xml.text(el, "concertClefType");
                if (ct == null) ct = Xml.text(el, "subtype");
                if (ct != null && v == 0 && tick == 0) {
                    if (m == 0) s.staff(st).defaultClef = Clef.fromMscx(ct);
                    else sm.clef = Clef.fromMscx(ct);
                }
                // mid-measure clef changes are not supported and are ignored
            } else if (t.equals("Spanner") && ("Slur".equals(el.getAttribute("type")) || "HairPin".equals(el.getAttribute("type")))) {
                // the start of a slur or hairpin: <next><location> says where it ends, relative to here
                Element next = Xml.child(el, "next");
                Element loc = next == null ? null : Xml.child(next, "location");
                if (loc != null) {
                    int kind = 0;
                    if ("HairPin".equals(el.getAttribute("type"))) {
                        int sub = Xml.intText(Xml.child(el, "HairPin"), "subtype", 0);
                        kind = sub == 1 || sub == 3 ? ChordRest.DIMINUENDO : ChordRest.CRESCENDO;
                    }
                    pendingSpans.add(new int[]{kind, m + Xml.intText(loc, "measures", 0), tick + Xml.fractionTicks(Xml.text(loc, "fractions"))});
                }
            } else if (t.equals("Tuplet")) {
                int normal = Xml.intText(el, "normalNotes", 2);
                int actual = Xml.intText(el, "actualNotes", 3);
                int base = Duration.typeFromXml(Xml.text(el, "baseNote"));
                if (base < 0) base = Duration.EIGHTH;
                Tuplet tp = new Tuplet(actual, normal, base);
                String id = el.getAttribute("id");
                if (id != null && !id.isEmpty()) tupletsById.put(id, tp);
                else curTuplet = tp;
                if (Xml.child(el, "Tuplet") != null) res.warnings.add("Nested tuplets were simplified.");
            } else if (t.equals("endTuplet")) {
                curTuplet = null;
            } else if (t.equals("Dynamic")) {
                String d = Xml.text(el, "subtype");
                if (Dynamics.index(d) >= 0) pendingDynamic = d;
            } else if (t.equals("Harmony")) {
                int root = Xml.intText(el, "root", -100);
                String name = Xml.text(el, "name");
                int base = Xml.intText(el, "base", -100);
                if (Pitch.validTpc(root)) pendingHarmony = ChordSymbols.format(root, name, base);
                else if (name != null) pendingHarmony = name;
            } else if (t.equals("StaffText") || t.equals("SystemText") || t.equals("Expression")) {
                String txt = Xml.plainText(Xml.child(el, "text"));
                if (txt != null && !txt.isEmpty()) pendingText = txt;
            } else if (t.equals("Fermata")) {
                pendingFermata = true;
            } else if (t.equals("location")) {
                int dt = Xml.fractionTicks(Xml.text(el, "fractions"));
                if (dt > 0) {
                    fillRests(voice, tick, dt);
                    tick += dt;
                }
            } else if (t.equals("Chord") || t.equals("Rest") || t.equals("MeasureRepeat")) {
                if (t.equals("Chord") && isGrace(el)) {
                    pendingGraces.add(readGrace(el, mm.keyFifths));
                    continue;
                }
                ChordRest cr;
                if (t.equals("MeasureRepeat")) {
                    res.warnings.add("Measure repeats were replaced by rests.");
                    cr = Rest.measureRest(mm.ticks());
                } else if (t.equals("Rest")) {
                    String dt = Xml.text(el, "durationType");
                    if ("measure".equals(dt)) {
                        cr = Rest.measureRest(mm.ticks());
                    } else {
                        Rest r = new Rest();
                        r.type = Math.max(0, Duration.typeFromXml(dt));
                        r.dots = Xml.intText(el, "dots", 0);
                        cr = r;
                    }
                } else {
                    Chord c = new Chord();
                    c.graces.addAll(pendingGraces);
                    pendingGraces.clear();
                    c.type = Math.max(0, Duration.typeFromXml(Xml.text(el, "durationType")));
                    c.dots = Xml.intText(el, "dots", 0);
                    String sd = Xml.text(el, "StemDirection");
                    if ("up".equals(sd)) c.stemDir = Chord.STEM_UP;
                    else if ("down".equals(sd)) c.stemDir = Chord.STEM_DOWN;
                    for (Element ne : Xml.children(el, "Note")) {
                        int pitch = Xml.intText(ne, "pitch", 60);
                        int tpc = Xml.intText(ne, "tpc", -100);
                        if (!Pitch.validTpc(tpc) || Pitch.pitchClassOfTpc(tpc) != MathMod.mod(pitch, 12)) {
                            tpc = Pitch.spell(pitch, mm.keyFifths);
                        }
                        Note n = new Note(pitch, tpc);
                        c.notes.add(n);
                        String eid = Xml.text(ne, "eid");
                        if (eid != null) eidNotes.put(eid, n);
                        for (Element sp : Xml.children(ne, "Spanner")) {
                            if (!"Tie".equals(sp.getAttribute("type"))) continue;
                            if (Xml.child(sp, "next") != null) {
                                TieRef tr = new TieRef();
                                tr.note = n;
                                tr.hasNext = true;
                                ties.add(tr);
                            }
                        }
                        Element tie = Xml.child(ne, "Tie");
                        if (tie != null) {
                            String startEl = Xml.text(tie, "startElement");
                            if (startEl != null) eidTies.add(new String[]{startEl});
                            else {
                                // MuseScore 2/3 style: <Tie id=..> on the starting note
                                TieRef tr = new TieRef();
                                tr.note = n;
                                tr.hasNext = true;
                                ties.add(tr);
                            }
                        }
                    }
                    if (c.notes.isEmpty()) {
                        Rest r = new Rest();
                        r.type = c.type;
                        r.dots = c.dots;
                        cr = r;
                    } else {
                        c.sortNotes();
                        for (Element le : Xml.children(el, "Lyrics")) {
                            int no = Xml.intText(le, "no", 0);
                            if (no != 0) continue;
                            String txt = Xml.plainText(Xml.child(le, "text"));
                            String syl = Xml.text(le, "syllabic");
                            if (txt != null && syl != null && (syl.equals("begin") || syl.equals("middle"))) txt = txt + "-";
                            c.lyric = txt;
                            // melisma length: ticks_f (a fraction of a whole note) since MuseScore 3.x, ticks before
                            String tf = Xml.text(le, "ticks_f");
                            int mt = tf != null ? Syllables.parseFraction(tf) : Xml.intText(le, "ticks", 0);
                            if (mt > 0) res.melismaTicks.put(c, mt);
                        }
                        for (Element ae : Xml.children(el, "Articulation")) {
                            String a = Articulation.fromMscx(Xml.text(ae, "subtype"));
                            if (a != null && !c.articulations.contains(a)) c.articulations.add(a);
                        }
                        cr = c;
                    }
                }
                String tid = Xml.text(el, "Tuplet");
                if (tid != null && tupletsById.containsKey(tid)) cr.tuplet = tupletsById.get(tid);
                else if (curTuplet != null && !(cr instanceof Rest && ((Rest) cr).measureRest)) cr.tuplet = curTuplet;
                cr.dynamic = pendingDynamic;
                cr.chordSymbol = pendingHarmony;
                cr.staffText = pendingText;
                if (pendingFermata) cr.articulations.add(Articulation.FERMATA);
                pendingDynamic = null;
                pendingHarmony = null;
                pendingText = null;
                pendingFermata = false;
                for (int[] sp : pendingSpans) res.spans.add(new Object[]{cr, sp[0], m, tick, sp[1], sp[2]});
                pendingSpans.clear();
                voice.items.add(cr);
                tick += cr.ticks();
            } else if (t.equals("BarLine") && v == 0) {
                String sub = Xml.text(el, "subtype");
                if (sub != null && st == 0) {
                    for (int i = 0; i < Measure.BAR_MSCX.length; i++) {
                        if (Measure.BAR_MSCX[i].equals(sub)) mm.endBarline = i;
                    }
                    if (sub.equals("light-heavy") || sub.equals("final")) mm.endBarline = Measure.BAR_FINAL;
                    if (sub.equals("double")) mm.endBarline = Measure.BAR_DOUBLE;
                }
            }
        }
    }

    private static void fillRests(Voice voice, int start, int len) {
        for (int[] d : Duration.decomposeRests(start, len)) {
            Rest r = new Rest();
            r.type = d[0];
            r.dots = d[1];
            voice.items.add(r);
        }
    }

    /** floorMod helper usable on all API levels. */
    static final class MathMod {
        static int mod(int a, int b) {
            int r = a % b;
            return r < 0 ? r + b : r;
        }
    }
}
