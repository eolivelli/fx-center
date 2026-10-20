package dev.olivelli.fxcenter.android;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import dev.olivelli.fxcenter.core.AudioPlayer;
import java.io.IOException;
import java.nio.file.Path;

/**
 * {@link AudioPlayer} on {@link MediaPlayer}, which reads MP3, WAV, OGG, FLAC
 * and AAC on every supported device. One MediaPlayer per file: resetting and
 * reusing one is where MediaPlayer's state machine bites.
 */
final class AndroidPlayer implements AudioPlayer {

    private Listener listener;
    private MediaPlayer player;
    private float volume = 1f;
    private boolean prepared;

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public synchronized void play(Path file) throws IOException {
        stop();
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(file.toString());
            mp.setOnCompletionListener(p -> onCompletion(p));
            mp.setOnErrorListener((p, what, extra) -> {
                onError(p, "MediaPlayer errore " + what + "/" + extra + " su " + file.getFileName());
                return true;
            });
            mp.prepare();
            mp.setVolume(volume, volume);
            mp.start();
        } catch (IOException | RuntimeException e) {
            mp.release();
            throw e instanceof IOException ? (IOException) e
                    : new IOException("Non riesco a riprodurre " + file.getFileName() + ": " + e.getMessage(), e);
        }
        player = mp;
        prepared = true;
    }

    private void onCompletion(MediaPlayer p) {
        Listener l;
        synchronized (this) {
            if (p != player) {
                return;
            }
            release();
            l = listener;
        }
        if (l != null) {
            l.finished();
        }
    }

    private void onError(MediaPlayer p, String message) {
        Listener l;
        synchronized (this) {
            if (p != player) {
                return;
            }
            release();
            l = listener;
        }
        if (l != null) {
            l.failed(message);
        }
    }

    @Override
    public synchronized void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    @Override
    public synchronized void resume() {
        if (player != null && prepared) {
            player.start();
        }
    }

    @Override
    public synchronized void stop() {
        release();
    }

    private void release() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // already past playing
            }
            player.release();
            player = null;
            prepared = false;
        }
    }

    @Override
    public synchronized void setVolume(double v) {
        volume = (float) Math.max(0, Math.min(1, v));
        if (player != null) {
            player.setVolume(volume, volume);
        }
    }

    @Override
    public synchronized long positionMillis() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    @Override
    public synchronized long durationMillis() {
        return player != null && prepared ? player.getDuration() : -1;
    }

    @Override
    public synchronized void close() {
        release();
    }
}
