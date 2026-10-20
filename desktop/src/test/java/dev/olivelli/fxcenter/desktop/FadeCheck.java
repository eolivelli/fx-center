package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.Cue;
import dev.olivelli.fxcenter.core.LocalFolderSource;
import dev.olivelli.fxcenter.core.ShowController;
import java.nio.file.Paths;

/**
 * Manual check, not a test: plays the first cue of a folder for a few seconds
 * on the real sound card, then presses STOP and prints the states the
 * controller goes through, so the fade can be heard and timed.
 *
 * <pre>java -cp desktop/target/test-classes:desktop/target/fx-center-*.jar \
 *     dev.olivelli.fxcenter.desktop.FadeCheck FOLDER</pre>
 */
public final class FadeCheck {

    private FadeCheck() {
    }

    public static void main(String[] args) throws Exception {
        ShowController ctl = new ShowController(new JavaSoundPlayer(), new ShowController.Listener() {
            @Override
            public void changed() {
            }

            @Override
            public void error(String message) {
                System.out.println("error: " + message);
            }
        });
        ctl.setSource(new LocalFolderSource(Paths.get(args[0])));
        ctl.refresh();
        Cue cue = ctl.selectedCue();
        System.out.println("playing " + cue.fileName());
        ctl.go();
        Thread.sleep(3000);
        long t0 = System.currentTimeMillis();
        ctl.stop();
        ShowController.State last = null;
        while (ctl.state() != ShowController.State.IDLE) {
            if (ctl.state() != last) {
                last = ctl.state();
                System.out.println((System.currentTimeMillis() - t0) + " ms: " + last);
            }
            Thread.sleep(10);
        }
        System.out.println((System.currentTimeMillis() - t0) + " ms: IDLE, position was "
                + ctl.positionMillis() + " duration " + ctl.durationMillis());
        ctl.close();
        System.out.println("ok");
    }
}
