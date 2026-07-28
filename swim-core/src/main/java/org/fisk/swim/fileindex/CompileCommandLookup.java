package org.fisk.swim.fileindex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import com.google.gson.JsonParser;

/** Looks up the exact project build invocation for one translation unit. */
public final class CompileCommandLookup {
    public record Entry(Path directory, String command) { }

    private CompileCommandLookup() { }

    public static Entry find(Path database, Path source) throws IOException {
        if (database == null || source == null || !Files.isRegularFile(database)) return null;
        Path wanted = source.toAbsolutePath().normalize();
        var entries = JsonParser.parseString(Files.readString(database)).getAsJsonArray();
        for (var element : entries) {
            if (!element.isJsonObject()) continue;
            var object = element.getAsJsonObject();
            if (!object.has("file")) continue;
            Path directory = object.has("directory") ? Path.of(object.get("directory").getAsString()) : database.getParent();
            if (!directory.isAbsolute()) directory = database.getParent().resolve(directory);
            Path file = Path.of(object.get("file").getAsString());
            if (!file.isAbsolute()) file = directory.resolve(file);
            if (!wanted.equals(file.toAbsolutePath().normalize())) continue;
            if (object.has("arguments") && object.get("arguments").isJsonArray()) {
                var arguments = new ArrayList<String>();
                for (var argument : object.getAsJsonArray("arguments")) arguments.add(shellQuote(argument.getAsString()));
                return new Entry(directory.toAbsolutePath().normalize(), String.join(" ", arguments));
            }
            if (object.has("command")) return new Entry(directory.toAbsolutePath().normalize(), object.get("command").getAsString());
        }
        return null;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
