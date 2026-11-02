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

    private static Path chirp(Path dir) throws IOException {
        Path file = dir.resolve("chirp-vbr.mp3");
        try (java.io.InputStream in = JavaSoundPlayerTest.class.getResourceAsStream("/chirp-vbr.mp3")) {
            Files.copy(in, file);
        }
        return file;
    }

    private static short[] samples(AudioInputStream in, int count) throws IOException {
        byte[] b = new byte[count * 2];
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        short[] s = new short[n / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        }
        return s;
    }

    /** The fast MP3 seek lands on the same samples as decoding from the start and dropping. */
    @Test
    void mp3SeekMatchesDecodingFromTheStart(@TempDir Path dir) throws IOException {
        Path mp3 = chirp(dir);
        for (long at : new long[]{200, 1000, 3333, 6500, 7900}) {
            short[] expected;
            try (AudioInputStream in = JavaSoundPlayer.openPcm(mp3)) {
                AudioFormat f = in.getFormat();
                samples(in, (int) (at * (long) f.getFrameRate() / 1000) * f.getChannels());
                expected = samples(in, 4096);
            }
            short[] actual;
            long t0 = System.nanoTime();
            try (AudioInputStream in = JavaSoundPlayer.openPcmAt(mp3, at)) {
                actual = samples(in, 4096);
            }
            long micros = (System.nanoTime() - t0) / 1000;
            double err = 0;
            double energy = 0;
            for (int i = 0; i < expected.length; i++) {
                err += Math.abs(expected[i] - actual[i]);
                energy += Math.abs(expected[i]);
            }
            assertTrue(energy > 0);
            assertTrue(err / energy < 0.001, "at " + at + " ms the seek is off: relative error " + err / energy);
            assertTrue(micros < 500_000, "seek took " + micros + " µs");
        }
    }

    @Test
    void mp3FrameHeaders() {
        // MPEG-1 Layer III, 128 kbit/s, 44.1 kHz, no padding: 417 bytes.
        assertEquals(417, Mp3Seek.frameLength(new byte[]{(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0x00}));
        assertEquals(-1, Mp3Seek.frameLength(new byte[]{'I', 'D', '3', 3}));
    }

    @Test
    void wavSeekIsExact(@TempDir Path dir) throws IOException {
        Path wav = writeWav(dir, 8000, 1, 2.0);
        try (AudioInputStream in = JavaSoundPlayer.openPcmAt(wav, 1500)) {
            assertEquals(500 * 8 * 2, samples(in, 100_000).length * 2);
        }
    }

    @Test
    void clock() {
        assertEquals("0:00", MainWindow.clock(0));
        assertEquals("1:05", MainWindow.clock(65_400));
        assertEquals("12:00", MainWindow.clock(720_000));
    }
}
