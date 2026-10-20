package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriveFolderSourceTest {

    private static final String HELLO_MD5 = "5d41402abc4b2a76b9719d911017c592";

    @Test
    void downloadsOnceThenServesFromCache(@TempDir Path cache) throws IOException {
        FakeHttp http = new FakeHttp().on("/files/ID1?alt=media", "hello");
        DriveFolderSource src = new DriveFolderSource(new DriveClient(http, "K"), "F", cache);
        Cue cue = new Cue("ID1", "MUS-01 x.wav", 5, HELLO_MD5);

        assertFalse(src.isLocallyAvailable(cue));
        Path p1 = src.localFile(cue, Progress.NONE);
        assertTrue(src.isLocallyAvailable(cue));
        Path p2 = src.localFile(cue, Progress.NONE);
        assertEquals(p1, p2);
        assertEquals(1, http.requests.size());
        assertEquals(cache.resolve("F").resolve("ID1").resolve("MUS-01 x.wav"), p1);
    }

    @Test
    void refetchesWhenDriveReportsANewChecksum(@TempDir Path cache) throws IOException {
        FakeHttp http = new FakeHttp().on("/files/ID1?alt=media", "hello");
        DriveFolderSource src = new DriveFolderSource(new DriveClient(http, "K"), "F", cache);
        src.localFile(new Cue("ID1", "x.wav", 5, HELLO_MD5), Progress.NONE);

        Cue replaced = new Cue("ID1", "x.wav", 5, "0000000000000000000000000000ffff");
        assertFalse(src.isLocallyAvailable(replaced));
        // The second download still yields "hello", whose md5 is not the one announced: rejected.
        assertThrows(IOException.class, () -> src.localFile(replaced, Progress.NONE));
        assertFalse(Files.exists(cache.resolve("F").resolve("ID1").resolve("x.wav")));
    }

    @Test
    void trustsTheFileWhenNoChecksumIsKnown(@TempDir Path cache) throws IOException {
        FakeHttp http = new FakeHttp().on("/files/ID1?alt=media", "hello");
        DriveFolderSource src = new DriveFolderSource(new DriveClient(http, "K"), "F", cache);
        Cue cue = new Cue("ID1", "x.wav", -1, null);
        src.localFile(cue, Progress.NONE);
        assertTrue(src.isLocallyAvailable(cue));
    }

    @Test
    void keepsTheLastListingForOfflineStarts(@TempDir Path cache) throws IOException {
        FakeHttp http = new FakeHttp().on("/drive/v3/files?q=", DriveClientTest.fixture());
        DriveFolderSource src = new DriveFolderSource(new DriveClient(http, "K"), "F", cache);
        assertTrue(src.lastListing().isEmpty());
        Library online = src.list();

        DriveFolderSource offline = new DriveFolderSource(new DriveClient(new FakeHttp(), "K"), "F", cache);
        assertThrows(IOException.class, offline::list);
        Library saved = offline.lastListing();
        assertEquals(online.cues(), saved.cues());
        assertEquals(online.documents(), saved.documents());
        assertTrue(new String(Files.readAllBytes(cache.resolve("F").resolve("library.json")),
                StandardCharsets.UTF_8).contains("Vento"));
    }
}
