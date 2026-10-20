package dev.olivelli.fxcenter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one scan of the folder found: cues in show order, and documents. */
public final class Library {

    public static final Library EMPTY = new Library(Collections.emptyList(), Collections.emptyList());

    private final List<Cue> cues;
    private final List<Document> documents;

    /** Sorts both lists in natural order, so the caller need not. */
    public Library(List<Cue> cues, List<Document> documents) {
        List<Cue> c = new ArrayList<>(cues);
        c.sort((a, b) -> NaturalOrder.compare(a.fileName(), b.fileName()));
        List<Document> d = new ArrayList<>(documents);
        d.sort((a, b) -> NaturalOrder.compare(a.name(), b.name()));
        this.cues = Collections.unmodifiableList(c);
        this.documents = Collections.unmodifiableList(d);
    }

    public List<Cue> cues() {
        return cues;
    }

    public List<Document> documents() {
        return documents;
    }

    public boolean isEmpty() {
        return cues.isEmpty() && documents.isEmpty();
    }
}
