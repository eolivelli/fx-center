package dev.olivelli.fxcenter.desktop;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds where in an MP3 file to start decoding to reach a given time, by
 * walking the frame headers without decoding: a few milliseconds for a whole
 * song, where decoding up to the same point would make GO wait about a second
 * for a clip that starts three minutes in. VBR files (what yt-dlp makes) work
 * too, since every frame header says how long its frame is.
 *
 * <p>The subtle part is the bit reservoir: a Layer III frame may keep part of
 * its data in the frames before it ({@code main_data_begin} bytes back), and
 * JLayer outputs <em>nothing</em> for a frame whose data starts before the
 * point where decoding began. So decoding entered at frame {@code e} produces
 * fewer samples than the frames it reads, by an amount that depends on the
 * content. This class enters far enough back that the target frame's data is
 * all there, and works out how many of the samples decoded before the target
 * to throw away by replaying the reservoir from the side info alone.
 */
final class Mp3Seek {

    /** Where to start reading, and how many decoded sample frames to drop before the target. */
    static final class Point {
        final long byteOffset;
        final long discardFrames;

        Point(long byteOffset, long discardFrames) {
            this.byteOffset = byteOffset;
            this.discardFrames = discardFrames;
        }
    }

    private static final int[] MPEG1_KBPS = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, -1};
    private static final int[] MPEG1_RATES = {44100, 48000, 32000};

    /** The furthest back {@code main_data_begin} can point (9 bits). */
    private static final int MAX_RESERVOIR = 511;

    /** Frames decoded on top of the reservoir's needs, for the decoder's overlap state. */
    private static final int WARM_UP_FRAMES = 2;

    /** Frames of history kept while scanning; plenty for 511 bytes even at 32 kbit/s. */
    private static final int HISTORY = 64;

    private Mp3Seek() {
    }

    /**
     * Where to enter the file to reach {@code targetMillis}, or null when the
     * file is not a plain MPEG-1 Layer III stream, or the target is too close
     * to the start or past the end: then the caller decodes from the start.
     */
    static Point before(Path file, long targetMillis) throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
            long pos = skipId3(in);
            long[] offsets = new long[HISTORY];
            int[] mainBegin = new int[HISTORY];
            int[] mainBytes = new int[HISTORY];
            long samples = 0;
            long targetSample = -1;
            int frames = 0;
            byte[] h = new byte[4];
            byte[] side = new byte[3];
            while (true) {
                in.mark(4);
                if (readFully(in, h) < 4) {
                    return null; // the target is past the end
                }
                int length = frameLength(h);
                if (length <= 0) {
                    in.reset();
                    if (in.skip(1) < 1) {
                        return null;
                    }
                    pos++;
                    if (frames == 0 && pos > 1 << 20) {
                        return null; // not an MP3 we understand
                    }
                    continue;
                }
                if (targetSample < 0) {
                    targetSample = targetMillis * MPEG1_RATES[(h[2] >> 2) & 3] / 1000;
                }
                boolean crc = (h[1] & 1) == 0;
                boolean mono = ((h[3] >> 6) & 3) == 3;
                int slot = frames % HISTORY;
                offsets[slot] = pos;
                in.mark(length);
                if (crc && skipFully(in, 2) < 2) {
                    return null;
                }
                if (readFully(in, side) < side.length) {
                    return null;
                }
                in.reset();
                mainBegin[slot] = ((side[0] & 0xff) << 1) | ((side[1] & 0xff) >> 7);
                mainBytes[slot] = Math.max(0, length - 4 - (crc ? 2 : 0) - (mono ? 17 : 32));
                frames++;
                if (samples + 1152 > targetSample) {
                    return entry(offsets, mainBegin, mainBytes, frames - 1, targetSample - samples);
                }
                samples += 1152;
                long body = length - 4;
                if (skipFully(in, body) < body) {
                    return null;
                }
                pos += length;
            }
        }
    }

    /**
     * @param k       index (in frames scanned) of the frame holding the target
     * @param inFrame sample frames from the start of frame k to the target
     */
    private static Point entry(long[] offsets, int[] mainBegin, int[] mainBytes, int k, long inFrame) {
        // Walk back until the frames before k hold all the data k, or any later frame, can ask for.
        int e = k;
        long buffered = 0;
        while (buffered < MAX_RESERVOIR) {
            e--;
            if (e < 0 || k - e >= HISTORY) {
                return null;
            }
            buffered += mainBytes[e % HISTORY];
        }
        // Two frames more, so the frames that do decode before k warm up the synthesis filterbank:
        // without them the first ~20 ms differ audibly from a decode from the start.
        e = Math.max(0, Math.max(e - WARM_UP_FRAMES, k - HISTORY + 1));
        // Replay the reservoir from e: a frame asking for more than is buffered decodes to nothing.
        long reservoir = 0;
        long decodedBeforeK = 0;
        for (int f = e; f < k; f++) {
            if (mainBegin[f % HISTORY] <= reservoir) {
                decodedBeforeK += 1152;
            }
            reservoir += mainBytes[f % HISTORY];
        }
        return new Point(offsets[e % HISTORY], decodedBeforeK + inFrame);
    }

    /** Skips an ID3v2 tag at the start, returning how many bytes it took. */
    private static long skipId3(InputStream in) throws IOException {
        in.mark(10);
        byte[] h = new byte[10];
        if (readFully(in, h) == 10 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
            long size = ((h[6] & 0x7f) << 21) | ((h[7] & 0x7f) << 14) | ((h[8] & 0x7f) << 7) | (h[9] & 0x7f);
            if ((h[5] & 0x10) != 0) {
                size += 10; // footer
            }
            skipFully(in, size);
            return 10 + size;
        }
        in.reset();
        return 0;
    }

    /**
     * Bytes in the frame this header starts, or -1 when it is not an MPEG-1
     * Layer III header. MPEG-2/2.5 (low sample rates, rare for music) is left
     * to the slow path rather than modelled.
     */
    static int frameLength(byte[] h) {
        if ((h[0] & 0xff) != 0xff || (h[1] & 0xe0) != 0xe0) {
            return -1;
        }
        int version = (h[1] >> 3) & 3; // 3 = MPEG-1
        int layer = (h[1] >> 1) & 3; // 1 = Layer III
        int bitrateIndex = (h[2] >> 4) & 0xf;
        int rateIndex = (h[2] >> 2) & 3;
        int padding = (h[2] >> 1) & 1;
        if (version != 3 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
            return -1;
        }
        return 144 * MPEG1_KBPS[bitrateIndex] * 1000 / MPEG1_RATES[rateIndex] + padding;
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) {
                break;
            }
            n += r;
        }
        return n;
    }

    private static long skipFully(InputStream in, long n) throws IOException {
        long done = 0;
        while (done < n) {
            long s = in.skip(n - done);
            if (s <= 0) {
                if (in.read() < 0) {
                    break;
                }
                s = 1;
            }
            done += s;
        }
        return done;
    }
}
