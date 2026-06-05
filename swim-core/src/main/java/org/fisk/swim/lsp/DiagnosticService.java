package org.fisk.swim.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.text.BufferContext;

public final class DiagnosticService {
    private static final DiagnosticService INSTANCE = new DiagnosticService();

    private static final Comparator<DiagnosticEntry> DIAGNOSTIC_ORDER = Comparator
            .comparing((DiagnosticEntry entry) -> entry.path() == null ? "" : entry.path().toString())
            .thenComparingInt(DiagnosticEntry::startLine)
            .thenComparingInt(DiagnosticEntry::startCharacter)
            .thenComparingInt(DiagnosticEntry::endLine)
            .thenComparingInt(DiagnosticEntry::endCharacter)
            .thenComparing(entry -> entry.message().toLowerCase(java.util.Locale.ROOT));

    private final Object _lock = new Object();
    private final Map<String, List<DiagnosticEntry>> _diagnosticsByKey = new HashMap<>();

    private DiagnosticService() {
    }

    public static DiagnosticService getInstance() {
        return INSTANCE;
    }

    public void publish(String providerId, String uri, Path path, List<Diagnostic> diagnostics) {
        String key = key(providerId, uri);
        List<DiagnosticEntry> entries = new ArrayList<>();
        if (diagnostics != null) {
            for (var diagnostic : diagnostics) {
                entries.add(new DiagnosticEntry(providerId, uri, path, diagnostic));
            }
        }
        entries.sort(DIAGNOSTIC_ORDER);
        synchronized (_lock) {
            if (entries.isEmpty()) {
                _diagnosticsByKey.remove(key);
            } else {
                _diagnosticsByKey.put(key, List.copyOf(entries));
            }
        }
    }

    public void clear(String providerId, String uri) {
        synchronized (_lock) {
            _diagnosticsByKey.remove(key(providerId, uri));
        }
    }

    public void clearProvider(String providerId) {
        synchronized (_lock) {
            _diagnosticsByKey.entrySet().removeIf(entry -> entry.getKey().startsWith(providerId + "\u0000"));
        }
    }

    public List<DiagnosticEntry> diagnosticsFor(BufferContext bufferContext) {
        if (bufferContext == null) {
            return List.of();
        }
        String uri = bufferContext.getBuffer().getURI().toString();
        synchronized (_lock) {
            var result = new ArrayList<DiagnosticEntry>();
            for (var entries : _diagnosticsByKey.values()) {
                for (var entry : entries) {
                    if (uri.equals(entry.uri())) {
                        result.add(entry);
                    }
                }
            }
            result.sort(DIAGNOSTIC_ORDER);
            return List.copyOf(result);
        }
    }

    public List<DiagnosticEntry> diagnosticsForLine(BufferContext bufferContext, int logicalLine) {
        var result = new ArrayList<DiagnosticEntry>();
        for (var entry : diagnosticsFor(bufferContext)) {
            if (entry.coversLogicalLine(logicalLine)) {
                result.add(entry);
            }
        }
        return List.copyOf(result);
    }

    public DiagnosticSeverity lineSeverity(BufferContext bufferContext, int logicalLine) {
        DiagnosticSeverity severity = null;
        for (var entry : diagnosticsForLine(bufferContext, logicalLine)) {
            if (!entry.isCounted()) {
                continue;
            }
            if (entry.isError()) {
                return DiagnosticSeverity.Error;
            }
            severity = DiagnosticSeverity.Warning;
        }
        return severity;
    }

    public DiagnosticCounts countsForBuffer(Path path) {
        Path normalized = normalize(path);
        if (normalized == null) {
            return DiagnosticCounts.EMPTY;
        }
        int errors = 0;
        int warnings = 0;
        synchronized (_lock) {
            for (var entries : _diagnosticsByKey.values()) {
                for (var entry : entries) {
                    if (!normalized.equals(entry.path()) || !entry.isCounted()) {
                        continue;
                    }
                    if (entry.isError()) {
                        errors++;
                    } else if (entry.isWarning()) {
                        warnings++;
                    }
                }
            }
        }
        return new DiagnosticCounts(errors, warnings);
    }

    public DiagnosticCounts countsForProject(Path path) {
        List<DiagnosticEntry> entries = projectDiagnostics(path);
        if (entries.isEmpty()) {
            return DiagnosticCounts.EMPTY;
        }
        int errors = 0;
        int warnings = 0;
        for (var entry : entries) {
            if (!entry.isCounted()) {
                continue;
            }
            if (entry.isError()) {
                errors++;
            } else if (entry.isWarning()) {
                warnings++;
            }
        }
        return new DiagnosticCounts(errors, warnings);
    }

    public List<DiagnosticEntry> projectDiagnostics(Path currentPath) {
        Path normalized = normalize(currentPath);
        if (normalized == null) {
            return List.of();
        }
        Path projectRoot = ProjectPaths.getProjectRootPath(normalized);
        synchronized (_lock) {
            var result = new ArrayList<DiagnosticEntry>();
            for (var entries : _diagnosticsByKey.values()) {
                for (var entry : entries) {
                    if (entry.path() == null) {
                        continue;
                    }
                    if (projectRoot != null) {
                        if (entry.path().startsWith(projectRoot)) {
                            result.add(entry);
                        }
                    } else if (normalized.equals(entry.path())) {
                        result.add(entry);
                    }
                }
            }
            result.sort(DIAGNOSTIC_ORDER);
            return List.copyOf(result);
        }
    }

    public DiagnosticEntry findNext(Path currentPath, int currentLine, int currentColumn, boolean forward, boolean errorsOnly) {
        Path normalized = normalize(currentPath);
        if (normalized == null) {
            return null;
        }
        var entries = projectDiagnostics(normalized);
        if (entries.isEmpty()) {
            return null;
        }
        var filtered = entries.stream()
                .filter(entry -> !errorsOnly || entry.isError())
                .filter(entry -> !errorsOnly || entry.isCounted())
                .toList();
        if (filtered.isEmpty()) {
            return null;
        }
        int currentIndex = insertionIndex(filtered, normalized, currentLine, currentColumn);
        if (forward) {
            return filtered.get(currentIndex >= filtered.size() ? 0 : currentIndex);
        }
        int reverseIndex = currentIndex - 1;
        if (reverseIndex < 0) {
            reverseIndex = filtered.size() - 1;
        }
        return filtered.get(reverseIndex);
    }

    private static int insertionIndex(List<DiagnosticEntry> entries, Path currentPath, int currentLine, int currentColumn) {
        for (int i = 0; i < entries.size(); ++i) {
            if (compare(entries.get(i), currentPath, currentLine, currentColumn) > 0) {
                return i;
            }
        }
        return entries.size();
    }

    private static int compare(DiagnosticEntry entry, Path currentPath, int currentLine, int currentColumn) {
        String entryPath = entry.path() == null ? "" : entry.path().toString();
        String current = currentPath == null ? "" : currentPath.toString();
        int pathCompare = entryPath.compareTo(current);
        if (pathCompare != 0) {
            return pathCompare;
        }
        int lineCompare = Integer.compare(entry.startLine(), currentLine);
        if (lineCompare != 0) {
            return lineCompare;
        }
        return Integer.compare(entry.startCharacter(), currentColumn);
    }

    private static String key(String providerId, String uri) {
        return providerId + "\u0000" + uri;
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
