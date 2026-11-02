package io.github.vlesk.stave.model;

/**
 * Clef types, with their MuseScore file names and engraving data. The table follows MuseScore's
 * ClefInfo (src/engraving/dom/clef.cpp): every clef MuseScore has except the four tablature clefs,
 * which only exist on tablature staves.
 */
public final class Clef {
    private Clef() {}

    public static final int TREBLE = 0, BASS = 1, ALTO = 2, TENOR = 3, TREBLE_8VB = 4, TREBLE_8VA = 5, BASS_8VB = 6, PERC = 7,
            SOPRANO = 8, MEZZO = 9, BARITONE_C = 10, TREBLE_15MA = 11, BASS_8VA = 12, BASS_15MA = 13, BASS_15MB = 14,
            TREBLE_15MB = 15, TREBLE_8VB_OLD = 16, TREBLE_8VB_PARENS = 17, FRENCH_VIOLIN = 18, C_19C = 19,
            SOPRANO_F18C = 20, ALTO_F18C = 21, TENOR_F18C = 22, SOPRANO_F20C = 23, ALTO_F20C = 24, TENOR_F20C = 25,
            BARITONE_F = 26, SUBBASS = 27, BASS_F18C = 28, BASS_19C = 29, PERC2 = 30, TENOR_8VB = 31, TREBLE_8VB_C = 32;
    public static final int COUNT = 33;

    // key-signature lines (half spaces below the top line): 7 sharps, then 7 flats
    private static final int[] KS_G = {0, 3, -1, 2, 5, 1, 4, 4, 1, 5, 2, 6, 3, 7};
    private static final int[] KS_F = {2, 5, 1, 4, 7, 3, 6, 6, 3, 7, 4, 8, 5, 9};
    private static final int[] KS_C1 = {5, 1, 4, 0, 3, -1, 2, 2, 6, 3, 7, 4, 8, 5};
    private static final int[] KS_C2 = {3, 6, 2, 5, 1, 4, 0, 0, 4, 1, 5, 2, 6, 3};
    private static final int[] KS_C3 = {1, 4, 0, 3, 6, 2, 5, 5, 2, 6, 3, 7, 4, 8};
    private static final int[] KS_C4 = {6, 2, 5, 1, 4, 0, 3, 3, 0, 4, 1, 5, 2, 6};
    private static final int[] KS_C5 = {4, 0, 3, -1, 2, 5, 1, 1, 5, 2, 6, 3, 7, 4};

    private static final class Info {
        final String mscx, label, name, glyph, mxlSign;
        /** Staff line of the clef (1 = bottom), MuseScore's pitch offset, MusicXML octave change. */
        final int line, pitchOffset, mxlOctave;
        final int[] keySig;

        Info(String mscx, String label, String name, int line, int pitchOffset, int[] keySig, String glyph, String mxlSign, int mxlOctave) {
            this.mscx = mscx;
            this.label = label;
            this.name = name;
            this.line = line;
            this.pitchOffset = pitchOffset;
            this.keySig = keySig;
            this.glyph = glyph;
            this.mxlSign = mxlSign;
            this.mxlOctave = mxlOctave;
        }
    }

    /** Indexed by clef constant. */
    private static final Info[] INFO = {
            new Info("G", "Treble", "Treble clef", 2, 45, KS_G, "gClef", "G", 0),
            new Info("F", "Bass", "Bass clef", 4, 33, KS_F, "fClef", "F", 0),
            new Info("C3", "Alto", "Alto clef", 3, 39, KS_C3, "cClef", "C", 0),
            new Info("C4", "Tenor", "Tenor clef", 4, 37, KS_C4, "cClef", "C", 0),
            new Info("G8vb", "Treble 8vb", "Treble clef 8va bassa", 2, 38, KS_G, "gClef8vb", "G", -1),
            new Info("G8va", "Treble 8va", "Treble clef 8va alta", 2, 52, KS_G, "gClef8va", "G", 1),
            new Info("F8vb", "Bass 8vb", "Bass clef 8va bassa", 4, 26, KS_F, "fClef8vb", "F", -1),
            new Info("PERC", "Percussion", "Percussion clef", 2, 45, KS_G, "unpitchedPercussionClef1", "percussion", 0),
            new Info("C1", "Soprano", "Soprano clef", 1, 43, KS_C1, "cClef", "C", 0),
            new Info("C2", "Mezzo-soprano", "Mezzo-soprano clef", 2, 41, KS_C2, "cClef", "C", 0),
            new Info("C5", "Baritone (C)", "Baritone clef (C clef)", 5, 35, KS_C5, "cClef", "C", 0),
            new Info("G15ma", "Treble 15ma", "Treble clef 15ma alta", 2, 59, KS_G, "gClef15ma", "G", 2),
            new Info("F8va", "Bass 8va", "Bass clef 8va alta", 4, 40, KS_F, "fClef8va", "F", 1),
            new Info("F15ma", "Bass 15ma", "Bass clef 15ma alta", 4, 47, KS_F, "fClef15ma", "F", 2),
            new Info("F15mb", "Bass 15mb", "Bass clef 15ma bassa", 4, 19, KS_F, "fClef15mb", "F", -2),
            new Info("G15mb", "Treble 15mb", "Treble clef 15ma bassa", 2, 31, KS_G, "gClef15mb", "G", -2),
            new Info("G8vbo", "Double treble", "Double treble clef 8va bassa on 2nd line", 2, 38, KS_G, "gClef8vbOld", "G", -1),
            new Info("G8vbp", "Treble (8vb)", "Treble clef optional 8va bassa", 2, 45, KS_G, "gClef8vbParens", "G", 0),
            new Info("G1", "French violin", "French violin clef", 1, 47, KS_F, "gClef", "G", 0),
            new Info("C_19C", "C (19th c.)", "C clef, H shape (19th century)", 2, 45, KS_G, "cClefSquare", "G", 0),
            new Info("C1_F18C", "Soprano (Fr.)", "Soprano clef (French, 18th century)", 1, 43, KS_C1, "cClefFrench", "C", 0),
            new Info("C3_F18C", "Alto (Fr.)", "Alto clef (French, 18th century)", 3, 39, KS_C3, "cClefFrench", "C", 0),
            new Info("C4_F18C", "Tenor (Fr.)", "Tenor clef (French, 18th century)", 4, 37, KS_C4, "cClefFrench", "C", 0),
            new Info("C1_F20C", "Soprano (Fr. 20c.)", "Soprano clef (French, 20th century)", 1, 43, KS_C1, "cClefFrench20C", "C", 0),
            new Info("C3_F20C", "Alto (Fr. 20c.)", "Alto clef (French, 20th century)", 3, 39, KS_C3, "cClefFrench20C", "C", 0),
            new Info("C4_F20C", "Tenor (Fr. 20c.)", "Tenor clef (French, 20th century)", 4, 37, KS_C4, "cClefFrench20C", "C", 0),
            new Info("F3", "Baritone (F)", "Baritone clef (F clef)", 3, 35, KS_C5, "fClef", "F", 0),
            new Info("F5", "Subbass", "Subbass clef", 5, 31, KS_G, "fClef", "F", 0),
            new Info("F_F18C", "F (French)", "F clef (French, 18th century)", 4, 33, KS_F, "fClefFrench", "F", 0),
            new Info("F_19C", "F (19th c.)", "F clef (19th century)", 4, 33, KS_F, "fClef19thCentury", "F", 0),
            new Info("PERC2", "Percussion 2", "Percussion clef 2", 2, 45, KS_G, "unpitchedPercussionClef2", "percussion", 0),
            new Info("C4_8VB", "Tenor 8vb", "Tenor clef 8va bassa", 4, 30, KS_C4, "cClef8vb", "C", -1),
            new Info("G8vbc", "Treble 8vb + C", "G clef ottava bassa with C clef", 2, 38, KS_G, "gClef8vbCClef", "G", -1),
    };

    /** The clef palette, in MuseScore's order. */
    public static final int[] PALETTE = {TREBLE, TREBLE_8VA, TREBLE_15MA, TREBLE_8VB, TREBLE_15MB, TREBLE_8VB_OLD, TREBLE_8VB_PARENS,
            TREBLE_8VB_C, FRENCH_VIOLIN, SOPRANO, MEZZO, ALTO, TENOR, TENOR_8VB, BARITONE_C, C_19C, SOPRANO_F18C, ALTO_F18C, TENOR_F18C,
            SOPRANO_F20C, ALTO_F20C, TENOR_F20C, BASS, BASS_8VA, BASS_15MA, BASS_8VB, BASS_15MB, BARITONE_F, SUBBASS, BASS_F18C,
            BASS_19C, PERC, PERC2};

    public static String mscxName(int clef) {
        return INFO[clef].mscx;
    }

    public static int fromMscx(String s) {
        if (s == null) return TREBLE;
        s = s.trim();
        for (int i = 0; i < COUNT; i++) if (INFO[i].mscx.equals(s)) return i;
        // tablature clefs (TAB, TAB4, TAB2, TAB4_SERIF) need a tablature staff: read as notation
        if (s.startsWith("TAB")) return TREBLE_8VB;
        if (s.startsWith("PERC")) return PERC;
        if (s.startsWith("G")) return TREBLE;
        if (s.startsWith("F")) return BASS;
        if (s.startsWith("C4")) return TENOR;
        if (s.startsWith("C")) return ALTO;
        return TREBLE;
    }

    /** Short name, for palette captions. */
    public static String label(int clef) {
        return INFO[clef].label;
    }

    /** MuseScore's name of the clef, e.g. "Treble clef 15ma bassa". */
    public static String name(int clef) {
        return INFO[clef].name;
    }

    /** SMuFL glyph name. */
    public static String glyphName(int clef) {
        return INFO[clef].glyph;
    }

    public static boolean isPercussion(int clef) {
        return clef == PERC || clef == PERC2;
    }

    /** Diatonic number of the top staff line (middle C = 28). */
    public static int topLineDiatonic(int clef) {
        return INFO[clef].pitchOffset - 7;
    }

    /** Staff position (half spaces below the top line) of the glyph origin, as MuseScore places it. */
    public static int glyphLine(int clef) {
        if (clef == C_19C) return 3;
        if (isPercussion(clef)) return 4;
        return 2 * (5 - INFO[clef].line);
    }

    public static String musicXmlSign(int clef) {
        return INFO[clef].mxlSign;
    }

    public static int musicXmlLine(int clef) {
        return isPercussion(clef) ? 0 : (clef == C_19C ? 2 : INFO[clef].line);
    }

    public static int musicXmlOctave(int clef) {
        return INFO[clef].mxlOctave;
    }

    /** The plain clef for a MusicXML sign, line and octave change (style variants such as French clefs read as their plain form). */
    public static int fromMusicXml(String sign, int line, int octaveChange) {
        if ("F".equals(sign)) {
            if (line == 3) return BARITONE_F;
            if (line == 5) return SUBBASS;
            if (octaveChange <= -2) return BASS_15MB;
            if (octaveChange < 0) return BASS_8VB;
            if (octaveChange >= 2) return BASS_15MA;
            if (octaveChange > 0) return BASS_8VA;
            return BASS;
        }
        if ("C".equals(sign)) {
            switch (line) {
                case 1:
                    return SOPRANO;
                case 2:
                    return MEZZO;
                case 4:
                    return octaveChange < 0 ? TENOR_8VB : TENOR;
                case 5:
                    return BARITONE_C;
                default:
                    return ALTO;
            }
        }
        if ("percussion".equals(sign)) return PERC;
        if ("G".equals(sign) && line == 1) return FRENCH_VIOLIN;
        if (octaveChange <= -2) return TREBLE_15MB;
        if (octaveChange < 0) return TREBLE_8VB;
        if (octaveChange >= 2) return TREBLE_15MA;
        if (octaveChange > 0) return TREBLE_8VA;
        return TREBLE;
    }

    /** Staff line (half spaces below the top line) of a spelled note in this clef. */
    public static int line(int clef, int pitch, int tpc) {
        return topLineDiatonic(clef) - Pitch.diatonic(pitch, tpc);
    }

    /** Staff lines of key-signature accidentals, in order of appearance (MuseScore's ClefInfo tables). */
    public static int[] keySigLines(int clef, boolean sharps) {
        int[] k = INFO[clef].keySig;
        int[] out = new int[7];
        System.arraycopy(k, sharps ? 0 : 7, out, 0, 7);
        return out;
    }

    /** Typical middle pitch used to place new notes when no reference is available. */
    public static int middlePitch(int clef) {
        return Pitch.pitchFromDiatonic(topLineDiatonic(clef) - 4, 0);
    }
}
