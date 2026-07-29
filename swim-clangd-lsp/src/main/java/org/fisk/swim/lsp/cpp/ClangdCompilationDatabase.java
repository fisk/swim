package org.fisk.swim.lsp.cpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

final class ClangdCompilationDatabase {
    private ClangdCompilationDatabase() {
    }

    static Path filteredRoot(Path sourceRoot, Path workspacePath, List<String> removedArguments) {
        if (sourceRoot == null || removedArguments == null || removedArguments.isEmpty()) {
            return sourceRoot;
        }
        Path source = sourceRoot.resolve("compile_commands.json");
        Path target = workspacePath.resolve("compile_commands.json");
        try {
            JsonArray entries = JsonParser.parseString(Files.readString(source)).getAsJsonArray();
            for (var entry : entries) {
                if (!entry.isJsonObject()) continue;
                var command = entry.getAsJsonObject();
                if (command.has("arguments") && command.get("arguments").isJsonArray()) {
                    JsonArray arguments = command.getAsJsonArray("arguments");
                    for (int index = arguments.size() - 1; index >= 0; index--) {
                        if (removedArguments.contains(arguments.get(index).getAsString())) {
                            arguments.remove(index);
                        }
                    }
                }
                if (command.has("command")) {
                    String text = command.get("command").getAsString();
                    for (String argument : removedArguments) {
                        text = text.replaceAll("(?<!\\S)" + Pattern.quote(argument) + "(?!\\S)", "");
                    }
                    command.addProperty("command", text.replaceAll(" {2,}", " "));
                }
            }
            Files.createDirectories(workspacePath);
            Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(entries), StandardCharsets.UTF_8);
            return workspacePath;
        } catch (IOException | IllegalStateException e) {
            return sourceRoot;
        }
    }

    static boolean containsCommandFor(Path sourceRoot, Path file) {
        if (sourceRoot == null || file == null) {
            return false;
        }
        Path normalizedFile = file.toAbsolutePath().normalize();
        try {
            JsonArray entries = JsonParser.parseString(Files.readString(sourceRoot.resolve("compile_commands.json")))
                    .getAsJsonArray();
            for (var entry : entries) {
                if (!entry.isJsonObject()) continue;
                var command = entry.getAsJsonObject();
                if (!command.has("file")) continue;
                Path entryFile = Path.of(command.get("file").getAsString());
                if (!entryFile.isAbsolute() && command.has("directory")) {
                    entryFile = Path.of(command.get("directory").getAsString()).resolve(entryFile);
                }
                if (normalizedFile.equals(entryFile.toAbsolutePath().normalize())) {
                    return true;
                }
            }
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException e) {
            return false;
        }
        return false;
    }
}
