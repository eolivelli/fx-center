package dev.olivelli.fxcenter.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShowControllerTest {

    /** Records calls; never plays anything. */
    static final class FakePlayer implements AudioPlayer {
        Listener listener;
        final List<String> calls = new ArrayList<>();
        Path current;

        @Override public void setListener(Listener l) { listener = l; }
        @Override public void play(Path file) throws IOException {
            if (file.getFileName().toString().contains("broken")) {
                throw new IOException("formato sconosciuto");
            }
            calls.add("play " + file.getFileName());
            current = file;
        }
        @Override public void pause() { calls.add("pause"); }
        @Override public void resume() { calls.add("resume"); }
        @Override public void stop() { calls.add("stop"); current = null; }
        @Override public void setVolume(double v) { calls.add("volume " + v); }
        @Override public long positionMillis() { return 0; }
        @Override public long durationMillis() { return -1; }
        @Override public void close() { }
    }

    static final class FakeSource implements LibrarySource {
        final Library library;
        final List<String> fetched = new ArrayList<>();

        FakeSource(Library library) { this.library = library; }
        @Override public String describe() { return "fake"; }
        @Override public Library list() { return library; }
        @Override public Path localFile(Cue cue, Progress p) {
            fetched.add(cue.fileName());
            return Path.of("/cache", cue.fileName());
        }
        @Override public boolean isLocallyAvailable(Cue cue) { return fetched.contains(cue.fileName()); }
    }

    static Library library(String... names) {
        List<Cue> cues = new ArrayList<>();
        for (String n : names) {
            cues.add(new Cue("id-" + n, n, 1, null));
        }
        return new Library(cues, List.of());
    }

    FakePlayer player;
    List<String> errors;
    int changes;
    ShowController ctl;

    @BeforeEach
    void setUp() {
        player = new FakePlayer();
        errors = new ArrayList<>();
        ctl = new ShowController(player, new ShowController.Listener() {
            @Override public void changed() { changes++; }
            @Override public void error(String m) { errors.add(m); }
        });
    }

    @Test
    void goPlaysTheSelectedCueAndFinishingAdvancesTheSelectionWithoutPlaying() {
        FakeSource src = new FakeSource(library("MUS-01.mp3", "MUS-02.mp3", "SFX-01.wav"));
        ctl.setSource(src);
        ctl.refresh();
        assertEquals(0, ctl.selectedIndex());
        assertEquals(ShowController.State.IDLE, ctl.state());

        ctl.go();
        assertEquals(ShowController.State.PLAYING, ctl.state());
        assertEquals(0, ctl.playingIndex());
        assertEquals(List.of("stop", "volume 1.0", "play MUS-01.mp3"), player.calls);

        player.listener.finished();
        assertEquals(ShowController.State.IDLE, ctl.state());
        assertEquals(-1, ctl.playingIndex());
        assertEquals(1, ctl.selectedIndex());
        assertFalse(String.join(",", player.calls).contains("play MUS-02"), "finishing must not start the next cue");
        assertTrue(errors.isEmpty());
    }

    @Test
    void stopKeepsTheSelectionAndFinishingOnTheLastCueStaysThere() {
        ctl.setSource(new FakeSource(library("a.mp3", "b.mp3")));
        ctl.refresh();
        ctl.setFadeMillis(0);
        ctl.select(1);
        ctl.go();
        ctl.stop();
        assertEquals(1, ctl.selectedIndex());
        assertEquals(ShowController.State.IDLE, ctl.state());

        ctl.go();
        player.listener.finished();
        assertEquals(1, ctl.selectedIndex());
    }

    @Test
    void pauseAndResume() {
        ctl.setSource(new FakeSource(library("a.mp3")));
        ctl.refresh();
        ctl.pauseOrResume();
        assertEquals(ShowController.State.IDLE, ctl.state(), "nothing to pause");
        ctl.go();
        ctl.pauseOrResume();
        assertEquals(ShowController.State.PAUSED, ctl.state());
        ctl.pauseOrResume();
        assertEquals(ShowController.State.PLAYING, ctl.state());
        assertEquals(List.of("stop", "volume 1.0", "play a.mp3", "pause", "resume"), player.calls);
    }

    @Test
    void playbackErrorIsReportedAndLeavesTheControllerIdle() {
        ctl.setSource(new FakeSource(library("broken.mp3")));
        ctl.refresh();
        ctl.go();
        assertEquals(ShowController.State.IDLE, ctl.state());
        assertEquals(-1, ctl.playingIndex());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("broken.mp3"));
        assertEquals(0, ctl.selectedIndex());
    }

    @Test
    void refreshKeepsTheSelectionOnTheSameCueById() {
        ctl.setSource(new FakeSource(library("a.mp3", "b.mp3", "c.mp3")));
        ctl.refresh();
        ctl.select(2);
        ctl.setLibrary(library("new.mp3", "a.mp3", "c.mp3"));
        assertEquals("c.mp3", ctl.selectedCue().fileName());
        ctl.setLibrary(library("x.mp3"));
        assertEquals(0, ctl.selectedIndex());
        ctl.setLibrary(Library.EMPTY);
        assertNull(ctl.selectedCue());
        assertEquals(-1, ctl.selectedIndex());
    }

    @Test
    void refreshFailureIsReported() {
        ctl.setSource(new LibrarySource() {
            @Override public String describe() { return "x"; }
            @Override public Library list() throws IOException { throw new IOException("rete assente"); }
            @Override public Path localFile(Cue cue, Progress p) { return null; }
            @Override public boolean isLocallyAvailable(Cue cue) { return false; }
        });
        ctl.refresh();
        assertEquals(List.of("rete assente"), errors);
        assertTrue(ctl.library().isEmpty());
    }

    @Test
    void downloadAllFetchesOnlyWhatIsMissing() {
        FakeSource src = new FakeSource(library("a.mp3", "b.mp3"));
        ctl.setSource(src);
        ctl.refresh();
        ctl.select(1);
        ctl.go();
        assertEquals(List.of("b.mp3"), src.fetched);
        ctl.downloadAll(Progress.NONE);
        assertEquals(List.of("b.mp3", "a.mp3"), src.fetched);
        ctl.downloadAll(Progress.NONE);
        assertEquals(2, src.fetched.size());
    }

    private void awaitIdle() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (ctl.state() != ShowController.State.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(ShowController.State.IDLE, ctl.state());
    }

    private List<Double> volumesSet() {
        List<Double> v = new ArrayList<>();
        for (String c : player.calls) {
            if (c.startsWith("volume ")) {
                v.add(Double.parseDouble(c.substring(7)));
            }
        }
        return v;
    }

    @Test
    void stopFadesOutThenStopsAndRestoresTheVolume() throws InterruptedException {
        ctl.setSource(new FakeSource(library("a.mp3")));
        ctl.refresh();
        ctl.setVolume(0.8);
        ctl.setFadeMillis(100);
        ctl.go();
        player.calls.clear();

        ctl.stop();
        assertEquals(ShowController.State.FADING, ctl.state());
        assertEquals(0, ctl.playingIndex(), "still the playing cue while it fades");
        awaitIdle();

        List<Double> volumes = volumesSet();
        assertTrue(volumes.size() >= 10, "steps: " + volumes);
        for (int i = 1; i < volumes.size() - 1; i++) {
            assertTrue(volumes.get(i) <= volumes.get(i - 1), "monotonic: " + volumes);
        }
        assertEquals(0.0, volumes.get(volumes.size() - 2), 1e-9);
        assertEquals(0.8, volumes.get(volumes.size() - 1), 1e-9, "restored after the stop");
        assertEquals("stop", player.calls.get(player.calls.size() - 2));
        assertEquals(-1, ctl.playingIndex());
        assertEquals(0, ctl.selectedIndex());
    }

    @Test
    void secondStopDuringFadeCutsAtOnce() throws InterruptedException {
        ctl.setSource(new FakeSource(library("a.mp3")));
        ctl.refresh();
        ctl.setFadeMillis(10_000);
        ctl.go();
        ctl.stop();
        assertEquals(ShowController.State.FADING, ctl.state());
        ctl.stop();
        assertEquals(ShowController.State.IDLE, ctl.state());
        assertEquals(1.0, volumesSet().get(volumesSet().size() - 1), 1e-9);
        Thread.sleep(50);
        assertEquals(ShowController.State.IDLE, ctl.state(), "the abandoned fade must not come back");
    }

    @Test
    void goDuringFadeStartsTheNextCueImmediately() throws InterruptedException {
        ctl.setSource(new FakeSource(library("a.mp3", "b.mp3")));
        ctl.refresh();
        ctl.setFadeMillis(10_000);
        ctl.go();
        ctl.stop();
        ctl.select(1);
        ctl.go();
        assertEquals(ShowController.State.PLAYING, ctl.state());
        assertEquals(1, ctl.playingIndex());
        assertEquals("play b.mp3", player.calls.get(player.calls.size() - 1));
        Thread.sleep(50);
        assertEquals(ShowController.State.PLAYING, ctl.state());
    }

    @Test
    void stopWhilePausedOrWithFadeDisabledIsImmediate() {
        ctl.setSource(new FakeSource(library("a.mp3")));
        ctl.refresh();
        ctl.go();
        ctl.pauseOrResume();
        ctl.stop();
        assertEquals(ShowController.State.IDLE, ctl.state());

        ctl.setFadeMillis(0);
        ctl.go();
        ctl.stop();
        assertEquals(ShowController.State.IDLE, ctl.state());
    }

    @Test
    void selectionIsClamped() {
        ctl.setSource(new FakeSource(library("a.mp3", "b.mp3")));
        ctl.refresh();
        ctl.selectPrevious();
        assertEquals(0, ctl.selectedIndex());
        ctl.selectNext();
        ctl.selectNext();
        assertEquals(1, ctl.selectedIndex());
    }
}
