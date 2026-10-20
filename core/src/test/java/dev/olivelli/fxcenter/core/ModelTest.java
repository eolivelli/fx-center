package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelTest {

    @Test
    void naturalOrderCountsNumbers() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "MUS-10 b.mp3", "mus-2 a.mp3", "MUS-02 a.mp3", "SFX-1.wav", "MUS-1.mp3", "intro.mp3"));
        names.sort(NaturalOrder::compare);
        assertEquals(List.of("intro.mp3", "MUS-1.mp3", "mus-2 a.mp3", "MUS-02 a.mp3", "MUS-10 b.mp3", "SFX-1.wav"),
                names);
    }

    @Test
    void cueTitleAndKind() {
        assertEquals("MUS-01 Sigla", new Cue("a", "MUS-01 Sigla.mp3", 1, null).title());
        assertEquals(Cue.Kind.MUSIC, new Cue("a", "mus-01 Sigla.mp3", 1, null).kind());
        assertEquals(Cue.Kind.EFFECT, new Cue("a", "SFX-07 porta.wav", 1, null).kind());
        assertEquals(Cue.Kind.EFFECT, new Cue("a", "FX-07 porta.wav", 1, null).kind());
        assertEquals(Cue.Kind.OTHER, new Cue("a", "applausi.wav", 1, null).kind());
        assertEquals("noext", new Cue("a", "noext", 1, null).title());
    }

    @Test
    void audioByExtension() {
        assertTrue(AudioFiles.isAudio("x.MP3"));
        assertTrue(AudioFiles.isAudio("x.wav"));
        assertTrue(AudioFiles.isAudio("x.ogg"));
        assertFalse(AudioFiles.isAudio("x.pdf"));
        assertFalse(AudioFiles.isAudio("mp3"));
    }

    @Test
    void driveLinks() {
        String id = "1esZtjXmn_0XvrELADnSSzhaxIjDxU8K0";
        assertEquals(id, DriveLinks.folderId("https://drive.google.com/drive/folders/" + id + "?usp=sharing"));
        assertEquals(id, DriveLinks.folderId("https://drive.google.com/drive/u/0/folders/" + id));
        assertEquals(id, DriveLinks.folderId("https://drive.google.com/open?id=" + id));
        assertEquals(id, DriveLinks.folderId("  " + id + "\n"));
        assertNull(DriveLinks.folderId("not a link"));
        assertNull(DriveLinks.folderId(""));
        assertNull(DriveLinks.folderId(null));
    }

    @Test
    void settingsRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("conf").resolve("settings.properties");
        Settings fresh = Settings.load(file);
        assertEquals(Settings.Mode.DRIVE, fresh.mode());
        assertEquals(Defaults.DRIVE_FOLDER_ID, fresh.driveFolderId());

        fresh.setMode(Settings.Mode.LOCAL);
        fresh.setLocalFolder("/tmp/musiche");
        fresh.setDriveFolderId("abc");
        fresh.setVolume(0.42);
        fresh.save(file);

        Settings again = Settings.load(file);
        assertEquals(Settings.Mode.LOCAL, again.mode());
        assertEquals("/tmp/musiche", again.localFolder());
        assertEquals("abc", again.driveFolderId());
        assertEquals(0.42, again.volume(), 1e-9);
    }
}
