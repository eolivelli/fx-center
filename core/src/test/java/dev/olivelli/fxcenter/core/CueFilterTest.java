package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CueFilterTest {

    private static Cue cue(String name) {
        return new Cue(name, name, 1, null);
    }

    private final List<Cue> cues = Arrays.asList(
            cue("MUS-08 Tema di Charlie, carillon, breve.mp3"),
            cue("MUS-14 Tema di Charlie, carillon lentissimo.mp3"),
            cue("SFX-25 Tonfo enorme nella cioccolata.mp3"),
            cue("Perché no.mp3"));

    @Test
    void emptyQueryShowsEverything() {
        assertFalse(CueFilter.of("").isActive());
        assertFalse(CueFilter.of("   ").isActive());
        assertFalse(CueFilter.of(null).isActive());
        assertArrayEquals(new int[] {0, 1, 2, 3}, CueFilter.of(" ").visible(cues));
    }

    @Test
    void everyWordMustMatchInAnyOrder() {
        assertArrayEquals(new int[] {0, 1}, CueFilter.of("charlie").visible(cues));
        assertArrayEquals(new int[] {1}, CueFilter.of("lent tema").visible(cues));
        assertArrayEquals(new int[] {}, CueFilter.of("tema tonfo").visible(cues));
    }

    @Test
    void caseAccentsAndPunctuationDoNotCount() {
        assertTrue(CueFilter.of("PERCHE").matches(cues.get(3)));
        assertTrue(CueFilter.of("perché").matches(cues.get(3)));
        assertTrue(CueFilter.of("mus 14").matches(cues.get(1)));
        assertTrue(CueFilter.of("mus-14").matches(cues.get(1)));
        assertFalse(CueFilter.of("sfx25").matches(cues.get(2)));
        assertTrue(CueFilter.of("carillon,").matches(cues.get(0)));
    }
}
