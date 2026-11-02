package io.github.vlesk.stave.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MuseScore's full instrument catalog (share/instruments/instruments.xml, generated into
 * {@link InstrumentData}). Ids, names, MIDI programs and clefs follow MuseScore. Scores are shown at
 * concert pitch, so staves get MuseScore's concert clef: octave-transposing instruments use octave
 * clefs and read as usual.
 */
public final class Instruments {
    private Instruments() {}

    public static final class Def {
        /** MuseScore's instrument group, e.g. "Woodwinds" or "Strings - Bowed". */
        public final String group;
        /** MuseScore instrument id, e.g. "bb-clarinet". */
        public final String id;
        /** The part's name, as MuseScore 4 puts it on the score, e.g. "Clarinet in B♭". */
        public final String name;
        /** MuseScore's longName without the trait, e.g. "Clarinet". */
        public final String longName;
        /** The name in the instrument list, e.g. "Clarinet in B♭". */
        public final String label;
        public final String shortName, musicXmlId;
        public final int program;
        public final int[] clefs;
        public final boolean drums;
        /** True for MuseScore's tablature templates; Stave shows them as standard notation. */
        public final boolean tablature;
        /** False for templates MuseScore's picker leaves out (tablature, aliases, unnamed families). */
        public final boolean listed;
        /** The template a tablature template or alias is based on, or null. */
        public final String base;

        Def(String row) {
            String[] f = row.split("\\|", -1);
            group = f[0];
            id = f[1];
            name = f[2];
            label = f[3];
            shortName = f[4];
            musicXmlId = f[5];
            program = Integer.parseInt(f[6]);
            String flags = f[7];
            drums = flags.indexOf('d') >= 0;
            tablature = flags.indexOf('t') >= 0;
            listed = !tablature && flags.indexOf('a') < 0 && flags.indexOf('h') < 0;
            String[] c = f[8].split(" ");
            clefs = new int[c.length];
            for (int i = 0; i < c.length; i++) clefs[i] = Clef.fromMscx(c[i]);
            base = f[9].isEmpty() ? null : f[9];
            longName = f[10].isEmpty() ? name : f[10];
        }

        public Part create() {
            Part p = new Part(name, shortName, id, program);
            p.drums = drums;
            for (int c : clefs) p.staves.add(new Staff(c));
            return p;
        }

        /** True if every word of the query starts a word of the label, name, short name or group. */
        public boolean matches(String query) {
            String hay = normalize(label + " " + name + " " + shortName + " " + group);
            for (String q : normalize(query).split(" ")) {
                if (q.isEmpty()) continue;
                if (!hay.startsWith(q) && !hay.contains(" " + q)) return false;
            }
            return true;
        }
    }

    /** Every MuseScore instrument template, in the order of MuseScore's instrument list. */
    public static final Def[] ALL;
    /** The templates offered in the instrument picker, in MuseScore's order. */
    public static final List<Def> LISTED;
    private static final Map<String, Def> BY_ID = new HashMap<String, Def>();

    static {
        String[] rows = InstrumentData.ROWS;
        ALL = new Def[rows.length];
        List<Def> listed = new ArrayList<Def>();
        for (int i = 0; i < rows.length; i++) {
            Def d = new Def(rows[i]);
            ALL[i] = d;
            BY_ID.put(d.id, d);
            if (d.listed) listed.add(d);
        }
        LISTED = Collections.unmodifiableList(listed);
    }

    public static Def byId(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    /**
     * Finds an instrument by its part name or list name, preferring the ones in the picker. A full name
     * ("Piccolo", "Clarinet in A") wins over a long name without the trait ("Piccolo" of "Piccolo in E♭").
     */
    public static Def byName(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (int pass = 0; pass < 4; pass++) {
            boolean listedOnly = pass < 2, full = pass % 2 == 0;
            for (Def d : ALL) {
                if (listedOnly && !d.listed) continue;
                boolean hit = full ? d.label.equalsIgnoreCase(n) || d.name.equalsIgnoreCase(n) : d.longName.equalsIgnoreCase(n);
                if (hit) return d;
            }
        }
        return null;
    }

    /**
     * Finds the instrument of a MusicXML part from its MusicXML sound id (instrument-sound) and part name.
     * Several MuseScore instruments share a sound id (all dizis are "wind.flutes.di-zi"), so the name decides
     * between them.
     */
    public static Def forMusicXml(String sound, String name) {
        Def byName = byName(name);
        if (sound == null || sound.isEmpty()) return byName;
        if (byName != null && sound.equals(byName.musicXmlId)) return byName;
        String n = name == null ? "" : name.trim();
        Def best = null;
        int bestScore = -1;
        for (Def d : ALL) {
            if (!sound.equals(d.musicXmlId)) continue;
            int score = (d.label.equalsIgnoreCase(n) || d.name.equalsIgnoreCase(n) ? 4 : d.longName.equalsIgnoreCase(n) ? 2 : 0) + (d.listed ? 1 : 0);
            if (score > bestScore) {
                best = d;
                bestScore = score;
            }
        }
        return best != null ? best : byName;
    }

    /** The picker's instrument groups, in MuseScore's order. */
    public static List<String> groups() {
        List<String> g = new ArrayList<String>();
        for (Def d : LISTED) if (!g.contains(d.group)) g.add(d.group);
        return g;
    }

    /** Picker instruments in a group (null = all) whose names match a query (null or empty = all). */
    public static List<Def> search(String group, String query) {
        List<Def> out = new ArrayList<Def>();
        boolean any = query == null || query.trim().isEmpty();
        for (Def d : LISTED) {
            if (group != null && !group.equals(d.group)) continue;
            if (any || d.matches(query)) out.add(d);
        }
        return out;
    }

    /** Lower case, ♭/♯ spelled as b/#, punctuation as spaces, so "bb clar" finds "Clarinet in B♭". */
    static String normalize(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c == '♭') b.append('b');
            else if (c == '♯') b.append('#');
            else if (c == 'á' || c == 'à' || c == 'â' || c == 'ä') b.append('a');
            else if (c == 'é' || c == 'è' || c == 'ê' || c == 'ë') b.append('e');
            else if (c == 'í' || c == 'ï') b.append('i');
            else if (c == 'ó' || c == 'ö' || c == 'ô') b.append('o');
            else if (c == 'ú' || c == 'ü') b.append('u');
            else if (c == 'ñ') b.append('n');
            else if (Character.isLetterOrDigit(c) || c == '#') b.append(c);
            else b.append(' ');
        }
        return b.toString();
    }

    /** Ready-made score templates shown in the "New score" screen. */
    public static final String[][] TEMPLATES = {
            {"Piano", "piano"},
            {"Lead sheet / voice", "voice"},
            {"Voice + piano", "voice", "piano"},
            {"SATB choir", "soprano", "alto", "tenor", "bass"},
            {"String quartet", "violin", "violin", "viola", "violoncello"},
            {"Guitar", "guitar-nylon"},
            {"Flute", "flute"},
            {"Violin + piano", "violin", "piano"},
            {"Jazz combo", "alto-saxophone", "bb-trumpet", "piano", "bass-guitar", "drumset"},
    };
}
