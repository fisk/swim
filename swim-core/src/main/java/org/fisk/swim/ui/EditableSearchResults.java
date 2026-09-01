package org.fisk.swim.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.fileindex.ProjectSearch;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Buffer;

/**
 * Keeps the text portion of an editable project-search buffer connected to
 * the source lines it represents.  The location prefix is deliberately kept
 * in the buffer: it lets one result buffer safely span many files.
 */
final class EditableSearchResults implements Buffer.ContentChangeListener {
    private record Entry(Path path, int lineNumber, String prefix, String text) {
    }

    private final Window _window;
    private final Map<String, Entry> _entries = new LinkedHashMap<>();
    private final String _query;
    private boolean _applying;

    private EditableSearchResults(Window window, String query, List<ProjectSearch.Match> matches) {
        _window = window;
        _query = query == null ? "" : query;
        for (ProjectSearch.Match match : matches) {
            String prefix = match.relativePath() + ":" + match.lineNumber() + ": ";
            _entries.put(prefix, new Entry(match.path(), match.lineNumber(), prefix, match.lineText()));
        }
    }

    static String text(Window window, String query, List<ProjectSearch.Match> matches) {
        var controller = new EditableSearchResults(window, query, matches);
        return controller.initialText();
    }

    static EditableSearchResults create(Window window, String query, List<ProjectSearch.Match> matches) {
        return new EditableSearchResults(window, query, matches);
    }

    private String initialText() {
        return _entries.values().stream().map(entry -> entry.prefix() + entry.text())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Override
    public void contentChanged(Buffer buffer) {
        if (_applying) {
            return;
        }
        _applying = true;
        try {
            for (String line : buffer.getString().split("\\n", -1)) {
                Entry entry = entryFor(line);
                if (entry == null) {
                    continue;
                }
                String replacement = line.substring(entry.prefix().length());
                if (!replacement.equals(entry.text()) && _window.applyEditableSearchResultChange(entry.path(),
                        entry.lineNumber(), replacement)) {
                    _entries.put(entry.prefix(), new Entry(entry.path(), entry.lineNumber(), entry.prefix(), replacement));
                }
            }
            applyColours(buffer);
        } finally {
            _applying = false;
        }
    }

    private Entry entryFor(String line) {
        for (Entry entry : _entries.values()) {
            if (line.startsWith(entry.prefix())) {
                return entry;
            }
        }
        return null;
    }

    void applyColours(Buffer buffer) {
        var ranges = new java.util.ArrayList<AttributedString.FormatRange>();
        String text = buffer.getString();
        int lineStart = 0;
        for (String line : text.split("\\n", -1)) {
            ranges.add(new AttributedString.FormatRange(lineStart, lineStart + line.length(), UiTheme.TEXT_PRIMARY,
                    UiTheme.SURFACE_ELEVATED));
            Entry entry = entryFor(line);
            if (entry != null) {
                int pathEnd = entry.prefix().indexOf(':');
                int numberStart = pathEnd + 1;
                int numberEnd = entry.prefix().length() - 2;
                ranges.add(new AttributedString.FormatRange(lineStart, lineStart + pathEnd, UiTheme.ACCENT_BLUE,
                        UiTheme.SURFACE_ELEVATED));
                ranges.add(new AttributedString.FormatRange(lineStart + numberStart, lineStart + numberEnd,
                        UiTheme.ACCENT_GOLD, UiTheme.SURFACE_ELEVATED));
                highlightQuery(ranges, line, lineStart, entry.prefix().length());
            }
            lineStart += line.length() + 1;
        }
        buffer.setFormatOverlays(ranges);
    }

    private void highlightQuery(List<AttributedString.FormatRange> ranges, String line, int lineStart, int bodyStart) {
        if (_query.isBlank() || bodyStart > line.length()) return;
        // The prefix is stable; only the editable suffix receives hit colouring.
        String body = line.substring(bodyStart);
        String needle = _query.toLowerCase(java.util.Locale.ROOT);
        String lower = body.toLowerCase(java.util.Locale.ROOT);
        for (int hit = lower.indexOf(needle); hit >= 0; hit = lower.indexOf(needle, hit + needle.length())) {
            ranges.add(new AttributedString.FormatRange(lineStart + bodyStart + hit,
                    lineStart + bodyStart + hit + _query.length(),
                    UiTheme.ACCENT_GREEN, UiTheme.SURFACE_ELEVATED));
        }
    }
}
