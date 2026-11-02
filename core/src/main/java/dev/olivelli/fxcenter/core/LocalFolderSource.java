package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
                if (!Files.isRegularFile(p) || name.startsWith(".") || Scaletta.isScalettaFile(name)) {
                    continue;
                }
                if (AudioFiles.isAudio(name)) {
                    cues.add(new Cue(name, name, Files.size(p), null));
                } else {
                    docs.add(new Document(name, p.toUri().toString(), ""));
                }
            }
        }
        Library listing = new Library(cues, docs);
        Path scaletta = scalettaFile();
        if (!Files.isRegularFile(scaletta)) {
            return listing;
        }
        byte[] content = Files.readAllBytes(scaletta);
        return Scaletta.parse(new String(content, StandardCharsets.UTF_8))
                .arrange(listing, null, Scaletta.md5(content));
    }

    /** Where this folder keeps its running order, whether or not it exists yet. */
    public Path scalettaFile() {
        try (Stream<Path> entries = Files.list(folder)) {
            for (Path p : (Iterable<Path>) entries::iterator) {
                if (Scaletta.isScalettaFile(p.getFileName().toString())) {
                    return p;
                }
            }
        } catch (IOException e) {
            // fall through to the canonical name
        }
        return folder.resolve(Scaletta.FILE_NAME);
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
