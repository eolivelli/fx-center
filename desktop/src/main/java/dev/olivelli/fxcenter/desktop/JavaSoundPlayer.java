package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.AudioPlayer;
import dev.olivelli.fxcenter.core.Clip;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Plays a file through {@code javax.sound.sampled}, streaming it to a
 * {@link SourceDataLine} from a thread of its own. WAV/AIFF/AU come with the
 * JDK; MP3 comes from mp3spi on the classpath.
 */
public final class JavaSoundPlayer implements AudioPlayer {

    private volatile Listener listener;
    private volatile double volume = 1.0;
    private Playback current;

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void play(Path file) throws IOException {
        play(file, Clip.NONE);
    }

    @Override
    public synchronized void play(Path file, Clip clip) throws IOException {
        stop();
        Playback p = new Playback(file, clip);
        current = p;
        p.thread.start();
    }

    @Override
    public synchronized void pause() {
        if (current != null) {
            current.setPaused(true);
        }
    }

    @Override
    public synchronized void resume() {
        if (current != null) {
            current.setPaused(false);
        }
    }

    @Override
    public synchronized void stop() {
        if (current != null) {
            current.cancel();
            current = null;
        }
    }

    @Override
    public synchronized void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        if (current != null) {
            current.applyVolume();
        }
    }

    @Override
    public synchronized long positionMillis() {
        return current == null ? 0 : current.line.getMicrosecondPosition() / 1000;
    }

    @Override
    public synchronized long durationMillis() {
        return current == null ? -1 : current.durationMillis;
    }

    @Override
    public void close() {
        stop();
    }

    /** The length of a file in milliseconds, or -1 when the provider cannot say without decoding. */
    public static long durationMillis(Path file) {
        try {
            AudioFileFormat aff = AudioSystem.getAudioFileFormat(file.toFile());
            Map<String, Object> props = aff.properties();
            Object micros = props.get("duration"); // mp3spi puts the length here, in microseconds
            if (micros instanceof Long) {
                return (Long) micros / 1000;
            }
            AudioFormat f = aff.getFormat();
            if (aff.getFrameLength() > 0 && f.getFrameRate() > 0) {
                return (long) (aff.getFrameLength() * 1000.0 / f.getFrameRate());
            }
        } catch (UnsupportedAudioFileException | IOException e) {
            // unknown is a legitimate answer
        }
        return -1;
    }

    /** Opens the file as 16-bit signed PCM, whatever it is stored as. */
    static AudioInputStream openPcm(Path file) throws IOException {
        return toPcm(new BufferedInputStream(Files.newInputStream(file), 1 << 16), file);
    }

    /**
     * Opens the file as PCM positioned at {@code startMillis}, on the very
     * sample a decode from the start would reach. An MP3 is entered a few frames
     * early at the point {@link Mp3Seek} finds and the samples up to the target
     * are decoded and dropped; anything else is decoded from the start and
     * dropped, which is fast for uncompressed formats.
     */
    static AudioInputStream openPcmAt(Path file, long startMillis) throws IOException {
        if (startMillis <= 0) {
            return openPcm(file);
        }
        AudioInputStream in = null;
        long frames = -1;
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
            Mp3Seek.Point p = Mp3Seek.before(file, startMillis);
            if (p != null) {
                InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
                skipFully(raw, p.byteOffset);
                in = toPcm(raw, file);
                frames = p.discardFrames;
            }
        }
        if (in == null) {
            in = openPcm(file);
            frames = Math.round(startMillis / 1000.0 * in.getFormat().getFrameRate());
        }
        AudioFormat f = in.getFormat();
        try {
            discard(in, frames * f.getFrameSize());
        } catch (IOException e) {
            in.close();
            throw e;
        }
        return in;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long done = 0;
        while (done < n) {
            long s = in.skip(n - done);
            if (s <= 0) {
                if (in.read() < 0) {
                    return;
                }
                s = 1;
            }
            done += s;
        }
    }

    /** Reads and drops bytes: skip() on a converted stream is not reliable across providers. */
    private static void discard(InputStream in, long bytes) throws IOException {
        byte[] buf = new byte[1 << 16];
        long left = bytes;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) {
                return;
            }
            left -= n;
        }
    }

    private static AudioInputStream toPcm(InputStream raw, Path file) throws IOException {
        try {
            AudioInputStream in = AudioSystem.getAudioInputStream(raw);
            AudioFormat src = in.getFormat();
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    src.getSampleRate(), 16, src.getChannels(),
                    src.getChannels() * 2, src.getSampleRate(), false);
            if (src.matches(pcm)) {
                return in;
            }
            return AudioSystem.getAudioInputStream(pcm, in);
        } catch (UnsupportedAudioFileException e) {
            raw.close();
            throw new IOException("Formato audio non supportato: " + file.getFileName(), e);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /** One file on one line on one thread. */
    private final class Playback implements Runnable {
        final Path file;
        final Clip clip;
        final AudioInputStream in;
        final SourceDataLine line;
        final long durationMillis;
        /** Bytes of PCM the clip lasts, or -1 to play to the end of the file. */
        final long clipBytes;
        final long fadeInBytes;
        final long fadeOutBytes;
        final Thread thread;
        private volatile boolean cancelled;
        private boolean paused;

        Playback(Path file, Clip clip) throws IOException {
            this.file = file;
            this.clip = clip;
            this.in = openPcmAt(file, clip.startMillis());
            AudioFormat format = in.getFormat();
            long bytesPerSecond = (long) format.getFrameRate() * format.getFrameSize();
            int frame = format.getFrameSize();
            clipBytes = clip.hasEnd()
                    ? (clip.endMillis() - clip.startMillis()) * bytesPerSecond / 1000 / frame * frame : -1;
            // Entering a song mid-phrase clicks; a 20 ms ramp is too short to hear as a fade.
            fadeInBytes = clip.startMillis() > 0 ? bytesPerSecond / 50 / frame * frame : 0;
            fadeOutBytes = clip.hasEnd()
                    ? Math.min(clipBytes, Clip.END_FADE_MILLIS * bytesPerSecond / 1000 / frame * frame) : 0;
            try {
                line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
                line.open(format);
            } catch (LineUnavailableException | IllegalArgumentException e) {
                in.close();
                throw new IOException("Uscita audio non disponibile: " + e.getMessage(), e);
            }
            applyVolume();
            long fromHeader = JavaSoundPlayer.durationMillis(file);
            long fileMillis = fromHeader > 0 ? fromHeader
                    : in.getFrameLength() > 0 ? (long) (in.getFrameLength() * 1000.0 / format.getFrameRate()) : -1;
            durationMillis = clip.lengthMillis(fileMillis);
            thread = new Thread(this, "fx-center-playback");
            thread.setDaemon(true);
        }

        /** Scales 16-bit little-endian samples for the fade-in and the end-of-clip fade-out. */
        void envelope(byte[] buf, int n, long at) {
            boolean inFadeIn = at < fadeInBytes;
            boolean inFadeOut = fadeOutBytes > 0 && at + n > clipBytes - fadeOutBytes;
            if (!inFadeIn && !inFadeOut) {
                return;
            }
            int frame = in.getFormat().getFrameSize();
            for (int i = 0; i + frame <= n; i += frame) {
                long pos = at + i;
                double g = 1.0;
                if (pos < fadeInBytes) {
                    g = (double) pos / fadeInBytes;
                }
                long fromEnd = clipBytes - pos;
                if (fadeOutBytes > 0 && fromEnd < fadeOutBytes) {
                    g = Math.min(g, (double) fromEnd / fadeOutBytes);
                }
                for (int b = i; b + 1 < i + frame; b += 2) {
                    int sample = (short) ((buf[b] & 0xff) | (buf[b + 1] << 8));
                    int scaled = (int) Math.round(sample * g);
                    buf[b] = (byte) scaled;
                    buf[b + 1] = (byte) (scaled >> 8);
                }
            }
        }

        void applyVolume() {
            if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                // Perceptual: the slider is linear in amplitude, the control wants decibels.
                float db = volume <= 0.0001 ? gain.getMinimum() : (float) (20.0 * Math.log10(volume));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
        }

        synchronized void setPaused(boolean p) {
            paused = p;
            if (p) {
                line.stop();
            } else {
                line.start();
            }
            notifyAll();
        }

        void cancel() {
            cancelled = true;
            synchronized (this) {
                paused = false;
                notifyAll();
            }
            line.stop();
            line.flush();
        }

        @Override
        public void run() {
            boolean completed = false;
            String failure = null;
            try {
                line.start();
                int frame = in.getFormat().getFrameSize();
                byte[] buf = new byte[line.getBufferSize() / 4 / frame * frame];
                long played = 0;
                int n;
                while (!cancelled && (clipBytes < 0 || played < clipBytes)
                        && (n = in.read(buf, 0, clipBytes < 0 ? buf.length
                        : (int) Math.min(buf.length, clipBytes - played))) >= 0) {
                    n = n / frame * frame;
                    envelope(buf, n, played);
                    played += n;
                    synchronized (this) {
                        while (paused && !cancelled) {
                            wait();
                        }
                    }
                    if (cancelled) {
                        break;
                    }
                    line.write(buf, 0, n);
                }
                if (!cancelled) {
                    line.drain();
                    completed = true;
                }
            } catch (IOException e) {
                failure = "Errore leggendo " + file.getFileName() + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                line.stop();
                line.close();
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing left to do with it
                }
            }
            Listener l = listener;
            if (cancelled || l == null) {
                return;
            }
            synchronized (JavaSoundPlayer.this) {
                if (current == this) {
                    current = null;
                }
            }
            if (completed) {
                l.finished();
            } else if (failure != null) {
                l.failed(failure);
            }
        }
    }
}
