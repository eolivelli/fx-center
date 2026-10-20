package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.AudioPlayer;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Manual check, not a test: plays a file for a few seconds on the real sound
 * card and prints position and duration, which is how the MP3 path through
 * the shaded jar gets verified on a developer machine.
 *
 * <pre>java -cp desktop/target/test-classes:desktop/target/fx-center-*.jar \
 *     dev.olivelli.fxcenter.desktop.PlayCheck file.mp3 [seconds]</pre>
 */
public final class PlayCheck {

    private PlayCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Paths.get(args[0]);
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        JavaSoundPlayer player = new JavaSoundPlayer();
        player.setListener(new AudioPlayer.Listener() {
            @Override
            public void finished() {
                System.out.println("finished");
            }

            @Override
            public void failed(String message) {
                System.out.println("failed: " + message);
            }
        });
        player.play(file);
        System.out.println("duration " + player.durationMillis() + " ms");
        for (int i = 0; i < seconds; i++) {
            Thread.sleep(1000);
            System.out.println("position " + player.positionMillis() + " ms");
        }
        player.pause();
        long p = player.positionMillis();
        Thread.sleep(500);
        System.out.println("paused, position stayed: " + (player.positionMillis() == p));
        player.resume();
        Thread.sleep(500);
        System.out.println("resumed, position moved: " + (player.positionMillis() > p));
        player.close();
        System.out.println("ok");
    }
}
