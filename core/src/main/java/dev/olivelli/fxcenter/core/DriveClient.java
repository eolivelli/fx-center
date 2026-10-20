package dev.olivelli.fxcenter.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Drive API v3, the two calls the app makes, with an API key only.
 *
 * <p>An API key reads what is shared as "anyone with the link": the show's
 * folder is shared that way on purpose, so no device has to log in. Google
 * Docs appear in the listing with no {@code size}/{@code md5Checksum} and a
 * {@code application/vnd.google-apps.*} MIME type; they are documents, opened
 * by their {@code webViewLink}, never downloaded.
 */
public final class DriveClient {

    private static final String API = "https://www.googleapis.com/drive/v3/files";
    private static final String FIELDS = "nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime,webViewLink)";

    private final HttpTransport http;
    private final String apiKey;

    public DriveClient(HttpTransport http, String apiKey) {
        this.http = http;
        this.apiKey = apiKey;
    }

    /** Lists a folder (all pages), split into cues and documents. */
    public Library listFolder(String folderId) throws IOException {
        List<Cue> cues = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        String pageToken = null;
        do {
            String url = API + "?q=" + encode("'" + folderId + "' in parents and trashed = false")
                    + "&fields=" + encode(FIELDS) + "&pageSize=1000&orderBy=name"
                    + "&supportsAllDrives=true&includeItemsFromAllDrives=true"
                    + (pageToken == null ? "" : "&pageToken=" + encode(pageToken))
                    + "&key=" + encode(apiKey);
            JsonObject page;
            try (HttpTransport.Response r = http.get(url);
                 Reader reader = new InputStreamReader(r.body, StandardCharsets.UTF_8)) {
                page = JsonParser.parseReader(reader).getAsJsonObject();
            }
            parseFiles(page, cues, docs);
            pageToken = page.has("nextPageToken") ? page.get("nextPageToken").getAsString() : null;
        } while (pageToken != null);
        return new Library(cues, docs);
    }

    /** Parses one {@code files.list} page. Package-private for the test fixture. */
    static void parseFiles(JsonObject page, List<Cue> cues, List<Document> docs) {
        JsonArray files = page.getAsJsonArray("files");
        if (files == null) {
            return;
        }
        for (JsonElement e : files) {
            JsonObject f = e.getAsJsonObject();
            String id = f.get("id").getAsString();
            String name = f.get("name").getAsString();
            String mime = str(f, "mimeType");
            if (mime.equals("application/vnd.google-apps.folder")) {
                continue; // subfolders are not scanned, by design: one flat folder per show
            }
            if (AudioFiles.isAudio(name) && !mime.startsWith("application/vnd.google-apps.")) {
                long size = f.has("size") ? Long.parseLong(f.get("size").getAsString()) : -1;
                cues.add(new Cue(id, name, size, f.has("md5Checksum") ? f.get("md5Checksum").getAsString() : null));
            } else {
                String link = str(f, "webViewLink");
                if (link.isEmpty()) {
                    link = "https://drive.google.com/file/d/" + id + "/view";
                }
                docs.add(new Document(name, link, mime));
            }
        }
    }

    /**
     * Downloads a file's content to {@code target}, through a temp file beside
     * it, so a half-written file is never mistaken for a complete one.
     *
     * @return the hex MD5 of what was written
     */
    public String download(String fileId, Path target, String what, Progress progress) throws IOException {
        String url = API + "/" + encode(fileId) + "?alt=media&supportsAllDrives=true&key=" + encode(apiKey);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest md5 = md5();
        try (HttpTransport.Response r = http.get(url);
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = r.body.read(buf)) > 0) {
                out.write(buf, 0, n);
                md5.update(buf, 0, n);
                done += n;
                progress.update(what, done, r.contentLength);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return hex(md5.digest());
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
