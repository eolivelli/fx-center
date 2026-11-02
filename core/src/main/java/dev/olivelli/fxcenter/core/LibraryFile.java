package dev.olivelli.fxcenter.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Saves and loads a {@link Library} as JSON: the offline copy of the last Drive listing. */
final class LibraryFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LibraryFile() {
    }

    static void save(Library library, Path file) throws IOException {
        JsonObject root = new JsonObject();
        JsonArray cues = new JsonArray();
        for (Cue c : library.cues()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id());
            o.addProperty("fileName", c.fileName());
            o.addProperty("size", c.size());
            if (c.md5() != null) {
                o.addProperty("md5", c.md5());
            }
            if (c.clip().isClipped()) {
                o.addProperty("clipStart", c.clip().startMillis());
                o.addProperty("clipEnd", c.clip().endMillis());
            }
            cues.add(o);
        }
        JsonArray docs = new JsonArray();
        for (Document d : library.documents()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", d.name());
            o.addProperty("link", d.link());
            o.addProperty("mimeType", d.mimeType());
            docs.add(o);
        }
        root.add("cues", cues);
        root.add("documents", docs);
        Library.ScalettaInfo info = library.scaletta();
        if (info.present()) {
            JsonObject sc = new JsonObject();
            if (info.fileId() != null) {
                sc.addProperty("fileId", info.fileId());
            }
            if (info.md5() != null) {
                sc.addProperty("md5", info.md5());
            }
            JsonArray orphans = new JsonArray();
            info.orphanLines().forEach(orphans::add);
            sc.add("orphanLines", orphans);
            root.add("scaletta", sc);
        }
        Files.createDirectories(file.getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, w);
        }
    }

    static Library load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Library.EMPTY;
        }
        JsonObject root;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        List<Cue> cues = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("cues")) {
            JsonObject o = e.getAsJsonObject();
            Clip clip = o.has("clipStart")
                    ? Clip.of(o.get("clipStart").getAsLong(), o.get("clipEnd").getAsLong()) : Clip.NONE;
            cues.add(new Cue(o.get("id").getAsString(), o.get("fileName").getAsString(),
                    o.get("size").getAsLong(), o.has("md5") ? o.get("md5").getAsString() : null, clip));
        }
        List<Document> docs = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("documents")) {
            JsonObject o = e.getAsJsonObject();
            docs.add(new Document(o.get("name").getAsString(), o.get("link").getAsString(),
                    o.get("mimeType").getAsString()));
        }
        Library.ScalettaInfo info = Library.ScalettaInfo.NONE;
        if (root.has("scaletta")) {
            JsonObject sc = root.getAsJsonObject("scaletta");
            List<String> orphans = new ArrayList<>();
            if (sc.has("orphanLines")) {
                sc.getAsJsonArray("orphanLines").forEach(e -> orphans.add(e.getAsString()));
            }
            info = new Library.ScalettaInfo(true, sc.has("fileId") ? sc.get("fileId").getAsString() : null,
                    sc.has("md5") ? sc.get("md5").getAsString() : null, orphans, java.util.Collections.emptyList());
        }
        // The order saved is the running order: keep it, do not re-sort.
        return Library.ordered(cues, docs, info);
    }
}
