package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * The show as the technician sees it: a list of cues, one selected, at most one
 * playing. Both user interfaces drive this and render its state; it holds no
 * threads of its own, so the caller decides where the blocking calls run.
 *
 * <p>Rules, chosen for a live show rather than a music player:
 * <ul>
 *   <li>GO plays the <em>selected</em> cue; it does not pause or toggle.</li>
 *   <li>When a cue ends by itself the selection moves to the next one and
 *       nothing plays: the next GO is the technician's, not the clock's.</li>
 *   <li>STOP never changes the selection, so a cue stopped early can be
 *       restarted with GO.</li>
 * </ul>
 */
public final class ShowController implements AudioPlayer.Listener {

    public enum State { IDLE, LOADING, PLAYING, PAUSED, FADING }

    /** How long STOP takes to bring a playing cue to silence. */
    public static final long DEFAULT_FADE_MILLIS = 1500;
    private static final int FADE_STEPS = 20;

    public interface Listener {
        /** Anything visible changed: list, selection, state, or an error. */
        void changed();

        /** Something went wrong and the technician should see it. */
        void error(String message);
    }

    private final AudioPlayer player;
    private final Listener listener;
    private LibrarySource source;
    private Library library = Library.EMPTY;
    private int selected = -1;
    private int playing = -1;
    private State state = State.IDLE;
    private double volume = 1.0;
    private long fadeMillis = DEFAULT_FADE_MILLIS;
    private Fade fade;

    public ShowController(AudioPlayer player, Listener listener) {
        this.player = player;
        this.listener = listener;
        player.setListener(this);
    }

    /** Zero disables the fade: STOP cuts at once. */
    public synchronized void setFadeMillis(long fadeMillis) {
        this.fadeMillis = Math.max(0, fadeMillis);
    }

    // --- library -------------------------------------------------------

    public synchronized void setSource(LibrarySource source) {
        this.source = source;
    }

    public synchronized LibrarySource source() {
        return source;
    }

    /** Replaces the list, keeping the selection on the same cue when it is still there. */
    public synchronized void setLibrary(Library library) {
        Cue previous = selectedCue();
        this.library = library;
        selected = previous == null ? -1 : indexOf(previous.id());
        if (selected < 0 && !library.cues().isEmpty()) {
            selected = 0;
        }
        playing = playing < 0 || playingCue == null ? -1 : indexOf(playingCue.id());
        listener.changed();
    }

    private Cue playingCue;

    /** Lists the source; blocking. Falls back to the last Drive listing when offline. */
    public void refresh() {
        LibrarySource s;
        synchronized (this) {
            s = source;
        }
        if (s == null) {
            return;
        }
        try {
            setLibrary(s.list());
        } catch (IOException e) {
            Library fallback = s instanceof DriveFolderSource ? ((DriveFolderSource) s).lastListing() : null;
            if (fallback != null && !fallback.isEmpty()) {
                setLibrary(fallback);
                listener.error("Drive non raggiungibile, uso l'ultimo elenco salvato: " + e.getMessage());
            } else {
                listener.error(e.getMessage());
            }
        }
    }

    /** Downloads every cue not yet local; blocking. */
    public void downloadAll(Progress progress) {
        List<Cue> cues;
        LibrarySource s;
        synchronized (this) {
            cues = library.cues();
            s = source;
        }
        if (s == null) {
            return;
        }
        int failures = 0;
        for (Cue c : cues) {
            if (s.isLocallyAvailable(c)) {
                continue;
            }
            try {
                s.localFile(c, progress);
            } catch (IOException e) {
                failures++;
                listener.error("Scaricamento fallito: " + c.fileName() + " — " + e.getMessage());
            }
            listener.changed();
        }
        if (failures == 0) {
            listener.changed();
        }
    }

    public synchronized Library library() {
        return library;
    }

    // --- selection -----------------------------------------------------

    public synchronized int selectedIndex() {
        return selected;
    }

    public synchronized Cue selectedCue() {
        return selected >= 0 && selected < library.cues().size() ? library.cues().get(selected) : null;
    }

    public synchronized void select(int index) {
        if (index >= 0 && index < library.cues().size() && index != selected) {
            selected = index;
            listener.changed();
        }
    }

    public synchronized void selectNext() {
        select(selected + 1);
    }

    public synchronized void selectPrevious() {
        select(selected - 1);
    }

    // --- transport -----------------------------------------------------

    public synchronized State state() {
        return state;
    }

    public synchronized int playingIndex() {
        return playing;
    }

    public synchronized Cue playingCue() {
        return playing >= 0 && playing < library.cues().size() ? library.cues().get(playing) : null;
    }

    /** Plays the selected cue from the start; blocking while it is fetched. */
    public void go() {
        Cue cue;
        int index;
        LibrarySource s;
        synchronized (this) {
            cue = selectedCue();
            index = selected;
            s = source;
            if (cue == null || s == null) {
                return;
            }
            cutNow();
            state = State.LOADING;
            playing = index;
            playingCue = cue;
        }
        listener.changed();
        try {
            Path file = s.localFile(cue, Progress.NONE);
            synchronized (this) {
                if (playing != index) {
                    return; // a STOP or another GO happened meanwhile
                }
                player.play(file);
                state = State.PLAYING;
            }
        } catch (IOException | RuntimeException e) {
            synchronized (this) {
                state = State.IDLE;
                playing = -1;
                playingCue = null;
            }
            listener.error("Non riesco a riprodurre " + cue.fileName() + ": " + e.getMessage());
        }
        listener.changed();
    }

    /**
     * Fades a playing cue out, then stops it. Pressed again while fading, or
     * while paused or loading, it stops at once.
     */
    public void stop() {
        synchronized (this) {
            if (state == State.PLAYING && fadeMillis > 0) {
                state = State.FADING;
                fade = new Fade(fadeMillis);
                fade.thread.start();
            } else {
                cutNow();
                state = State.IDLE;
                playing = -1;
                playingCue = null;
            }
        }
        listener.changed();
    }

    /** Stops the player immediately, abandoning any fade and restoring the volume. Caller holds the lock. */
    private void cutNow() {
        if (fade != null) {
            fade.cancelled = true;
            fade = null;
        }
        player.stop();
        player.setVolume(volume);
    }

    /** One fade-out, on its own thread so STOP returns and GO can interrupt it. */
    private final class Fade implements Runnable {
        final Thread thread = new Thread(this, "fx-center-fade");
        final long millis;
        volatile boolean cancelled;

        Fade(long millis) {
            this.millis = millis;
            thread.setDaemon(true);
        }

        @Override
        public void run() {
            double start = volume;
            for (int i = 1; i <= FADE_STEPS && !cancelled; i++) {
                player.setVolume(start * (FADE_STEPS - i) / FADE_STEPS);
                try {
                    Thread.sleep(millis / FADE_STEPS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            synchronized (ShowController.this) {
                if (cancelled || fade != this) {
                    return; // someone cut, or GO started another cue: they restored the volume
                }
                fade = null;
                player.stop();
                player.setVolume(volume);
                state = State.IDLE;
                playing = -1;
                playingCue = null;
            }
            listener.changed();
        }
    }

    /** Pause/resume toggle; a no-op unless something is playing or paused. */
    public void pauseOrResume() {
        synchronized (this) {
            if (state == State.PLAYING) {
                player.pause();
                state = State.PAUSED;
            } else if (state == State.PAUSED) {
                player.resume();
                state = State.PLAYING;
            } else {
                return;
            }
        }
        listener.changed();
    }

    public synchronized void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        if (fade == null) {
            player.setVolume(this.volume);
        }
    }

    public long positionMillis() {
        return player.positionMillis();
    }

    public long durationMillis() {
        return player.durationMillis();
    }

    // --- AudioPlayer.Listener -------------------------------------------

    @Override
    public void finished() {
        synchronized (this) {
            int next = playing + 1;
            cutNow(); // the file ended under a fade: drop the fade and restore the volume
            state = State.IDLE;
            playing = -1;
            playingCue = null;
            if (next > 0 && next < library.cues().size()) {
                selected = next;
            }
        }
        listener.changed();
    }

    @Override
    public void failed(String message) {
        synchronized (this) {
            cutNow();
            state = State.IDLE;
            playing = -1;
            playingCue = null;
        }
        listener.error(message);
        listener.changed();
    }

    private int indexOf(String cueId) {
        List<Cue> cues = library.cues();
        for (int i = 0; i < cues.size(); i++) {
            if (cues.get(i).id().equals(cueId)) {
                return i;
            }
        }
        return -1;
    }

    public void close() {
        player.close();
    }
}
