package dev.olivelli.fxcenter.android;

import static org.junit.Assert.assertEquals;

import dev.olivelli.fxcenter.core.Cue;
import org.junit.Test;

public class CueAdapterTest {

    @Test
    public void rowTextMarksPlayingAndMissing() {
        Cue cue = new Cue("id", "MUS-01 Sigla.mp3", 1, null);
        assertEquals("MUS-01 Sigla", CueAdapter.rowText(cue, false, true));
        assertEquals("▶  MUS-01 Sigla", CueAdapter.rowText(cue, true, true));
        assertEquals("MUS-01 Sigla   ⤓", CueAdapter.rowText(cue, false, false));
    }

    @Test
    public void clock() {
        assertEquals("0:07", MainActivity.clock(7_900));
        assertEquals("3:00", MainActivity.clock(180_000));
    }
}
