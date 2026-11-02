package io.github.vlesk.stave.model;

/** A tuplet ratio shared by the chords/rests that belong to it (e.g. 3 in the time of 2). */
public final class Tuplet {
    public int actual;
    public int normal;
    /** Duration type of the tuplet's base unit (used for the file format and bracket number). */
    public int baseType;

    public Tuplet(int actual, int normal, int baseType) {
        this.actual = actual;
        this.normal = normal;
        this.baseType = baseType;
    }

    /** Total length in ticks of the complete tuplet. */
    public int spanTicks() {
        return Duration.baseTicks(baseType) * normal;
    }

    public int scale(int rawTicks) {
        return rawTicks * normal / actual;
    }

    /** Tuplets offered for note input (septuplets need fractions of a tick). */
    public static final int[] OFFERED = {2, 3, 4, 5, 6};

    /** The usual ratio: a duplet is 2 in the time of 3, a triplet 3:2, a quadruplet 4:3, quintuplet and sextuplet n:4. */
    public static int normalFor(int actual) {
        switch (actual) {
            case 2: return 3;
            case 3: return 2;
            case 4: return 3;
            case 5:
            case 6:
            case 7: return 4;
            default: return Integer.highestOneBit(actual);
        }
    }

    public static String name(int actual) {
        switch (actual) {
            case 2: return "duplet";
            case 3: return "triplet";
            case 4: return "quadruplet";
            case 5: return "quintuplet";
            case 6: return "sextuplet";
            case 7: return "septuplet";
            default: return actual + "-tuplet";
        }
    }

    /** True if every value of this tuplet down to the given type is a whole number of ticks. */
    public static boolean exact(int actual, int unit) {
        return Duration.baseTicks(unit) * normalFor(actual) % actual == 0;
    }

    public Tuplet copy() {
        return new Tuplet(actual, normal, baseType);
    }
}
