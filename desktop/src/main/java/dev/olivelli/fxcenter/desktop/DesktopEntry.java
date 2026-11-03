package dev.olivelli.fxcenter.desktop;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;

/**
 * On Linux, the launcher that lets the desktop (GNOME and the others that
 * follow freedesktop.org) recognise the running window: without it the dock
 * shows no FX Center icon, because nothing declares the window's class.
 *
 * <p>Written to {@code ~/.local/share/applications/fx-center.desktop} (with
 * the icon in {@code ~/.local/share/icons/fx-center.png}) at each
 * start, only when it would change (for instance after downloading a new jar
 * to another place), so it always starts the jar the user last ran. It also
 * puts FX Center among the applications, where it can be pinned to the dock.
 */
final class DesktopEntry {

    /** What AWT names the window class after: the main class, dots as dashes. */
    static final String WM_CLASS = FxCenterApp.class.getName().replace('.', '-');

    private DesktopEntry() {
    }

    /** Best effort: a launcher that cannot be written only costs the icon. */
    static void install() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")
                || Boolean.getBoolean("fxcenter.noLauncher")) {
            return;
        }
        try {
            Path jar = runningJar();
            if (jar == null) {
                return; // started from classes (development, tests): nothing to launch
            }
            String dataHome = System.getenv("XDG_DATA_HOME");
            Path data = dataHome != null && !dataHome.isEmpty() ? Paths.get(dataHome)
                    : Paths.get(System.getProperty("user.home"), ".local", "share");
            Path icon = data.resolve("icons").resolve("fx-center.png");
            Files.createDirectories(icon.getParent());
            ImageIO.write(AppIcon.image(256), "png", icon.toFile());
            Path java = Paths.get(System.getProperty("java.home"), "bin", "java");
            String text = text(java, jar, icon);
            Path dir = data.resolve("applications");
            Path file = dir.resolve("fx-center.desktop");
            if (Files.isRegularFile(file) && new String(Files.readAllBytes(file), StandardCharsets.UTF_8).equals(text)) {
                return;
            }
            Files.createDirectories(dir);
            Path tmp = dir.resolve(".fx-center.desktop.tmp");
            Files.write(tmp, text.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.err.println("FX Center: launcher not written: " + e);
        }
    }

    static String text(Path java, Path jar, Path icon) {
        return "[Desktop Entry]\n"
                + "Type=Application\n"
                + "Name=FX Center\n"
                + "Comment=Musiche ed effetti dello spettacolo\n"
                + "Exec=" + quote(java.toString()) + " -jar " + quote(jar.toString()) + "\n"
                + "Icon=" + icon + "\n"
                + "StartupWMClass=" + WM_CLASS + "\n"
                + "Terminal=false\n"
                + "Categories=AudioVideo;Audio;Player;\n";
    }

    /** Exec quoting of the Desktop Entry spec: double quotes, with " ` $ \ escaped. */
    static String quote(String arg) {
        return "\"" + arg.replaceAll("([\"`$\\\\])", "\\\\$1") + "\"";
    }

    private static Path runningJar() {
        try {
            File f = new File(FxCenterApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return f.isFile() && f.getName().endsWith(".jar") ? f.toPath().toAbsolutePath() : null;
        } catch (URISyntaxException | SecurityException | NullPointerException e) {
            return null;
        }
    }
}
