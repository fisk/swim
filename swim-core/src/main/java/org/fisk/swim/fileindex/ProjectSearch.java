package org.fisk.swim.fileindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class ProjectSearch {
    private static final List<String> IGNORED_DIRECTORIES = List.of("target", "build", "out", "node_modules");
    private static final Pattern IGNORED_FILE_PATTERN = Pattern.compile(".*\\.(class|jar)$", Pattern.CASE_INSENSITIVE);

    public record Match(Path path, Path relativePath, int lineNumber, int columnNumber, String lineText) {
        public String displayString() {
            return relativePath + ":" + lineNumber + ":" + columnNumber + "  " + previewText();
        }

        public String previewText() {
            return lineText.replace('\t', ' ').trim();
        }
    }

    private final Path _root;

    public ProjectSearch(Path startPath) {
        _root = ProjectPaths.getSourceRootPath(startPath);
    }

    public boolean isAvailable() {
        return _root != null;
    }

    public Path getRoot() {
        return _root;
    }

    public List<Match> search(String query) {
        if (_root == null || query == null || query.isBlank()) {
            return List.of();
        }

        String needle = query;
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        boolean caseSensitive = !needle.equals(normalizedNeedle);
        var matches = new ArrayList<Match>();
        try {
            var files = Files.find(_root, Integer.MAX_VALUE, (path, attributes) -> attributes.isRegularFile())
                    .filter(path -> isSearchablePath(_root.relativize(path)))
                    .sorted(Comparator.comparing(path -> _root.relativize(path).toString()))
                    .toList();
            for (var path : files) {
                matches.addAll(searchFile(path, needle, normalizedNeedle, caseSensitive));
            }
        } catch (IOException e) {
            return List.of();
        }
        return matches;
    }

    private List<Match> searchFile(Path path, String needle, String normalizedNeedle, boolean caseSensitive) {
        var matches = new ArrayList<Match>();
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return matches;
        }

        Path relativePath = _root.relativize(path);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int start = caseSensitive
                    ? line.indexOf(needle)
                    : line.toLowerCase(Locale.ROOT).indexOf(normalizedNeedle);
            if (start < 0) {
                continue;
            }
            matches.add(new Match(path, relativePath, i + 1, start + 1, line));
        }
        return matches;
    }

    private static boolean isSearchablePath(Path relativePath) {
        for (int i = 0; i < relativePath.getNameCount(); i++) {
            String component = relativePath.getName(i).toString();
            if (component.startsWith(".")) {
                return false;
            }
            if (IGNORED_DIRECTORIES.contains(component)) {
                return false;
            }
        }
        String fileName = relativePath.getFileName() == null ? "" : relativePath.getFileName().toString();
        return !IGNORED_FILE_PATTERN.matcher(fileName).matches();
    }
}
