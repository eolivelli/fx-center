package dev.olivelli.fxcenter.core;

/**
 * What the app does out of the box, so the technician installs it and sees the
 * show's folder without typing anything.
 *
 * <p>The API key is restricted to the Drive API and only ever reads folders
 * already shared with "anyone with the link": it is a routing token, not a
 * secret, and shipping it in a public APK is how Google expects such keys to be
 * used.
 */
public final class Defaults {

    /** Drive folder "La fabbrica di cioccolato". */
    public static final String DRIVE_FOLDER_ID = "1esZtjXmn_0XvrELADnSSzhaxIjDxU8K0";

    public static final String DRIVE_API_KEY = "AIzaSyCzBwsnP9hAYQaDCCCtfohkJAqJrkpr6cQ";

    private Defaults() {
    }
}
