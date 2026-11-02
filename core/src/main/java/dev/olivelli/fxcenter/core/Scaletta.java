package dev.olivelli.fxcenter.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code scaletta.txt}: the running order and the clips, as a text file that
 * sits in the show's folder next to the songs and that people can edit by hand.
 *
 * <pre>
 * # comment
 * MUS-01 Sigla.mp3
 * Main Titles.mp3 | 0:12.5 - 1:40
 * Wonka.mp3 | 0:30 -
 * </pre>
 *
 * <p>One file name per line, in show order; after a {@code |}, an optional
 * clip {@code start - end} in {@code m:ss}, {@code m:ss.d} or plain seconds,
 * either side may be left empty. Files of the folder the text does not name go
 * after the named ones, in name order, so a new upload shows up without anyone
 * editing the text. A line naming no file in the folder is kept when the app
 * rewrites the text, so a file renamed or removed by mistake does not lose its
 * clip for good.
 */
public final class Scaletta {

    public static final String FILE_NAME = "scaletta.txt";

    /** One non-comment line, as parsed. */
    public static final class Entry {
        final String fileName;
        final Clip clip;
        final String line;

        Entry(String fileName, Clip clip, String line) {
            this.fileName = fileName;
            this.clip = clip;
            this.line = line;
        }
    }

    private final List<Entry> entries;
    private final List<String> warnings;

    private Scaletta(List<Entry> entries, List<String> warnings) {
        this.entries = entries;
        this.warnings = warnings;
    }

    public static boolean isScalettaFile(String name) {
        return FILE_NAME.equalsIgnoreCase(name);
    }

    /** Never fails: a line it cannot read is reported in the warnings and its file still counts for the order. */
    public static Scaletta parse(String text) {
        List<Entry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String[] lines = text.replace("﻿", "").split("\r?\n|\r");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int bar = line.lastIndexOf('|');
            String name = (bar < 0 ? line : line.substring(0, bar)).trim();
            Clip clip = Clip.NONE;
            if (bar >= 0) {
                try {
                    clip = parseClip(line.substring(bar + 1));
                } catch (IllegalArgumentException e) {
                    warnings.add("riga " + (i + 1) + ": taglio non valido (" + e.getMessage() + "), suono il brano intero");
                }
            }
            if (!name.isEmpty()) {
                entries.add(new Entry(name, clip, line));
            }
        }
        return new Scaletta(entries, warnings);
    }

    static Clip parseClip(String s) {
        String t = s.trim();
        if (t.isEmpty()) {
            return Clip.NONE;
        }
        int dash = t.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("manca il '-' fra inizio e fine");
        }
        String a = t.substring(0, dash).trim();
        String b = t.substring(dash + 1).trim();
        return Clip.of(a.isEmpty() ? 0 : parseTime(a), b.isEmpty() ? -1 : parseTime(b));
    }

    /** {@code 75}, {@code 75.5}, {@code 1:15}, {@code 1:15.5}, {@code 1:01:15}; comma as decimal mark too. */
    public static long parseTime(String s) {
        String t = s.trim().replace(',', '.');
        String[] parts = t.split(":");
        if (parts.length > 3) {
            throw new IllegalArgumentException("tempo non valido: " + s);
        }
        try {
            double total = 0;
            for (int i = 0; i < parts.length; i++) {
                double v = Double.parseDouble(parts[i]);
                if (v < 0 || (i > 0 && v >= 60)) {
                    throw new IllegalArgumentException("tempo non valido: " + s);
                }
                total = total * 60 + v;
            }
            return Math.round(total * 1000);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("tempo non valido: " + s);
        }
    }

    /** {@code m:ss.d}, to the tenth of a second. */
    public static String formatTime(long millis) {
        long tenths = Math.round(Math.max(0, millis) / 100.0);
        long seconds = tenths / 10;
        return String.format(Locale.ROOT, "%d:%02d.%d", seconds / 60, seconds % 60, tenths % 10);
    }

    public static String formatClip(Clip clip) {
        return formatTime(clip.startMillis()) + " - " + (clip.hasEnd() ? formatTime(clip.endMillis()) : "");
    }

    /**
     * Orders the cues of a folder listing and gives each its clip.
     *
     * @param listing what the folder holds, already in name order
     * @param fileId  where the text lives on Drive, or null for a local folder
     * @param md5     hex MD5 of the text as it was read
     */
    public Library arrange(Library listing, String fileId, String md5) {
        Map<String, Cue> exact = new HashMap<>();
        Map<String, Cue> loose = new HashMap<>();
        for (Cue c : listing.cues()) {
            exact.put(c.fileName(), c);
            loose.putIfAbsent(c.fileName().toLowerCase(Locale.ROOT), c);
            loose.putIfAbsent(c.title().toLowerCase(Locale.ROOT), c);
        }
        List<Cue> ordered = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<String> orphans = new ArrayList<>();
        List<String> warn = new ArrayList<>(warnings);
        for (Entry e : entries) {
            Cue c = exact.get(e.fileName);
            if (c == null) {
                c = loose.get(e.fileName.toLowerCase(Locale.ROOT));
            }
            if (c == null) {
                orphans.add(e.line);
                warn.add("non trovo \"" + e.fileName + "\" nella cartella");
                continue;
            }
            if (!used.add(c.id())) {
                warn.add("\"" + e.fileName + "\" compare due volte: tengo la prima");
                continue;
            }
            ordered.add(c.withClip(e.clip));
        }
        for (Cue c : listing.cues()) {
            if (!used.contains(c.id())) {
                ordered.add(c);
            }
        }
        return Library.ordered(ordered, listing.documents(),
                new Library.ScalettaInfo(true, fileId, md5, orphans, warn));
    }

    /** The text for this running order, with the lines that matched no file kept at the end. */
    public static String format(List<Cue> cues, List<String> orphans) {
        StringBuilder sb = new StringBuilder();
        sb.append("# FX Center - scaletta: un brano per riga, nell'ordine dello spettacolo.\n");
        sb.append("# Taglio facoltativo dopo '|':  inizio - fine   (m:ss oppure m:ss.d; uno dei due puo' mancare)\n");
        sb.append("# I brani della cartella che non sono qui vanno in fondo all'elenco.\n");
        sb.append('\n');
        for (Cue c : cues) {
            sb.append(c.fileName());
            if (c.clip().isClipped()) {
                sb.append(" | ").append(formatClip(c.clip()));
            }
            sb.append('\n');
        }
        if (!orphans.isEmpty()) {
            sb.append("\n# Righe che non corrispondono a nessun file della cartella (tenute per sicurezza):\n");
            for (String o : orphans) {
                sb.append(o).append('\n');
            }
        }
        return sb.toString();
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }

    public static String md5(byte[] content) {
        try {
            return DriveClient.hex(MessageDigest.getInstance("MD5").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String md5(String text) {
        return md5(text.getBytes(StandardCharsets.UTF_8));
    }
}
