package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalettaTest {

    private static Library folder(String... names) {
        List<Cue> cues = new ArrayList<>();
        for (String n : names) {
            cues.add(new Cue("id-" + n, n, 1, null));
        }
        return new Library(cues, List.of());
    }

    private static List<String> names(Library lib) {
        return lib.cues().stream().map(Cue::fileName).collect(Collectors.toList());
    }

    @Test
    void timesInEveryHumanForm() {
        assertEquals(75_000, Scaletta.parseTime("75"));
        assertEquals(75_500, Scaletta.parseTime("75.5"));
        assertEquals(75_500, Scaletta.parseTime("1:15,5"));
        assertEquals(12_500, Scaletta.parseTime(" 0:12.5 "));
        assertEquals(3_675_000, Scaletta.parseTime("1:01:15"));
        assertThrows(IllegalArgumentException.class, () -> Scaletta.parseTime("1:75"));
        assertThrows(IllegalArgumentException.class, () -> Scaletta.parseTime("abc"));
        assertEquals("0:12.5", Scaletta.formatTime(12_500));
        assertEquals("1:40.0", Scaletta.formatTime(100_000));
        assertEquals("1:40.0", Scaletta.formatTime(99_960)); // rounds to the tenth
    }

    @Test
    void ordersNamedFilesFirstThenTheRestByName() {
        Scaletta s = Scaletta.parse("# commento\n\nMUS-10 c.mp3\r\nMUS-01 a.mp3 | 0:12.5 - 1:40\n");
        Library lib = s.arrange(folder("MUS-01 a.mp3", "MUS-02 b.mp3", "MUS-10 c.mp3", "SFX-01 x.wav"), "F", "M");
        assertEquals(List.of("MUS-10 c.mp3", "MUS-01 a.mp3", "MUS-02 b.mp3", "SFX-01 x.wav"), names(lib));
        assertEquals(Clip.of(12_500, 100_000), lib.cues().get(1).clip());
        assertFalse(lib.cues().get(0).clip().isClipped());
        assertEquals("F", lib.scaletta().fileId());
        assertTrue(lib.scaletta().warnings().isEmpty());
    }

    @Test
    void openEndedClipsAndLooseNames() {
        Scaletta s = Scaletta.parse("wonka | 0:30 -\nmus-01 A.MP3 | - 1:00\n");
        Library lib = s.arrange(folder("MUS-01 a.mp3", "Wonka.mp3"), null, null);
        assertEquals(List.of("Wonka.mp3", "MUS-01 a.mp3"), names(lib));
        assertEquals(Clip.of(30_000, -1), lib.cues().get(0).clip());
        assertEquals(Clip.of(0, 60_000), lib.cues().get(1).clip());
    }

    @Test
    void badLinesWarnButNeverBreakTheShow() {
        Scaletta s = Scaletta.parse("a.mp3 | 1:00 - 0:30\nmissing.mp3 | 0:10 -\na.mp3\nb.mp3 | ciao\n");
        Library lib = s.arrange(folder("a.mp3", "b.mp3"), null, null);
        assertEquals(List.of("a.mp3", "b.mp3"), names(lib));
        assertFalse(lib.cues().get(0).clip().isClipped());
        assertFalse(lib.cues().get(1).clip().isClipped());
        assertEquals(List.of("missing.mp3 | 0:10 -"), lib.scaletta().orphanLines());
        assertEquals(4, lib.scaletta().warnings().size(), lib.scaletta().warnings().toString());
    }

    @Test
    void formatRoundTrips() {
        Library lib = Scaletta.parse("b.mp3 | 0:05 - 0:20.3\na.mp3\n").arrange(folder("a.mp3", "b.mp3", "c.mp3"), null, null);
        String text = Scaletta.format(lib.cues(), List.of("gone.mp3 | 1:00 -"));
        assertTrue(text.contains("b.mp3 | 0:05.0 - 0:20.3\n"), text);
        assertTrue(text.contains("\ngone.mp3 | 1:00 -\n"), text);
        Library again = Scaletta.parse(text).arrange(folder("a.mp3", "b.mp3", "c.mp3"), null, null);
        assertEquals(lib.cues(), again.cues());
        assertEquals(List.of("gone.mp3 | 1:00 -"), again.scaletta().orphanLines());
    }

    @Test
    void localFolderReadsItAndHidesItFromDocuments(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("a.mp3"), new byte[1]);
        Files.write(dir.resolve("b.mp3"), new byte[1]);
        Files.write(dir.resolve("Scaletta.TXT"), "b.mp3 | 0:01 - 0:02\n".getBytes(StandardCharsets.UTF_8));
        Library lib = new LocalFolderSource(dir).list();
        assertEquals(List.of("b.mp3", "a.mp3"), names(lib));
        assertTrue(lib.documents().isEmpty());
        assertTrue(lib.scaletta().present());
        assertEquals(dir.resolve("Scaletta.TXT"), new LocalFolderSource(dir).scalettaFile());
    }

    @Test
    void driveListingAppliesItAndTheOfflineCopyKeepsIt(@TempDir Path cache) throws IOException {
        String listing = "{\"files\":["
                + "{\"id\":\"A\",\"name\":\"a.mp3\",\"mimeType\":\"audio/mpeg\",\"size\":\"1\",\"md5Checksum\":\"aa\"},"
                + "{\"id\":\"B\",\"name\":\"b.mp3\",\"mimeType\":\"audio/mpeg\",\"size\":\"1\",\"md5Checksum\":\"bb\"},"
                + "{\"id\":\"S\",\"name\":\"scaletta.txt\",\"mimeType\":\"text/plain\",\"size\":\"9\",\"md5Checksum\":\"ss\"}]}";
        FakeHttp http = new FakeHttp()
                .on("/files?", listing)
                .on("/files/S?alt=media", "b.mp3 | 0:03 - 0:09\ngone.mp3\n");
        DriveFolderSource src = new DriveFolderSource(new DriveClient(http, "KEY"), "FOLDER", cache);
        Library lib = src.list();
        assertEquals(List.of("b.mp3", "a.mp3"), names(lib));
        assertEquals(Clip.of(3_000, 9_000), lib.cues().get(0).clip());
        assertTrue(lib.documents().isEmpty());
        assertEquals("S", lib.scaletta().fileId());

        Library offline = src.lastListing();
        assertEquals(lib.cues(), offline.cues());
        assertEquals("S", offline.scaletta().fileId());
        assertEquals(List.of("gone.mp3"), offline.scaletta().orphanLines());
    }

    @Test
    void clipLength() {
        assertEquals(87_500, Clip.of(12_500, 100_000).lengthMillis(300_000));
        assertEquals(270_000, Clip.of(30_000, -1).lengthMillis(300_000));
        assertEquals(10_000, Clip.of(290_000, 400_000).lengthMillis(300_000));
        assertEquals(-1, Clip.of(30_000, -1).lengthMillis(-1));
        assertThrows(IllegalArgumentException.class, () -> Clip.of(5_000, 5_000));
        assertEquals(Clip.NONE, Clip.of(0, -1));
    }
}
