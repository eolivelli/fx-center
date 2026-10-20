package dev.olivelli.fxcenter.core;

import java.util.Locale;
import java.util.Objects;

/**
 * One playable item of the show: an audio file in the folder.
 *
 * <p>Immutable value. {@code id} is stable across refreshes (the Drive file id,
 * or the file name for a local folder) and is what the cache is keyed by.
 */
public final class Cue {

    /** What the file name says the cue is; only affects how it is shown. */
    public enum Kind { MUSIC, EFFECT, OTHER }

    private final String id;
    private final String fileName;
    private final long size;
    private final String md5;

    /**
     * @param id       stable identifier
     * @param fileName name with extension, as in the folder
     * @param size     bytes, or -1 when unknown
     * @param md5      hex MD5 as reported by Drive, or null when unknown
     */
    public Cue(String id, String fileName, long size, String md5) {
        this.id = Objects.requireNonNull(id, "id");
        this.fileName = Objects.requireNonNull(fileName, "fileName");
        this.size = size;
        this.md5 = md5;
    }

    public String id() {
        return id;
    }

    public String fileName() {
        return fileName;
    }

    public long size() {
        return size;
    }

    /** MD5 of the content, or null when the source does not report one. */
    public String md5() {
        return md5;
    }

    /** The name without its extension: what the technician reads on the list. */
    public String title() {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Inferred from the cue-register prefixes used in the scripts (MUS-xx, SFX-xx). */
    public Kind kind() {
        String upper = fileName.toUpperCase(Locale.ROOT);
        if (upper.startsWith("MUS")) {
            return Kind.MUSIC;
        }
        if (upper.startsWith("SFX") || upper.startsWith("FX")) {
            return Kind.EFFECT;
        }
        return Kind.OTHER;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Cue)) {
            return false;
        }
        Cue other = (Cue) o;
        return id.equals(other.id) && fileName.equals(other.fileName)
                && size == other.size && Objects.equals(md5, other.md5);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, fileName, size, md5);
    }

    @Override
    public String toString() {
        return "Cue[" + fileName + "]";
    }
}
