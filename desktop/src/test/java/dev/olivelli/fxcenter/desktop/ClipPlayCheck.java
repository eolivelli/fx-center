package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.AudioPlayer;
import dev.olivelli.fxcenter.core.Clip;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Manual check: plays FILE from START to END ms on the sound card and reports timing. */
public final class ClipPlayCheck {
    private ClipPlayCheck() {
    }

    public static void main(String[] a) throws Exception {
        JavaSoundPlayer p = new JavaSoundPlayer();
        CountDownLatch done = new CountDownLatch(1);
        p.setListener(new AudioPlayer.Listener() {
            @Override
            public void finished() {
                done.countDown();
            }

            @Override
            public void failed(String message) {
                System.out.println("failed " + message);
                done.countDown();
            }
        });
        p.setVolume(a.length > 3 ? Double.parseDouble(a[3]) : 0.15);
        long t0 = System.nanoTime();
        p.play(Paths.get(a[0]), Clip.of(Long.parseLong(a[1]), Long.parseLong(a[2])));
        long started = (System.nanoTime() - t0) / 1_000_000;
        Thread.sleep(500);
        System.out.println("GO->play " + started + " ms; duration reported " + p.durationMillis()
                + " ms; position after 0.5 s " + p.positionMillis() + " ms");
        boolean ok = done.await(60, TimeUnit.SECONDS);
        System.out.println((ok ? "finished" : "TIMEOUT") + " after " + (System.nanoTime() - t0) / 1_000_000 + " ms");
        System.exit(0);
    }
}
