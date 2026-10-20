package dev.olivelli.fxcenter.core;

import java.util.Objects;

/**
 * A non-audio file in the folder (the script, a PDF, a spreadsheet): shown
 * under "Documenti" and opened with whatever the platform opens the link with.
 */
public final class Document {

    private final String name;
    private final String link;
    private final String mimeType;

    /**
     * @param name     display name
     * @param link     a URL (Drive) or a {@code file:} URI (local folder)
     * @param mimeType as reported by the source; may be empty
     */
    public Document(String name, String link, String mimeType) {
        this.name = Objects.requireNonNull(name, "name");
        this.link = Objects.requireNonNull(link, "link");
        this.mimeType = mimeType == null ? "" : mimeType;
    }

    public String name() {
        return name;
    }

    public String link() {
        return link;
    }

    public String mimeType() {
        return mimeType;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Document)) {
            return false;
        }
        Document other = (Document) o;
        return name.equals(other.name) && link.equals(other.link) && mimeType.equals(other.mimeType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, link, mimeType);
    }

    @Override
    public String toString() {
        return "Document[" + name + "]";
    }
}
