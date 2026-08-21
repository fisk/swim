package org.fisk.swim.lsp.cpp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class ClangdCompilationDatabase {
    private ClangdCompilationDatabase() {
    }

    static Path filteredRoot(Path sourceRoot, Path workspacePath, List<String> removedArguments, Path requestedFile) {
        if (sourceRoot == null) {
            return sourceRoot;
        }
        Path source = sourceRoot.resolve("compile_commands.json");
        Path target = workspacePath.resolve("compile_commands.json");
        try {
            JsonArray entries = JsonParser.parseString(Files.readString(source)).getAsJsonArray();
            boolean changed = false;
            for (var entry : entries) {
                if (!entry.isJsonObject()) continue;
                var command = entry.getAsJsonObject();
                if (command.has("arguments") && command.get("arguments").isJsonArray()) {
                    JsonArray arguments = command.getAsJsonArray("arguments");
                    for (int index = arguments.size() - 1; index >= 0; index--) {
                        if (removedArguments != null && removedArguments.contains(arguments.get(index).getAsString())) {
                            arguments.remove(index);
                            changed = true;
                        }
                    }
                }
                if (removedArguments != null && !removedArguments.isEmpty() && command.has("command")) {
                    String text = command.get("command").getAsString();
                    for (String argument : removedArguments) {
                        text = text.replaceAll("(?<!\\S)" + Pattern.quote(argument) + "(?!\\S)", "");
                    }
                    command.addProperty("command", text.replaceAll(" {2,}", " "));
                    changed = true;
                }
            }
            JsonObject headerCommand = headerCommand(entries, requestedFile);
            if (headerCommand != null) {
                entries.add(headerCommand);
                changed = true;
            }
            if (!changed) {
                return sourceRoot;
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
            // Match g m exactly: files are siblings when everything before
            // their first dot is identical. This deliberately treats
            // engine.cpp, engine.inline.cpp, and engine.inline.hpp as the
            // same family, rather than maintaining a C++ suffix list here.
            // clangd can then infer the header's flags from that translation
            // unit even though headers do not normally appear in the DB.
            for (var entry : entries) {
                if (!entry.isJsonObject()) continue;
                Path entryFile = entryFile(entry.getAsJsonObject());
                if (isSameBasenameSibling(normalizedFile, entryFile)) return true;
            }
            // Headers such as HotSpot's z_globals.hpp do not necessarily
            // have an implementation with the same name. A non-empty
            // database is still enough context: filteredRoot() writes a
            // workspace-local entry using the closest translation unit's
            // flags before clangd starts.
            if (isHeader(normalizedFile)) {
                return entries.asList().stream().anyMatch(entry -> entry.isJsonObject()
                        && entry.getAsJsonObject().has("file"));
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
        return !requested.equals(command) && siblingPrefix(requested).equals(siblingPrefix(command));
    }

    private static String siblingPrefix(String fileName) {
        int dot = fileName.indexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static JsonObject headerCommand(JsonArray entries, Path requestedFile) {
        if (requestedFile == null) return null;
        Path header = requestedFile.toAbsolutePath().normalize();
        if (!isHeader(header)) return null;
        for (var entry : entries) {
            if (entry.isJsonObject() && header.equals(entryFile(entry.getAsJsonObject()))) {
                return null;
            }
        }
        JsonObject closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (var entry : entries) {
            if (!entry.isJsonObject()) continue;
            JsonObject command = entry.getAsJsonObject();
            Path candidate = entryFile(command);
            if (candidate == null) continue;
            int distance = directoryDistance(header.getParent(), candidate.getParent());
            if (distance < closestDistance) {
                closest = command;
                closestDistance = distance;
            }
        }
        if (closest == null) return null;
        JsonObject copy = closest.deepCopy();
        copy.addProperty("file", header.toString());
        return copy;
    }

    private static boolean isHeader(Path path) {
        Path name = path == null ? null : path.getFileName();
        if (name == null) return false;
        String lower = name.toString().toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".h") || lower.endsWith(".hh") || lower.endsWith(".hpp") || lower.endsWith(".hxx");
    }

    private static int directoryDistance(Path left, Path right) {
        if (left == null || right == null) return Integer.MAX_VALUE - 1;
        int shared = 0;
        int limit = Math.min(left.getNameCount(), right.getNameCount());
        while (shared < limit && left.getName(shared).equals(right.getName(shared))) {
            shared++;
        }
        return left.getNameCount() + right.getNameCount() - 2 * shared;
    }
}
