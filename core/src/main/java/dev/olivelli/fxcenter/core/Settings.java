package dev.olivelli.fxcenter.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** The few things the app remembers between runs, as a properties file on both platforms. */
public final class Settings {

    public enum Mode { DRIVE, LOCAL }

    private Mode mode = Mode.DRIVE;
    private String driveFolderId = Defaults.DRIVE_FOLDER_ID;
    private String localFolder = "";
    private double volume = 1.0;

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String driveFolderId() {
        return driveFolderId;
    }

    public void setDriveFolderId(String driveFolderId) {
        this.driveFolderId = driveFolderId;
    }

    public String localFolder() {
        return localFolder;
    }

    public void setLocalFolder(String localFolder) {
        this.localFolder = localFolder == null ? "" : localFolder;
    }

    public double volume() {
        return volume;
    }

    public void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
    }

    public static Settings load(Path file) {
        Settings s = new Settings();
        if (!Files.isRegularFile(file)) {
            return s;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        } catch (IOException e) {
            return s;
        }
        try {
            s.mode = Mode.valueOf(p.getProperty("mode", s.mode.name()));
        } catch (IllegalArgumentException ignored) {
            // an unknown mode falls back to the default
        }
        s.driveFolderId = p.getProperty("driveFolderId", s.driveFolderId);
        s.localFolder = p.getProperty("localFolder", s.localFolder);
        try {
            s.setVolume(Double.parseDouble(p.getProperty("volume", "1.0")));
        } catch (NumberFormatException ignored) {
            // keep the default
        }
        return s;
    }

    public void save(Path file) throws IOException {
        Properties p = new Properties();
        p.setProperty("mode", mode.name());
        p.setProperty("driveFolderId", driveFolderId);
        p.setProperty("localFolder", localFolder);
        p.setProperty("volume", Double.toString(volume));
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            p.store(out, "FX Center");
        }
    }
}
