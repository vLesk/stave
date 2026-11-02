package io.github.vlesk.stave.model;

/** Clef types supported by the editor, with their MuseScore file names and engraving data. */
public final class Clef {
    private Clef() {}

    public static final int TREBLE = 0, BASS = 1, ALTO = 2, TENOR = 3, TREBLE_8VB = 4, TREBLE_8VA = 5, BASS_8VB = 6, PERC = 7,
            SOPRANO = 8, MEZZO = 9, BARITONE_C = 10, TREBLE_15MA = 11, BASS_8VA = 12, BASS_15MA = 13, BASS_15MB = 14;
    public static final int COUNT = 15;

    /** MuseScore clef type names as written in .mscx files. */
    private static final String[] MSCX = {"G", "F", "C3", "C4", "G8vb", "G8va", "F8vb", "PERC", "C1", "C2", "C5", "G15ma", "F8va", "F15ma", "F15mb"};
    private static final String[] LABELS = {"Treble", "Bass", "Alto", "Tenor", "Treble 8vb", "Treble 8va", "Bass 8vb", "Percussion",
            "Soprano", "Mezzo-soprano", "Baritone (C)", "Treble 15ma", "Bass 8va", "Bass 15ma", "Bass 15mb"};
    /** Diatonic number of the top staff line (middle C = 28). */
    private static final int[] TOP_LINE_DIA = {38, 26, 32, 30, 31, 45, 19, 38, 36, 34, 28, 52, 33, 40, 12};
    /** Staff position (half spaces from top line) of the glyph origin. */
    private static final int[] GLYPH_LINE = {6, 2, 4, 2, 6, 6, 2, 4, 8, 6, 0, 6, 2, 2, 2};
    /** MusicXML sign/line/octave change. */
    private static final String[] MXL_SIGN = {"G", "F", "C", "C", "G", "G", "F", "percussion", "C", "C", "C", "G", "F", "F", "F"};
    private static final int[] MXL_LINE = {2, 4, 3, 4, 2, 2, 4, 0, 1, 2, 5, 2, 4, 4, 4};
    private static final int[] MXL_OCT = {0, 0, 0, 0, -1, 1, -1, 0, 0, 0, 0, 2, 1, 2, -2};

    public static String mscxName(int clef) {
        return MSCX[clef];
    }

    public static int fromMscx(String s) {
        if (s == null) return TREBLE;
        s = s.trim();
        for (int i = 0; i < COUNT; i++) if (MSCX[i].equals(s)) return i;
        // Clefs Stave cannot draw yet fall back to the nearest one.
        if (s.startsWith("PERC")) return PERC;
        if (s.equals("G15mb")) return TREBLE_8VB;
        if (s.startsWith("G")) return TREBLE;
        if (s.startsWith("F")) return BASS;
        if (s.startsWith("C4")) return TENOR;
        if (s.startsWith("C")) return ALTO;
        if (s.startsWith("TAB")) return TREBLE_8VB;
        return TREBLE;
    }

    public static String label(int clef) {
        return LABELS[clef];
    }

    public static int topLineDiatonic(int clef) {
        return TOP_LINE_DIA[clef];
    }

    public static int glyphLine(int clef) {
        return GLYPH_LINE[clef];
    }

    public static String musicXmlSign(int clef) {
        return MXL_SIGN[clef];
    }

    public static int musicXmlLine(int clef) {
        return MXL_LINE[clef];
    }

    public static int musicXmlOctave(int clef) {
        return MXL_OCT[clef];
    }

    public static int fromMusicXml(String sign, int line, int octaveChange) {
        if ("F".equals(sign)) {
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
                    return TENOR;
                case 5:
                    return BARITONE_C;
                default:
                    return ALTO;
            }
        }
        if ("percussion".equals(sign)) return PERC;
        if (octaveChange < 0) return TREBLE_8VB;
        if (octaveChange >= 2) return TREBLE_15MA;
        if (octaveChange > 0) return TREBLE_8VA;
        return TREBLE;
    }

    /** Staff line (half spaces below the top line) of a spelled note in this clef. */
    public static int line(int clef, int pitch, int tpc) {
        return TOP_LINE_DIA[clef] - Pitch.diatonic(pitch, tpc);
    }

    /** Staff lines of key-signature accidentals, in order of appearance (MuseScore's ClefInfo tables). */
    public static int[] keySigLines(int clef, boolean sharps) {
        int[] t;
        switch (clef) {
            case BASS:
            case BASS_8VB:
            case BASS_8VA:
            case BASS_15MA:
            case BASS_15MB:
                t = sharps ? new int[]{2, 5, 1, 4, 7, 3, 6} : new int[]{6, 3, 7, 4, 8, 5, 9};
                break;
            case SOPRANO:
                t = sharps ? new int[]{5, 1, 4, 0, 3, -1, 2} : new int[]{2, 6, 3, 7, 4, 8, 5};
                break;
            case MEZZO:
                t = sharps ? new int[]{3, 6, 2, 5, 1, 4, 0} : new int[]{0, 4, 1, 5, 2, 6, 3};
                break;
            case ALTO:
                t = sharps ? new int[]{1, 4, 0, 3, 6, 2, 5} : new int[]{5, 2, 6, 3, 7, 4, 8};
                break;
            case TENOR:
                t = sharps ? new int[]{6, 2, 5, 1, 4, 0, 3} : new int[]{3, 0, 4, 1, 5, 2, 6};
                break;
            case BARITONE_C:
                t = sharps ? new int[]{4, 0, 3, -1, 2, 5, 1} : new int[]{1, 5, 2, 6, 3, 7, 4};
                break;
            default:
                t = sharps ? new int[]{0, 3, -1, 2, 5, 1, 4} : new int[]{4, 1, 5, 2, 6, 3, 7};
        }
        return t;
    }

    /** Typical middle pitch used to place new notes when no reference is available. */
    public static int middlePitch(int clef) {
        return Pitch.pitchFromDiatonic(TOP_LINE_DIA[clef] - 4, 0);
    }
}
