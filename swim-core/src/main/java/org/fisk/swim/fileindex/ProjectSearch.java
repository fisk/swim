package org.fisk.swim.fileindex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Phaser;
import java.util.stream.Stream;

public final class ProjectSearch {
    /** Bounds file buffers and virtual-thread objects on very large trees. */
    static final int MAX_IN_FLIGHT_FILES = 16;
    /** Avoid retaining pathological minified/generated lines in result previews. */
    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    /** A result list must remain inexpensive to render, sort, and navigate. */
    public static final int MAX_MATCHES = 10_000;
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
        search(query, batch -> {
            synchronized (matches) {
                matches.addAll(batch);
            }
        }, () -> false);
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
        var matchBudget = new AtomicInteger(MAX_MATCHES);
        var filePermits = new Semaphore(MAX_IN_FLIGHT_FILES);
        Phaser tasks = new Phaser(1);
        try (Stream<Path> files = Files.find(_root, Integer.MAX_VALUE, (path, attributes) -> attributes.isRegularFile())) {
            files
                    .filter(path -> _fileFilter.isIncluded(_root.relativize(path), false))
                    .takeWhile(path -> !cancelled.getAsBoolean() && matchBudget.get() > 0)
                    .forEach(path -> {
                        if (!acquireFilePermit(filePermits, cancelled)) return;
                        tasks.register();
                        Thread.ofVirtual().start(() -> {
                            try {
                                searchFile(path, needle, normalizedNeedle, caseSensitive, matchBudget, cancelled,
                                        matches -> { if (!matches.isEmpty()) onMatches.accept(matches); });
                            } finally {
                                filePermits.release();
                                tasks.arriveAndDeregister();
                            }
                        });
                    });
        } catch (IOException | UncheckedIOException e) {
            // A project can disappear while an asynchronous panel search is
            // walking it (notably while a workspace is closing). Treat that
            // as a cancelled/incomplete search rather than leaking a worker.
        } finally {
            tasks.arriveAndAwaitAdvance();
        }
    }

    private void searchFile(Path path, String needle, String normalizedNeedle, boolean caseSensitive,
            AtomicInteger matchBudget, BooleanSupplier cancelled, Consumer<List<Match>> onMatches) {
        try {
            if (Files.size(path) > MAX_FILE_BYTES) return;
        } catch (IOException e) {
            return;
        }
        Path relativePath = _root.relativize(path);
        var batch = new ArrayList<Match>(64);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (int lineNumber = 1; !cancelled.getAsBoolean() && matchBudget.get() > 0; lineNumber++) {
                String line = reader.readLine();
                if (line == null) break;
                int start = caseSensitive ? line.indexOf(needle) : line.toLowerCase(Locale.ROOT).indexOf(normalizedNeedle);
                if (start < 0 || !reserveMatch(matchBudget)) continue;
                batch.add(new Match(path, relativePath, lineNumber, start + 1, line));
                if (batch.size() == 64) {
                    onMatches.accept(List.copyOf(batch));
                    batch.clear();
                }
            }
        } catch (IOException e) {
            return;
        }
        if (!batch.isEmpty() && !cancelled.getAsBoolean()) onMatches.accept(List.copyOf(batch));
    }

    private static boolean reserveMatch(AtomicInteger budget) {
        for (int remaining; (remaining = budget.get()) > 0;) {
            if (budget.compareAndSet(remaining, remaining - 1)) return true;
        }
        return false;
    }

    private static boolean acquireFilePermit(Semaphore permits, BooleanSupplier cancelled) {
        while (!cancelled.getAsBoolean()) {
            try {
                if (permits.tryAcquire(25, java.util.concurrent.TimeUnit.MILLISECONDS)) return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

}
