package dev.olivelli.fxcenter.core;

/**
 * The part of a file that a cue plays: from {@link #startMillis} to
 * {@link #endMillis}, or to the end of the file when {@code endMillis} is -1.
 * Immutable value; {@link #NONE} is the whole file.
 */
public final class Clip {

    public static final Clip NONE = new Clip(0, -1);

    /** How long the fade at a clip's end point lasts. */
    public static final long END_FADE_MILLIS = 500;

    private final long startMillis;
    private final long endMillis;

    private Clip(long startMillis, long endMillis) {
        this.startMillis = startMillis;
        this.endMillis = endMillis;
    }

    /**
     * @param startMillis where playing begins, clamped to 0 or more
     * @param endMillis   where it stops, or -1 for the end of the file
     * @throws IllegalArgumentException when the end is not after the start
     */
    public static Clip of(long startMillis, long endMillis) {
        long start = Math.max(0, startMillis);
        long end = endMillis < 0 ? -1 : endMillis;
        if (end >= 0 && end <= start) {
            throw new IllegalArgumentException("la fine (" + Scaletta.formatTime(end)
                    + ") deve venire dopo l'inizio (" + Scaletta.formatTime(start) + ")");
        }
        return start == 0 && end < 0 ? NONE : new Clip(start, end);
    }

    public long startMillis() {
        return startMillis;
    }

    /** The end point, or -1 for the end of the file. */
    public long endMillis() {
        return endMillis;
    }

    public boolean hasEnd() {
        return endMillis >= 0;
    }

    /** Whether this plays less than the whole file. */
    public boolean isClipped() {
        return startMillis > 0 || endMillis >= 0;
    }

    /** How long the clip plays given the file's length (-1 if unknown); -1 when it cannot say. */
    public long lengthMillis(long fileMillis) {
        long end = hasEnd() ? (fileMillis > 0 ? Math.min(endMillis, fileMillis) : endMillis) : fileMillis;
        return end < 0 ? -1 : Math.max(0, end - startMillis);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Clip && ((Clip) o).startMillis == startMillis && ((Clip) o).endMillis == endMillis;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(startMillis) * 31 + Long.hashCode(endMillis);
    }

    @Override
    public String toString() {
        return isClipped() ? Scaletta.formatClip(this) : "intero";
    }
}
