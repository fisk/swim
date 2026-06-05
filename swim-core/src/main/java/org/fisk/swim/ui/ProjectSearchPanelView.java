package org.fisk.swim.ui;

import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.fileindex.ProjectSearch;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class ProjectSearchPanelView extends View {
    private final ProjectSearch _projectSearch;
    private final StringBuilder _query = new StringBuilder();
    private final ListEventResponder _responders = new ListEventResponder();
    private List<ProjectSearch.Match> _results = List.of();
    private int _selection;
    private int _start;

    public static ProjectSearchPanelView create(Rect bounds, Path startPath) {
        var search = new ProjectSearch(startPath);
        if (!search.isAvailable()) {
            return null;
        }
        return new ProjectSearchPanelView(bounds, search);
    }

    public ProjectSearchPanelView(Rect bounds, ProjectSearch projectSearch) {
        super(bounds);
        _projectSearch = projectSearch;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        _responders.addEventResponder("<DOWN>", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", () -> moveSelection(-1));
        _responders.addEventResponder("<ESC>", this::close);
        _responders.addEventResponder("<ENTER>", this::openSelection);
        _responders.addEventResponder("<BACKSPACE>", () -> {
            if (_query.length() == 0) {
                return;
            }
            _query.delete(_query.length() - 1, _query.length());
            refreshResults();
        });
        _responders.addEventResponder(new EventResponder() {
            private char _character;

            @Override
            public Response processEvent(KeyStrokes events) {
                if (events.remaining() != 0) {
                    return Response.NO;
                }
                var event = events.current();
                if (event.getKeyType() == KeyType.Character) {
                    _character = event.getCharacter();
                    return Response.YES;
                }
                return Response.NO;
            }

            @Override
            public void respond() {
                _query.append(_character);
                refreshResults();
            }
        });
    }

    String getTitle() {
        return "Project Search";
    }

    String getQuery() {
        return _query.toString();
    }

    List<ProjectSearch.Match> getResults() {
        return _results;
    }

    public void setQuery(String query) {
        _query.setLength(0);
        if (query != null && !query.isBlank()) {
            _query.append(query);
        }
        refreshResults();
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();

        var header = new AttributedString();
        header.append(" project search ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + _results.size() + " matches ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        header.append(" enter open  esc close ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var queryLine = new AttributedString();
        queryLine.append(" query ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        queryLine.append(_query.length() == 0 ? " type to search project text" : " " + _query,
                _query.length() == 0 ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY,
                UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width, queryLine,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);

        int listHeight = Math.max(0, rect.getSize().getHeight() - 2);
        clampSelection(listHeight);
        for (int row = 0; row < listHeight; row++) {
            int index = _start + row;
            int y = rect.getPoint().getY() + 2 + row;
            boolean selected = index == _selection && index < _results.size();
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.fillRow(graphics, Point.create(rect.getPoint().getX(), y), width, background);
            if (index >= _results.size()) {
                continue;
            }
            var line = new AttributedString();
            var match = _results.get(index);
            line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE,
                    background);
            line.append(match.displayString(),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), y), width, line, UiTheme.TEXT_MUTED,
                    background);
        }

        if (_query.length() == 0 && listHeight > 0) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 2), width,
                    AttributedString.create("  search across visible project files", UiTheme.TEXT_MUTED,
                            UiTheme.SURFACE_BACKGROUND),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND);
        } else if (_query.length() > 0 && _results.isEmpty() && listHeight > 0) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 2), width,
                    AttributedString.create("  no matches for current query", UiTheme.TEXT_MUTED,
                            UiTheme.SURFACE_BACKGROUND),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND);
        }
    }

    private void refreshResults() {
        _results = _projectSearch.search(_query.toString());
        var window = Window.getInstance();
        if (window != null) {
            window.setQuickfixResults("Quickfix", _results);
        }
        _selection = Math.max(0, Math.min(_selection, Math.max(0, _results.size() - 1)));
        _start = Math.max(0, Math.min(_start, Math.max(0, _results.size() - 1)));
        setNeedsRedraw();
    }

    private void moveSelection(int delta) {
        if (_results.isEmpty()) {
            return;
        }
        _selection = Math.max(0, Math.min(_selection + delta, _results.size() - 1));
        setNeedsRedraw();
    }

    private void clampSelection(int listHeight) {
        if (_results.isEmpty()) {
            _selection = 0;
            _start = 0;
            return;
        }
        _selection = Math.max(0, Math.min(_selection, _results.size() - 1));
        if (_selection >= _start + listHeight) {
            _start = _selection - listHeight + 1;
        } else if (_selection < _start) {
            _start = _selection;
        }
        _start = Math.max(0, Math.min(_start, Math.max(0, _results.size() - Math.max(1, listHeight))));
    }

    private void openSelection() {
        if (_selection < 0 || _selection >= _results.size()) {
            return;
        }
        var match = _results.get(_selection);
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        if (!window.openBufferLocation(match.path(), match.lineNumber(), match.columnNumber())) {
            window.getCommandView().setMessage("Failed to open search result");
            return;
        }
        close();
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            window.hidePanel();
        }
    }
}
