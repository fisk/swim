package org.fisk.swim.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.fileindex.ProjectSearch;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalCursorShape;
import org.fisk.swim.text.AttributedString;

import org.fisk.swim.terminal.TextColor;
import org.fisk.swim.event.KeyType;

public class ProjectSearchPanelView extends View implements KeyBindingHintProvider {
    private static final int MAX_RESULTS = ProjectSearch.MAX_MATCHES;
    private static final int MAX_FLUSH_MATCHES = 512;
    private static final class QueryCursor extends Cursor {
        private final ProjectSearchPanelView _owner;

        private QueryCursor(ProjectSearchPanelView owner) {
            super(null);
            _owner = owner;
        }

        @Override
        public int getXOnScreen() {
            return _owner.cursorScreenPosition().getX();
        }

        @Override
        public int getYOnScreen() {
            return _owner.cursorScreenPosition().getY();
        }

        @Override
        public TerminalCursorShape getShape() {
            return TerminalCursorShape.BAR;
        }
    }

    private final ProjectSearch _projectSearch;
    private final boolean _editableResults;
    private final StringBuilder _query = new StringBuilder();
    private final ListEventResponder _responders = new ListEventResponder();
    private final QueryCursor _cursor;
    private List<ProjectSearch.Match> _results = List.of();
    private final AtomicLong _searchGeneration = new AtomicLong();
    private final AtomicBoolean _closed = new AtomicBoolean();
    private final AtomicBoolean _resultFlushScheduled = new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicInteger _acceptedResults = new java.util.concurrent.atomic.AtomicInteger();
    private final ConcurrentLinkedQueue<ProjectSearch.Match> _pendingResults = new ConcurrentLinkedQueue<>();
    private volatile boolean _searching;
    private int _selection;
    private int _start;

    public static ProjectSearchPanelView create(Rect bounds, Path startPath) {
        return create(bounds, startPath, false);
    }

    public static ProjectSearchPanelView createEditable(Rect bounds, Path startPath) {
        return create(bounds, startPath, true);
    }

    private static ProjectSearchPanelView create(Rect bounds, Path startPath, boolean editableResults) {
        var search = new ProjectSearch(startPath);
        if (!search.isAvailable()) {
            return null;
        }
        return new ProjectSearchPanelView(bounds, search, editableResults);
    }

    public ProjectSearchPanelView(Rect bounds, ProjectSearch projectSearch) {
        this(bounds, projectSearch, false);
    }

    private ProjectSearchPanelView(Rect bounds, ProjectSearch projectSearch, boolean editableResults) {
        super(bounds);
        _projectSearch = projectSearch;
        _editableResults = editableResults;
        _cursor = new QueryCursor(this);
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        _responders.addEventResponder("<DOWN>", "Results", "move down", () -> moveSelection(1));
        _responders.addEventResponder("<UP>", "Results", "move up", () -> moveSelection(-1));
        _responders.addEventResponder("<ESC>", "Search", "close", this::close);
        _responders.addEventResponder("<ENTER>", "Results",
                editableResults ? "edit all results" : "open selected", this::openSelection);
        _responders.addEventResponder("<BACKSPACE>", "Search", "delete character", () -> {
            if (_query.length() == 0) {
                return;
            }
            _query.delete(_query.length() - 1, _query.length());
            refreshResults();
        });
        _responders.addKeyBindingHint("<CHAR>", "Search", "type to search project");
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
        return _editableResults ? "Project Search (Editable Results)" : "Project Search";
    }

    @Override
    public String keyHintContext() {
        return "project search";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _responders.keyBindingHints();
    }

    @Override
    public Cursor getCursor() {
        return _cursor;
    }

    String getQuery() {
        return _query.toString();
    }

    List<ProjectSearch.Match> getResults() {
        return _results;
    }

    /** Test-only synchronization point for the asynchronous/debounced search pipeline. */
    boolean awaitSearchCompletionForTests(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (_pendingResults.isEmpty() && _searching && System.nanoTime() < deadline) Thread.sleep(5);
        flushPendingMatches(_searchGeneration.get());
        return !_results.isEmpty() || !_searching;
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
        var graphics = TerminalContext.getInstance().getTerminalGraphics();
        int width = rect.getSize().getWidth();

        var header = new AttributedString();
        header.append(" project search ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" " + _results.size() + (_searching ? "+ matches " : " matches "), UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
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
            var line = resultRow(width, index, selected, background);
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

    AttributedString buildResultRowForTest(int width, int index, boolean selected) {
        TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND : UiTheme.SURFACE_BACKGROUND;
        return resultRow(width, index, selected, background);
    }

    private AttributedString resultRow(int width, int index, boolean selected, TextColor background) {
        var line = new AttributedString();
        if (index < 0 || index >= _results.size()) {
            return line;
        }
        var match = _results.get(index);
        TextColor primary = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
        TextColor muted = selected ? UiTheme.TEXT_ON_ACCENT : UiTheme.TEXT_MUTED;
        TextColor pathColor = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.ACCENT_BLUE;
        TextColor lineColor = selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.ACCENT_GOLD;
        TextColor hitColor = selected ? UiTheme.TEXT_ON_ACCENT : UiTheme.ACCENT_GREEN;
        line.append(selected ? "▌" : " ", selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_SUBTLE,
                background);
        line.append(" " + leftPad(Integer.toString(match.lineNumber()), lineNumberWidth()) + " ",
                lineColor, background);
        line.append(" " + match.relativePath(), pathColor, background);
        line.append(":" + match.lineNumber() + ":" + match.columnNumber() + "  ", muted, background);
        appendPreview(line, match, primary, muted, hitColor, background);
        if (line.length() < width) {
            line.append(UiTheme.repeat(" ", width - line.length()), muted, background);
        }
        return line.length() > width ? line.slice(0, width) : line;
    }

    private void appendPreview(AttributedString line, ProjectSearch.Match match, TextColor primary,
            TextColor muted, TextColor hitColor, TextColor background) {
        String preview = match.previewText();
        String needle = _query.toString();
        if (needle.isBlank()) {
            line.append(preview, primary, background);
            return;
        }
        int start = previewIndex(preview, needle);
        if (start < 0) {
            line.append(preview, primary, background);
            return;
        }
        if (start > 0) {
            line.append(preview.substring(0, start), muted, background);
        }
        int end = Math.min(preview.length(), start + needle.length());
        line.append(preview.substring(start, end), hitColor, background);
        if (end < preview.length()) {
            line.append(preview.substring(end), primary, background);
        }
    }

    private int previewIndex(String preview, String needle) {
        if (needle.equals(needle.toLowerCase(java.util.Locale.ROOT))) {
            return preview.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT));
        }
        return preview.indexOf(needle);
    }

    private int lineNumberWidth() {
        int max = 1;
        for (var result : _results) {
            max = Math.max(max, result.lineNumber());
        }
        return Math.max(3, Integer.toString(max).length());
    }

    private static String leftPad(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return " ".repeat(width - text.length()) + text;
    }

    private void refreshResults() {
        if (_closed.get()) return;
        long generation = _searchGeneration.incrementAndGet();
        String query = _query.toString();
        _results = new ArrayList<>();
        _pendingResults.clear();
        _acceptedResults.set(0);
        _resultFlushScheduled.set(false);
        _searching = !query.isBlank();
        setNeedsRedraw();
        if (query.isBlank()) return;
        Thread.ofVirtual().start(() -> {
            _projectSearch.search(query, matches -> publishMatches(generation, matches),
                    () -> _closed.get() || generation != _searchGeneration.get());
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                if (generation != _searchGeneration.get()) return;
                _searching = false;
                updateQuickfix();
                setNeedsRedraw();
            }));
        });
    }

    private void publishMatches(long generation, List<ProjectSearch.Match> matches) {
        if (generation != _searchGeneration.get()) return;
        for (ProjectSearch.Match match : matches) {
            if (generation != _searchGeneration.get()) return;
            if (!reserveResultSlot()) break;
            _pendingResults.add(match);
        }
        schedulePendingFlush(generation);
    }

    private boolean reserveResultSlot() {
        for (int current; (current = _acceptedResults.get()) < MAX_RESULTS;) {
            if (_acceptedResults.compareAndSet(current, current + 1)) return true;
        }
        return false;
    }

    private void schedulePendingFlush(long generation) {
        if (!_resultFlushScheduled.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            EventThread.getInstance().enqueue(new RunnableEvent(() -> flushPendingMatches(generation)));
        });
    }

    private void flushPendingMatches(long generation) {
        _resultFlushScheduled.set(false);
        if (generation != _searchGeneration.get()) return;
        var next = new ArrayList<>(_results);
        for (int count = 0; count < MAX_FLUSH_MATCHES && next.size() < MAX_RESULTS; count++) {
            ProjectSearch.Match match = _pendingResults.poll();
            if (match == null) break;
            next.add(match);
        }
        if (next.size() == _results.size()) return;
        next.sort(java.util.Comparator.comparing(match -> match.relativePath().toString()));
        _results = next;
        updateQuickfix();
        setNeedsRedraw();
        if (!_pendingResults.isEmpty()) schedulePendingFlush(generation);
    }

    private void updateQuickfix() {
        var window = Window.getInstance();
        if (window != null) window.setQuickfixResults("Quickfix", _results);
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
        if (_editableResults) {
            openEditableResults();
            return;
        }
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

    private void openEditableResults() {
        if (_results.isEmpty()) {
            return;
        }
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        if (!window.openEditableSearchResults(_projectSearch.getRoot(), _results)) {
            window.getCommandView().setMessage("Failed to open editable search results");
            return;
        }
        close();
    }

    private void close() {
        _closed.set(true);
        _searchGeneration.incrementAndGet();
        var window = Window.getInstance();
        if (window != null) {
            window.hidePanel();
        }
    }

    private Point cursorScreenPosition() {
        Point origin = absoluteOrigin();
        int width = Math.max(1, getBounds().getSize().getWidth());
        int queryColumn = " query ".length() + 1 + _query.length();
        int x = Math.min(width - 1, queryColumn);
        int y = Math.min(Math.max(0, getBounds().getSize().getHeight() - 1), 1);
        return Point.create(origin.getX() + Math.max(0, x), origin.getY() + y);
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (var parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }
}
