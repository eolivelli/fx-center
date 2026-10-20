package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A shared Drive folder, read through a local cache.
 *
 * <p>Layout: {@code cacheDir/<folderId>/<fileId>/<fileName>}, with a sidecar
 * {@code <fileId>/.md5} holding the checksum of the verified download. A file
 * is served from the cache only when the sidecar equals the MD5 Drive reports
 * now, so a track replaced on Drive is fetched again and a torn download is
 * never played. The last listing is also kept on disk, so the app starts with
 * yesterday's list when the theatre has no network.
 */
public final class DriveFolderSource implements LibrarySource {

    private final DriveClient client;
    private final String folderId;
    private final Path cacheDir;

    public DriveFolderSource(DriveClient client, String folderId, Path cacheDir) {
        this.client = client;
        this.folderId = folderId;
        this.cacheDir = cacheDir;
    }

    public String folderId() {
        return folderId;
    }

    @Override
    public String describe() {
        return "Google Drive " + folderId;
    }

    @Override
    public Library list() throws IOException {
        Library library = client.listFolder(folderId);
        LibraryFile.save(library, folderDir().resolve("library.json"));
        return library;
    }

    /** The listing saved by the last successful {@link #list()}, or an empty one. */
    public Library lastListing() {
        try {
            return LibraryFile.load(folderDir().resolve("library.json"));
        } catch (IOException e) {
            return Library.EMPTY;
        }
    }

    @Override
    public Path localFile(Cue cue, Progress progress) throws IOException {
        Path file = cachedPath(cue);
        if (isLocallyAvailable(cue)) {
            return file;
        }
        String md5 = client.download(cue.id(), file, cue.title(), progress);
        if (cue.md5() != null && !cue.md5().equalsIgnoreCase(md5)) {
            Files.deleteIfExists(file);
            throw new IOException("Download corrotto per " + cue.fileName() + " (md5 diverso)");
        }
        Files.write(sidecar(cue), md5.getBytes(StandardCharsets.US_ASCII));
        return file;
    }

    @Override
    public boolean isLocallyAvailable(Cue cue) {
        Path file = cachedPath(cue);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (cue.md5() == null) {
            return true;
        }
        try {
            String stored = new String(Files.readAllBytes(sidecar(cue)), StandardCharsets.US_ASCII).trim();
            return stored.equalsIgnoreCase(cue.md5());
        } catch (IOException e) {
            return false;
        }
    }

    Path cachedPath(Cue cue) {
        return folderDir().resolve(cue.id()).resolve(cue.fileName());
    }

    private Path sidecar(Cue cue) {
        return folderDir().resolve(cue.id()).resolve(".md5");
    }

    private Path folderDir() {
        return cacheDir.resolve(folderId);
    }
}
