package dev.olivelli.fxcenter.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Which files in a folder count as cues. By extension: Drive MIME types for audio are inconsistent. */
public final class AudioFiles {

    private static final Set<String> EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3", "wav", "ogg", "flac", "m4a", "aac", "aif", "aiff", "au"));

    private AudioFiles() {
    }

    public static boolean isAudio(String fileName) {
        return EXTENSIONS.contains(extension(fileName));
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
