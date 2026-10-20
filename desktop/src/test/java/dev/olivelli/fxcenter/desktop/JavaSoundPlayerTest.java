package dev.olivelli.fxcenter.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Decoding only: CI has no sound card, so nothing here opens a line. */
class JavaSoundPlayerTest {

    static Path writeWav(Path dir, float rate, int channels, double seconds) throws IOException {
        AudioFormat f = new AudioFormat(rate, 16, channels, true, false);
        int frames = (int) (rate * seconds);
        byte[] pcm = new byte[frames * f.getFrameSize()];
        Path file = dir.resolve("tone.wav");
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), f, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    @Test
    void durationFromAWavHeader(@TempDir Path dir) throws IOException {
        Path wav = writeWav(dir, 8000, 1, 2.5);
        assertEquals(2500, JavaSoundPlayer.durationMillis(wav));
    }

    @Test
    void unknownFileHasNoDuration(@TempDir Path dir) throws IOException {
        Path junk = dir.resolve("x.mp3");
        Files.write(junk, new byte[]{1, 2, 3});
        assertEquals(-1, JavaSoundPlayer.durationMillis(junk));
        assertThrows(IOException.class, () -> JavaSoundPlayer.openPcm(junk));
    }

    @Test
    void stereoWavOpensAsPcm(@TempDir Path dir) throws IOException {
        Path wav = writeWav(dir, 44100, 2, 0.1);
        try (AudioInputStream in = JavaSoundPlayer.openPcm(wav)) {
            assertEquals(2, in.getFormat().getChannels());
            assertEquals(16, in.getFormat().getSampleSizeInBits());
        }
    }

    @Test
    void mp3ProviderIsOnTheClasspath() {
        boolean found = false;
        for (AudioFileFormat.Type t : AudioSystem.getAudioFileTypes()) {
            found |= t.toString().toLowerCase().contains("wave");
        }
        assertTrue(found);
        // mp3spi registers a reader, not a writer; the reader's presence is what
        // lets getAudioInputStream accept an MP3. Its class must load.
        assertThrows(IOException.class, () -> JavaSoundPlayer.openPcm(Path.of("/nonexistent.mp3")));
        try {
            Class.forName("javazoom.spi.mpeg.sampled.file.MpegAudioFileReader");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("mp3spi missing from the classpath", e);
        }
    }

    @Test
    void clock() {
        assertEquals("0:00", MainWindow.clock(0));
        assertEquals("1:05", MainWindow.clock(65_400));
        assertEquals("12:00", MainWindow.clock(720_000));
    }
}
