package io.github.vlesk.stave;

import io.github.vlesk.stave.engrave.DisplayList;
import io.github.vlesk.stave.engrave.Layout;
import io.github.vlesk.stave.engrave.Sym;
import io.github.vlesk.stave.engrave.TextMetrics;
import io.github.vlesk.stave.io.Mscz;
import io.github.vlesk.stave.model.*;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/** Renders scores to PNG with Java2D to check the engraving engine without a device. */
public class RenderPng {
    static Font leland, supplement, edwin, edwinItalic, edwinBold;
    static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    static Font font(int f) {
        switch (f) {
            case TextMetrics.FONT_ITALIC: return edwinItalic;
            case TextMetrics.FONT_BOLD: return edwinBold;
            case TextMetrics.FONT_MUSIC: return leland;
            default: return edwin;
        }
    }

    static final TextMetrics TM = new TextMetrics() {
        public float width(String text, int f, float size) {
            Font fo = font(f).deriveFont(100f);
            return (float) fo.getStringBounds(text, FRC).getWidth() / 100f * size;
        }
    };

    public static void render(Score s, float widthSp, float sp, File out) throws Exception {
        Layout.Options o = new Layout.Options();
        o.width = widthSp;
        DisplayList dl = Layout.layout(s, TM, o);
        int w = (int) (dl.width * sp), h = (int) (dl.height * sp);
        BufferedImage img = new BufferedImage(w, Math.max(h, 10), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        for (DisplayList.Item it : dl.items) {
            g.setColor(it.color == DisplayList.C_MUTED ? Color.GRAY : Color.BLACK);
            switch (it.kind) {
                case DisplayList.GLYPH:
                    g.setFont((Sym.SUPPLEMENT[it.glyph] ? supplement : leland).deriveFont(4 * sp * it.w));
                    g.drawString(String.valueOf(Sym.CODE[it.glyph]), it.x * sp, it.y * sp);
                    break;
                case DisplayList.LINE:
                    g.setStroke(new BasicStroke(Math.max(1f, it.w * sp), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    g.draw(new Line2D.Float(it.x * sp, it.y * sp, it.x2 * sp, it.y2 * sp));
                    break;
                case DisplayList.POLY: {
                    Path2D.Float p = new Path2D.Float();
                    p.moveTo(it.pts[0] * sp, it.pts[1] * sp);
                    for (int i = 2; i < it.pts.length; i += 2) p.lineTo(it.pts[i] * sp, it.pts[i + 1] * sp);
                    p.closePath();
                    g.fill(p);
                    break;
                }
                case DisplayList.TEXT: {
                    Font f = font(it.font).deriveFont(it.w * sp);
                    g.setFont(f);
                    float tw = (float) f.getStringBounds(it.text, FRC).getWidth();
                    float x = it.x * sp;
                    if (it.align == DisplayList.ALIGN_CENTER) x -= tw / 2;
                    if (it.align == DisplayList.ALIGN_RIGHT) x -= tw;
                    g.drawString(it.text, x, it.y * sp);
                    break;
                }
                case DisplayList.RECT_OUTLINE:
                    g.setStroke(new BasicStroke(Math.max(1f, it.w * sp)));
                    g.draw(new Rectangle2D.Float(it.x * sp, it.y * sp, (it.x2 - it.x) * sp, (it.y2 - it.y) * sp));
                    break;
                default:
                    break;
            }
        }
        ImageIO.write(img, "png", out);
    }

    static Chord ch(int key, int... p) {
        Chord c = new Chord();
        for (int x : p) c.addNote(new Note(x, Pitch.spell(x, key)));
        return c;
    }

    public static Score demo() {
        List<Part> parts = new ArrayList<Part>();
        parts.add(Instruments.byId("voice").create());
        parts.add(Instruments.byId("piano").create());
        Score s = Score.create("Morning Song", "Demo Composer", parts, 4, 4, -1, 96, 8);
        s.subtitle = "for voice and piano";
        s.measures.get(0).tempoText = "Andante";
        int k = -1;
        Pos p = new Pos(0, 0, 0, 0);
        int[][] mel = {{65, 2, 0}, {67, 3, 0}, {69, 3, 0}, {70, 2, 1}, {72, 3, 0}, {74, 1, 0}, {72, 2, 0}, {70, 3, 0}, {69, 3, 0},
                {67, 2, 0}, {66, 2, 0}, {65, 1, 0}};
        String[] lyr = {"Ri-", "sing", "with", "the", "sun", "we", "sing", "a", "new", "day", "is", "here"};
        for (int i = 0; i < mel.length; i++) {
            Chord c = ch(k, mel[i][0]);
            c.lyric = lyr[i];
            p = ScoreEdit.enter(s, p, c, mel[i][1], mel[i][2], false);
        }
        ScoreEdit.elementAt(s, new Pos(0, 0, 0, 0)).dynamic = "mp";
        ScoreEdit.elementAt(s, new Pos(0, 0, 0, 0)).chordSymbol = "F";
        // triplet + tie across barline
        p = ScoreEdit.enter(s, p, ch(k, 72), Duration.EIGHTH, 0, true);
        p = ScoreEdit.enter(s, p, ch(k, 74), Duration.EIGHTH, 0, false);
        p = ScoreEdit.enter(s, p, ch(k, 76), Duration.EIGHTH, 0, false);
        p = ScoreEdit.enter(s, p, ch(k, 77), Duration.HALF, 1, false);
        p = ScoreEdit.enter(s, p, ch(k, 73), Duration.N16, 0, false);
        p = ScoreEdit.enter(s, p, ch(k, 74), Duration.N16, 0, false);
        p = ScoreEdit.enter(s, p, ch(k, 76), Duration.EIGHTH, 1, false);
        p = ScoreEdit.enter(s, p, ch(k, 77), Duration.N16, 0, false);
        ChordRest st = ScoreEdit.elementAt(s, new Pos(3, 0, 0, 0));
        if (st != null) st.articulations.add(Articulation.ACCENT);
        // piano right hand chords
        Pos q = new Pos(0, 1, 0, 0);
        int[][] rh = {{65, 69, 72}, {65, 69, 72}, {67, 70, 74}, {65, 69, 72}, {64, 67, 72}, {65, 69, 72}, {62, 65, 70}, {60, 64, 67, 70}};
        for (int[] c : rh) q = ScoreEdit.enter(s, q, ch(k, c), Duration.HALF, 0, false);
        for (int i = 0; i < 4; i++) {
            Chord c = ch(k, 72 + i);
            c.articulations.add(Articulation.STACCATO);
            q = ScoreEdit.enter(s, q, c, Duration.EIGHTH, 0, false);
        }
        // left hand with two voices
        Pos l = new Pos(0, 2, 0, 0);
        int[] bass = {41, 48, 53, 48, 43, 50, 55, 50, 41, 48, 53, 48, 36, 43, 48, 43};
        for (int b : bass) l = ScoreEdit.enter(s, l, ch(k, b), Duration.QUARTER, 0, false);
        Pos l2 = new Pos(0, 2, 1, 0);
        ScoreEdit.enter(s, l2, ch(k, 29), Duration.WHOLE, 0, false);
        ScoreEdit.elementAt(s, new Pos(0, 2, 0, 0)).dynamic = "p";
        s.measures.get(3).endBarline = Measure.BAR_DOUBLE;
        s.measures.get(4).rehearsal = "A";
        s.measures.get(4).keyFifths = 2;
        s.setKeySig(4, 2);
        s.measures.get(4).staves.get(2).clef = Clef.TREBLE;
        s.measures.get(6).staves.get(2).clef = Clef.BASS;
        s.measures.get(5).startRepeat = true;
        s.measures.get(6).endBarline = Measure.BAR_END_REPEAT;
        Pos r = new Pos(4, 0, 0, 0);
        int[] m5 = {74, 73, 71, 69, 78, 76, 74, 73};
        for (int x : m5) {
            Chord c = new Chord();
            c.addNote(new Note(x, Pitch.spell(x, 2)));
            r = ScoreEdit.enter(s, r, c, Duration.EIGHTH, 0, false);
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        String fonts = args[0];
        leland = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Leland.otf"));
        supplement = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "StaveSupplement.otf"));
        edwin = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Edwin-Roman.otf"));
        edwinItalic = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Edwin-Italic.otf"));
        edwinBold = Font.createFont(Font.TRUETYPE_FONT, new File(fonts, "Edwin-Bold.otf"));
        File outDir = new File(args[1]);
        outDir.mkdirs();
        Score d = demo();
        render(d, 70, 14, new File(outDir, "demo-phone.png"));
        render(d, 120, 12, new File(outDir, "demo-tablet.png"));
        for (int i = 2; i < args.length; i++) {
            File f = new File(args[i]);
            Score s = Mscz.load(Files.readAllBytes(f.toPath()), f.getName()).score;
            render(s, 100, 10, new File(outDir, f.getName().replaceAll("\\.\\w+$", "") + ".png"));
        }
        System.out.println("rendered");
    }
}
