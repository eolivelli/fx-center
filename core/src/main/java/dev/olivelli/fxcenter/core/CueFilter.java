package dev.olivelli.fxcenter.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The search box of both apps: which cues match what the user typed.
 *
 * <p>Forgiving on purpose, since it is typed in a hurry, in the dark: case and
 * accents do not count ({@code "perche"} finds {@code "Perché"}), punctuation
 * counts as a space, and every word typed must appear somewhere in the title,
 * in any order ({@code "tema lent"} finds {@code "MUS-14 Tema di Charlie,
 * carillon lentissimo"}).
 */
public final class CueFilter {

    private final String[] words;

    private CueFilter(String[] words) {
        this.words = words;
    }

    public static CueFilter of(String query) {
        String n = normalize(query == null ? "" : query).trim();
        return new CueFilter(n.isEmpty() ? new String[0] : n.split(" +"));
    }

    /** Whether the query has anything in it; an empty filter shows every cue. */
    public boolean isActive() {
        return words.length > 0;
    }

    public boolean matches(Cue cue) {
        String title = " " + normalize(cue.title()) + " ";
        for (String w : words) {
            if (!title.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** The positions in {@code cues} of the cues that match, in order. */
    public int[] visible(List<Cue> cues) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < cues.size(); i++) {
            if (matches(cues.get(i))) {
                out.add(i);
            }
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = out.get(i);
        }
        return a;
    }

    /** Lower case, accents removed, anything that is not a letter or a digit as one space. */
    static String normalize(String s) {
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return d.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
    }
}
