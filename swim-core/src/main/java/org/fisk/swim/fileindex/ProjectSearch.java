package org.fisk.swim.fileindex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.Phaser;
import java.util.stream.Stream;

public final class ProjectSearch {
    public record Match(Path path, Path relativePath, int lineNumber, int columnNumber, String lineText) {
        public String displayString() {
            return relativePath + ":" + lineNumber + ":" + columnNumber + "  " + previewText();
        }

        public String previewText() {
            return lineText.replace('\t', ' ').trim();
        }
    }

    private final Path _root;
    private final ProjectFileFilter _fileFilter;

    public ProjectSearch(Path startPath) {
        _root = ProjectPaths.getSourceRootPath(startPath);
        _fileFilter = ProjectFileFilter.load(_root);
    }

    public boolean isAvailable() {
        return _root != null;
    }

    public Path getRoot() {
        return _root;
    }

    public List<Match> search(String query) {
        var matches = new ArrayList<Match>();
        search(query, batch -> matches.addAll(batch), () -> false);
        matches.sort(Comparator.comparing(match -> match.relativePath().toString()));
        return matches;
    }

    /** Searches files as they are discovered, without waiting to enumerate the whole project. */
    public void search(String query, Consumer<List<Match>> onMatches, BooleanSupplier cancelled) {
        if (_root == null || query == null || query.isBlank()) {
            return;
        }

        String needle = query;
        String normalizedNeedle = needle.toLowerCase(Locale.ROOT);
        boolean caseSensitive = !needle.equals(normalizedNeedle);
        Phaser tasks = new Phaser(1);
        try (Stream<Path> files = Files.find(_root, Integer.MAX_VALUE, (path, attributes) -> attributes.isRegularFile())) {
            files
                    .filter(path -> _fileFilter.isIncluded(_root.relativize(path), false))
                    .takeWhile(path -> !cancelled.getAsBoolean())
                    .forEach(path -> {
                        tasks.register();
                        Thread.ofVirtual().start(() -> {
                            try {
                                var matches = searchFile(path, needle, normalizedNeedle, caseSensitive);
                                if (!matches.isEmpty() && !cancelled.getAsBoolean()) onMatches.accept(matches);
                            } finally {
                                tasks.arriveAndDeregister();
                            }
                        });
                    });
        } catch (IOException e) {
        } finally {
            tasks.arriveAndAwaitAdvance();
        }
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

}
