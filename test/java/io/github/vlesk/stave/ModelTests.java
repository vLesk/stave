package io.github.vlesk.stave;

import io.github.vlesk.stave.audio.MidiWriter;
import io.github.vlesk.stave.audio.Timeline;
import io.github.vlesk.stave.io.MscxReader;
import io.github.vlesk.stave.io.MscxWriter;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.io.MusicXmlReader;
import io.github.vlesk.stave.io.MusicXmlWriter;
import io.github.vlesk.stave.model.*;

import io.github.vlesk.stave.engrave.DisplayList;
import io.github.vlesk.stave.engrave.Layout;
import io.github.vlesk.stave.engrave.TextMetrics;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** Plain-JVM tests for the model, editing engine and file formats (run by tools/test.sh). */
public class ModelTests {
    static int failures = 0, checks = 0;

    static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            failures++;
            System.out.println("FAIL: " + msg);
        }
    }

    static void checkVoicesFit(Score s, String ctx) {
        for (int m = 0; m < s.measures.size(); m++) {
            Measure mm = s.measures.get(m);
            check(mm.staves.size() == s.staffCount(), ctx + ": staff count m" + m);
            for (int st = 0; st < mm.staves.size(); st++) {
                for (int v = 0; v < 4; v++) {
                    Voice voice = mm.staves.get(st).voices[v];
                    if (voice.isEmpty()) {
                        check(v > 0, ctx + ": voice 1 empty m" + m + " s" + st);
                        continue;
                    }
                    check(voice.totalTicks() == mm.ticks(), ctx + ": voice length " + voice.totalTicks() + " != " + mm.ticks() + " at m" + m + " s" + st + " v" + v);
                }
            }
        }
    }

    static Score pianoScore() {
        List<Part> parts = new ArrayList<Part>();
        parts.add(Instruments.byId("piano").create());
        parts.add(Instruments.byId("violin").create());
        return Score.create("Test", "Me", parts, 4, 4, 2, 100, 4);
    }

    static String lyricAt(io.github.vlesk.stave.edit.EditorState es, Pos p) {
        ChordRest cr = ScoreEdit.elementAt(es.score, p);
        return cr == null ? null : cr.lyric;
    }

    static Chord chord(int... pitches) {
        Chord c = new Chord();
        for (int p : pitches) c.addNote(new Note(p, Pitch.spell(p, 2)));
        return c;
    }

    public static void main(String[] args) throws Exception {
        // pitch spelling
        check(Pitch.spell(61, 0) == 21, "C# in C");
        check(Pitch.spell(70, 0) == 12, "Bb in C");
        check(Pitch.spell(66, -1) == 20, "F# in F? " + Pitch.spell(66, -1));
        check(Pitch.spell(66, 2) == 20, "F# in D");
        check(Pitch.spell(63, -3) == 11, "Eb in Eb");
        check(Pitch.octave(60, 14) == 4, "C4");
        check(Pitch.octave(59, 7) == 4, "Cb4");
        check(Clef.line(Clef.TREBLE, 64, 18) == 8, "E4 on bottom line of treble");
        check(Clef.line(Clef.BASS, 57, 17) == 0, "A3 top line bass");
        check(Pitch.keyAlter(2, 3) == 1 && Pitch.keyAlter(2, 0) == 1 && Pitch.keyAlter(2, 4) == 0, "D major alters");
        check(Pitch.keyAlter(-1, 6) == -1 && Pitch.keyAlter(-1, 2) == 0, "F major alters");
        // chromatic notes from the piano: fewer accidentals win a tie (Lindenbaum, Maple Leaf Rag)
        check(Pitch.spell(72, 4) == 14, "C natural in E major, not B sharp: " + Pitch.spell(72, 4));
        check(Pitch.spell(69, -5) == 17, "A natural in D flat major, not B double flat: " + Pitch.spell(69, -5));
        check(Pitch.spell(66, 2) == 20 && Pitch.spell(70, -1) == 12, "diatonic spellings unchanged");
        // respell cycles the spellings of one pitch: B -> A double sharp -> C flat -> B
        check(Pitch.nextSpelling(19) == 31 && Pitch.nextSpelling(31) == 7 && Pitch.nextSpelling(7) == 19, "respell cycle B");
        check(Pitch.nextSpelling(21) == 33 && Pitch.nextSpelling(33) == 9 && Pitch.nextSpelling(9) == 21, "respell C sharp -> B double sharp -> D flat");
        {
            Score rs = pianoScore();
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(rs);
            es.startInput();
            es.enterPitch(71, Integer.MIN_VALUE);
            int before = es.selectedNote().tpc;
            check(es.respell() && es.selectedNote().pitch == 71 && es.selectedNote().tpc != before, "respell keeps the pitch, changes the spelling");
            es.respell();
            es.respell();
            check(es.selectedNote().tpc == before, "respell cycles back");
        }
        check(MathUtil.floorMod(-1, 7) == 6 && MathUtil.floorDiv(-1, 7) == -1, "floor");

        // durations
        check(Duration.ticks(Duration.QUARTER, 1) == 720, "dotted quarter");
        check(Duration.decomposeRests(480, 1440).size() == 2, "rests on beat 2: quarter+half");
        check(Duration.decomposeRests(480, 1440).get(0)[0] == Duration.QUARTER, "first is quarter");

        // note entry
        Score s = pianoScore();
        checkVoicesFit(s, "new");
        Pos p = new Pos(0, 0, 0, 0);
        p = ScoreEdit.enter(s, p, chord(62), Duration.QUARTER, 0, false);
        check(p.tick == 480 && p.measure == 0, "advance quarter " + p);
        p = ScoreEdit.enter(s, p, chord(64, 67), Duration.EIGHTH, 1, false);
        p = ScoreEdit.enter(s, p, chord(66), Duration.N16, 0, false);
        p = ScoreEdit.enter(s, p, null, Duration.QUARTER, 0, false);
        check(p.tick == 1440, "after rest " + p);
        // half note at beat 4 crosses the bar line -> tied
        p = ScoreEdit.enter(s, p, chord(69), Duration.HALF, 0, false);
        check(p.measure == 1 && p.tick == 480, "crossed bar " + p);
        ChordRest last = s.measures.get(0).staves.get(0).voices[0].items.get(s.measures.get(0).staves.get(0).voices[0].items.size() - 1);
        check(last instanceof Chord && ((Chord) last).notes.get(0).tieForward, "tie across bar");
        checkVoicesFit(s, "after entry");
        // triplets
        p = ScoreEdit.enter(s, p, chord(71), Duration.EIGHTH, 0, true);
        check(p.tick == 640, "triplet advance " + p);
        p = ScoreEdit.enter(s, p, chord(72), Duration.EIGHTH, 0, false);
        p = ScoreEdit.enter(s, p, chord(74), Duration.EIGHTH, 0, false);
        check(p.tick == 960, "triplet end " + p);
        checkVoicesFit(s, "after triplet");
        // overwrite inside the triplet with a normal half note dissolves it
        Pos q = ScoreEdit.enter(s, new Pos(1, 0, 0, 480), chord(60), Duration.HALF, 0, false);
        check(q.tick == 1440, "overwrite " + q);
        checkVoicesFit(s, "after overwrite");
        // voice 2
        ScoreEdit.enter(s, new Pos(2, 0, 1, 0), chord(50), Duration.HALF, 0, false);
        checkVoicesFit(s, "voice 2");
        // delete
        ScoreEdit.deleteChordRest(s, new Pos(2, 0, 1, 0));
        check(s.measures.get(2).staves.get(0).voices[1].isEmpty(), "voice 2 removed after deleting");
        // duration change
        ScoreEdit.changeDuration(s, new Pos(0, 0, 0, 0), Duration.HALF, 0);
        checkVoicesFit(s, "duration change");
        // time signature change
        s.setTimeSig(1, 3, 4, false);
        checkVoicesFit(s, "3/4");
        s.setTimeSig(1, 5, 4, false);
        checkVoicesFit(s, "5/4");
        // copy paste
        ScoreEdit.Clip clip = ScoreEdit.copyRange(s, 0, 0, 0, 1);
        ScoreEdit.paste(s, clip, 3, 0);
        checkVoicesFit(s, "paste");
        // transpose
        ScoreEdit.transposeRange(s, 0, 3, 0, 2, 2);
        checkVoicesFit(s, "transpose");
        // measures
        s.insertMeasure(1);
        s.appendMeasures(2);
        s.deleteMeasures(0, 1);
        checkVoicesFit(s, "measures");
        // markings
        ChordRest first = ScoreEdit.elementAt(s, new Pos(0, 0, 0, 0));
        first.dynamic = "mf";
        first.chordSymbol = "F#m7/C#";
        first.lyric = "Hel-";
        first.articulations.add(Articulation.STACCATO);
        first.articulations.add(Articulation.FERMATA);
        s.measures.get(0).rehearsal = "A";
        s.measures.get(1).endBarline = Measure.BAR_END_REPEAT;
        s.measures.get(0).startRepeat = true;

        // mscx round trip
        String xml = MscxWriter.write(s);
        Score r = MscxReader.read(xml.getBytes("UTF-8")).score;
        checkVoicesFit(r, "mscx reload");
        String xml2 = MscxWriter.write(r);
        check(xml.equals(xml2), "mscx round trip stable");
        if (!xml.equals(xml2)) {
            Files.write(new File("/tmp/claude-0/a.mscx").toPath(), xml.getBytes("UTF-8"));
            Files.write(new File("/tmp/claude-0/b.mscx").toPath(), xml2.getBytes("UTF-8"));
        }
        ChordRest rf = ScoreEdit.elementAt(r, new Pos(0, 0, 0, 0));
        check("mf".equals(rf.dynamic) && "F#m7/C#".equals(rf.chordSymbol) && "Hel-".equals(rf.lyric), "markings kept: " + rf.dynamic + " " + rf.chordSymbol + " " + rf.lyric);
        check(rf.articulations.contains(Articulation.FERMATA) && rf.articulations.contains(Articulation.STACCATO), "articulations kept");

        // mscz
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Mscz.write(s, bo, null);
        Score z = Mscz.load(bo.toByteArray(), "x.mscz").score;
        check(MscxWriter.write(z).equals(xml), "mscz round trip");

        // musicxml round trip
        String mx = MusicXmlWriter.write(s);
        Score mr = MusicXmlReader.read(mx.getBytes("UTF-8")).score;
        checkVoicesFit(mr, "musicxml reload");
        String mx2 = MusicXmlWriter.write(mr);
        check(mx.equals(mx2), "musicxml round trip stable");
        if (!mx.equals(mx2)) {
            Files.write(new File("/tmp/claude-0/a.musicxml").toPath(), mx.getBytes("UTF-8"));
            Files.write(new File("/tmp/claude-0/b.musicxml").toPath(), mx2.getBytes("UTF-8"));
        }

        // playback
        Timeline tl = Timeline.build(s);
        check(tl.plays.size() == s.measures.size() + 2, "repeat unrolled: " + tl.plays.size());
        byte[] midi = MidiWriter.write(s, tl, 1.0);
        check(midi.length > 50 && midi[0] == 'M', "midi");
        check(tl.positionAt(0)[0] == 0, "position 0");

        // pickup
        List<Part> parts = new ArrayList<Part>();
        parts.add(Instruments.byId("flute").create());
        Score pk = Score.create("Pickup", "", parts, 3, 4, 0, 90, 4, 480);
        checkVoicesFit(pk, "pickup");
        check(pk.measures.get(0).ticks() == 480 && pk.measureNumber(1) == 1, "pickup length/numbering");
        Score pk2 = MscxReader.read(MscxWriter.write(pk).getBytes("UTF-8")).score;
        check(pk2.measures.get(0).ticks() == 480 && pk2.measures.get(0).irregular, "pickup reload");
        Score pk3 = MusicXmlReader.read(MusicXmlWriter.write(pk).getBytes("UTF-8")).score;
        check(pk3.measures.get(0).ticks() == 480, "pickup musicxml reload " + pk3.measures.get(0).ticks());

        // editor session (note input as the UI drives it)
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.EIGHTH);
            for (int i = 0; i < 6; i++) es.enterPitch(60 + i * 2, Integer.MIN_VALUE);
            es.chordMode = true;
            es.enterPitch(76, Integer.MIN_VALUE);
            es.chordMode = false;
            check(((Chord) es.selectedChordRest()).notes.size() == 2, "chord mode adds note");
            es.transpose(1);
            es.applyAccidental(-1);
            es.enterTie();
            es.enterRest();
            es.triplet = true;
            es.enterPitch(67, 1);
            es.triplet = false;
            es.enterLetter(4, false);
            es.enterLetter(0, true);
            es.moveCursor(-1);
            es.deleteSelection();
            checkVoicesFit(es.score, "editor input");
            int before = es.score.measures.size();
            es.undo();
            es.redo();
            es.stopInput();
            es.selectMeasure(1, 0);
            es.extendTo(2, 2);
            es.copy();
            es.selectMeasure(3, 0);
            es.paste();
            es.transpose(-2);
            es.toggleArticulation(Articulation.ACCENT);
            es.setDynamic("ff");
            es.setClef(Clef.BASS);
            es.setKeySig(-3);
            es.setTimeSig(6, 8, false);
            es.setBarline(Measure.BAR_DOUBLE);
            es.insertMeasureBefore();
            es.appendMeasures(2);
            es.deleteSelectedMeasures();
            checkVoicesFit(es.score, "editor ops");
            check(es.score.measures.size() >= before - 2, "measures sane");
            while (es.canUndo()) es.undo();
            checkVoicesFit(es.score, "undo all");
        }

        // render culling: tall system items (system barline, bracket, brace) must stay visible
        // while the top of their system is scrolled off screen
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("piano").create());
            ps.add(Instruments.byId("soprano").create());
            ps.add(Instruments.byId("tenor").create());
            ps.add(Instruments.byId("bass").create());
            Score sc = Score.create("Cull", "", ps, 4, 4, 0, 100, 4);
            Layout.Options lo = new Layout.Options();
            lo.width = 40;
            DisplayList dl = Layout.layout(sc, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, lo);
            DisplayList.SystemInfo si = dl.systems.get(0);
            float top = si.staffY[0], bottom = si.staffY[si.staffY.length - 1] + 4;
            check(bottom - top > 30, "cull test system is tall: " + (bottom - top));
            // a viewport showing only the bottom of each tall item, far below its top
            int tall = 0;
            for (DisplayList.Item it : dl.items) {
                float iTop, iBottom;
                if (it.kind == DisplayList.LINE && it.x == it.x2) {
                    iTop = Math.min(it.y, it.y2);
                    iBottom = Math.max(it.y, it.y2);
                } else if (it.kind == DisplayList.POLY) {
                    iTop = Float.MAX_VALUE;
                    iBottom = -Float.MAX_VALUE;
                    for (int k = 1; k < it.pts.length; k += 2) {
                        iTop = Math.min(iTop, it.pts[k]);
                        iBottom = Math.max(iBottom, it.pts[k]);
                    }
                } else continue;
                if (iBottom - iTop < 16) continue;
                tall++;
                check(it.intersects(iBottom - 2, iBottom, 2), "tall item visible from below: kind " + it.kind + " top " + iTop);
            }
            check(tall >= 3, "found system barline, bracket and brace: " + tall);
            float v0 = bottom - 4, v1 = bottom + 2;
            DisplayList.Item off = new DisplayList.Item();
            off.kind = DisplayList.LINE;
            off.y = off.y2 = top;
            check(!off.intersects(v0, v1, 12), "far-off line culled");
        }

        // playback cursor: moves steadily through each measure and on into the next one
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("piano").create());
            ps.add(Instruments.byId("soprano").create());
            Score sc = Score.create("Cursor", "", ps, 4, 4, 3, 100, 4);
            ScoreEdit.enter(sc, new Pos(0, 0, 0, 0), chord(60), Duration.WHOLE, 0, false);
            Layout.Options lo = new Layout.Options();
            lo.width = 40;
            DisplayList dl = Layout.layout(sc, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, lo);
            float prev = -Float.MAX_VALUE;
            int samples = 0;
            for (int m = 0; m < sc.measures.size(); m++) {
                int len = sc.measures.get(m).ticks();
                // a new system starts at the left margin, so only compare within one system
                boolean sameSystem = m == 0 || dl.measures.get(m).system == dl.measures.get(m - 1).system;
                if (!sameSystem) prev = -Float.MAX_VALUE;
                for (int t = 0; t <= len; t += len / 16) {
                    float x = dl.playX(m, t);
                    check(x >= prev - 1e-4f, "cursor moves forward m" + m + " t" + t + ": " + x + " < " + prev);
                    prev = x;
                    samples++;
                }
                if (m + 1 < sc.measures.size() && dl.measures.get(m + 1).system == dl.measures.get(m).system) {
                    check(Math.abs(dl.playX(m, len) - dl.playX(m + 1, 0)) < 1e-4f, "cursor continuous into m" + (m + 1));
                }
            }
            check(samples > 60, "cursor samples: " + samples);
            // halfway through a measure with a single whole note is not stuck at the note
            check(dl.playX(0, sc.measures.get(0).ticks() / 2) > dl.playX(0, 0) + 0.5f, "cursor moves within a whole-note bar");
        }

        // inline lyric entry: typing, space, composing text, backspace, undo, empty syllables
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.enterPitch(60, Integer.MIN_VALUE);
            es.enterPitch(62, Integer.MIN_VALUE);
            es.enterPitch(64, Integer.MIN_VALUE);
            es.stopInput();
            Pos a = new Pos(0, 0, 0, 0), b = new Pos(0, 0, 0, 480), c3 = new Pos(0, 0, 0, 960);
            check(es.beginLyric(a), "lyric starts on a chord");
            es.lyricType("Hel-");
            check(es.lyricEdit && es.selPos.tick == 480, "a hyphen after a syllable moves to the next note");
            es.lyricCompose("l");
            es.lyricCompose("lo");
            es.lyricFinishComposing();
            es.lyricType(" ");
            check(es.lyricEdit && es.selPos.tick == 960, "space moves to the next note");
            check(!es.lyricNext() && es.lyricEdit && es.selPos.tick == 960, "at the last note, nothing moves and the mode stays");
            es.lyricType("-");
            check(es.lyricEdit && es.selPos.tick == 960 && "".equals(lyricAt(es, c3)) && "lo".equals(lyricAt(es, b)),
                    "a hyphen on an empty last syllable has nothing to connect to");
            es.lyricBackspace();
            check(es.lyricEdit && es.selPos.tick == 480 && "lo".equals(lyricAt(es, b)), "backspace on an empty syllable goes back");
            es.lyricType("-");
            check(es.selPos.tick == 960 && "lo-".equals(lyricAt(es, b)), "typing the hyphen again moves on");
            es.lyricPrevious();
            es.lyricPrevious();
            check(!es.lyricPrevious() && es.lyricEdit && es.selPos.tick == 0, "at the first note, nothing moves and the mode stays");
            es.lyricInsertLiteral('-');
            check("Hel--".equals(lyricAt(es, a)) && es.selPos.tick == 0, "a literal hyphen does not move");
            es.lyricBackspace();
            es.lyricNext();
            es.lyricBackspace();
            es.endLyric();
            check(!es.lyricEdit, "endLyric ends entry");
            check("Hel-".equals(lyricAt(es, a)) && "lo".equals(lyricAt(es, b)) && lyricAt(es, c3) == null,
                    "syllables typed: " + lyricAt(es, a) + " / " + lyricAt(es, b));
            es.beginLyric(a);
            es.lyricBackspace();
            es.endLyric();
            check("Hel".equals(lyricAt(es, a)), "backspace removes a character");
            es.undo();
            check("Hel-".equals(lyricAt(es, a)), "undo restores the syllable");
            es.beginLyric(c3);
            es.endLyric();
            check(lyricAt(es, c3) == null, "an empty syllable is not kept");
            // where lyric entry starts
            es.clearSelection();
            Pos start = es.lyricStartPos();
            check(start != null && start.measure == 0 && start.tick == 0, "nothing selected: starts at the first chord");
            es.selectNote(b, -1);
            start = es.lyricStartPos();
            check(start != null && start.tick == 480, "starts at the selected chord");
            check(new io.github.vlesk.stave.edit.EditorState(pianoScore()).lyricStartPos() == null, "no chords: no start");
        }

        // dotted values in triplet mode (Schubert, Der Lindenbaum bar 11): a dotted 16th + 32nd inside
        // triplet eighths, entered with the duration, dot and triplet buttons
        {
            List<Part> vp = new ArrayList<Part>();
            vp.add(Instruments.byId("voice").create());
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(Score.create("T", "", vp, 3, 4, 4, 100, 2));
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.toggleDot();
            es.enterPitch(64, Integer.MIN_VALUE);
            es.setDuration(Duration.EIGHTH);
            while (es.dots != 0) es.toggleDot();
            es.enterPitch(66, Integer.MIN_VALUE);
            es.triplet = true;
            int[][] rhythm = {{Duration.N16, 1}, {Duration.N32, 0}, {Duration.EIGHTH, 0}, {Duration.N16, 1}, {Duration.N32, 0}};
            for (int[] rh : rhythm) {
                es.setDuration(rh[0]);
                while (es.dots != rh[1]) es.toggleDot();
                es.enterPitch(68, Integer.MIN_VALUE);
            }
            Voice tv = es.score.measures.get(0).staves.get(0).voices[0];
            check(tv.totalTicks() == 1440, "triplet bar fills the measure: " + tv.totalTicks());
            StringBuilder got = new StringBuilder();
            for (ChordRest cr : tv.items) got.append(cr.type).append('.').append(cr.dots).append(cr.tuplet != null ? "t" + cr.tuplet.baseType : "").append(' ');
            check(got.toString().equals("2.1 3.0 4.1t3 5.0t3 3.0t3 4.1t3 5.0t3 "), "dotted rhythm inside triplet eighths: " + got);
            check(es.cursor.measure == 1 && es.cursor.tick == 0, "cursor after the triplet: " + es.cursor);
            // a voice that ends before its bar does: next() still moves on instead of returning the same note
            tv.items.remove(tv.items.size() - 1);
            int lastIdx = tv.items.size() - 1;
            Pos nx = ScoreEdit.next(es.score, new Pos(0, 0, 0, tv.starts()[lastIdx]));
            check(nx != null && nx.measure == 1 && nx.tick == 0, "next() past a short voice: " + nx);
        }

        // a triplet quarter + eighth in one beat (Lindenbaum bar 37): triplets turned on with the eighth
        // selected make a triplet of eighths, and a quarter entered in it takes two of its slots
        {
            List<Part> vp = new ArrayList<Part>();
            vp.add(Instruments.byId("piano").create());
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(Score.create("T", "", vp, 3, 4, 4, 100, 2));
            es.startInput();
            es.setDuration(Duration.EIGHTH);
            es.setTriplet(true);
            es.setDuration(Duration.QUARTER);
            es.enterPitch(59, Integer.MIN_VALUE);
            es.setDuration(Duration.EIGHTH);
            es.enterPitch(64, Integer.MIN_VALUE);
            check(es.cursor.tick == 480, "after quarter + eighth triplet the cursor is on beat 2: " + es.cursor);
            es.setTriplet(false);
            es.setDuration(Duration.QUARTER);
            es.enterRest();
            es.enterRest();
            Voice tv = es.score.measures.get(0).staves.get(0).voices[0];
            StringBuilder got = new StringBuilder();
            int[] st = tv.starts();
            for (int k = 0; k < tv.items.size(); k++) got.append(st[k]).append(':').append(tv.items.get(k).ticks()).append(' ');
            check(got.toString().equals("0:320 320:160 480:480 960:480 "), "quarter-eighth triplet then quarters: " + got);
            check(tv.items.get(0).tuplet == tv.items.get(1).tuplet && tv.items.get(0).tuplet.baseType == Duration.EIGHTH,
                    "both in one triplet of eighths");
        }

        // keys pressed together on the piano make one chord; keys pressed one after another, separate notes
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.enterPianoKey(60, false);
            es.enterPianoKey(64, true);
            es.enterPianoKey(67, true);
            es.enterPianoKey(62, false);
            Voice pv = es.score.measures.get(0).staves.get(0).voices[0];
            check(pv.items.get(0) instanceof Chord && ((Chord) pv.items.get(0)).notes.size() == 3, "three keys held together: one chord");
            check(pv.items.get(1) instanceof Chord && ((Chord) pv.items.get(1)).notes.size() == 1 && es.cursor.tick == 960, "then a single note");
            check(!es.chordMode, "chord mode is left as it was");
        }

        // grace notes: added to the note just entered, pitch from the next key; selected, moved, deleted;
        // drawn small before the note; saved and read in both formats; played before the note
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.enterPitch(67, Integer.MIN_VALUE);
            check(es.addGrace(Chord.ACCIACCATURA, Duration.EIGHTH) && es.graceAwaitingPitch, "grace added, waiting for its pitch");
            es.enterPianoKey(69, false);
            Chord main = (Chord) es.score.measures.get(0).staves.get(0).voices[0].items.get(0);
            check(main.graces.size() == 1 && main.graces.get(0).notes.get(0).pitch == 69 && !es.graceAwaitingPitch, "the key set the grace's pitch");
            es.enterPianoKey(72, false);
            check(es.score.measures.get(0).staves.get(0).voices[0].items.get(1) instanceof Chord && es.cursor.tick == 960, "the next key enters a note again");
            es.selectGrace(new Pos(0, 0, 0, 0), 0);
            es.transpose(-2);
            check(main.graces.get(0).notes.get(0).pitch == 67, "a selected grace moves with the arrows");
            es.selectNote(new Pos(0, 0, 0, 0), 0);
            es.addGrace(Chord.APPOGGIATURA, Duration.N16);
            es.enterPitch(65, Integer.MIN_VALUE);
            check(main.graces.size() == 2 && main.graces.get(1).type == Duration.N16 && main.graces.get(1).notes.get(0).pitch == 65, "second grace, closest to the note");
            // layout: small noteheads left of the note, hits for tapping
            DisplayList gdl = Layout.layout(es.score, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, new Layout.Options());
            int smallHeads = 0, graceHits = 0;
            for (DisplayList.Item it : gdl.items) {
                if (it.kind == DisplayList.GLYPH && it.glyph == io.github.vlesk.stave.engrave.Sym.noteheadBlack && it.w < 1f && it.x < gdl.xAt(0, 0)) smallHeads++;
            }
            for (DisplayList.Hit h : gdl.hits) if (h.grace >= 0) graceHits++;
            check(smallHeads == 2 && graceHits == 2, "two grace heads before the note: " + smallHeads + " hits " + graceHits);
            // files
            String gx = MscxWriter.write(es.score);
            check(gx.contains("<acciaccatura/>") && gx.contains("<grace16/>"), "mscx grace tags");
            Chord gm = (Chord) MscxReader.read(gx.getBytes("UTF-8")).score.measures.get(0).staves.get(0).voices[0].items.get(0);
            check(gm.graces.size() == 2 && gm.graces.get(0).graceKind == Chord.ACCIACCATURA && gm.graces.get(1).type == Duration.N16, "mscx graces read back");
            String gxml = MusicXmlWriter.write(es.score);
            check(gxml.contains("<grace slash=\"yes\"/>"), "musicxml grace");
            Chord gmx = (Chord) MusicXmlReader.read(gxml.getBytes("UTF-8")).score.measures.get(0).staves.get(0).voices[0].items.get(0);
            check(gmx.graces.size() == 2 && gmx.graces.get(0).graceKind == Chord.ACCIACCATURA, "musicxml graces read back");
            // playback: graces sound first, the note after them
            Timeline gtl = Timeline.build(es.score);
            int first67 = -1, main67 = -1;
            for (Timeline.NoteEvent e : gtl.events) {
                if (e.track == 0 && e.pitch == 67 && first67 < 0) first67 = e.startTick;
                if (e.track == 0 && e.pitch == 67) main67 = e.startTick;
            }
            check(first67 == 0 && main67 > 0, "grace played first, then the note: " + first67 + " " + main67);
            // delete the selected grace
            es.selectGrace(new Pos(0, 0, 0, 0), 1);
            es.deleteSelection();
            check(main.graces.size() == 1, "grace deleted");
        }

        // slurs and hairpins: in note input they follow the entered notes (MuseScore S, <, >); on a selection
        // they reach the next note and grow by one each time; drawn, saved, read back and played
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.enterPitch(60, Integer.MIN_VALUE);
            check(es.slur() && es.slurOpen, "S opens a slur on the entered note");
            check(es.hairpin(ChordRest.CRESCENDO) && es.hairpinOpen == ChordRest.CRESCENDO, "< opens a crescendo");
            es.enterPitch(62, Integer.MIN_VALUE);
            es.enterPitch(64, Integer.MIN_VALUE);
            es.slur();
            es.hairpin(ChordRest.CRESCENDO);
            es.enterPitch(65, Integer.MIN_VALUE);
            ChordRest c0 = es.score.measures.get(0).staves.get(0).voices[0].items.get(0);
            check(c0.slurTicks == 960, "the slur followed two notes: " + c0.slurTicks);
            check(c0.hairpin == ChordRest.CRESCENDO && c0.hairpinTicks == 1440, "the crescendo covers three notes: " + c0.hairpinTicks);
            es.enterPitch(67, Integer.MIN_VALUE);
            es.enterPitch(69, Integer.MIN_VALUE);
            es.enterPitch(71, Integer.MIN_VALUE);
            es.stopInput();
            // on a selection: next note, then one more each time; across the barline
            es.selectNote(new Pos(0, 0, 0, 1440), -1);
            es.slur();
            es.slur();
            ChordRest c3 = es.score.measures.get(0).staves.get(0).voices[0].items.get(3);
            check(c3.slurTicks == 960, "slur over three notes into the next bar: " + c3.slurTicks);
            es.selectNote(new Pos(1, 0, 0, 0), -1);
            es.hairpin(ChordRest.DIMINUENDO);
            ChordRest c4 = es.score.measures.get(1).staves.get(0).voices[0].items.get(0);
            check(c4.hairpin == ChordRest.DIMINUENDO && c4.hairpinTicks == 960, "diminuendo over this and the next note");
            List<io.github.vlesk.stave.model.Spanners.Span> spans = io.github.vlesk.stave.model.Spanners.all(es.score);
            check(spans.size() == 4, "four lines: " + spans.size());
            // layout: a curve per slur, two lines per hairpin
            DisplayList ldl = Layout.layout(es.score, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, new Layout.Options());
            int polys = 0, slanted = 0;
            for (DisplayList.Item it : ldl.items) {
                if (it.kind == DisplayList.POLY && it.owner != null && it.owner.slurTicks > 0) polys++;
                if (it.kind == DisplayList.LINE && it.owner != null && it.owner.hairpin != 0 && it.y != it.y2 && it.x != it.x2) slanted++;
            }
            check(polys == 2 && slanted == 4, "slur curves and hairpin wedges drawn: " + polys + " " + slanted);
            // files
            String lx = MscxWriter.write(es.score);
            check(lx.contains("<Spanner type=\"Slur\">") && lx.contains("<Spanner type=\"HairPin\">") && lx.contains("<prev>"), "mscx spanners");
            Score lb = MscxReader.read(lx.getBytes("UTF-8")).score;
            check(MscxWriter.write(lb).equals(lx), "mscx lines round trip");
            ChordRest lb3 = lb.measures.get(0).staves.get(0).voices[0].items.get(3);
            check(lb3.slurTicks == 960 && lb.measures.get(1).staves.get(0).voices[0].items.get(0).hairpinTicks == 960, "mscx lines read back");
            String lxml = MusicXmlWriter.write(es.score);
            check(lxml.contains("<slur type=\"start\"") && lxml.contains("<wedge type=\"crescendo\"") && lxml.contains("<wedge type=\"stop\""), "musicxml lines");
            Score lbx = MusicXmlReader.read(lxml.getBytes("UTF-8")).score;
            ChordRest x0 = lbx.measures.get(0).staves.get(0).voices[0].items.get(0);
            ChordRest x4 = lbx.measures.get(1).staves.get(0).voices[0].items.get(0);
            check(x0.slurTicks == 960 && x0.hairpin == ChordRest.CRESCENDO && x0.hairpinTicks == 1440 && x4.hairpin == ChordRest.DIMINUENDO
                    && x4.hairpinTicks == 960, "musicxml lines read back: " + x0.slurTicks + " " + x0.hairpinTicks + " " + x4.hairpinTicks);
            // playback: legato under the slur, louder through the crescendo
            Timeline ltl = Timeline.build(es.score);
            int v0 = -1, v2 = -1, len0 = -1;
            for (Timeline.NoteEvent e : ltl.events) {
                if (e.track != 0) continue;
                if (e.pitch == 60) { v0 = e.velocity; len0 = e.lengthTicks; }
                if (e.pitch == 64) v2 = e.velocity;
            }
            check(v2 > v0 && len0 == 480, "crescendo and legato: " + v0 + " -> " + v2 + ", length " + len0);
            // remove
            es.selectNote(new Pos(1, 0, 0, 0), -1);
            check(es.removeLines() && c4.hairpin == 0, "remove the hairpin");
        }

        // double dots and tuplets other than triplets, with the dot button and the tuplet popup
        {
            List<Part> vp = new ArrayList<Part>();
            vp.add(Instruments.byId("piano").create());
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(Score.create("T", "", vp, 4, 4, 0, 100, 3));
            es.startInput();
            es.setDuration(Duration.QUARTER);
            es.toggleDot();
            es.toggleDot();
            check(es.dots == 2, "the dot button cycles to a double dot");
            es.enterPitch(60, Integer.MIN_VALUE);
            es.toggleDot();
            check(es.dots == 0, "and back to no dot");
            es.setDuration(Duration.N16);
            es.enterPitch(62, Integer.MIN_VALUE);
            Voice v0 = es.score.measures.get(0).staves.get(0).voices[0];
            check(v0.items.get(0).dots == 2 && v0.items.get(0).ticks() == 840, "double-dotted quarter");
            check(es.cursor.tick == 960, "after a double-dotted quarter and a 16th: beat 3");
            // a quintuplet of 16ths on beat 3, a sextuplet of 16ths on beat 4
            es.setTuplet(5);
            for (int k = 0; k < 5; k++) es.enterPitch(64 + k, Integer.MIN_VALUE);
            es.setTuplet(6);
            for (int k = 0; k < 6; k++) es.enterPitch(60 + k, Integer.MIN_VALUE);
            check(es.cursor.measure == 1 && es.cursor.tick == 0, "quintuplet and sextuplet fill beats 3 and 4: " + es.cursor);
            check(v0.totalTicks() == 1920, "bar adds up: " + v0.totalTicks());
            ChordRest qn = v0.items.get(2), sx = v0.items.get(7);
            check(qn.tuplet != null && qn.tuplet.actual == 5 && qn.tuplet.normal == 4 && qn.ticks() == 96, "quintuplet 16th = 96 ticks");
            check(sx.tuplet != null && sx.tuplet.actual == 6 && sx.tuplet.normal == 4 && sx.ticks() == 80, "sextuplet 16th = 80 ticks");
            // a duplet of eighths in 6/8 and a quadruplet
            Score six = Score.create("D", "", vp, 6, 8, 0, 100, 2);
            io.github.vlesk.stave.edit.EditorState e6 = new io.github.vlesk.stave.edit.EditorState(six);
            e6.startInput();
            e6.setDuration(Duration.EIGHTH);
            e6.setTuplet(2);
            e6.enterPitch(67, Integer.MIN_VALUE);
            e6.enterPitch(69, Integer.MIN_VALUE);
            check(e6.cursor.tick == 720 && six.measures.get(0).staves.get(0).voices[0].items.get(0).ticks() == 360, "duplet eighths fill a dotted quarter");
            e6.setTuplet(4);
            for (int k = 0; k < 4; k++) e6.enterPitch(60 + k, Integer.MIN_VALUE);
            check(e6.cursor.measure == 1 && e6.cursor.tick == 0, "quadruplet eighths fill the second dotted quarter: " + e6.cursor);
            // files keep the ratios
            Score back = MscxReader.read(MscxWriter.write(es.score).getBytes("UTF-8")).score;
            ChordRest bq = back.measures.get(0).staves.get(0).voices[0].items.get(2);
            check(bq.tuplet != null && bq.tuplet.actual == 5 && bq.tuplet.normal == 4 && back.measures.get(0).staves.get(0).voices[0].items.get(0).dots == 2,
                    "quintuplet and double dot read back");
            Score backX = MusicXmlReader.read(MusicXmlWriter.write(es.score).getBytes("UTF-8")).score;
            check(backX.measures.get(0).staves.get(0).voices[0].totalTicks() == 1920
                    && backX.measures.get(0).staves.get(0).voices[0].items.get(7).tuplet.actual == 6, "musicxml tuplets read back");
        }

        // melisma (extender line) and hyphen extension, typed as in MuseScore
        {
            io.github.vlesk.stave.edit.EditorState es = new io.github.vlesk.stave.edit.EditorState(pianoScore());
            es.startInput();
            es.setDuration(Duration.QUARTER);
            for (int k = 0; k < 6; k++) es.enterPitch(60 + k, Integer.MIN_VALUE);
            es.stopInput();
            Pos[] n = {new Pos(0, 0, 0, 0), new Pos(0, 0, 0, 480), new Pos(0, 0, 0, 960), new Pos(0, 0, 0, 1440),
                    new Pos(1, 0, 0, 0), new Pos(1, 0, 0, 480)};
            es.beginLyric(n[0]);
            es.lyricType("Glo_");
            check(es.selPos.tick == 480 && ScoreEdit.elementAt(es.score, n[0]).melisma == 1, "_ on a syllable: one-note melisma, next note");
            es.lyricType("__");
            check(es.selPos.tick == 1440 && ScoreEdit.elementAt(es.score, n[0]).melisma == 3 && lyricAt(es, n[1]) == null && lyricAt(es, n[2]) == null,
                    "each further _ holds the syllable over one more note: " + ScoreEdit.elementAt(es.score, n[0]).melisma);
            es.lyricType("ri-a");
            es.endLyric();
            check("Glo".equals(lyricAt(es, n[0])) && "ri-".equals(lyricAt(es, n[3])) && "a".equals(lyricAt(es, n[4])), "text after the melisma");
            // a new syllable on a held note shortens the melisma
            es.beginLyric(n[2]);
            es.lyricType("x");
            es.endLyric();
            check(ScoreEdit.elementAt(es.score, n[0]).melisma == 2, "melisma shortened to end before the new syllable");
            es.undo();
            check(ScoreEdit.elementAt(es.score, n[0]).melisma == 3 && lyricAt(es, n[2]) == null, "undo restores the melisma");

            // layout: the held syllable is left-aligned, the line runs to the last held notehead
            DisplayList dl = Layout.layout(es.score, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, new Layout.Options());
            float head = io.github.vlesk.stave.engrave.Sym.width(io.github.vlesk.stave.engrave.Sym.noteheadBlack);
            DisplayList.Hit glo = null;
            for (DisplayList.Hit h : dl.lyricHits) if ("Glo".equals(h.cr.lyric)) glo = h;
            check(glo != null && Math.abs(glo.x0 - dl.xAt(0, 0)) < 1e-3f, "a melisma syllable is left-aligned with its notehead");
            int lines = 0;
            for (DisplayList.Item it : dl.items) {
                if (it.kind == DisplayList.LINE && it.y == it.y2 && Math.abs(it.y - (glo.baseline - 0.05f)) < 1e-3f) {
                    lines++;
                    check(Math.abs(it.x - (glo.x1 + 0.2f)) < 1e-3f && Math.abs(it.x2 - (dl.xAt(0, 960) + head)) < 1e-3f,
                            "extender from the syllable to the last held notehead: " + it.x + ".." + it.x2);
                }
            }
            check(lines == 1, "one extender line: " + lines);

            // files: ticks_f in .mscx, <extend/> in MusicXML, both read back
            String mscx = MscxWriter.write(es.score);
            check(mscx.contains("<ticks_f>1/2</ticks_f>"), "mscx melisma length");
            Score back = MscxReader.read(mscx.getBytes("UTF-8")).score;
            check(ScoreEdit.elementAt(back, n[0]).melisma == 3, "mscx melisma read back: " + ScoreEdit.elementAt(back, n[0]).melisma);
            String melXml = MusicXmlWriter.write(es.score);
            check(melXml.contains("<extend/>"), "musicxml extend");
            Score backXml = MusicXmlReader.read(melXml.getBytes("UTF-8")).score;
            check(ScoreEdit.elementAt(backXml, n[0]).melisma == 3, "musicxml melisma read back: " + ScoreEdit.elementAt(backXml, n[0]).melisma);

            // "-" on an empty syllable carries the previous syllable's hyphen over this note
            es.beginLyric(n[3]);
            es.lyricBackspace();
            es.lyricBackspace();
            es.lyricBackspace();
            es.lyricType("bo-");
            check(es.selPos.measure == 1 && es.selPos.tick == 0, "after bo- the next note");
            es.lyricBackspace();
            check(es.selPos.measure == 1 && "".equals(lyricAt(es, n[4])), "syllable cleared");
            es.lyricType("-");
            check(es.selPos.measure == 1 && es.selPos.tick == 480 && lyricAt(es, n[4]) == null && "bo-".equals(lyricAt(es, n[3])),
                    "- on an empty syllable skips the note and keeps the hyphen going");
            es.lyricType("dy");
            es.endLyric();
            String hyMscx = MscxWriter.write(es.score);
            check(hyMscx.contains("<syllabic>begin</syllabic>") && hyMscx.contains("<syllabic>end</syllabic>"), "hyphen across the skipped note");
            // "_" after a hyphenated syllable ends the word there and holds it instead
            es.beginLyric(n[4]);
            es.lyricType("_");
            es.endLyric();
            check("bo".equals(lyricAt(es, n[3])) && ScoreEdit.elementAt(es.score, n[3]).melisma == 2 && lyricAt(es, n[4]) == null,
                    "_ turns the hyphen into an extender: " + lyricAt(es, n[3]) + " " + ScoreEdit.elementAt(es.score, n[3]).melisma);
        }

        // lyric positions are in page coordinates: below their staff, not at the top of the page
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("soprano").create());
            Score sc = Score.create("Lyr", "", ps, 4, 4, 0, 100, 40);
            for (int m = 0; m < sc.measures.size(); m += 4) {
                Chord ch = chord(67);
                ch.lyric = "la";
                ScoreEdit.enter(sc, new Pos(m, 0, 0, 0), ch, Duration.QUARTER, 0, false);
            }
            Layout.Options lo = new Layout.Options();
            lo.width = 40;
            DisplayList dl = Layout.layout(sc, new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            }, lo);
            check(dl.systems.size() > 2, "several systems: " + dl.systems.size());
            int checked = 0;
            for (DisplayList.Hit h : dl.lyricHits) {
                DisplayList.SystemInfo si = dl.systems.get(dl.measures.get(h.pos.measure).system);
                float staffBottom = si.staffY[0] + 4;
                check(h.y0 > staffBottom && h.y1 < staffBottom + 8, "lyric hit sits below its staff: " + h.y0 + " vs staff bottom " + staffBottom);
                checked++;
            }
            check(checked >= 3, "lyric hits found: " + checked);
        }

        // lyrics as MuseScore lays them out: centred on the notehead, hyphen drawn as a centred dash,
        // neighbours never overlap, and files carry <syllabic> instead of a literal hyphen
        {
            List<Part> ps = new ArrayList<Part>();
            ps.add(Instruments.byId("soprano").create());
            Score sc = Score.create("Dash", "", ps, 4, 4, 0, 100, 4);
            String[] words = {"Hal-", "le-", "lu-", "jah,", "ex-", "tra-", "or-", "di-", "na-", "ry", "a", "song"};
            Pos wp = new Pos(0, 0, 0, 0);
            for (String w : words) {
                Chord ch = chord(67);
                ch.lyric = w;
                wp = ScoreEdit.enter(sc, wp, ch, Duration.QUARTER, 0, false);
            }
            TextMetrics tm = new TextMetrics() {
                public float width(String t, int font, float sizeSp) {
                    return t.length() * sizeSp * 0.5f;
                }
            };
            Layout.Options lo = new Layout.Options();
            lo.width = 50;
            DisplayList dl = Layout.layout(sc, tm, lo);
            float head = io.github.vlesk.stave.engrave.Sym.width(io.github.vlesk.stave.engrave.Sym.noteheadBlack);
            DisplayList.Hit hal = null, le = null, jah = null;
            for (DisplayList.Hit h : dl.lyricHits) {
                if ("Hal-".equals(h.cr.lyric)) hal = h;
                if ("le-".equals(h.cr.lyric)) le = h;
                if ("jah,".equals(h.cr.lyric)) jah = h;
            }
            check(hal != null && le != null && jah != null, "lyric hits for the test words");
            float noteCentre = dl.xAt(hal.pos.measure, hal.pos.tick) + head / 2;
            check(Math.abs((hal.x0 + hal.x1) / 2 - noteCentre) < 1e-3f && Math.abs(hal.x1 - hal.x0 - 3 * 1.0f) < 1e-3f,
                    "a syllable is centred on its notehead, without its hyphen: " + hal.x0 + ".." + hal.x1 + " centre " + noteCentre);
            float jahCentre = dl.xAt(jah.pos.measure, jah.pos.tick) + head / 2;
            check(Math.abs((jah.x0 + 3 * 1.0f / 2) - jahCentre) < 1e-3f, "trailing punctuation hangs outside the centring");
            boolean hyphenText = false;
            int dashes = 0;
            for (DisplayList.Item it : dl.items) {
                if (it.kind == DisplayList.TEXT && it.text != null && it.text.endsWith("-") && it.text.length() > 1) hyphenText = true;
                if (it.kind == DisplayList.LINE && it.y == it.y2 && it.x >= hal.x1 && it.x2 <= le.x0) {
                    dashes++;
                    float gapMid = (hal.x1 + le.x0) / 2;
                    check(Math.abs((it.x + it.x2) / 2 - gapMid) < 1e-3f, "the dash is centred between the syllables");
                    check(it.x2 - it.x >= 0.4f - 1e-4f && it.x2 - it.x <= 0.6f + 1e-4f, "dash length " + (it.x2 - it.x));
                    float textBaseline = Float.NaN;
                    for (DisplayList.Item t : dl.items) if (t.kind == DisplayList.TEXT && "Hal".equals(t.text)) textBaseline = t.y;
                    check(it.y < textBaseline && it.y > textBaseline - 1.0f, "the dash sits at mid x-height of the text: " + it.y + " vs " + textBaseline);
                }
            }
            check(!hyphenText, "no hyphen is drawn as text");
            check(dashes == 1, "one dash between Hal and le: " + dashes);
            // neighbours on a system keep MuseScore's gap; hyphenated ones leave room for the dash
            for (int k = 0; k + 1 < dl.lyricHits.size(); k++) {
                DisplayList.Hit a = dl.lyricHits.get(k), b = dl.lyricHits.get(k + 1);
                if (dl.measures.get(a.pos.measure).system != dl.measures.get(b.pos.measure).system) continue;
                float need = io.github.vlesk.stave.model.Syllables.hyphenated(a.cr.lyric) ? 0.5f : 0.25f;
                check(b.x0 - a.x1 >= need * 0.85f - 1e-3f, "lyrics overlap: " + a.cr.lyric + " / " + b.cr.lyric + " gap " + (b.x0 - a.x1));
            }
            // files: <syllabic> begin/middle/end, no literal hyphen; reading gives the same syllables back
            String mscx = MscxWriter.write(sc);
            check(mscx.contains("<syllabic>begin</syllabic>") && mscx.contains("<syllabic>middle</syllabic>")
                    && mscx.contains("<syllabic>end</syllabic>") && !mscx.contains("<text>Hal-</text>") && mscx.contains("<text>Hal</text>"),
                    "mscx writes syllabic");
            Score back = MscxReader.read(mscx.getBytes("UTF-8")).score;
            Score backXml = MusicXmlReader.read(MusicXmlWriter.write(sc).getBytes("UTF-8")).score;
            for (int k = 0; k < 4; k++) {
                ChordRest o = sc.measures.get(0).staves.get(0).voices[0].items.get(k);
                ChordRest rb = back.measures.get(0).staves.get(0).voices[0].items.get(k);
                ChordRest rx = backXml.measures.get(0).staves.get(0).voices[0].items.get(k);
                check(o.lyric.equals(rb.lyric) && o.lyric.equals(rx.lyric), "syllable round trip: " + o.lyric + " / " + rb.lyric + " / " + rx.lyric);
            }
        }

        // MuseScore's own test files
        int ok = 0, total = 0;
        if (args.length > 0) {
            List<File> files = new ArrayList<File>();
            collect(new File(args[0]), files);
            for (File f : files) {
                total++;
                try {
                    MscxReader.Result res = Mscz.load(Files.readAllBytes(f.toPath()), f.getName());
                    checkVoicesFit(res.score, f.getName());
                    Score again = MscxReader.read(MscxWriter.write(res.score).getBytes("UTF-8")).score;
                    checkVoicesFit(again, f.getName() + " rewrite");
                    Timeline.build(res.score);
                    MusicXmlReader.read(MusicXmlWriter.write(res.score).getBytes("UTF-8"));
                    ok++;
                } catch (Exception e) {
                    System.out.println("load failed " + f + ": " + e);
                }
            }
            System.out.println("MuseScore test files loaded: " + ok + "/" + total);
        }
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    static void collect(File dir, List<File> out) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) collect(f, out);
            else if (f.getName().endsWith(".mscx") || f.getName().endsWith(".mscz")) out.add(f);
        }
    }
}
