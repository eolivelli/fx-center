package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The platform's audio output: javax.sound on the desktop, MediaPlayer on
 * Android. One file at a time; a new {@link #play} replaces what was playing.
 */
public interface AudioPlayer {

    interface Listener {
        /** The file reached its end on its own (not stopped). */
        void finished();

        /** Playback failed after it had started. */
        void failed(String message);
    }

    void setListener(Listener listener);

    /** Starts the file from the beginning, stopping anything already playing. */
    void play(Path file) throws IOException;

    void pause();

    void resume();

    /** Stops and forgets the current file; {@link Listener#finished} is not called. */
    void stop();

    /** 0.0 (silent) to 1.0 (full). Applies to the current and later files. */
    void setVolume(double volume);

    /** Milliseconds into the current file, or 0 when nothing is loaded. */
    long positionMillis();

    /** Length of the current file in milliseconds, or -1 when unknown. */
    long durationMillis();

    void close();
}
