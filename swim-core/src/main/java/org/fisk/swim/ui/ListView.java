package org.fisk.swim.ui;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class ListView extends View {
    public static abstract class ListItem {
        public abstract void onClick();
        public abstract String displayString();
    }

    private List<? extends ListItem> _list;
    private List<? extends ListItem> _filteredList;
    private String _title;
    private int _selection;
    private int _start;
    private StringBuilder _filter = new StringBuilder();
    protected ListEventResponder _responders = new ListEventResponder();

    private void filterList() {
        if (_filter.length() == 0) {
            _filteredList = _list;
            if (_selection >= _filteredList.size()) {
                _selection = Math.max(0, _filteredList.size() - 1);
            }
            setNeedsRedraw();
            return;
        }
        var filters = _filter.toString().split(" ");
        _filteredList = _filter.length() == 0 ? _list : _list.stream().filter((item) -> {
            for (var filter: filters) {
                if (!Pattern.matches("(?i:.*" + Pattern.quote(filter) + ".*)", item.displayString())) {
                    return false;
                }
            }
            return true;
        }).collect(Collectors.toList());
        if (_selection >= _filteredList.size()) {
            _selection = Math.max(0, _filteredList.size() - 1);
        }
        setNeedsRedraw();
    }

    public ListView(Rect bounds, List<? extends ListItem> list, String title) {
        super(bounds);
        _list = list;
        _filteredList = _list;
        _title = title;
        _selection = 0;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        _responders.addEventResponder("<DOWN>", () -> {
            if (_selection >= _filteredList.size() - 1) {
                return;
            }
            ++_selection;
            ListView.this.setNeedsRedraw();
        });
        _responders.addEventResponder("<UP>", () -> {
            if (_selection <= 0) {
                return;
            }
            --_selection;
            ListView.this.setNeedsRedraw();
        });
        _responders.addEventResponder("<ESC>", () -> {
            ListView.this.getParent().setNeedsRedraw();
            Window.getInstance().hidePanel();
        });
        _responders.addEventResponder("<ENTER>", () -> {
            if (_selection >= _filteredList.size()) {
                return;
            }
            var item = _filteredList.get(_selection);
            ListView.this.getParent().setNeedsRedraw();
            item.onClick();
            Window.getInstance().hidePanel();
        });
        _responders.addEventResponder("<BACKSPACE>", () -> {
            if (_filter.length() > 0) {
                _filter.delete(_filter.length() - 1, _filter.length());
                filterList();
            }
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
                _filter.append(_character);
                filterList();
            }
        });
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    String getTitle() {
        return _title;
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var graphics = terminalContext.getGraphics();
        int width = rect.getSize().getWidth();

        var title = new AttributedString();
        title.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        title.append(" " + _filteredList.size() + "/" + _list.size() + " ", UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        title.append(" enter open  esc close ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, title, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var search = new AttributedString();
        search.append(" filter ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GOLD);
        search.append(_filter.length() == 0 ? " type to narrow results" : " " + _filter, UiTheme.TEXT_PRIMARY,
                UiTheme.SURFACE_MUTED);
        UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width, search,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);

        int totalHeight = rect.getSize().getHeight();
        int listHeight = totalHeight - 2;

        if (_selection >= _filteredList.size()) {
            _selection = _filteredList.size() - 1;
        }
        if (_selection < 0) {
            _selection = 0;
        }

        if (_selection >= _start + listHeight) {
            _start = _selection - listHeight + 1;
        } else if (_selection < _start) {
            _start = _selection;
        }

        for (int row = 0; row < listHeight; ++row) {
            int index = _start + row;
            int y = rect.getPoint().getY() + 2 + row;
            boolean selected = index == _selection && index < _filteredList.size();
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.fillRow(graphics, Point.create(rect.getPoint().getX(), y), width, background);
            if (index >= _filteredList.size()) {
                continue;
            }
            var item = _filteredList.get(index);
            var line = new AttributedString();
            line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE,
                    background);
            line.append(item.displayString(),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), y), width, line, UiTheme.TEXT_MUTED,
                    background);
        }

        if (_filteredList.isEmpty() && listHeight > 0) {
            var empty = AttributedString.create("  no matches for current filter", UiTheme.TEXT_MUTED,
                    UiTheme.SURFACE_BACKGROUND);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 2), width, empty,
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND);
        }
    }
}
