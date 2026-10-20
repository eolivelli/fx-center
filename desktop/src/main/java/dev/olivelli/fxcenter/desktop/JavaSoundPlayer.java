package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.AudioPlayer;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    public synchronized void play(Path file) throws IOException {
        stop();
        Playback p = new Playback(file);
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
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
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
        final AudioInputStream in;
        final SourceDataLine line;
        final long durationMillis;
        final Thread thread;
        private volatile boolean cancelled;
        private boolean paused;

        Playback(Path file) throws IOException {
            this.file = file;
            this.in = openPcm(file);
            AudioFormat format = in.getFormat();
            try {
                line = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, format));
                line.open(format);
            } catch (LineUnavailableException | IllegalArgumentException e) {
                in.close();
                throw new IOException("Uscita audio non disponibile: " + e.getMessage(), e);
            }
            applyVolume();
            long fromHeader = JavaSoundPlayer.durationMillis(file);
            durationMillis = fromHeader > 0 ? fromHeader
                    : in.getFrameLength() > 0 ? (long) (in.getFrameLength() * 1000.0 / format.getFrameRate()) : -1;
            thread = new Thread(this, "fx-center-playback");
            thread.setDaemon(true);
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
                byte[] buf = new byte[line.getBufferSize() / 4];
                int n;
                while (!cancelled && (n = in.read(buf)) >= 0) {
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
