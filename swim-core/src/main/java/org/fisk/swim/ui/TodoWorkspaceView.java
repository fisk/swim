package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalCursorShape;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.todo.TodoItem;
import org.fisk.swim.todo.TodoProject;
import org.fisk.swim.todo.TodoSnapshot;
import org.fisk.swim.todo.TodoStore;
import org.fisk.swim.todo.TodoTag;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public class TodoWorkspaceView extends View implements KeyBindingHintProvider {
    private static final class PromptCursor extends Cursor {
        private final TodoWorkspaceView _owner;

        private PromptCursor(TodoWorkspaceView owner) {
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

    private enum FilterKind {
        INBOX,
        ALL,
        COMPLETED,
        PROJECT,
        TAG
    }

    private enum PromptKind {
        NEW_ITEM,
        PROJECT,
        TAGS
    }

    private record Filter(FilterKind kind, Long projectId, String label, String tagName) {
        static Filter inbox() {
            return new Filter(FilterKind.INBOX, null, "Inbox", null);
        }

        static Filter all() {
            return new Filter(FilterKind.ALL, null, "All Open", null);
        }

        static Filter completed() {
            return new Filter(FilterKind.COMPLETED, null, "Completed", null);
        }

        static Filter project(TodoProject project) {
            return new Filter(FilterKind.PROJECT, project.id(), project.name(), null);
        }

        static Filter tag(TodoTag tag) {
            return new Filter(FilterKind.TAG, null, "#" + tag.name(), tag.name());
        }
    }

    private record SidebarEntry(String label, Filter filter, boolean selectable, int indent) {
    }

    private static final class Prompt {
        private final PromptKind _kind;
        private final long _itemId;
        private final StringBuilder _value;

        private Prompt(PromptKind kind, long itemId, String initialValue) {
            _kind = kind;
            _itemId = itemId;
            _value = new StringBuilder(initialValue == null ? "" : initialValue);
        }
    }

    private final TodoStore _store;
    private TodoSnapshot _snapshot = TodoSnapshot.empty();
    private List<SidebarEntry> _sidebar = List.of();
    private List<TodoItem> _visibleItems = List.of();
    private Filter _filter = Filter.inbox();
    private int _sidebarSelection;
    private int _itemSelection;
    private boolean _sidebarFocused;
    private Prompt _prompt;
    private String _message = "";
    private Runnable _pendingAction;
    private final PromptCursor _cursor;

    public TodoWorkspaceView(Rect bounds, TodoStore store) {
        super(bounds);
        _store = store;
        _cursor = new PromptCursor(this);
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        refresh();
    }

    public String getTitle() {
        return "Todo";
    }

    public void refreshFromStore() {
        refresh();
        setNeedsRedraw();
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        _pendingAction = null;
        if (events.remaining() != 0) {
            return Response.NO;
        }
        KeyStroke event = events.current();
        if (_prompt != null) {
            _pendingAction = promptAction(event);
        } else {
            _pendingAction = normalAction(event);
        }
        return _pendingAction == null ? Response.NO : Response.YES;
    }

    @Override
    public void respond() {
        if (_pendingAction != null) {
            _pendingAction.run();
            _pendingAction = null;
        }
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        int height = rect.getSize().getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        int sidebarWidth = sidebarWidth(width);
        int mainX = rect.getPoint().getX() + sidebarWidth;
        int mainWidth = Math.max(0, width - sidebarWidth);

        drawHeader(rect, width);
        drawSidebar(rect, sidebarWidth, height);
        drawItems(Point.create(mainX, rect.getPoint().getY() + 1), mainWidth, Math.max(0, height - 2));
        drawFooter(Point.create(rect.getPoint().getX(), rect.getPoint().getY() + height - 1), width);
    }

    @Override
    public String keyHintContext() {
        return _prompt == null ? "todo workspace" : "todo prompt";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        if (_prompt != null) {
            return List.of(
                    KeyBindingHint.of("<ENTER>", "Prompt", "submit"),
                    KeyBindingHint.of("<ESC>", "Prompt", "cancel"),
                    KeyBindingHint.of("<BACKSPACE>", "Prompt", "delete character"),
                    KeyBindingHint.of("<CHAR>", "Prompt", "type text"));
        }
        return List.of(
                KeyBindingHint.of("j", "Navigation", "move down"),
                KeyBindingHint.of("k", "Navigation", "move up"),
                KeyBindingHint.of("<DOWN>", "Navigation", "move down"),
                KeyBindingHint.of("<UP>", "Navigation", "move up"),
                KeyBindingHint.of("<TAB>", "Navigation", "switch sidebar/items"),
                KeyBindingHint.of("<ENTER>", "Items", "toggle or open filter"),
                KeyBindingHint.of("n", "Items", "new inbox todo"),
                KeyBindingHint.of("p", "Items", "assign project"),
                KeyBindingHint.of("g", "Items", "edit tags"),
                KeyBindingHint.of("c", "Items", "toggle done"),
                KeyBindingHint.of("x", "Items", "delete"),
                KeyBindingHint.of("D", "Items", "delete"),
                KeyBindingHint.of("i", "Filters", "inbox"),
                KeyBindingHint.of("a", "Filters", "all open"),
                KeyBindingHint.of("r", "Workspace", "refresh"),
                KeyBindingHint.of("q", "Workspace", "close tab"),
                KeyBindingHint.of("<ESC>", "Workspace", "close tab"));
    }

    @Override
    public Cursor getCursor() {
        return _prompt == null ? null : _cursor;
    }

    private Runnable promptAction(KeyStroke event) {
        if (event.getKeyType() == KeyType.Enter) {
            return this::submitPrompt;
        }
        if (event.getKeyType() == KeyType.Escape) {
            return () -> {
                _prompt = null;
                _message = "Cancelled";
                setNeedsRedraw();
            };
        }
        if (event.getKeyType() == KeyType.Backspace) {
            return () -> {
                if (_prompt._value.length() > 0) {
                    _prompt._value.delete(_prompt._value.length() - 1, _prompt._value.length());
                }
                setNeedsRedraw();
            };
        }
        if (event.getKeyType() == KeyType.Character && !event.isCtrlDown() && !event.isAltDown()) {
            char character = event.getCharacter();
            return () -> {
                _prompt._value.append(character);
                setNeedsRedraw();
            };
        }
        return null;
    }

    private Runnable normalAction(KeyStroke event) {
        return switch (event.getKeyType()) {
        case Escape -> this::closeWorkspace;
        case Enter -> _sidebarFocused ? this::selectSidebarEntry : this::toggleSelectedItem;
        case Tab, ReverseTab -> () -> {
            _sidebarFocused = !_sidebarFocused;
            _message = _sidebarFocused ? "Sidebar" : "Todos";
            setNeedsRedraw();
        };
        case ArrowDown -> this::moveDown;
        case ArrowUp -> this::moveUp;
        default -> characterAction(event);
        };
    }

    private Runnable characterAction(KeyStroke event) {
        if (event.getKeyType() != KeyType.Character || event.isCtrlDown() || event.isAltDown()) {
            return null;
        }
        return switch (event.getCharacter()) {
        case 'q' -> this::closeWorkspace;
        case 'j' -> this::moveDown;
        case 'k' -> this::moveUp;
        case 'n' -> () -> _prompt = new Prompt(PromptKind.NEW_ITEM, 0L, "");
        case 'p' -> this::promptProject;
        case 'g' -> this::promptTags;
        case 'c' -> this::toggleSelectedItem;
        case 'x', 'D' -> this::deleteSelectedItem;
        case 'r' -> () -> {
            refresh();
            _message = "Refreshed";
        };
        case 'i' -> () -> setFilter(Filter.inbox());
        case 'a' -> () -> setFilter(Filter.all());
        default -> null;
        };
    }

    private void moveDown() {
        if (_sidebarFocused) {
            moveSidebar(1);
        } else if (_itemSelection < _visibleItems.size() - 1) {
            _itemSelection++;
        }
        setNeedsRedraw();
    }

    private void moveUp() {
        if (_sidebarFocused) {
            moveSidebar(-1);
        } else if (_itemSelection > 0) {
            _itemSelection--;
        }
        setNeedsRedraw();
    }

    private void moveSidebar(int delta) {
        if (_sidebar.isEmpty()) {
            return;
        }
        int index = _sidebarSelection;
        for (int i = 0; i < _sidebar.size(); i++) {
            index = Math.floorMod(index + delta, _sidebar.size());
            if (_sidebar.get(index).selectable()) {
                _sidebarSelection = index;
                return;
            }
        }
    }

    private void selectSidebarEntry() {
        if (_sidebarSelection < 0 || _sidebarSelection >= _sidebar.size()) {
            return;
        }
        SidebarEntry entry = _sidebar.get(_sidebarSelection);
        if (!entry.selectable()) {
            return;
        }
        setFilter(entry.filter());
    }

    private void setFilter(Filter filter) {
        _filter = filter == null ? Filter.inbox() : filter;
        _itemSelection = 0;
        refreshVisibleItems();
        syncSidebarSelectionToFilter();
        _message = "Showing " + _filter.label();
        setNeedsRedraw();
    }

    private void promptProject() {
        TodoItem item = selectedItem();
        if (item == null) {
            _message = "No todo selected";
            setNeedsRedraw();
            return;
        }
        _prompt = new Prompt(PromptKind.PROJECT, item.id(), item.projectName() == null ? "" : item.projectName());
        setNeedsRedraw();
    }

    private void promptTags() {
        TodoItem item = selectedItem();
        if (item == null) {
            _message = "No todo selected";
            setNeedsRedraw();
            return;
        }
        _prompt = new Prompt(PromptKind.TAGS, item.id(), String.join(", ", item.tags()));
        setNeedsRedraw();
    }

    private void submitPrompt() {
        Prompt prompt = _prompt;
        _prompt = null;
        String value = prompt._value.toString().trim();
        try {
            switch (prompt._kind) {
            case NEW_ITEM -> {
                if (value.isBlank()) {
                    _message = "Todo title cannot be empty";
                    break;
                }
                TodoItem item = _store.createInboxItem(value);
                _message = "Added to Inbox";
                refresh();
                selectItem(item.id());
                setFilter(Filter.inbox());
            }
            case PROJECT -> {
                _store.assignProject(prompt._itemId, value);
                _message = value.isBlank() ? "Moved to Inbox" : "Moved to " + value;
                refresh();
                selectItem(prompt._itemId);
            }
            case TAGS -> {
                _store.replaceTags(prompt._itemId, parseTags(value));
                _message = value.isBlank() ? "Cleared tags" : "Updated tags";
                refresh();
                selectItem(prompt._itemId);
            }
            }
        } catch (RuntimeException e) {
            _message = e.getMessage() == null ? "Todo update failed" : e.getMessage();
        }
        setNeedsRedraw();
    }

    private void toggleSelectedItem() {
        TodoItem item = selectedItem();
        if (item == null) {
            _message = "No todo selected";
            setNeedsRedraw();
            return;
        }
        _store.toggleCompleted(item.id());
        _message = item.completed() ? "Reopened" : "Completed";
        refresh();
        clampItemSelection();
        setNeedsRedraw();
    }

    private void deleteSelectedItem() {
        TodoItem item = selectedItem();
        if (item == null) {
            _message = "No todo selected";
            setNeedsRedraw();
            return;
        }
        _store.deleteItem(item.id());
        _message = "Deleted";
        refresh();
        clampItemSelection();
        setNeedsRedraw();
    }

    private void closeWorkspace() {
        Window window = Window.getInstance();
        if (window != null) {
            window.closeCurrentTabOrExit();
        }
    }

    private void refresh() {
        _snapshot = _store.snapshot();
        rebuildSidebar();
        refreshVisibleItems();
        syncSidebarSelectionToFilter();
    }

    private void rebuildSidebar() {
        var entries = new ArrayList<SidebarEntry>();
        entries.add(new SidebarEntry("Inbox", Filter.inbox(), true, 0));
        entries.add(new SidebarEntry("All Open", Filter.all(), true, 0));
        entries.add(new SidebarEntry("Completed", Filter.completed(), true, 0));
        entries.add(new SidebarEntry("Projects", null, false, 0));
        for (TodoProject project : _snapshot.projects()) {
            entries.add(new SidebarEntry(project.name(), Filter.project(project), true, 2));
        }
        entries.add(new SidebarEntry("Tags", null, false, 0));
        for (TodoTag tag : _snapshot.tags()) {
            entries.add(new SidebarEntry("#" + tag.name(), Filter.tag(tag), true, 2));
        }
        _sidebar = List.copyOf(entries);
        if (_sidebarSelection >= _sidebar.size()) {
            _sidebarSelection = Math.max(0, _sidebar.size() - 1);
        }
        if (!_sidebar.isEmpty() && !_sidebar.get(_sidebarSelection).selectable()) {
            moveSidebar(1);
        }
    }

    private void refreshVisibleItems() {
        _visibleItems = _snapshot.items().stream().filter(this::matchesFilter).toList();
        clampItemSelection();
    }

    private boolean matchesFilter(TodoItem item) {
        return switch (_filter.kind()) {
        case INBOX -> !item.completed() && item.projectId() == null;
        case ALL -> !item.completed();
        case COMPLETED -> item.completed();
        case PROJECT -> !item.completed() && Objects.equals(_filter.projectId(), item.projectId());
        case TAG -> !item.completed() && item.tags().stream().anyMatch(tag -> tag.equals(_filter.tagName()));
        };
    }

    private void syncSidebarSelectionToFilter() {
        for (int i = 0; i < _sidebar.size(); i++) {
            Filter candidate = _sidebar.get(i).filter();
            if (sameFilter(candidate, _filter)) {
                _sidebarSelection = i;
                return;
            }
        }
        _filter = Filter.inbox();
        _sidebarSelection = 0;
        refreshVisibleItems();
    }

    private static boolean sameFilter(Filter left, Filter right) {
        if (left == null || right == null || left.kind() != right.kind()) {
            return false;
        }
        return Objects.equals(left.projectId(), right.projectId())
                && Objects.equals(left.tagName(), right.tagName());
    }

    private void selectItem(long id) {
        refreshVisibleItems();
        for (int i = 0; i < _visibleItems.size(); i++) {
            if (_visibleItems.get(i).id() == id) {
                _itemSelection = i;
                return;
            }
        }
    }

    private void clampItemSelection() {
        if (_visibleItems.isEmpty()) {
            _itemSelection = 0;
            return;
        }
        _itemSelection = Math.max(0, Math.min(_itemSelection, _visibleItems.size() - 1));
    }

    private TodoItem selectedItem() {
        if (_visibleItems.isEmpty() || _itemSelection < 0 || _itemSelection >= _visibleItems.size()) {
            return null;
        }
        return _visibleItems.get(_itemSelection);
    }

    private void drawHeader(Rect rect, int width) {
        var title = new AttributedString();
        title.append(" Todo ", UiTheme.TEXT_ON_ACCENT, UiTheme.ACCENT_GREEN);
        title.append(" " + _filter.label() + " ", UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_ACCENT);
        title.append(" " + _visibleItems.size() + " shown  " + openCount() + " open ",
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(TerminalContext.getInstance().getGraphics(), rect.getPoint(), width, title,
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

    }

    private void drawSidebar(Rect rect, int sidebarWidth, int height) {
        var graphics = TerminalContext.getInstance().getGraphics();
        int x = rect.getPoint().getX();
        int y = rect.getPoint().getY() + 1;
        int rows = Math.max(0, height - 2);
        for (int row = 0; row < rows; row++) {
            int index = row;
            TextColor background = index == _sidebarSelection && _sidebarFocused
                    ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.fillRow(graphics, Point.create(x, y + row), sidebarWidth, background);
            if (index >= _sidebar.size()) {
                continue;
            }
            SidebarEntry entry = _sidebar.get(index);
            var line = new AttributedString();
            boolean selectedFilter = sameFilter(entry.filter(), _filter);
            String prefix = selectedFilter ? "> " : "  ";
            String indent = " ".repeat(entry.indent());
            TextColor foreground = entry.selectable()
                    ? selectedFilter ? UiTheme.ACCENT_GREEN : UiTheme.TEXT_PRIMARY
                    : UiTheme.TEXT_MUTED;
            line.append(prefix + indent + fit(entry.label(), Math.max(0, sidebarWidth - prefix.length() - entry.indent())),
                    foreground, background);
            UiTheme.drawLine(graphics, Point.create(x, y + row), sidebarWidth, line, UiTheme.TEXT_MUTED, background);
        }
    }

    private void drawItems(Point point, int width, int rows) {
        var graphics = TerminalContext.getInstance().getGraphics();
        for (int row = 0; row < rows; row++) {
            TextColor background = row == _itemSelection && !_sidebarFocused
                    ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.fillRow(graphics, Point.create(point.getX(), point.getY() + row), width, background);
            if (row >= _visibleItems.size()) {
                continue;
            }
            TodoItem item = _visibleItems.get(row);
            var line = new AttributedString();
            line.append(row == _itemSelection && !_sidebarFocused ? "> " : "  ",
                    UiTheme.PANEL_SELECTION_ACCENT, background);
            line.append(item.completed() ? "[x] " : "[ ] ",
                    item.completed() ? UiTheme.TEXT_MUTED : UiTheme.TEXT_PRIMARY, background);
            line.append(fit(item.title(), Math.max(8, width / 2)), UiTheme.TEXT_PRIMARY, background);
            if (item.projectName() != null && !item.projectName().isBlank()) {
                line.append(" @" + item.projectName(), UiTheme.ACCENT_BLUE, background);
            } else {
                line.append(" @Inbox", UiTheme.TEXT_MUTED, background);
            }
            for (String tag : item.tags()) {
                line.append(" #" + tag, UiTheme.ACCENT_GOLD, background);
            }
            UiTheme.drawLine(graphics, Point.create(point.getX(), point.getY() + row), width, line,
                    UiTheme.TEXT_MUTED, background);
        }
        if (_visibleItems.isEmpty() && rows > 0) {
            var empty = AttributedString.create("  No todos in " + _filter.label(), UiTheme.TEXT_MUTED,
                    UiTheme.SURFACE_BACKGROUND);
            UiTheme.drawLine(graphics, point, width, empty, UiTheme.TEXT_MUTED, UiTheme.SURFACE_BACKGROUND);
        }
    }

    private void drawFooter(Point point, int width) {
        var line = new AttributedString();
        if (_prompt != null) {
            line.append(" " + promptLabel(_prompt._kind) + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.COMMAND_PROMPT);
            line.append(" " + _prompt._value, UiTheme.TEXT_PRIMARY, UiTheme.COMMAND_BACKGROUND);
        } else if (_message != null && !_message.isBlank()) {
            line.append(" status ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
            line.append(" " + _message, UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        } else {
            line.append(" data ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
            line.append(" " + _store.getDataPath(), UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        }
        UiTheme.drawLine(TerminalContext.getInstance().getGraphics(), point, width, line,
                UiTheme.TEXT_MUTED, _prompt == null ? UiTheme.SURFACE_MUTED : UiTheme.COMMAND_BACKGROUND);
    }

    private long openCount() {
        return _snapshot.items().stream().filter(item -> !item.completed()).count();
    }

    private static int sidebarWidth(int width) {
        if (width < 44) {
            return Math.max(14, Math.min(20, width / 2));
        }
        return Math.max(20, Math.min(32, width / 4));
    }

    private static String promptLabel(PromptKind kind) {
        return switch (kind) {
        case NEW_ITEM -> "New todo";
        case PROJECT -> "Project";
        case TAGS -> "Tags";
        };
    }

    private static String fit(String text, int width) {
        if (width <= 0) {
            return "";
        }
        String value = text == null ? "" : text;
        if (value.length() <= width) {
            return value;
        }
        if (width == 1) {
            return ".";
        }
        return value.substring(0, width - 1) + ".";
    }

    private Point cursorScreenPosition() {
        Point origin = absoluteOrigin();
        int width = Math.max(1, getBounds().getSize().getWidth());
        int height = Math.max(1, getBounds().getSize().getHeight());
        int promptLength = _prompt == null ? 0 : _prompt._value.length();
        int labelLength = _prompt == null ? 0 : (" " + promptLabel(_prompt._kind) + " ").length();
        int x = Math.min(width - 1, labelLength + 1 + promptLength);
        return Point.create(origin.getX() + Math.max(0, x), origin.getY() + height - 1);
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

    private static List<String> parseTags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        var tags = new ArrayList<String>();
        for (String token : value.split("[,\\s]+")) {
            String tag = token.trim();
            while (tag.startsWith("#")) {
                tag = tag.substring(1).trim();
            }
            if (!tag.isBlank()) {
                tags.add(tag.toLowerCase(Locale.ROOT));
            }
        }
        return tags;
    }
}
