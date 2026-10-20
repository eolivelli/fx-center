package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DriveClientTest {

    static String fixture() throws IOException {
        try (InputStream in = DriveClientTest.class.getResourceAsStream("/drive-list.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void listsAudioAsCuesInNaturalOrderAndTheRestAsDocuments() throws IOException {
        FakeHttp http = new FakeHttp().on("/drive/v3/files?q=", fixture());
        Library lib = new DriveClient(http, "KEY").listFolder("FOLDER");

        List<String> names = new ArrayList<>();
        for (Cue c : lib.cues()) {
            names.add(c.fileName());
        }
        assertEquals(List.of("MUS-2 Sigla dei narratori.mp3", "MUS-10 Tema di Charlie.mp3",
                "SFX-01 Vento tra le assi.wav"), names);
        assertEquals(6018813L, lib.cues().get(0).size());
        assertEquals("188a197306ebbc6345f44fbcff1dd352", lib.cues().get(0).md5());

        assertEquals(2, lib.documents().size());
        assertEquals("La Fabbrica di Cioccolato", lib.documents().get(0).name());
        assertTrue(lib.documents().get(0).link().startsWith("https://docs.google.com/document/d/1ybE"));
        assertEquals("Scaletta.pdf", lib.documents().get(1).name());

        String url = http.requests.get(0);
        assertTrue(url.contains("%27FOLDER%27+in+parents"), url);
        assertTrue(url.endsWith("&key=KEY"), url);
    }

    @Test
    void followsPagination() throws IOException {
        String page1 = "{\"nextPageToken\":\"T2\",\"files\":[{\"id\":\"a\",\"name\":\"b.mp3\",\"mimeType\":\"audio/mpeg\"}]}";
        String page2 = "{\"files\":[{\"id\":\"c\",\"name\":\"a.wav\",\"mimeType\":\"audio/wav\"}]}";
        FakeHttp http = new FakeHttp().on("pageToken=T2", page2).on("/drive/v3/files?q=", page1);
        Library lib = new DriveClient(http, "K").listFolder("F");
        assertEquals(2, lib.cues().size());
        assertEquals("a.wav", lib.cues().get(0).fileName());
        assertEquals(-1, lib.cues().get(0).size());
        assertNull(lib.cues().get(0).md5());
        assertEquals(2, http.requests.size());
    }

    @Test
    void downloadWritesTheFileAndReturnsItsMd5(@TempDir Path dir) throws IOException {
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        FakeHttp http = new FakeHttp().on("/drive/v3/files/ID1?alt=media", body);
        Path target = dir.resolve("sub").resolve("x.wav");
        List<long[]> seen = new ArrayList<>();
        String md5 = new DriveClient(http, "K").download("ID1", target, "x", (w, d, t) -> seen.add(new long[]{d, t}));
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5);
        assertEquals("hello", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertEquals(5, seen.get(seen.size() - 1)[0]);
        assertEquals(5, seen.get(seen.size() - 1)[1]);
        assertFalse(Files.exists(dir.resolve("sub").resolve("x.wav.part")));
    }

    @Test
    void failedDownloadLeavesNothingBehind(@TempDir Path dir) {
        FakeHttp http = new FakeHttp();
        Path target = dir.resolve("x.wav");
        assertThrows(IOException.class, () -> new DriveClient(http, "K").download("ID1", target, "x", Progress.NONE));
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(dir.resolve("x.wav.part")));
    }
}
