package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** A folder on this machine. Audio files are cues; every other regular file is a document. */
public final class LocalFolderSource implements LibrarySource {

    private final Path folder;

    public LocalFolderSource(Path folder) {
        this.folder = folder;
    }

    public Path folder() {
        return folder;
    }

    @Override
    public String describe() {
        return folder.toString();
    }

    @Override
    public Library list() throws IOException {
        if (!Files.isDirectory(folder)) {
            throw new IOException("Cartella non trovata: " + folder);
        }
        List<Cue> cues = new ArrayList<>();
        List<Document> docs = new ArrayList<>();
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                String name = p.getFileName().toString();
                if (!Files.isRegularFile(p) || name.startsWith(".")) {
                    continue;
                }
                if (AudioFiles.isAudio(name)) {
                    cues.add(new Cue(name, name, Files.size(p), null));
                } else {
                    docs.add(new Document(name, p.toUri().toString(), ""));
                }
            }
        }
        return new Library(cues, docs);
    }

    @Override
    public Path localFile(Cue cue, Progress progress) throws IOException {
        Path p = folder.resolve(cue.fileName());
        if (!Files.isRegularFile(p)) {
            throw new IOException("File non trovato: " + p);
        }
        return p;
    }

    @Override
    public boolean isLocallyAvailable(Cue cue) {
        return Files.isRegularFile(folder.resolve(cue.fileName()));
    }
}
