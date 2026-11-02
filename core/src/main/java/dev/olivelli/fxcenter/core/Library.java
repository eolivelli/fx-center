package dev.olivelli.fxcenter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one scan of the folder found: cues in show order, documents, and what
 * {@code scaletta.txt} said, if the folder has one.
 */
public final class Library {

    /** Where the running order came from, and what to keep when rewriting it. */
    public static final class ScalettaInfo {
        public static final ScalettaInfo NONE = new ScalettaInfo(false, null, null,
                Collections.emptyList(), Collections.emptyList());

        private final boolean present;
        private final String fileId;
        private final String md5;
        private final List<String> orphanLines;
        private final List<String> warnings;

        public ScalettaInfo(boolean present, String fileId, String md5, List<String> orphanLines,
                            List<String> warnings) {
            this.present = present;
            this.fileId = fileId;
            this.md5 = md5;
            this.orphanLines = Collections.unmodifiableList(new ArrayList<>(orphanLines));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        /** Whether the folder has a scaletta.txt (an empty one counts). */
        public boolean present() {
            return present;
        }

        /** The Drive file id, or null for a local folder or when there is no file yet. */
        public String fileId() {
            return fileId;
        }

        /** Hex MD5 of the text as read, to notice that someone else changed it since. */
        public String md5() {
            return md5;
        }

        /** Lines that named no file of the folder, kept verbatim when the text is rewritten. */
        public List<String> orphanLines() {
            return orphanLines;
        }

        public List<String> warnings() {
            return warnings;
        }

        /** Same file, nothing to report: for a listing that only carries where the file is. */
        public ScalettaInfo withoutWarnings() {
            return new ScalettaInfo(present, fileId, md5, orphanLines, Collections.emptyList());
        }
    }

    public static final Library EMPTY = new Library(Collections.emptyList(), Collections.emptyList());

    private final List<Cue> cues;
    private final List<Document> documents;
    private final ScalettaInfo scaletta;

    /** Sorts both lists in natural order, so the caller need not. */
    public Library(List<Cue> cues, List<Document> documents) {
        this(sorted(cues), documents, ScalettaInfo.NONE);
    }

    private Library(List<Cue> cues, List<Document> documents, ScalettaInfo scaletta) {
        List<Document> d = new ArrayList<>(documents);
        d.sort((a, b) -> NaturalOrder.compare(a.name(), b.name()));
        this.cues = Collections.unmodifiableList(new ArrayList<>(cues));
        this.documents = Collections.unmodifiableList(d);
        this.scaletta = scaletta;
    }

    /** Keeps the cues in the order given: the running order of a scaletta. */
    public static Library ordered(List<Cue> cues, List<Document> documents, ScalettaInfo scaletta) {
        return new Library(cues, documents, scaletta == null ? ScalettaInfo.NONE : scaletta);
    }

    private static List<Cue> sorted(List<Cue> cues) {
        List<Cue> c = new ArrayList<>(cues);
        c.sort((a, b) -> NaturalOrder.compare(a.fileName(), b.fileName()));
        return c;
    }

    public List<Cue> cues() {
        return cues;
    }

    public List<Document> documents() {
        return documents;
    }

    public ScalettaInfo scaletta() {
        return scaletta;
    }

    /** The same library with other cues (reordered or reclipped), in the order given. */
    public Library withCues(List<Cue> newCues) {
        return new Library(newCues, documents, scaletta);
    }

    public boolean isEmpty() {
        return cues.isEmpty() && documents.isEmpty();
    }
}
