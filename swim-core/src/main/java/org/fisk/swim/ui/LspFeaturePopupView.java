package org.fisk.swim.ui;

import java.util.List;

import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TextColor;

public class LspFeaturePopupView extends View implements KeyBindingHintProvider {
    public record Entry(String kind, String label, String detail, TextColor accent, Runnable action) {
        public Entry {
            kind = kind == null || kind.isBlank() ? "LSP" : kind.strip();
            label = label == null ? "" : label;
            detail = detail == null ? "" : detail;
            accent = accent == null ? UiTheme.ACCENT_BLUE : accent;
        }

        public boolean actionable() {
            return action != null;
        }
    }

    private static final int MIN_WIDTH = 34;
    private static final int MAX_WIDTH = 112;
    private static final int MAX_VISIBLE_ROWS = 10;

    private final ListEventResponder _responders = new ListEventResponder();
    private List<Entry> _entries = List.of();
    private String _title = "LSP";
    private int _selection;
    private int _scrollOffset;
    private Point _anchor = Point.create(0, 0);
    private Runnable _onClose = () -> {
    };

    public LspFeaturePopupView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
        _responders.addEventResponder("j", "LSP", "move down", () -> moveSelection(1));
        _responders.addEventResponder("k", "LSP", "move up", () -> moveSelection(-1));
        _responders.addEventResponder("<DOWN>", "LSP", "move down", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", "LSP", "move up", () -> moveSelection(-1));
        _responders.addEventResponder("<ENTER>", "LSP", "open/apply", this::acceptSelection);
        _responders.addEventResponder("q", "LSP", "close", this::close);
        _responders.addEventResponder("<ESC>", "LSP", "close", this::close);
    }

    public void configure(String title, List<Entry> entries, Point anchor) {
        _title = title == null || title.isBlank() ? "LSP" : title;
        _entries = entries == null ? List.of() : List.copyOf(entries);
        _anchor = anchor == null ? Point.create(0, 0) : anchor;
        _selection = Math.max(0, Math.min(_selection, Math.max(0, _entries.size() - 1)));
        _scrollOffset = 0;
        syncBounds();
        setNeedsRedraw();
    }

    public List<Entry> getEntries() {
        return _entries;
    }

    public String getTitle() {
        return _title;
    }

    public void setOnClose(Runnable onClose) {
        _onClose = onClose == null ? () -> {
        } : onClose;
    }

    @Override
    public String keyHintContext() {
        return "lsp";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _entries.isEmpty() ? List.of() : _responders.keyBindingHints();
    }

    public void syncBounds() {
        Size parentSize = getParent() == null ? getBounds().getSize() : getParent().getBounds().getSize();
        setBounds(calculateBounds(parentSize));
    }

    @Override
    public void resize(Size newParentSize) {
        setBounds(calculateBounds(newParentSize));
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (_entries.isEmpty()) {
            return Response.NO;
        }
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    @Override
    public void draw(Rect rect) {
        if (_entries.isEmpty()) {
            return;
        }
        syncBounds();
        rect = getBounds();
        super.draw(rect);

        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY();

        int visibleRows = Math.min(MAX_VISIBLE_ROWS, _entries.size());
        ensureSelectionVisible(visibleRows);

        var header = new AttributedString();
        header.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + (_selection + 1) + "/" + _entries.size() + " ", UiTheme.ACCENT_BLUE,
                UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, Point.create(x, y), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        int rowY = y + 1;
        for (int i = 0; i < visibleRows; ++i) {
            int index = _scrollOffset + i;
            if (index >= _entries.size()) {
                break;
            }
            boolean selected = index == _selection;
            Entry entry = _entries.get(index);
            var background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : (i % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED);
            UiTheme.fillRow(graphics, Point.create(x, rowY + i), width, background);

            var line = new AttributedString();
            line.append(selected ? "\u258c " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE,
                    background);
            String kind = UiTheme.fit(entry.kind(), 8);
            line.append(" " + kind + " ", UiTheme.TEXT_ON_ACCENT, entry.accent());
            int labelWidth = Math.max(0, width - line.length() - 1);
            line.append(" " + UiTheme.fit(entry.label(), labelWidth),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(x, rowY + i), width, line, UiTheme.TEXT_MUTED, background);
        }

        var footer = new AttributedString();
        Entry selected = selectedEntry();
        footer.append(selected != null && selected.actionable() ? " enter " : " detail ",
                UiTheme.TEXT_ON_ACCENT,
                selected != null && selected.actionable() ? UiTheme.ACCENT_GREEN : UiTheme.ACCENT_GOLD);
        footer.append(selected == null ? "" : " " + UiTheme.fit(selected.detail(), Math.max(0, width - 9)),
                UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(x, y + rect.getSize().getHeight() - 1), width, footer,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
    }

    private void moveSelection(int delta) {
        allowEditorDriveAction("lsp popup selection");
        if (_entries.isEmpty()) {
            return;
        }
        _selection = Math.max(0, Math.min(_entries.size() - 1, _selection + delta));
        setNeedsRedraw();
    }

    private void acceptSelection() {
        allowEditorDriveAction("lsp popup action");
        Entry selected = selectedEntry();
        if (selected != null && selected.actionable()) {
            selected.action().run();
            close();
        }
    }

    private Entry selectedEntry() {
        if (_entries.isEmpty() || _selection < 0 || _selection >= _entries.size()) {
            return null;
        }
        return _entries.get(_selection);
    }

    private void close() {
        allowEditorDriveAction("close lsp popup");
        _onClose.run();
    }

    private static void allowEditorDriveAction(String action) {
        if (Window.getInstance() != null) {
            Window.getInstance().allowEditorDriveAction(action);
        }
    }

    private void ensureSelectionVisible(int visibleRows) {
        if (_selection < _scrollOffset) {
            _scrollOffset = _selection;
        } else if (_selection >= _scrollOffset + visibleRows) {
            _scrollOffset = _selection - visibleRows + 1;
        }
    }

    private Rect calculateBounds(Size parentSize) {
        if (_entries.isEmpty()) {
            return Rect.create(0, 0, 0, 0);
        }
        int width = Math.min(parentSize.getWidth(), preferredWidth());
        int height = Math.min(parentSize.getHeight(), Math.min(MAX_VISIBLE_ROWS, _entries.size()) + 2);
        int x = Math.max(0, Math.min(_anchor.getX(), parentSize.getWidth() - width));
        int belowY = _anchor.getY() + 1;
        int y = belowY + height <= parentSize.getHeight()
                ? belowY
                : Math.max(0, _anchor.getY() - height + 1);
        return Rect.create(x, y, width, height);
    }

    private int preferredWidth() {
        int width = MIN_WIDTH;
        for (var entry : _entries) {
            width = Math.max(width, 14 + entry.kind().length() + entry.label().length());
            width = Math.max(width, 10 + entry.detail().length());
        }
        return Math.min(MAX_WIDTH, width);
    }
}
