package org.fisk.swim.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.fisk.swim.text.BufferContext;

public final class LspCompletionSession {
    public static final int DEFAULT_VISIBLE_ROWS = 8;

    public static final class Entry {
        private final CompletionItem _item;
        private final String _label;
        private final String _annotation;
        private final String _source;
        private final String _detail;
        private final String _sortKey;
        private final boolean _preselected;

        private Entry(CompletionItem item) {
            _item = item;
            _label = valueOrEmpty(item.getLabel());
            CompletionItemLabelDetails labelDetails = item.getLabelDetails();
            _annotation = labelDetails == null ? "" : valueOrEmpty(labelDetails.getDetail());
            _source = firstNonBlank(
                    labelDetails == null ? null : labelDetails.getDescription(),
                    item.getDetail());
            _detail = firstNonBlank(
                    item.getDetail(),
                    labelDetails == null ? null : labelDetails.getDescription(),
                    labelDetails == null ? null : labelDetails.getDetail());
            _sortKey = firstNonBlank(item.getSortText(), item.getFilterText(), _label);
            _preselected = Boolean.TRUE.equals(item.getPreselect());
        }

        public CompletionItem getItem() {
            return _item;
        }

        public CompletionItemKind getKind() {
            return _item.getKind();
        }

        public String getLabel() {
            return _label;
        }

        public String getAnnotation() {
            return _annotation;
        }

        public String getSource() {
            return _source;
        }

        public String getDetail() {
            return _detail;
        }

        public String getSortKey() {
            return _sortKey;
        }

        public boolean isPreselected() {
            return _preselected;
        }
    }

    private final BufferContext _bufferContext;
    private final List<Entry> _entries;
    private final String _prefix;
    private final boolean _incomplete;
    private final int _replacementStart;
    private final int _replacementEnd;

    private int _selection;
    private int _scrollOffset;

    private LspCompletionSession(
            BufferContext bufferContext,
            List<Entry> entries,
            String prefix,
            boolean incomplete,
            int replacementStart,
            int replacementEnd,
            int selection) {
        _bufferContext = bufferContext;
        _entries = entries;
        _prefix = prefix;
        _incomplete = incomplete;
        _replacementStart = replacementStart;
        _replacementEnd = replacementEnd;
        _selection = selection;
        _scrollOffset = 0;
        ensureSelectionVisible(DEFAULT_VISIBLE_ROWS);
    }

    public static LspCompletionSession create(
            BufferContext bufferContext,
            String prefix,
            int replacementStart,
            int replacementEnd,
            List<CompletionItem> items,
            boolean incomplete) {
        var entries = new ArrayList<Entry>();
        for (var item : items) {
            if (item != null && item.getLabel() != null && !item.getLabel().isBlank()) {
                entries.add(new Entry(item));
            }
        }
        entries.sort(Comparator
                .comparing(Entry::isPreselected).reversed()
                .thenComparing(Entry::getSortKey, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Entry::getLabel, String.CASE_INSENSITIVE_ORDER));

        int selection = 0;
        for (int i = 0; i < entries.size(); ++i) {
            if (entries.get(i).isPreselected()) {
                selection = i;
                break;
            }
        }

        return new LspCompletionSession(
                bufferContext,
                List.copyOf(entries),
                prefix,
                incomplete,
                replacementStart,
                replacementEnd,
                selection);
    }

    public BufferContext getBufferContext() {
        return _bufferContext;
    }

    public List<Entry> getEntries() {
        return _entries;
    }

    public String getPrefix() {
        return _prefix;
    }

    public boolean isIncomplete() {
        return _incomplete;
    }

    public int getReplacementStart() {
        return _replacementStart;
    }

    public int getReplacementEnd() {
        return _replacementEnd;
    }

    public int getSelection() {
        return _selection;
    }

    public int getScrollOffset() {
        return _scrollOffset;
    }

    public int size() {
        return _entries.size();
    }

    public boolean isEmpty() {
        return _entries.isEmpty();
    }

    public Entry getSelectedEntry() {
        if (_entries.isEmpty()) {
            return null;
        }
        return _entries.get(_selection);
    }

    public void moveSelection(int delta) {
        if (_entries.isEmpty()) {
            return;
        }
        _selection = Math.max(0, Math.min(_selection + delta, _entries.size() - 1));
        ensureSelectionVisible(DEFAULT_VISIBLE_ROWS);
    }

    public void pageSelection(int delta, int visibleRows) {
        if (_entries.isEmpty()) {
            return;
        }
        int pageSize = Math.max(1, visibleRows);
        _selection = Math.max(0, Math.min(_selection + delta * pageSize, _entries.size() - 1));
        ensureSelectionVisible(visibleRows);
    }

    public void ensureSelectionVisible(int visibleRows) {
        int rows = Math.max(1, visibleRows);
        if (_selection < _scrollOffset) {
            _scrollOffset = _selection;
        } else if (_selection >= _scrollOffset + rows) {
            _scrollOffset = _selection - rows + 1;
        }
    }

    public List<Entry> visibleEntries(int visibleRows) {
        int rows = Math.max(1, visibleRows);
        int start = Math.min(_scrollOffset, Math.max(0, _entries.size() - 1));
        int end = Math.min(start + rows, _entries.size());
        return _entries.subList(start, end);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
