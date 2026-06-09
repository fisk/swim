package org.fisk.swim.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class DirectoryBrowserView extends View implements KeyBindingHintProvider {
    record Entry(Path path, String label, boolean directory, boolean parent) {
    }

    private final ListEventResponder _responders = new ListEventResponder();
    private Path _directory;
    private List<Entry> _entries = List.of();
    private int _selection;
    private int _start;

    public DirectoryBrowserView(Rect bounds, Path directory) {
        super(bounds);
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        _responders.addEventResponder("<DOWN>", "Browse", "move down", () -> moveSelection(1));
        _responders.addEventResponder("j", "Browse", "move down", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", "Browse", "move up", () -> moveSelection(-1));
        _responders.addEventResponder("k", "Browse", "move up", () -> moveSelection(-1));
        _responders.addEventResponder("<ENTER>", "Browse", "open selected", this::activateSelection);
        _responders.addEventResponder("l", "Browse", "open selected", this::activateSelection);
        _responders.addEventResponder("<RIGHT>", "Browse", "open selected", this::activateSelection);
        _responders.addEventResponder("h", "Browse", "parent directory", this::goParent);
        _responders.addEventResponder("<LEFT>", "Browse", "parent directory", this::goParent);
        _responders.addEventResponder("<BACKSPACE>", "Browse", "parent directory", this::goParent);
        _responders.addEventResponder("r", "Browse", "refresh", this::refreshEntries);
        _responders.addEventResponder("q", "Browse", "close", this::close);
        _responders.addEventResponder("<ESC>", "Browse", "close", this::close);
        setDirectory(directory);
    }

    String getTitle() {
        return "Browse: " + (_directory == null ? "(none)" : _directory.getFileName() == null ? _directory.toString()
                : _directory.getFileName().toString());
    }

    Path getDirectory() {
        return _directory;
    }

    List<Entry> getEntries() {
        return _entries;
    }

    @Override
    public String keyHintContext() {
        return "directory browser";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _responders.keyBindingHints();
    }

    void setDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        _directory = directory.toAbsolutePath().normalize();
        refreshEntries();
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
        header.append(" directory ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + (_directory == null ? "" : _directory.toString()) + " ",
                UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        int listHeight = Math.max(0, rect.getSize().getHeight() - 1);
        clampSelection(listHeight);
        for (int row = 0; row < listHeight; row++) {
            int index = _start + row;
            int y = rect.getPoint().getY() + 1 + row;
            boolean selected = index == _selection && index < _entries.size();
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.fillRow(graphics, Point.create(rect.getPoint().getX(), y), width, background);
            if (index >= _entries.size()) {
                continue;
            }
            Entry entry = _entries.get(index);
            var line = new AttributedString();
            line.append(selected ? "▌ " : "  ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE,
                    background);
            String prefix = entry.parent() ? "↰ " : entry.directory() ? "▸ " : "  ";
            line.append(prefix + entry.label(),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY,
                    background);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), y), width, line, UiTheme.TEXT_MUTED,
                    background);
        }

        if (_entries.isEmpty() && listHeight > 0) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width,
                    AttributedString.create("  directory is empty", UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND);
        }
    }

    private void refreshEntries() {
        _entries = readEntries(_directory);
        _selection = Math.max(0, Math.min(_selection, Math.max(0, _entries.size() - 1)));
        _start = Math.max(0, Math.min(_start, Math.max(0, _entries.size() - 1)));
        setNeedsRedraw();
    }

    private static List<Entry> readEntries(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        var entries = new ArrayList<Entry>();
        Path parent = directory.getParent();
        if (parent != null) {
            entries.add(new Entry(parent, "..", true, true));
        }
        try (var stream = Files.list(directory)) {
            stream.sorted(Comparator
                    .comparing((Path path) -> !Files.isDirectory(path))
                    .thenComparing(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT)))
                    .forEach(path -> entries.add(new Entry(
                            path,
                            path.getFileName() == null ? path.toString() : path.getFileName().toString(),
                            Files.isDirectory(path),
                            false)));
        } catch (IOException e) {
            return entries;
        }
        return List.copyOf(entries);
    }

    private void moveSelection(int delta) {
        if (_entries.isEmpty()) {
            return;
        }
        _selection = Math.max(0, Math.min(_selection + delta, _entries.size() - 1));
        setNeedsRedraw();
    }

    private void clampSelection(int listHeight) {
        if (_entries.isEmpty()) {
            _selection = 0;
            _start = 0;
            return;
        }
        _selection = Math.max(0, Math.min(_selection, _entries.size() - 1));
        if (_selection >= _start + listHeight) {
            _start = _selection - listHeight + 1;
        } else if (_selection < _start) {
            _start = _selection;
        }
        _start = Math.max(0, Math.min(_start, Math.max(0, _entries.size() - Math.max(1, listHeight))));
    }

    private void activateSelection() {
        if (_selection < 0 || _selection >= _entries.size()) {
            return;
        }
        Entry entry = _entries.get(_selection);
        if (entry.directory()) {
            setDirectory(entry.path());
            return;
        }
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        if (!window.setBufferPath(entry.path())) {
            window.getCommandView().setMessage("Failed to open file");
            return;
        }
        window.focusActiveBuffer();
    }

    private void goParent() {
        if (_directory != null && _directory.getParent() != null) {
            setDirectory(_directory.getParent());
        }
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            if (window.getActiveView() == this && window.closeActiveView()) {
                return;
            }
            if (!window.closeCurrentWorkspaceWindow()) {
                window.hidePanel();
            }
        }
    }
}
