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
import io.github.vlesk.stave.model.Syllables;
import io.github.vlesk.stave.model.ScoreEdit;
import io.github.vlesk.stave.model.Staff;
import io.github.vlesk.stave.model.StaffMeasure;
import io.github.vlesk.stave.model.Tuplet;
import io.github.vlesk.stave.model.Voice;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/** MusicXML (partwise, .musicxml/.xml/.mxl) import. */
public final class MusicXmlReader {
    private MusicXmlReader() {}

    private static final String[] STEPS = {"C", "D", "E", "F", "G", "A", "B"};

    private static final class Item {
        int tick;
        ChordRest cr;
        String tupletKey;
    }

    public static MscxReader.Result readMxl(byte[] zipData) throws Exception {
        ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipData));
        Map<String, byte[]> files = new HashMap<String, byte[]>();
        ZipEntry e;
        while ((e = zin.getNextEntry()) != null) {
            if (!e.isDirectory()) files.put(e.getName(), Mscz.readAll(zin));
        }
        zin.close();
        byte[] container = files.get("META-INF/container.xml");
        if (container != null) {
            String c = new String(container, "UTF-8");
            int i = c.indexOf("full-path=\"");
            if (i >= 0) {
                String path = c.substring(i + 11, c.indexOf('"', i + 11));
                if (files.containsKey(path)) return read(files.get(path));
            }
        }
        for (Map.Entry<String, byte[]> en : files.entrySet()) {
            String n = en.getKey().toLowerCase();
            if (!n.startsWith("meta-inf") && (n.endsWith(".xml") || n.endsWith(".musicxml"))) return read(en.getValue());
        }
        throw new IllegalArgumentException("No MusicXML document inside the .mxl file");
    }

    public static MscxReader.Result read(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver(new EntityResolver() {
            @Override
            public InputSource resolveEntity(String publicId, String systemId) {
                return new InputSource(new StringReader(""));
            }
        });
        Document doc = b.parse(new ByteArrayInputStream(data));
        Element root = doc.getDocumentElement();
        if (root.getTagName().equals("score-timewise")) {
            throw new IllegalArgumentException("Timewise MusicXML is not supported; export partwise MusicXML instead.");
        }
        if (!root.getTagName().equals("score-partwise")) throw new IllegalArgumentException("Not a MusicXML score");
        MscxReader.Result res = new MscxReader.Result();
        Score s = new Score();
        res.score = s;
        Element work = Xml.child(root, "work");
        String title = work != null ? Xml.text(work, "work-title") : null;
        if (title == null || title.isEmpty()) title = Xml.text(root, "movement-title");
        if (title != null && !title.isEmpty()) s.title = title;
        Element ident = Xml.child(root, "identification");
        for (Element cr : Xml.children(ident, "creator")) {
            String type = cr.getAttribute("type");
            if ("composer".equals(type)) s.composer = cr.getTextContent().trim();
            else if ("lyricist".equals(type) || "poet".equals(type)) s.lyricist = cr.getTextContent().trim();
        }
        String rights = Xml.text(ident, "rights");
        if (rights != null) s.copyright = rights;
        for (Element cr : Xml.children(root, "credit")) {
            String type = Xml.text(cr, "credit-type");
            String words = Xml.text(cr, "credit-words");
            if (type == null || words == null) continue;
            if (type.equals("title") && s.title.equals("Untitled score")) s.title = words;
            if (type.equals("subtitle")) s.subtitle = words;
            if (type.equals("composer") && s.composer.isEmpty()) s.composer = words;
        }

        // part list
        Map<String, Part> partsById = new HashMap<String, Part>();
        List<String> order = new ArrayList<String>();
        Element pl = Xml.child(root, "part-list");
        for (Element sp : Xml.children(pl, "score-part")) {
            String id = sp.getAttribute("id");
            String name = Xml.text(sp, "part-name");
            if (name == null || name.isEmpty()) name = "Instrument";
            Instruments.Def def = Instruments.byName(name);
            int program = def != null ? def.program : 0;
            boolean drums = def != null && def.drums;
            Element mi = Xml.child(sp, "midi-instrument");
            if (mi != null) {
                int prog = Xml.intText(mi, "midi-program", 0);
                if (prog > 0) program = prog - 1;
                if (Xml.intText(mi, "midi-channel", 0) == 10) drums = true;
            }
            String abbr = Xml.text(sp, "part-abbreviation");
            Part p = new Part(name, abbr != null ? abbr : (def != null ? def.shortName : ""), def != null ? def.id : "piano", program);
            p.drums = drums;
            partsById.put(id, p);
            order.add(id);
        }

        List<Element> partEls = Xml.children(root, "part");
        // first pass: staff counts and clefs
        for (Element pe : partEls) {
            Part p = partsById.get(pe.getAttribute("id"));
            if (p == null) {
                p = new Part("Instrument", "", "piano", 0);
                partsById.put(pe.getAttribute("id"), p);
                order.add(pe.getAttribute("id"));
            }
            int staves = 1;
            Map<Integer, Integer> clefs = new HashMap<Integer, Integer>();
            outer:
            for (Element me : Xml.children(pe, "measure")) {
                for (Element at : Xml.children(me, "attributes")) {
                    staves = Math.max(staves, Xml.intText(at, "staves", 1));
                    for (Element ce : Xml.children(at, "clef")) {
                        int no = parseInt(ce.getAttribute("number"), 1);
                        if (!clefs.containsKey(no)) clefs.put(no, readClef(ce));
                    }
                }
                break outer;
            }
            for (int i = 1; i <= staves; i++) {
                Integer c = clefs.get(i);
                p.staves.add(new Staff(c != null ? c : (i == 2 ? Clef.BASS : Clef.TREBLE)));
            }
        }
        for (String id : order) {
            Part p = partsById.get(id);
            if (p.staves.isEmpty()) p.staves.add(new Staff(Clef.TREBLE));
            s.parts.add(p);
        }
        if (s.parts.isEmpty()) throw new IllegalArgumentException("The MusicXML file has no parts");
        int staffCount = s.staffCount();

        // measures from the first part define the bar structure
        int mcount = 0;
        for (Element pe : partEls) mcount = Math.max(mcount, Xml.children(pe, "measure").size());
        for (int i = 0; i < mcount; i++) {
            Measure m = new Measure();
            for (int k = 0; k < staffCount; k++) m.staves.add(new StaffMeasure());
            s.measures.add(m);
        }

        // slurs (start, stop) and hairpins (start element and kind; where they stop), resolved at the end
        List<ChordRest[]> slurPairs = new ArrayList<ChordRest[]>();
        Map<String, ChordRest> openSlurs = new HashMap<String, ChordRest>();
        List<Object[]> wedgeStarts = new ArrayList<Object[]>();
        List<int[]> wedgeStops = new ArrayList<int[]>();
        boolean firstPart = true;
        for (Element pe : partEls) {
            Part p = partsById.get(pe.getAttribute("id"));
            int firstStaff = s.firstStaffOf(p);
            int nst = p.staves.size();
            int divisions = 1;
            int curN = 4, curD = 4, curKey = 0;
            boolean curSym = false;
            List<Element> measures = Xml.children(pe, "measure");
            Map<String, Note> openTies = new HashMap<String, Note>();
            // grace notes wait for the next chord of their staff and voice
            Map<String, List<Chord>> pendingGraces = new HashMap<String, List<Chord>>();
            int[] pendingWedge = null;
            for (int mi = 0; mi < measures.size(); mi++) {
                Element me = measures.get(mi);
                Measure mm = s.measures.get(mi);
                // per staff -> voice number -> items
                List<Map<String, List<Item>>> voiceItems = new ArrayList<Map<String, List<Item>>>();
                for (int k = 0; k < nst; k++) voiceItems.add(new HashMap<String, List<Item>>());
                List<List<String>> voiceOrder = new ArrayList<List<String>>();
                for (int k = 0; k < nst; k++) voiceOrder.add(new ArrayList<String>());
                int pos = 0, maxPos = 0;
                Chord lastChord = null;
                int lastChordTick = 0;
                String pendingDyn = null, pendingWords = null, pendingHarmony = null;
                int pendingStaff = 0;
                Map<String, Tuplet> openTuplets = new HashMap<String, Tuplet>();
                Map<String, Integer> tupletFill = new HashMap<String, Integer>();
                for (Element el : Xml.children(me)) {
                    String tag = el.getTagName();
                    if (tag.equals("attributes")) {
                        divisions = Math.max(1, Xml.intText(el, "divisions", divisions));
                        Element key = Xml.child(el, "key");
                        if (key != null && Xml.text(key, "fifths") != null) curKey = Math.max(-7, Math.min(7, Xml.intText(key, "fifths", 0)));
                        Element time = Xml.child(el, "time");
                        if (time != null && Xml.text(time, "beats") != null) {
                            String beats = Xml.text(time, "beats");
                            int n = 0;
                            for (String part : beats.split("\\+")) n += parseInt(part.trim(), 0);
                            curN = Math.max(1, n);
                            curD = Math.max(1, Xml.intText(time, "beat-type", 4));
                            String sym = time.getAttribute("symbol");
                            curSym = "common".equals(sym) || "cut".equals(sym);
                        }
                        for (Element ce : Xml.children(el, "clef")) {
                            int no = parseInt(ce.getAttribute("number"), 1);
                            if (no < 1 || no > nst) continue;
                            int c = readClef(ce);
                            if (mi == 0 && pos == 0) p.staves.get(no - 1).defaultClef = c;
                            else if (pos == 0) mm.staves.get(firstStaff + no - 1).clef = c;
                        }
                        if (firstPart && pos == 0) {
                            mm.timeN = curN;
                            mm.timeD = curD;
                            mm.timeSymbol = curSym;
                            mm.keyFifths = curKey;
                        }
                    } else if (tag.equals("backup")) {
                        pos = Math.max(0, pos - ticks(Xml.intText(el, "duration", 0), divisions));
                    } else if (tag.equals("forward")) {
                        pos += ticks(Xml.intText(el, "duration", 0), divisions);
                        maxPos = Math.max(maxPos, pos);
                    } else if (tag.equals("direction")) {
                        int staffNo = Xml.intText(el, "staff", 1);
                        for (Element dt : Xml.children(el, "direction-type")) {
                            Element wedge = Xml.child(dt, "wedge");
                            if (wedge != null) {
                                String wt = wedge.getAttribute("type");
                                int off = ticks(Xml.intText(el, "offset", 0), divisions);
                                if ("crescendo".equals(wt) || "diminuendo".equals(wt)) {
                                    pendingWedge = new int[]{"diminuendo".equals(wt) ? ChordRest.DIMINUENDO : ChordRest.CRESCENDO, firstStaff + staffNo - 1};
                                } else if ("stop".equals(wt)) {
                                    wedgeStops.add(new int[]{firstStaff + staffNo - 1, mi, Math.max(0, pos + off)});
                                }
                            }
                            Element dyn = Xml.child(dt, "dynamics");
                            if (dyn != null) {
                                for (Element d : Xml.children(dyn)) {
                                    if (Dynamics.index(d.getTagName()) >= 0) pendingDyn = d.getTagName();
                                }
                            }
                            String words = Xml.text(dt, "words");
                            if (words != null && !words.isEmpty()) pendingWords = words;
                            String reh = Xml.text(dt, "rehearsal");
                            if (reh != null && firstPart) mm.rehearsal = reh;
                            Element met = Xml.child(dt, "metronome");
                            if (met != null && firstPart) {
                                int pm = Xml.intText(met, "per-minute", 0);
                                String unit = Xml.text(met, "beat-unit");
                                if (pm > 0) {
                                    int type = Duration.typeFromXml(unit);
                                    int dots = Xml.children(met, "beat-unit-dot").size();
                                    double q = type < 0 ? 1 : Duration.ticks(type, dots) / 480.0;
                                    mm.tempo = (int) Math.round(pm * q);
                                }
                            }
                        }
                        Element snd = Xml.child(el, "sound");
                        if (snd != null && firstPart && snd.hasAttribute("tempo") && mm.tempo == 0) {
                            mm.tempo = (int) Math.round(parseDouble(snd.getAttribute("tempo"), 120));
                        }
                        if (pendingWords != null && firstPart && mm.tempo > 0 && mm.tempoText == null && pos == 0) {
                            mm.tempoText = pendingWords;
                            pendingWords = null;
                        }
                        pendingStaff = staffNo;
                    } else if (tag.equals("harmony")) {
                        Element rootEl = Xml.child(el, "root");
                        if (rootEl != null) {
                            int step = indexOf(Xml.text(rootEl, "root-step"));
                            int alter = Xml.intText(rootEl, "root-alter", 0);
                            Element kind = Xml.child(el, "kind");
                            String q = kind != null && kind.hasAttribute("text") ? kind.getAttribute("text") : kindText(kind != null ? kind.getTextContent().trim() : "major");
                            int bassTpc = -100;
                            Element bass = Xml.child(el, "bass");
                            if (bass != null) {
                                int bs = indexOf(Xml.text(bass, "bass-step"));
                                if (bs >= 0) bassTpc = Pitch.tpcFromStepAlter(bs, Xml.intText(bass, "bass-alter", 0));
                            }
                            if (step >= 0) pendingHarmony = ChordSymbols.format(Pitch.tpcFromStepAlter(step, alter), q, bassTpc);
                        }
                    } else if (tag.equals("barline")) {
                        if (!firstPart) continue;
                        String loc = el.getAttribute("location");
                        Element rep = Xml.child(el, "repeat");
                        String style = Xml.text(el, "bar-style");
                        if (rep != null && "forward".equals(rep.getAttribute("direction"))) mm.startRepeat = true;
                        else if (rep != null && "backward".equals(rep.getAttribute("direction"))) mm.endBarline = Measure.BAR_END_REPEAT;
                        else if (!"left".equals(loc) && style != null) {
                            if (style.equals("light-light")) mm.endBarline = Measure.BAR_DOUBLE;
                            else if (style.equals("light-heavy")) mm.endBarline = Measure.BAR_FINAL;
                            else if (style.equals("dashed")) mm.endBarline = Measure.BAR_DASHED;
                        }
                    } else if (tag.equals("note")) {
                        if (Xml.child(el, "cue") != null) continue;
                        Element graceEl = Xml.child(el, "grace");
                        boolean isChord = Xml.child(el, "chord") != null;
                        int dur = ticks(Xml.intText(el, "duration", 0), divisions);
                        int staffNo = Math.max(1, Math.min(nst, Xml.intText(el, "staff", 1)));
                        String voiceKey = Xml.text(el, "voice");
                        if (voiceKey == null) voiceKey = "1";
                        Element pitchEl = Xml.child(el, "pitch");
                        Element unpitched = Xml.child(el, "unpitched");
                        Note note = null;
                        if (pitchEl != null) {
                            int step = indexOf(Xml.text(pitchEl, "step"));
                            int alter = (int) Math.round(Xml.doubleText(pitchEl, "alter", 0));
                            int oct = Xml.intText(pitchEl, "octave", 4);
                            if (step < 0) step = 0;
                            int pitch = Pitch.pitchFromDiatonic(oct * 7 + step, alter);
                            note = new Note(Math.max(0, Math.min(127, pitch)), Pitch.tpcFromStepAlter(step, Math.max(-2, Math.min(2, alter))));
                        } else if (unpitched != null) {
                            int step = indexOf(Xml.text(unpitched, "display-step"));
                            int oct = Xml.intText(unpitched, "display-octave", 4);
                            int pitch = Pitch.pitchFromDiatonic(oct * 7 + Math.max(0, step), 0);
                            note = new Note(pitch, Pitch.tpcFromStepAlter(Math.max(0, step), 0));
                        }
                        if (graceEl != null) {
                            if (note != null) {
                                String gk = staffNo + ":" + voiceKey;
                                List<Chord> pend = pendingGraces.get(gk);
                                if (pend == null) pendingGraces.put(gk, pend = new ArrayList<Chord>());
                                if (isChord && !pend.isEmpty()) {
                                    pend.get(pend.size() - 1).addNote(note);
                                } else {
                                    int gt = Duration.typeFromXml(Xml.text(el, "type"));
                                    pend.add(Chord.grace("yes".equals(graceEl.getAttribute("slash")) ? Chord.ACCIACCATURA : Chord.APPOGGIATURA,
                                            gt < 0 ? Duration.EIGHTH : gt, note));
                                }
                            }
                            continue;
                        }
                        if (isChord && lastChord != null && note != null) {
                            lastChord.addNote(note);
                            handleTies(el, note, openTies, staffNo + ":" + voiceKey);
                            continue;
                        }
                        int start = pos;
                        pos += dur;
                        maxPos = Math.max(maxPos, pos);
                        ChordRest cr;
                        Element restEl = Xml.child(el, "rest");
                        String typeName = Xml.text(el, "type");
                        int type = Duration.typeFromXml(typeName);
                        int dots = Xml.children(el, "dot").size();
                        if (restEl != null || note == null) {
                            Rest r = new Rest();
                            if ("yes".equals(restEl != null ? restEl.getAttribute("measure") : "") || type < 0) {
                                int[] ex = Duration.exact(dur);
                                if (ex != null && type < 0 && !"yes".equals(restEl != null ? restEl.getAttribute("measure") : "")) {
                                    r.type = ex[0];
                                    r.dots = ex[1];
                                } else {
                                    r = Rest.measureRest(dur);
                                }
                            } else {
                                r.type = type;
                                r.dots = dots;
                            }
                            cr = r;
                            lastChord = null;
                        } else {
                            Chord c = new Chord();
                            if (type < 0) {
                                int[] ex = Duration.exact(dur);
                                if (ex == null) ex = new int[]{Duration.QUARTER, 0};
                                type = ex[0];
                                dots = ex[1];
                            }
                            c.type = type;
                            c.dots = dots;
                            c.addNote(note);
                            List<Chord> graces = pendingGraces.remove(staffNo + ":" + voiceKey);
                            if (graces != null) c.graces.addAll(graces);
                            handleTies(el, note, openTies, staffNo + ":" + voiceKey);
                            lastChord = c;
                            lastChordTick = start;
                            cr = c;
                            // notations
                            Element nots = Xml.child(el, "notations");
                            if (nots != null) {
                                for (Element sl : Xml.children(nots, "slur")) {
                                    String key = firstStaff + ":" + sl.getAttribute("number");
                                    if ("start".equals(sl.getAttribute("type"))) {
                                        openSlurs.put(key, c);
                                    } else if ("stop".equals(sl.getAttribute("type"))) {
                                        ChordRest from = openSlurs.remove(key);
                                        if (from != null) slurPairs.add(new ChordRest[]{from, c});
                                    }
                                }
                                Element arts = Xml.child(nots, "articulations");
                                for (Element a : Xml.children(arts)) {
                                    String t = a.getTagName();
                                    if (t.equals("staccato")) c.articulations.add(Articulation.STACCATO);
                                    else if (t.equals("accent")) c.articulations.add(Articulation.ACCENT);
                                    else if (t.equals("tenuto")) c.articulations.add(Articulation.TENUTO);
                                    else if (t.equals("strong-accent")) c.articulations.add(Articulation.MARCATO);
                                    else if (t.equals("staccatissimo")) c.articulations.add(Articulation.STACCATISSIMO);
                                }
                                Element orn = Xml.child(nots, "ornaments");
                                for (Element a : Xml.children(orn)) {
                                    String t = a.getTagName();
                                    if (t.equals("trill-mark")) c.articulations.add(Articulation.TRILL);
                                    else if (t.equals("mordent") || t.equals("inverted-mordent")) c.articulations.add(Articulation.MORDENT);
                                    else if (t.equals("turn")) c.articulations.add(Articulation.TURN);
                                }
                            }
                            for (Element ly : Xml.children(el, "lyric")) {
                                String num = ly.getAttribute("number");
                                if (num != null && !num.isEmpty() && !num.equals("1")) continue;
                                String txt = Xml.text(ly, "text");
                                String syl = Xml.text(ly, "syllabic");
                                if (txt != null && syl != null && (syl.equals("begin") || syl.equals("middle"))) txt += "-";
                                c.lyric = txt;
                                Element ext = Xml.child(ly, "extend");
                                if (ext != null && !"stop".equals(ext.getAttribute("type")) && !"continue".equals(ext.getAttribute("type"))) {
                                    res.melismaExtends.add(c);
                                }
                                break;
                            }
                        }
                        Element nots = Xml.child(el, "notations");
                        if (nots != null && Xml.child(nots, "fermata") != null) cr.articulations.add(Articulation.FERMATA);
                        // tuplets
                        Element tm = Xml.child(el, "time-modification");
                        String tKey = null;
                        if (tm != null && !(cr instanceof Rest && ((Rest) cr).measureRest)) {
                            int actual = Xml.intText(tm, "actual-notes", 3);
                            int normal = Xml.intText(tm, "normal-notes", 2);
                            tKey = staffNo + ":" + voiceKey;
                            Tuplet t = openTuplets.get(tKey);
                            if (t == null || t.actual != actual || t.normal != normal) {
                                int base = Duration.typeFromXml(Xml.text(tm, "normal-type"));
                                if (base < 0) base = cr.type;
                                t = new Tuplet(actual, normal, base);
                                openTuplets.put(tKey, t);
                                tupletFill.put(tKey, 0);
                            }
                            cr.tuplet = t;
                            int fill = tupletFill.get(tKey) + cr.ticks();
                            tupletFill.put(tKey, fill);
                            boolean stop = false;
                            if (nots != null) {
                                for (Element tu : Xml.children(nots, "tuplet")) if ("stop".equals(tu.getAttribute("type"))) stop = true;
                            }
                            if (stop || fill >= t.spanTicks()) {
                                openTuplets.remove(tKey);
                            }
                        }
                        if (pendingWedge != null && pendingWedge[1] == firstStaff + staffNo - 1) {
                            wedgeStarts.add(new Object[]{cr, pendingWedge[0]});
                            pendingWedge = null;
                        }
                        if (pendingDyn != null && (pendingStaff == 0 || pendingStaff == staffNo)) {
                            cr.dynamic = pendingDyn;
                            pendingDyn = null;
                        }
                        if (pendingWords != null) {
                            cr.staffText = pendingWords;
                            pendingWords = null;
                        }
                        if (pendingHarmony != null) {
                            cr.chordSymbol = pendingHarmony;
                            pendingHarmony = null;
                        }
                        Item it = new Item();
                        it.tick = start;
                        it.cr = cr;
                        Map<String, List<Item>> vm = voiceItems.get(staffNo - 1);
                        List<Item> list = vm.get(voiceKey);
                        if (list == null) {
                            list = new ArrayList<Item>();
                            vm.put(voiceKey, list);
                            voiceOrder.get(staffNo - 1).add(voiceKey);
                        }
                        list.add(it);
                    }
                }
                if (firstPart) {
                    boolean implicit = "yes".equals(me.getAttribute("implicit"));
                    if ((implicit || mi == 0) && maxPos > 0 && maxPos < mm.nominalTicks()) {
                        mm.actualTicks = maxPos;
                        mm.irregular = implicit || mi == 0;
                    }
                }
                // build voices
                for (int k = 0; k < nst; k++) {
                    StaffMeasure sm = mm.staves.get(firstStaff + k);
                    List<String> vo = voiceOrder.get(k);
                    for (int vi = 0; vi < vo.size() && vi < StaffMeasure.VOICES; vi++) {
                        List<Item> items = voiceItems.get(k).get(vo.get(vi));
                        Collections.sort(items, new Comparator<Item>() {
                            @Override
                            public int compare(Item a, Item b) {
                                return a.tick < b.tick ? -1 : (a.tick > b.tick ? 1 : 0);
                            }
                        });
                        Voice v = sm.voices[vi];
                        int t = 0;
                        for (Item it : items) {
                            if (it.tick < t) continue; // overlapping, drop
                            if (it.tick > t) {
                                for (int[] d : Duration.decomposeRests(t, it.tick - t)) {
                                    Rest r = new Rest();
                                    r.type = d[0];
                                    r.dots = d[1];
                                    v.items.add(r);
                                }
                                t = it.tick;
                            }
                            v.items.add(it.cr);
                            t += it.cr.ticks();
                        }
                    }
                }
            }
            firstPart = false;
        }
        // propagate signatures for measures without attributes and fix voice lengths
        for (int i = 1; i < s.measures.size(); i++) {
            Measure prev = s.measures.get(i - 1), m = s.measures.get(i);
            // measures created with default 4/4 inherit when the file did not set attributes
            if (!hasOwnAttributes(partEls, i)) {
                m.timeN = prev.timeN;
                m.timeD = prev.timeD;
                m.timeSymbol = prev.timeSymbol;
                m.keyFifths = prev.keyFifths;
            }
        }
        for (int mi = 0; mi < s.measures.size(); mi++) {
            Measure mm = s.measures.get(mi);
            for (StaffMeasure sm : mm.staves) {
                for (int v = 0; v < StaffMeasure.VOICES; v++) {
                    if (!ScoreEdit.fitVoice(mm, sm.voices[v], v == 0)) {
                        res.warnings.add("Measure " + s.measureNumber(mi) + ": a rhythm could not be imported.");
                    }
                }
            }
        }
        Syllables.resolveExtends(s, res.melismaExtends);
        resolveLines(s, slurPairs, wedgeStarts, wedgeStops);
        return res;
    }

    /** Slur and hairpin lengths, once the voices are final: from where their elements ended up. */
    private static void resolveLines(Score s, List<ChordRest[]> slurPairs, List<Object[]> wedgeStarts, List<int[]> wedgeStops) {
        if (slurPairs.isEmpty() && wedgeStarts.isEmpty()) return;
        java.util.IdentityHashMap<ChordRest, int[]> where = new java.util.IdentityHashMap<ChordRest, int[]>();
        int base = 0;
        for (Measure mm : s.measures) {
            for (int st = 0; st < mm.staves.size(); st++) {
                for (Voice v : mm.staves.get(st).voices) {
                    int[] starts = v.starts();
                    for (int i = 0; i < v.items.size(); i++) where.put(v.items.get(i), new int[]{base + starts[i], st});
                }
            }
            base += mm.ticks();
        }
        for (ChordRest[] pr : slurPairs) {
            int[] a = where.get(pr[0]), b = where.get(pr[1]);
            if (a != null && b != null && b[0] > a[0]) pr[0].slurTicks = b[0] - a[0];
        }
        boolean[] used = new boolean[wedgeStops.size()];
        for (Object[] ws : wedgeStarts) {
            ChordRest cr = (ChordRest) ws[0];
            int[] a = where.get(cr);
            if (a == null) continue;
            int best = -1, bestTick = Integer.MAX_VALUE;
            for (int k = 0; k < wedgeStops.size(); k++) {
                int[] st = wedgeStops.get(k);
                if (used[k] || st[0] != a[1] || st[1] >= s.measures.size()) continue;
                int t = Syllables.globalTick(s, st[1], st[2]);
                if (t > a[0] && t < bestTick) {
                    best = k;
                    bestTick = t;
                }
            }
            if (best < 0) continue;
            used[best] = true;
            cr.hairpin = (Integer) ws[1];
            cr.hairpinTicks = bestTick - a[0];
        }
    }

    private static boolean hasOwnAttributes(List<Element> partEls, int measure) {
        if (partEls.isEmpty()) return false;
        List<Element> ms = Xml.children(partEls.get(0), "measure");
        if (measure >= ms.size()) return false;
        for (Element at : Xml.children(ms.get(measure), "attributes")) {
            if (Xml.child(at, "time") != null || Xml.child(at, "key") != null) return true;
        }
        return false;
    }

    private static void handleTies(Element noteEl, Note note, Map<String, Note> open, String voiceKey) {
        for (Element t : Xml.children(noteEl, "tie")) {
            if ("start".equals(t.getAttribute("type"))) note.tieForward = true;
        }
        Element nots = Xml.child(noteEl, "notations");
        for (Element t : Xml.children(nots, "tied")) {
            if ("start".equals(t.getAttribute("type"))) note.tieForward = true;
        }
    }

    private static int readClef(Element ce) {
        String sign = Xml.text(ce, "sign");
        int line = Xml.intText(ce, "line", 0);
        int oct = Xml.intText(ce, "clef-octave-change", 0);
        if ("TAB".equals(sign)) return Clef.TREBLE_8VB;
        return Clef.fromMusicXml(sign, line, oct);
    }

    private static int ticks(int duration, int divisions) {
        return (int) Math.round(duration * 480.0 / divisions);
    }

    private static int indexOf(String step) {
        if (step == null) return -1;
        for (int i = 0; i < STEPS.length; i++) if (STEPS[i].equalsIgnoreCase(step.trim())) return i;
        return -1;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String kindText(String kind) {
        if (kind == null) return "";
        if (kind.equals("major")) return "";
        if (kind.equals("minor")) return "m";
        if (kind.equals("dominant")) return "7";
        if (kind.equals("major-seventh")) return "maj7";
        if (kind.equals("minor-seventh")) return "m7";
        if (kind.equals("diminished")) return "dim";
        if (kind.equals("diminished-seventh")) return "dim7";
        if (kind.equals("augmented")) return "aug";
        if (kind.equals("half-diminished")) return "m7b5";
        if (kind.equals("suspended-fourth")) return "sus4";
        if (kind.equals("suspended-second")) return "sus2";
        if (kind.equals("major-sixth")) return "6";
        if (kind.equals("minor-sixth")) return "m6";
        if (kind.equals("dominant-ninth")) return "9";
        if (kind.equals("major-ninth")) return "maj9";
        if (kind.equals("minor-ninth")) return "m9";
        if (kind.equals("power")) return "5";
        return "";
    }
}
