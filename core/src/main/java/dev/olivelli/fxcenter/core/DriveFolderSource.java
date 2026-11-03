package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        Library.ScalettaInfo info = library.scaletta();
        if (info.present()) {
            // A copy of the text, so a scaletta.txt that cannot be downloaded
            // does not cost the fresh listing: new files still show, removed
            // ones still go.
            Path copy = folderDir().resolve(Scaletta.FILE_NAME);
            String text;
            String md5;
            String problem = null;
            try {
                text = client.downloadText(info.fileId());
                // Drive's own checksum when it gives one: it is what the save compares against.
                md5 = info.md5() != null ? info.md5() : Scaletta.md5(text);
                Files.createDirectories(copy.getParent());
                Files.write(copy, text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Library last = lastListing();
                if (Files.isRegularFile(copy)) {
                    text = new String(Files.readAllBytes(copy), StandardCharsets.UTF_8);
                    // the copy's own checksum: saving over a newer version on Drive is then a conflict
                    md5 = Scaletta.md5(text);
                    problem = "non riesco a scaricarlo, uso la copia dell'ultima volta";
                } else if (last.scaletta().present()) {
                    // no copy yet (saved by older versions): the order and clips of the last listing
                    text = Scaletta.format(last.cues(), last.scaletta().orphanLines());
                    md5 = last.scaletta().md5();
                    problem = "non riesco a scaricarlo, uso l'ordine dell'ultima volta";
                } else {
                    // never read: the songs as they are now, in name order. No checksum, so a save
                    // from here asks before overwriting the order nobody has seen.
                    text = "";
                    md5 = null;
                    problem = "non riesco a scaricarlo e non ne ho una copia: brani in ordine alfabetico";
                }
                problem += " (" + e.getMessage() + ")";
            }
            library = text.isEmpty() && md5 == null ? library : Scaletta.parse(text).arrange(library, info.fileId(), md5);
            if (problem != null) {
                Library.ScalettaInfo s = library.scaletta();
                List<String> warnings = new ArrayList<>();
                warnings.add(problem);
                warnings.addAll(s.warnings());
                library = Library.ordered(library.cues(), library.documents(),
                        new Library.ScalettaInfo(true, info.fileId(), md5, s.orphanLines(), warnings));
            }
        }
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
