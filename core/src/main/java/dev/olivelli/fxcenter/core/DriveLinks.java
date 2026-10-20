package dev.olivelli.fxcenter.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns whatever someone pastes — a folder URL, a share link, a bare id — into a folder id. */
public final class DriveLinks {

    private static final Pattern FOLDERS = Pattern.compile("/folders/([A-Za-z0-9_-]+)");
    private static final Pattern ID_PARAM = Pattern.compile("[?&]id=([A-Za-z0-9_-]+)");
    private static final Pattern BARE = Pattern.compile("[A-Za-z0-9_-]{10,}");

    private DriveLinks() {
    }

    /** @return the folder id, or null when the text holds nothing that looks like one */
    public static String folderId(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        Matcher m = FOLDERS.matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        m = ID_PARAM.matcher(t);
        if (m.find()) {
            return m.group(1);
        }
        if (BARE.matcher(t).matches()) {
            return t;
        }
        return null;
    }

    public static String folderUrl(String folderId) {
        return "https://drive.google.com/drive/folders/" + folderId;
    }
}
