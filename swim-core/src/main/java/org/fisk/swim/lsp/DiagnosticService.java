package org.fisk.swim.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
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

    public void applyTextChange(String uri, Path path, Range range, String replacementText) {
        var editRange = TextRange.from(range);
        if (editRange == null) {
            return;
        }
        Path normalizedPath = normalize(path);
        var insertedEnd = insertedEnd(editRange.start(), replacementText == null ? "" : replacementText);
        Map<String, List<DiagnosticEntry>> updates = new HashMap<>();
        synchronized (_lock) {
            for (var stored : _diagnosticsByKey.entrySet()) {
                boolean changed = false;
                var transformed = new ArrayList<DiagnosticEntry>();
                for (var entry : stored.getValue()) {
                    if (!matches(entry, uri, normalizedPath)) {
                        transformed.add(entry);
                        continue;
                    }
                    changed = true;
                    var diagnostic = transformDiagnostic(entry.diagnostic(), editRange, insertedEnd);
                    if (diagnostic != null) {
                        transformed.add(new DiagnosticEntry(entry.providerId(), entry.uri(), entry.path(), diagnostic));
                    }
                }
                if (changed) {
                    transformed.sort(DIAGNOSTIC_ORDER);
                    updates.put(stored.getKey(), List.copyOf(transformed));
                }
            }
            for (var update : updates.entrySet()) {
                if (update.getValue().isEmpty()) {
                    _diagnosticsByKey.remove(update.getKey());
                } else {
                    _diagnosticsByKey.put(update.getKey(), update.getValue());
                }
            }
        }
    }

    public void clear(String providerId, String uri) {
        Path path = pathForUri(uri);
        synchronized (_lock) {
            _diagnosticsByKey.remove(key(providerId, uri));
            if (path != null) {
                _diagnosticsByKey.entrySet().removeIf(entry -> entry.getKey().startsWith(providerId + "\u0000")
                        && entry.getValue().stream().anyMatch(diagnostic -> path.equals(diagnostic.path())));
            }
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
        Path path = normalize(bufferContext.getBuffer().getPath());
        synchronized (_lock) {
            var result = new ArrayList<DiagnosticEntry>();
            for (var entries : _diagnosticsByKey.values()) {
                for (var entry : entries) {
                    if (uri.equals(entry.uri()) || path != null && path.equals(entry.path())) {
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

    private static Path pathForUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            return normalize(Path.of(java.net.URI.create(uri)));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matches(DiagnosticEntry entry, String uri, Path path) {
        return uri != null && !uri.isBlank() && uri.equals(entry.uri())
                || path != null && path.equals(entry.path());
    }

    private static Diagnostic transformDiagnostic(Diagnostic diagnostic, TextRange editRange, TextPosition insertedEnd) {
        var diagnosticRange = TextRange.from(diagnostic.getRange());
        if (diagnosticRange == null) {
            return copyDiagnostic(diagnostic, diagnostic.getRange());
        }
        if (!editRange.isEmpty() && diagnosticRange.intersects(editRange)) {
            return null;
        }
        var start = transformPosition(diagnosticRange.start(), editRange, insertedEnd);
        var end = transformPosition(diagnosticRange.end(), editRange, insertedEnd);
        if (end.compareTo(start) < 0) {
            end = start;
        }
        return copyDiagnostic(diagnostic, new Range(start.toLspPosition(), end.toLspPosition()));
    }

    private static TextPosition transformPosition(TextPosition position, TextRange editRange, TextPosition insertedEnd) {
        if (position.compareTo(editRange.start()) < 0) {
            return position;
        }
        if (position.compareTo(editRange.end()) < 0) {
            return insertedEnd;
        }
        if (position.line() == editRange.end().line()) {
            return new TextPosition(
                    insertedEnd.line(),
                    Math.max(0, insertedEnd.character() + position.character() - editRange.end().character()));
        }
        return new TextPosition(
                Math.max(0, position.line() + insertedEnd.line() - editRange.end().line()),
                position.character());
    }

    private static TextPosition insertedEnd(TextPosition start, String text) {
        int line = start.line();
        int character = start.character();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                character = 0;
            } else {
                character++;
            }
        }
        return new TextPosition(line, character);
    }

    private static Diagnostic copyDiagnostic(Diagnostic source, Range range) {
        var copy = new Diagnostic();
        copy.setRange(range);
        copy.setSeverity(source.getSeverity());
        copy.setCode(source.getCode());
        copy.setCodeDescription(source.getCodeDescription());
        copy.setSource(source.getSource());
        copy.setMessage(source.getMessage());
        copy.setTags(source.getTags());
        copy.setRelatedInformation(source.getRelatedInformation());
        copy.setData(source.getData());
        return copy;
    }

    private record TextRange(TextPosition start, TextPosition end) {
        static TextRange from(Range range) {
            if (range == null || range.getStart() == null || range.getEnd() == null) {
                return null;
            }
            var start = TextPosition.from(range.getStart());
            var end = TextPosition.from(range.getEnd());
            if (end.compareTo(start) < 0) {
                return new TextRange(end, start);
            }
            return new TextRange(start, end);
        }

        boolean isEmpty() {
            return start.compareTo(end) == 0;
        }

        boolean intersects(TextRange other) {
            if (isEmpty()) {
                return other.start().compareTo(start) <= 0 && start.compareTo(other.end()) < 0;
            }
            if (other.isEmpty()) {
                return start.compareTo(other.start()) <= 0 && other.start().compareTo(end) < 0;
            }
            return start.compareTo(other.end()) < 0 && other.start().compareTo(end) < 0;
        }
    }

    private record TextPosition(int line, int character) implements Comparable<TextPosition> {
        static TextPosition from(Position position) {
            return new TextPosition(Math.max(0, position.getLine()), Math.max(0, position.getCharacter()));
        }

        Position toLspPosition() {
            return new Position(line, character);
        }

        @Override
        public int compareTo(TextPosition other) {
            int lineCompare = Integer.compare(line, other.line);
            if (lineCompare != 0) {
                return lineCompare;
            }
            return Integer.compare(character, other.character);
        }
    }
}
