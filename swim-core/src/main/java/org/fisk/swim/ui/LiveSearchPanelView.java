package org.fisk.swim.ui;

import java.util.List;
import java.util.function.BiConsumer;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalCursorShape;
import org.fisk.swim.text.AttributedString;

import org.fisk.swim.terminal.TextColor;
import org.fisk.swim.event.KeyType;

/** Generic bottom-panel search with live, asynchronously supplied results. */
public class LiveSearchPanelView extends View implements KeyBindingHintProvider {
    public record Entry(String kind, String label, String detail, TextColor accent, Runnable action) { }

    private final String _title;
    private final String _emptyHint;
    private final StringBuilder _query = new StringBuilder();
    private final ListEventResponder _responders = new ListEventResponder();
    private final Cursor _cursor = new Cursor(null) {
        @Override public int getXOnScreen() { return cursorScreenPosition().getX(); }
        @Override public int getYOnScreen() { return cursorScreenPosition().getY(); }
        @Override public TerminalCursorShape getShape() { return TerminalCursorShape.BAR; }
    };
    private BiConsumer<String, Long> _onQueryChanged;
    private List<Entry> _results = List.of();
    private long _generation;
    private boolean _searching;
    private int _selection;
    private int _start;

    public LiveSearchPanelView(Rect bounds, String title, String emptyHint) {
        super(bounds);
        _title = title;
        _emptyHint = emptyHint;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        _responders.addEventResponder("<DOWN>", "Results", "move down", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", "Results", "move up", () -> moveSelection(-1));
        _responders.addEventResponder("<ESC>", "Search", "close", this::close);
        _responders.addEventResponder("<ENTER>", "Results", "open selected", this::openSelection);
        _responders.addEventResponder("<BACKSPACE>", "Search", "delete character", () -> {
            if (!_query.isEmpty()) { _query.deleteCharAt(_query.length() - 1); refreshResults(); }
        });
        _responders.addKeyBindingHint("<CHAR>", "Search", "type to search");
        _responders.addEventResponder(new EventResponder() {
            private char _character;
            @Override public Response processEvent(KeyStrokes events) {
                if (events.remaining() != 0 || events.current().getKeyType() != KeyType.Character) return Response.NO;
                _character = events.current().getCharacter(); return Response.YES;
            }
            @Override public void respond() { _query.append(_character); refreshResults(); }
        });
    }

    public String getTitle() { return _title; }
    public void setOnQueryChanged(BiConsumer<String, Long> callback) { _onQueryChanged = callback; }
    public void setResults(long generation, List<Entry> results) {
        if (generation != _generation) return;
        _searching = false;
        _results = results == null ? List.of() : List.copyOf(results);
        _selection = Math.min(_selection, Math.max(0, _results.size() - 1));
        setNeedsRedraw();
    }
    @Override public String keyHintContext() { return _title.toLowerCase(); }
    @Override public List<KeyBindingHint> keyBindingHints() { return _responders.keyBindingHints(); }
    @Override public Cursor getCursor() { return _cursor; }
    @Override public Response processEvent(KeyStrokes events) { return _responders.processEvent(events); }
    @Override public void respond() { _responders.respond(); }

    @Override public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getTerminalGraphics(); int width = rect.getSize().getWidth();
        var header = new AttributedString();
        header.append(" " + _title.toLowerCase() + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + _results.size() + (_searching ? "+ results " : " results "), UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        var queryLine = new AttributedString(); queryLine.append(" query ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        queryLine.append(_query.isEmpty() ? " " + _emptyHint : " " + _query,
                _query.isEmpty() ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width, queryLine,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        int height = Math.max(0, rect.getSize().getHeight() - 2); clampSelection(height);
        for (int row = 0; row < height; row++) drawRow(graphics, width, row, _start + row);
        if (_query.isEmpty() && height > 0) drawMessage(graphics, width, "  " + _emptyHint);
        else if (!_query.isEmpty() && !_searching && _results.isEmpty() && height > 0)
            drawMessage(graphics, width, "  no results for current query");
    }

    private void drawRow(org.fisk.swim.terminal.TerminalGraphics graphics, int width, int row, int index) {
        int y = getBounds().getPoint().getY() + 2 + row; boolean selected = index == _selection && index < _results.size();
        TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
        UiTheme.fillRow(graphics, Point.create(getBounds().getPoint().getX(), y), width, background);
        if (index >= _results.size()) return;
        Entry entry = _results.get(index); TextColor primary = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
        TextColor muted = selected ? UiTheme.TEXT_ON_ACCENT : UiTheme.TEXT_MUTED; var line = new AttributedString();
        line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE, background);
        line.append(entry.kind() + " ", selected ? UiTheme.PANEL_SELECTION_ACCENT : entry.accent(), background);
        line.append(entry.label(), primary, background);
        if (entry.detail() != null && !entry.detail().isBlank()) line.append("  " + entry.detail(), muted, background);
        if (line.length() < width) line.append(" ".repeat(width - line.length()), muted, background);
        UiTheme.drawLine(graphics, Point.create(getBounds().getPoint().getX(), y), width,
                line.length() > width ? line.slice(0, width) : line, muted, background);
    }

    private void drawMessage(org.fisk.swim.terminal.TerminalGraphics graphics, int width, String text) {
        UiTheme.drawLine(graphics, Point.create(getBounds().getPoint().getX(), getBounds().getPoint().getY() + 2), width,
                AttributedString.create(text, UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND), UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND);
    }
    private void refreshResults() { _generation++; _results = List.of(); _selection = _start = 0; _searching = !_query.isEmpty(); setNeedsRedraw(); if (_onQueryChanged != null) _onQueryChanged.accept(_query.toString(), _generation); }
    private void moveSelection(int delta) { if (!_results.isEmpty()) { _selection = Math.max(0, Math.min(_selection + delta, _results.size() - 1)); setNeedsRedraw(); } }
    private void clampSelection(int height) { if (_results.isEmpty()) { _selection = _start = 0; return; } _selection = Math.max(0, Math.min(_selection, _results.size() - 1)); if (_selection >= _start + height) _start = _selection - height + 1; else if (_selection < _start) _start = _selection; _start = Math.max(0, Math.min(_start, Math.max(0, _results.size() - Math.max(1, height)))); }
    private void openSelection() { if (_selection >= 0 && _selection < _results.size() && _results.get(_selection).action() != null) _results.get(_selection).action().run(); close(); }
    private void close() { _generation++; Window window = Window.getInstance(); if (window != null) window.hidePanel(); }
    private Point cursorScreenPosition() { int x = getBounds().getPoint().getX(), y = getBounds().getPoint().getY(); for (View parent = getParent(); parent != null; parent = parent.getParent()) { x += parent.getBounds().getPoint().getX(); y += parent.getBounds().getPoint().getY(); } return Point.create(x + Math.min(Math.max(0, getBounds().getSize().getWidth() - 1), 8 + _query.length()), y + 1); }
}
