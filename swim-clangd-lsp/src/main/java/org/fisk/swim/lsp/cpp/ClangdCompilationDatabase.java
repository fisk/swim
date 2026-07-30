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
    private static final List<String> CPP_SUFFIXES = List.of(
            ".inline.hpp", ".cpp", ".cxx", ".cc", ".c", ".hpp", ".hxx", ".hh", ".h");

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
                Path entryFile = entryFile(command);
                if (entryFile != null && normalizedFile.equals(entryFile)) {
                    return true;
                }
            }
            // clangd uses a nearby translation unit's flags for headers. Do
            // the same at the eligibility boundary: a header/sibling is valid
            // when its same-basename file in the same directory has a compile
            // command, even though headers do not normally appear in the DB.
            for (var entry : entries) {
                if (!entry.isJsonObject()) continue;
                Path entryFile = entryFile(entry.getAsJsonObject());
                if (isSameBasenameSibling(normalizedFile, entryFile)) return true;
            }
        } catch (IOException | IllegalStateException | com.google.gson.JsonParseException e) {
            return false;
        }
        return false;
    }

    private static Path entryFile(com.google.gson.JsonObject command) {
        if (command == null || !command.has("file")) return null;
        Path entryFile = Path.of(command.get("file").getAsString());
        if (!entryFile.isAbsolute() && command.has("directory")) {
            entryFile = Path.of(command.get("directory").getAsString()).resolve(entryFile);
        }
        return entryFile.toAbsolutePath().normalize();
    }

    private static boolean isSameBasenameSibling(Path requestedFile, Path commandFile) {
        if (requestedFile == null || commandFile == null || !java.util.Objects.equals(requestedFile.getParent(), commandFile.getParent())) {
            return false;
        }
        String requested = requestedFile.getFileName().toString();
        String command = commandFile.getFileName().toString();
        return !requested.equals(command) && baseName(requested).equals(baseName(command));
    }

    private static String baseName(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : CPP_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
