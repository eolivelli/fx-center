package dev.olivelli.fxcenter.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.olivelli.fxcenter.core.Clip;
import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.Library;
import dev.olivelli.fxcenter.core.LocalFolderSource;
import dev.olivelli.fxcenter.core.Scaletta;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalettaStoreTest {

    @Test
    void localSaveReordersClipsAndDetectsAConcurrentEdit(@TempDir Path dir, @TempDir Path home) throws IOException {
        for (String n : new String[]{"a.mp3", "b.mp3", "c.mp3"}) {
            Files.write(dir.resolve(n), new byte[1]);
        }
        LocalFolderSource src = new LocalFolderSource(dir);
        ScalettaStore store = new ScalettaStore(new GoogleLogin(home));
        assertFalse(store.needsLogin(src));

        // First save: no file yet.
        Library lib = src.list();
        List<Cue> cues = new ArrayList<>(lib.cues());
        Collections.reverse(cues);
        cues.set(0, cues.get(0).withClip(Clip.of(1_000, 2_500)));
        store.save(src, lib.scaletta(), Scaletta.format(cues, List.of()), false);

        Library again = src.list();
        assertEquals(List.of("c.mp3", "b.mp3", "a.mp3"),
                again.cues().stream().map(Cue::fileName).collect(Collectors.toList()));
        assertEquals(Clip.of(1_000, 2_500), again.cues().get(0).clip());

        // Someone edits the file by hand after the app read it: the next save must not clobber it.
        Files.write(dir.resolve(Scaletta.FILE_NAME), "a.mp3\n".getBytes(StandardCharsets.UTF_8));
        String text = Scaletta.format(again.cues(), List.of());
        assertThrows(ScalettaStore.Conflict.class, () -> store.save(src, again.scaletta(), text, false));
        store.save(src, again.scaletta(), text, true);
        assertEquals(text, new String(Files.readAllBytes(dir.resolve(Scaletta.FILE_NAME)), StandardCharsets.UTF_8));
    }
}
