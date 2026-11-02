package io.github.vlesk.stave.model;

/** Dynamic markings with their glyphs and MIDI velocities. */
public final class Dynamics {
    private Dynamics() {}

    public static final String[] ALL = {"ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "fp", "sf", "sfz", "rf", "rfz"};
    private static final String[] SMUFL = {"dynamicPPP", "dynamicPP", "dynamicPiano", "dynamicMP", "dynamicMF", "dynamicForte",
            "dynamicFF", "dynamicFFF", "dynamicFortePiano", "dynamicSforzato", "dynamicSforzando1", "dynamicRinforzando1", "dynamicRinforzando2"};
    private static final int[] VELOCITY = {16, 33, 49, 64, 80, 96, 112, 126, 96, 112, 112, 112, 112};

    public static int index(String d) {
        if (d == null) return -1;
        for (int i = 0; i < ALL.length; i++) if (ALL[i].equals(d)) return i;
        return -1;
    }

    public static String smufl(String d) {
        int i = index(d);
        return i < 0 ? null : SMUFL[i];
    }

    public static int velocity(String d) {
        int i = index(d);
        return i < 0 ? 80 : VELOCITY[i];
    }

    /** True for accent-like dynamics that do not change the running level. */
    public static boolean isAccent(String d) {
        return "sf".equals(d) || "sfz".equals(d) || "rf".equals(d) || "rfz".equals(d) || "fp".equals(d);
    }
}
