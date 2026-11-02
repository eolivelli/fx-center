package dev.olivelli.fxcenter.desktop;

import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.SwingUtilities;

/** Entry point of the desktop app. */
public final class FxCenterApp {

    private FxCenterApp() {
    }

    /** Everything the app keeps lives here: settings and the Drive cache. */
    static Path homeDir() {
        String override = System.getProperty("fxcenter.home");
        return override != null ? Paths.get(override) : Paths.get(System.getProperty("user.home"), ".fx-center");
    }

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(() -> new MainWindow(homeDir()).setVisible(true));
    }
}
