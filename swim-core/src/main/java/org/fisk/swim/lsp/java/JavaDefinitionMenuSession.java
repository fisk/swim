package org.fisk.swim.lsp.java;

import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.fisk.swim.text.BufferContext;

public final class JavaDefinitionMenuSession {
    public static final int DEFAULT_VISIBLE_ROWS = 8;

    public record Entry(String label, String detail, Path path, Position position) {
    }

    private final BufferContext _bufferContext;
    private final List<Entry> _entries;
    private final String _title;
    private int _selection;
    private int _scrollOffset;

    public JavaDefinitionMenuSession(BufferContext bufferContext, List<Entry> entries) {
        this(bufferContext, entries, "Definitions");
    }

    public JavaDefinitionMenuSession(BufferContext bufferContext, List<Entry> entries, String title) {
        _bufferContext = bufferContext;
        _entries = List.copyOf(entries);
        _title = title == null || title.isBlank() ? "Definitions" : title;
    }

    public BufferContext getBufferContext() {
        return _bufferContext;
    }

    public String getTitle() {
        return _title;
    }

    public List<Entry> getEntries() {
        return _entries;
    }

    public int size() {
        return _entries.size();
    }

    public boolean isEmpty() {
        return _entries.isEmpty();
    }

    public int getSelection() {
        return _selection;
    }

    public int getScrollOffset() {
        return _scrollOffset;
    }

    public Entry getSelectedEntry() {
        if (_entries.isEmpty()) {
            return null;
        }
        return _entries.get(_selection);
    }

    public void moveSelection(int delta) {
        if (_entries.isEmpty() || delta == 0) {
            return;
        }
        _selection = Math.max(0, Math.min(_entries.size() - 1, _selection + delta));
    }

    public void ensureSelectionVisible(int visibleRows) {
        int clampedRows = Math.max(1, visibleRows);
        if (_selection < _scrollOffset) {
            _scrollOffset = _selection;
        } else if (_selection >= _scrollOffset + clampedRows) {
            _scrollOffset = _selection - clampedRows + 1;
        }
        int maxOffset = Math.max(0, _entries.size() - clampedRows);
        _scrollOffset = Math.max(0, Math.min(maxOffset, _scrollOffset));
    }

    public List<Entry> visibleEntries(int visibleRows) {
        if (_entries.isEmpty()) {
            return List.of();
        }
        int clampedRows = Math.max(1, visibleRows);
        ensureSelectionVisible(clampedRows);
        int end = Math.min(_entries.size(), _scrollOffset + clampedRows);
        return _entries.subList(_scrollOffset, end);
    }
}
