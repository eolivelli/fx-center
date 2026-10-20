package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFolderSourceTest {

    @Test
    void splitsAudioFromDocumentsAndSkipsHiddenAndDirs(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("SFX-3 porta.WAV"), new byte[10]);
        Files.write(dir.resolve("MUS-01 sigla.mp3"), new byte[20]);
        Files.write(dir.resolve("copione.pdf"), new byte[5]);
        Files.write(dir.resolve(".DS_Store"), new byte[1]);
        Files.createDirectory(dir.resolve("vecchie"));

        LocalFolderSource src = new LocalFolderSource(dir);
        Library lib = src.list();
        assertEquals(List.of("MUS-01 sigla.mp3", "SFX-3 porta.WAV"),
                lib.cues().stream().map(Cue::fileName).collect(Collectors.toList()));
        assertEquals(20, lib.cues().get(0).size());
        assertEquals(List.of("copione.pdf"),
                lib.documents().stream().map(Document::name).collect(Collectors.toList()));
        assertTrue(lib.documents().get(0).link().startsWith("file:"));

        Cue cue = lib.cues().get(0);
        assertTrue(src.isLocallyAvailable(cue));
        assertEquals(dir.resolve("MUS-01 sigla.mp3"), src.localFile(cue, Progress.NONE));
    }

    @Test
    void missingFolderIsAnError(@TempDir Path dir) {
        assertThrows(IOException.class, () -> new LocalFolderSource(dir.resolve("nope")).list());
    }
}
