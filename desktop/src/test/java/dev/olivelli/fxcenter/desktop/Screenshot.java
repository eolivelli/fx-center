package dev.olivelli.fxcenter.desktop;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Manual check, not a test: opens the window against the given home dir,
 * waits for the Drive listing and downloads, paints the window to a PNG and
 * exits. Lets a remote reviewer see the screen without a screenshot tool.
 *
 * <pre>java -cp desktop/target/test-classes:desktop/target/fx-center-*.jar \
 *     dev.olivelli.fxcenter.desktop.Screenshot HOME_DIR out.png [waitSeconds]</pre>
 */
public final class Screenshot {

    private Screenshot() {
    }

    public static void main(String[] args) throws Exception {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        MainWindow[] holder = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new MainWindow(Paths.get(args[0]));
            holder[0].setVisible(true);
        });
        Thread.sleep(1000L * (args.length > 2 ? Integer.parseInt(args[2]) : 15));
        SwingUtilities.invokeAndWait(() -> {
            MainWindow w = holder[0];
            BufferedImage img = new BufferedImage(w.getWidth(), w.getHeight(), BufferedImage.TYPE_INT_RGB);
            w.paint(img.getGraphics());
            try {
                ImageIO.write(img, "png", new File(args[1]));
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("written " + args[1]);
        System.exit(0);
    }
}
