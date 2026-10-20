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
            cues.add(new Cue(o.get("id").getAsString(), o.get("fileName").getAsString(),
                    o.get("size").getAsLong(), o.has("md5") ? o.get("md5").getAsString() : null));
        }
        List<Document> docs = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("documents")) {
            JsonObject o = e.getAsJsonObject();
            docs.add(new Document(o.get("name").getAsString(), o.get("link").getAsString(),
                    o.get("mimeType").getAsString()));
        }
        return new Library(cues, docs);
    }
}
