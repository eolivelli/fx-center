package dev.olivelli.fxcenter.desktop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.olivelli.fxcenter.core.DriveFolderSource;
import dev.olivelli.fxcenter.core.Library;
import dev.olivelli.fxcenter.core.LibrarySource;
import dev.olivelli.fxcenter.core.LocalFolderSource;
import dev.olivelli.fxcenter.core.Scaletta;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.UUID;

/**
 * Writes {@code scaletta.txt} back where it came from: straight into a local
 * folder, or to the Drive folder as the signed-in Google user.
 *
 * <p>Before writing, it checks that the file is still the one the app read, so
 * a hand edit made in Drive meanwhile is not silently overwritten: the caller
 * gets a {@link Conflict} and asks the user.
 */
final class ScalettaStore {

    /** The file changed (or appeared) since the app read it. */
    static final class Conflict extends IOException {
        Conflict() {
            super(Scaletta.FILE_NAME + " è stato modificato da qualcun altro dopo che l'ho letto");
        }
    }

    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files";

    private final GoogleLogin login;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    ScalettaStore(GoogleLogin login) {
        this.login = login;
    }

    /** Whether saving to this source will first open the browser to sign in. */
    boolean needsLogin(LibrarySource source) {
        return source instanceof DriveFolderSource && !login.loggedIn();
    }

    /**
     * @param read  what the app read, to detect a concurrent change
     * @param force write even if the file changed meanwhile
     */
    void save(LibrarySource source, Library.ScalettaInfo read, String text, boolean force) throws IOException {
        if (source instanceof LocalFolderSource) {
            saveLocal((LocalFolderSource) source, read, text, force);
        } else if (source instanceof DriveFolderSource) {
            saveDrive(((DriveFolderSource) source).folderId(), read, text, force);
        } else {
            throw new IOException("Non so salvare la scaletta qui");
        }
    }

    private static void saveLocal(LocalFolderSource src, Library.ScalettaInfo read, String text, boolean force)
            throws IOException {
        Path file = src.scalettaFile();
        if (!force) {
            String now = Files.isRegularFile(file) ? Scaletta.md5(Files.readAllBytes(file)) : null;
            String then = read.present() ? read.md5() : null;
            if (now != null && !now.equalsIgnoreCase(String.valueOf(then))) {
                throw new Conflict();
            }
        }
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void saveDrive(String folderId, Library.ScalettaInfo read, String text, boolean force) throws IOException {
        String token = login.accessToken();
        String fileId = read.fileId();
        String currentMd5;
        if (fileId != null) {
            JsonObject meta = json(get(API + "/" + GoogleLogin.enc(fileId)
                    + "?fields=md5Checksum,trashed&supportsAllDrives=true", token));
            boolean gone = meta.has("trashed") && meta.get("trashed").getAsBoolean();
            if (gone) {
                fileId = null;
                currentMd5 = null;
            } else {
                currentMd5 = meta.has("md5Checksum") ? meta.get("md5Checksum").getAsString() : null;
            }
        } else {
            currentMd5 = null;
        }
        if (fileId == null) {
            // Someone may have uploaded one since the app listed the folder.
            String q = "'" + folderId + "' in parents and name = '" + Scaletta.FILE_NAME + "' and trashed = false";
            JsonArray files = json(get(API + "?q=" + GoogleLogin.enc(q) + "&fields=files(id,md5Checksum)"
                    + "&supportsAllDrives=true&includeItemsFromAllDrives=true", token)).getAsJsonArray("files");
            if (files != null && files.size() > 0) {
                JsonObject f = files.get(0).getAsJsonObject();
                fileId = f.get("id").getAsString();
                currentMd5 = f.has("md5Checksum") ? f.get("md5Checksum").getAsString() : null;
            }
        }
        if (!force && fileId != null && (read.md5() == null || !read.md5().equalsIgnoreCase(String.valueOf(currentMd5)))) {
            throw new Conflict();
        }
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        HttpRequest req;
        if (fileId != null) {
            req = HttpRequest.newBuilder(URI.create(UPLOAD + "/" + GoogleLogin.enc(fileId)
                            + "?uploadType=media&supportsAllDrives=true"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
        } else {
            String boundary = "fxcenter" + UUID.randomUUID().toString().replace("-", "");
            JsonObject meta = new JsonObject();
            meta.addProperty("name", Scaletta.FILE_NAME);
            meta.addProperty("mimeType", "text/plain");
            JsonArray parents = new JsonArray();
            parents.add(folderId);
            meta.add("parents", parents);
            String head = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                    + "\r\n--" + boundary + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n";
            String tail = "\r\n--" + boundary + "--\r\n";
            byte[] h = head.getBytes(StandardCharsets.UTF_8);
            byte[] t = tail.getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[h.length + content.length + t.length];
            System.arraycopy(h, 0, body, 0, h.length);
            System.arraycopy(content, 0, body, h.length, content.length);
            System.arraycopy(t, 0, body, h.length + content.length, t.length);
            req = HttpRequest.newBuilder(URI.create(UPLOAD + "?uploadType=multipart&supportsAllDrives=true"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/related; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
        }
        HttpResponse<String> r = GoogleLogin.send(http, req);
        if (r.statusCode() / 100 != 2) {
            throw new IOException("Drive ha rifiutato il salvataggio: HTTP " + r.statusCode() + " "
                    + GoogleLogin.abbreviate(r.body()));
        }
    }

    private String get(String url, String token) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> r = GoogleLogin.send(http, req);
        if (r.statusCode() / 100 != 2) {
            throw new IOException("Drive: HTTP " + r.statusCode() + " " + GoogleLogin.abbreviate(r.body()));
        }
        return r.body();
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }
}
