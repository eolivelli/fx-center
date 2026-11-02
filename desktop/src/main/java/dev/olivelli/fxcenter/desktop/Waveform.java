package dev.olivelli.fxcenter.desktop;

import java.io.IOException;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

/**
 * A song's loudness envelope for drawing: the peak of each 10 ms, from a full
 * decode. About a second and a half for a five-minute MP3, so it is built off
 * the UI thread while the clip editor shows a placeholder.
 */
final class Waveform {

    static final int MILLIS_PER_BUCKET = 10;

    /** Peak absolute amplitude per bucket, 0..1. */
    final float[] peaks;
    final long durationMillis;

    private Waveform(float[] peaks, long durationMillis) {
        this.peaks = peaks;
        this.durationMillis = durationMillis;
    }

    static Waveform load(Path file) throws IOException {
        try (AudioInputStream in = JavaSoundPlayer.openPcm(file)) {
            AudioFormat f = in.getFormat();
            int channels = f.getChannels();
            int frameSize = f.getFrameSize();
            int framesPerBucket = Math.max(1, Math.round(f.getFrameRate() * MILLIS_PER_BUCKET / 1000f));
            float[] peaks = new float[1024];
            int buckets = 0;
            int inBucket = 0;
            int peak = 0;
            long frames = 0;
            byte[] buf = new byte[frameSize * 4096];
            int carry = 0;
            int n;
            while ((n = in.read(buf, carry, buf.length - carry)) >= 0) {
                int avail = carry + n;
                int whole = avail / frameSize * frameSize;
                for (int i = 0; i < whole; i += frameSize) {
                    for (int c = 0; c < channels; c++) {
                        int b = i + c * 2;
                        int s = Math.abs((short) ((buf[b] & 0xff) | (buf[b + 1] << 8)));
                        if (s > peak) {
                            peak = s;
                        }
                    }
                    frames++;
                    if (++inBucket == framesPerBucket) {
                        if (buckets == peaks.length) {
                            peaks = java.util.Arrays.copyOf(peaks, peaks.length * 2);
                        }
                        peaks[buckets++] = peak / 32768f;
                        inBucket = 0;
                        peak = 0;
                    }
                }
                carry = avail - whole;
                System.arraycopy(buf, whole, buf, 0, carry);
            }
            if (inBucket > 0) {
                if (buckets == peaks.length) {
                    peaks = java.util.Arrays.copyOf(peaks, peaks.length + 1);
                }
                peaks[buckets++] = peak / 32768f;
            }
            long millis = (long) (frames * 1000.0 / f.getFrameRate());
            return new Waveform(java.util.Arrays.copyOf(peaks, buckets), millis);
        }
    }

    /** The loudest bucket between two times, 0..1. */
    float peak(long fromMillis, long toMillis) {
        int a = (int) Math.max(0, fromMillis / MILLIS_PER_BUCKET);
        int b = (int) Math.min(peaks.length, Math.max(a + 1, toMillis / MILLIS_PER_BUCKET));
        float p = 0;
        for (int i = a; i < b; i++) {
            p = Math.max(p, peaks[i]);
        }
        return p;
    }
}
