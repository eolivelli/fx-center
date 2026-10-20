package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Where the show's files come from: a local folder or a Drive folder.
 *
 * <p>Both calls may block on I/O and are meant to run off the UI thread.
 * Playback only ever reads a local file: {@link #localFile} is the one place a
 * remote source touches the network for content, and it does so once per file.
 */
public interface LibrarySource {

    /** Short human-readable description, for the window title. */
    String describe();

    /** Lists the folder. Never plays from this listing directly. */
    Library list() throws IOException;

    /** A local, complete copy of the cue, downloading it first when needed. */
    Path localFile(Cue cue, Progress progress) throws IOException;

    /** Whether {@link #localFile} would return without network access. */
    boolean isLocallyAvailable(Cue cue);
}
