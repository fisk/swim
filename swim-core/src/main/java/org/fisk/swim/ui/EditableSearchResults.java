package org.fisk.swim.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.fileindex.ProjectSearch;
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
    private boolean _applying;

    private EditableSearchResults(Window window, List<ProjectSearch.Match> matches) {
        _window = window;
        for (ProjectSearch.Match match : matches) {
            String prefix = match.relativePath() + ":" + match.lineNumber() + ": ";
            _entries.put(prefix, new Entry(match.path(), match.lineNumber(), prefix, match.lineText()));
        }
    }

    static String text(Window window, List<ProjectSearch.Match> matches) {
        var controller = new EditableSearchResults(window, matches);
        return controller.initialText();
    }

    static EditableSearchResults create(Window window, List<ProjectSearch.Match> matches) {
        return new EditableSearchResults(window, matches);
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
}
