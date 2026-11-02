package dev.olivelli.fxcenter.desktop;

import dev.olivelli.fxcenter.core.Cue;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Manual check, not a test: the main window in "Modifica scaletta" mode and
 * the clip editor on the cue at the given index, painted to two PNGs.
 *
 * <pre>java -cp desktop/target/test-classes:desktop/target/fx-center-*.jar \
 *     dev.olivelli.fxcenter.desktop.EditScreenshot HOME_DIR out-prefix cueIndex</pre>
 */
public final class EditScreenshot {

    private EditScreenshot() {
    }

    public static void main(String[] args) throws Exception {
        Theme.install();
        MainWindow[] w = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            w[0] = new MainWindow(Paths.get(args[0]));
            w[0].setVisible(true);
        });
        Thread.sleep(3000);
        int index = Integer.parseInt(args[2]);
        SwingUtilities.invokeAndWait(() -> {
            w[0].startEditing();
            w[0].controllerForTests().select(index);
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> paint(w[0], args[1] + "-main.png"));
        Cue cue = w[0].controllerForTests().selectedCue();
        Path file = w[0].controllerForTests().source().localFile(cue, dev.olivelli.fxcenter.core.Progress.NONE);
        ClipEditor[] d = new ClipEditor[1];
        SwingUtilities.invokeAndWait(() -> d[0] = new ClipEditor(w[0], cue, file));
        SwingUtilities.invokeLater(() -> d[0].setVisible(true));
        Thread.sleep(4000);
        SwingUtilities.invokeAndWait(() -> paint(d[0], args[1] + "-clip.png"));
        System.out.println("written " + args[1] + "-main.png and -clip.png");
        System.exit(0);
    }

    private static void paint(Component c, String out) {
        BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        c.paint(img.getGraphics());
        try {
            ImageIO.write(img, "png", new File(out));
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
