package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailMessageSummary;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadFilter;
import org.fisk.swim.mail.MailThreadPage;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.input.KeyType;

public class MailPanelView extends View implements KeyBindingHintProvider {
    private static final int THREAD_PAGE_SIZE = 100;
    private static final int THREAD_PREFETCH_THRESHOLD = 10;

    private record ThreadRow(MailThreadSummary thread, MailMessageSummary message, String treePrefix) {
    }

    private enum BrowsePane {
        SIDEBAR,
        THREADS
    }

    private enum SidebarKind {
        ALL,
        UNSORTED,
        ACCOUNT,
        TAG
    }

    private record SidebarRow(SidebarKind kind, String value, String label) {
    }

    private record BrowseLayout(
            int bodyTop,
            int bodyHeight,
            int sidebarWidth,
            int separatorX,
            int threadsX,
            int threadsWidth,
            boolean statusVisible,
            int statusY) {
    }

    private enum Mode {
        BROWSE,
        SEARCH,
        COMPOSE
    }

    private enum ComposeField {
        TO,
        CC,
        BCC,
        SUBJECT,
        BODY
    }

    private enum Action {
        NONE,
        MOVE_DOWN,
        MOVE_UP,
        TOP,
        BOTTOM,
        REFRESH,
        REPLY,
        REPLY_ALL,
        SCROLL_BODY_DOWN,
        SCROLL_BODY_UP,
        CLOSE,
        START_SEARCH,
        APPLY_SEARCH,
        CANCEL_SEARCH,
        START_COMPOSE,
        SEND_COMPOSE,
        CANCEL_COMPOSE,
        NEXT_FIELD,
        PREVIOUS_FIELD,
        OPEN_LINK,
        COPY_LINK,
        MOVE_LEFT,
        MOVE_RIGHT,
        OPEN_MESSAGE_BUFFER,
        ACCUMULATE_SIDEBAR_JUMP,
        APPLY_SIDEBAR_JUMP,
        INSERT_NEWLINE,
        BACKSPACE,
        INSERT_CHAR
    }

    private final MailClient _client;
    private MailSnapshot _snapshot;
    private List<SidebarRow> _sidebarRows = List.of();
    private List<String> _availableTags = List.of();
    private Map<String, Integer> _tagUnreadCounts = Map.of();
    private int _unsortedUnreadCount;
    private int _allUnreadCount;
    private List<MailThreadSummary> _threads = List.of();
    private List<ThreadRow> _threadRows = List.of();
    private final Map<Long, List<MailMessageSummary>> _threadMessagesByThreadId = new LinkedHashMap<>();
    private int _totalThreadCount;
    private MailMessageDetail _selectedMessage;
    private int _selectedIndex;
    private int _sidebarSelection;
    private int _sidebarScrollOffset;
    private int _threadScrollOffset;
    private int _detailScrollOffset;
    private BrowsePane _browsePane = BrowsePane.THREADS;
    private Mode _mode = Mode.BROWSE;
    private ComposeField _composeField = ComposeField.TO;
    private String _composeAccountId = "";
    private StringBuilder _composeTo = new StringBuilder();
    private StringBuilder _composeCc = new StringBuilder();
    private StringBuilder _composeBcc = new StringBuilder();
    private StringBuilder _composeSubject = new StringBuilder();
    private List<StringBuilder> _composeBody = new ArrayList<>(List.of(new StringBuilder()));
    private String _composeInReplyToMessageId = "";
    private int _composeToCursor;
    private int _composeCcCursor;
    private int _composeBccCursor;
    private int _composeSubjectCursor;
    private int _composeBodyRow;
    private int _composeBodyColumn;
    private String _statusMessage = "";
    private String _searchQuery = "";
    private StringBuilder _searchInput = new StringBuilder();
    private int _searchCursor;
    private Action _pendingAction = Action.NONE;
    private Character _pendingCharacter;
    private Runnable _pendingMouseAction;
    private final StringBuilder _sidebarJumpDigits = new StringBuilder();
    private final AtomicBoolean _refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean _sendInFlight = new AtomicBoolean();
    private final AtomicLong _messageLoadGeneration = new AtomicLong();
    private String _lastActionableUrl;
    private boolean _awaitingOAuthCompletion;
    private long _oauthPollGeneration;
    private BufferContext _messageBufferContext;

    public MailPanelView(Rect bounds, MailClient client) {
        super(bounds);
        _client = client;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        reload();
        if (shouldAutoRefreshOnOpen()) {
            refreshClientAsync("Refreshing mail...");
        }
    }

    void attachMessageBuffer(BufferContext messageBufferContext) {
        _messageBufferContext = messageBufferContext;
        updateMessageBuffer();
    }

    boolean isComposeActive() {
        return _mode == Mode.COMPOSE;
    }

    @Override
    public String keyHintContext() {
        return switch (_mode) {
        case BROWSE -> "mail browse";
        case SEARCH -> "mail search";
        case COMPOSE -> "mail compose";
        };
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return switch (_mode) {
        case BROWSE -> List.of(
                KeyBindingHint.of("j", "Navigation", "move down"),
                KeyBindingHint.of("k", "Navigation", "move up"),
                KeyBindingHint.of("<DOWN>", "Navigation", "move down"),
                KeyBindingHint.of("<UP>", "Navigation", "move up"),
                KeyBindingHint.of("<LEFT>", "Navigation", "sidebar"),
                KeyBindingHint.of("<RIGHT>", "Navigation", "threads"),
                KeyBindingHint.of("g", "Navigation", "top"),
                KeyBindingHint.of("G", "Navigation", "bottom"),
                KeyBindingHint.of("1-9 g", "Navigation", "jump sidebar"),
                KeyBindingHint.of("<ENTER>", "Message", "open buffer"),
                KeyBindingHint.of("d", "Message", "scroll body down"),
                KeyBindingHint.of("u", "Message", "scroll body up"),
                KeyBindingHint.of("o", "Links", "open link"),
                KeyBindingHint.of("y", "Links", "copy link"),
                KeyBindingHint.of("/", "Search", "search mail"),
                KeyBindingHint.of("?", "Search", "search mail"),
                KeyBindingHint.of("c", "Compose", "new message"),
                KeyBindingHint.of("r", "Compose", "reply"),
                KeyBindingHint.of("R", "Compose", "reply all"),
                KeyBindingHint.of("e", "Workspace", "refresh"),
                KeyBindingHint.of("q", "Workspace", "close"),
                KeyBindingHint.of("<ESC>", "Workspace", "close"));
        case SEARCH -> List.of(
                KeyBindingHint.of("<ENTER>", "Search", "apply"),
                KeyBindingHint.of("<ESC>", "Search", "cancel"),
                KeyBindingHint.of("<BACKSPACE>", "Search", "delete character"),
                KeyBindingHint.of("<LEFT>", "Search", "cursor left"),
                KeyBindingHint.of("<RIGHT>", "Search", "cursor right"),
                KeyBindingHint.of("<CHAR>", "Search", "type query"));
        case COMPOSE -> List.of(
                KeyBindingHint.of("<CTRL>-s", "Compose", "send"),
                KeyBindingHint.of("<ESC>", "Compose", "cancel"),
                KeyBindingHint.of("<TAB>", "Compose", "next field"),
                KeyBindingHint.of("<REVERSE-TAB>", "Compose", "previous field"),
                KeyBindingHint.of("<ENTER>", "Compose", "newline"),
                KeyBindingHint.of("<BACKSPACE>", "Compose", "delete character"),
                KeyBindingHint.of("<UP>", "Compose", "move up"),
                KeyBindingHint.of("<DOWN>", "Compose", "move down"),
                KeyBindingHint.of("<LEFT>", "Compose", "cursor left"),
                KeyBindingHint.of("<RIGHT>", "Compose", "cursor right"),
                KeyBindingHint.of("<CHAR>", "Compose", "type text"));
        };
    }

    void triggerSend() {
        sendCompose();
    }

    @Override
    public org.fisk.swim.event.Response processEvent(org.fisk.swim.event.KeyStrokes events) {
        _pendingAction = Action.NONE;
        _pendingCharacter = null;
        _pendingMouseAction = null;
        if (events.remaining() != 0) {
            return org.fisk.swim.event.Response.NO;
        }
        var event = events.current();
        if (event instanceof MouseAction mouseAction) {
            _pendingMouseAction = mouseAction(mouseAction);
            return _pendingMouseAction == null ? org.fisk.swim.event.Response.NO : org.fisk.swim.event.Response.YES;
        }
        if (_mode == Mode.COMPOSE) {
            if (event.isCtrlDown() && event.getKeyType() == KeyType.Character
                    && (event.getCharacter() == 's' || event.getCharacter() == 'S')) {
                _pendingAction = Action.SEND_COMPOSE;
            } else {
                _pendingAction = switch (event.getKeyType()) {
                case Escape -> Action.CANCEL_COMPOSE;
                case Tab -> Action.NEXT_FIELD;
                case ReverseTab -> Action.PREVIOUS_FIELD;
                case Enter -> Action.INSERT_NEWLINE;
                case Backspace -> Action.BACKSPACE;
                case ArrowLeft -> Action.MOVE_LEFT;
                case ArrowRight -> Action.MOVE_RIGHT;
                case ArrowUp -> Action.MOVE_UP;
                case ArrowDown -> Action.MOVE_DOWN;
                case Character -> {
                    _pendingCharacter = event.getCharacter();
                    yield Action.INSERT_CHAR;
                }
                default -> Action.NONE;
                };
            }
            return _pendingAction == Action.NONE ? org.fisk.swim.event.Response.NO : org.fisk.swim.event.Response.YES;
        }
        if (_mode == Mode.SEARCH) {
            _pendingAction = switch (event.getKeyType()) {
            case Escape -> Action.CANCEL_SEARCH;
            case Enter -> Action.APPLY_SEARCH;
            case Backspace -> Action.BACKSPACE;
            case ArrowLeft -> Action.MOVE_LEFT;
            case ArrowRight -> Action.MOVE_RIGHT;
            case Character -> {
                _pendingCharacter = event.getCharacter();
                yield Action.INSERT_CHAR;
            }
            default -> Action.NONE;
            };
            return _pendingAction == Action.NONE ? org.fisk.swim.event.Response.NO : org.fisk.swim.event.Response.YES;
        }

        if (event.getKeyType() == KeyType.Character) {
            char character = event.getCharacter();
            if (Character.isDigit(character)) {
                _pendingCharacter = character;
                _pendingAction = Action.ACCUMULATE_SIDEBAR_JUMP;
                return org.fisk.swim.event.Response.YES;
            }
            if (character == 'g' && !_sidebarJumpDigits.isEmpty()) {
                _pendingAction = Action.APPLY_SIDEBAR_JUMP;
                return org.fisk.swim.event.Response.YES;
            }
            if (_sidebarJumpDigits.length() > 0) {
                _sidebarJumpDigits.setLength(0);
            }
        } else if (_sidebarJumpDigits.length() > 0) {
            _sidebarJumpDigits.setLength(0);
        }

        _pendingAction = switch (event.getKeyType()) {
        case ArrowDown -> Action.MOVE_DOWN;
        case ArrowUp -> Action.MOVE_UP;
        case ArrowLeft -> Action.MOVE_LEFT;
        case ArrowRight -> Action.MOVE_RIGHT;
        case Enter -> Action.OPEN_MESSAGE_BUFFER;
        case Escape -> Action.CLOSE;
        case Character -> switch (event.getCharacter()) {
        case 'j' -> Action.MOVE_DOWN;
        case 'k' -> Action.MOVE_UP;
        case 'g' -> Action.TOP;
        case 'G' -> Action.BOTTOM;
        case '/', '?' -> Action.START_SEARCH;
        case 'e' -> Action.REFRESH;
        case 'r' -> Action.REPLY;
        case 'R' -> Action.REPLY_ALL;
        case 'd' -> Action.SCROLL_BODY_DOWN;
        case 'u' -> Action.SCROLL_BODY_UP;
        case 'q' -> Action.CLOSE;
        case 'c' -> Action.START_COMPOSE;
        case 'o' -> Action.OPEN_LINK;
        case 'y' -> Action.COPY_LINK;
        default -> Action.NONE;
        };
        default -> Action.NONE;
        };
        return _pendingAction == Action.NONE ? org.fisk.swim.event.Response.NO : org.fisk.swim.event.Response.YES;
    }

    @Override
    public void respond() {
        if (_pendingMouseAction != null) {
            _pendingMouseAction.run();
            _pendingMouseAction = null;
            return;
        }
        switch (_pendingAction) {
        case MOVE_DOWN -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeVertical(1);
            } else if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarSelection(1);
            } else {
                moveSelection(1);
            }
        }
        case MOVE_UP -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeVertical(-1);
            } else if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarSelection(-1);
            } else {
                moveSelection(-1);
            }
        }
        case TOP -> {
            if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarTo(0);
            } else {
                moveTo(0);
            }
        }
        case BOTTOM -> {
            if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarTo(Math.max(0, _sidebarRows.size() - 1));
            } else {
                moveToBottom();
            }
        }
        case REFRESH -> refreshClient();
        case REPLY -> startReply(false);
        case REPLY_ALL -> startReply(true);
        case SCROLL_BODY_DOWN -> scrollBody(5);
        case SCROLL_BODY_UP -> scrollBody(-5);
        case CLOSE -> close();
        case START_SEARCH -> startSearch();
        case APPLY_SEARCH -> applySearch();
        case CANCEL_SEARCH -> cancelSearch();
        case START_COMPOSE -> startComposeFromSelection();
        case SEND_COMPOSE -> sendCompose();
        case CANCEL_COMPOSE -> cancelCompose();
        case NEXT_FIELD -> advanceComposeField(1);
        case PREVIOUS_FIELD -> advanceComposeField(-1);
        case OPEN_LINK -> openActionableUrl();
        case COPY_LINK -> copyActionableUrl();
        case MOVE_LEFT -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeHorizontal(-1);
            } else if (_mode == Mode.SEARCH) {
                moveSearchHorizontal(-1);
            } else {
                _browsePane = BrowsePane.SIDEBAR;
                setNeedsRedraw();
            }
        }
        case MOVE_RIGHT -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeHorizontal(1);
            } else if (_mode == Mode.SEARCH) {
                moveSearchHorizontal(1);
            } else {
                _browsePane = BrowsePane.THREADS;
                setNeedsRedraw();
            }
        }
        case OPEN_MESSAGE_BUFFER -> focusSelectedMessageBuffer();
        case ACCUMULATE_SIDEBAR_JUMP -> accumulateSidebarJumpDigit(_pendingCharacter);
        case APPLY_SIDEBAR_JUMP -> applySidebarJump();
        case INSERT_NEWLINE -> insertComposeNewline();
        case BACKSPACE -> {
            if (_mode == Mode.COMPOSE) {
                backspaceCompose();
            } else if (_mode == Mode.SEARCH) {
                backspaceSearch();
            }
        }
        case INSERT_CHAR -> {
            if (_mode == Mode.COMPOSE) {
                insertComposeCharacter(_pendingCharacter);
            } else if (_mode == Mode.SEARCH) {
                insertSearchCharacter(_pendingCharacter);
            }
        }
        default -> {
        }
        }
        _pendingAction = Action.NONE;
        _pendingCharacter = null;
    }

    private Runnable mouseAction(MouseAction action) {
        if (action == null || action.getPosition() == null) {
            return null;
        }
        if (action.getActionType() != MouseActionType.CLICK_DOWN
                && action.getActionType() != MouseActionType.SCROLL_UP
                && action.getActionType() != MouseActionType.SCROLL_DOWN) {
            return null;
        }
        Point origin = absoluteOrigin();
        int localX = action.getPosition().getColumn() - origin.getX();
        int localY = action.getPosition().getRow() - origin.getY();
        int width = getBounds().getSize().getWidth();
        int height = getBounds().getSize().getHeight();
        if (localX < 0 || localY < 0 || localX >= width || localY >= height) {
            return null;
        }
        if (_mode == Mode.COMPOSE) {
            return composeMouseAction(action, localX, localY);
        }
        return browseMouseAction(action, localX, localY, width, height);
    }

    private Runnable browseMouseAction(MouseAction action, int localX, int localY, int width, int height) {
        BrowseLayout layout = browseLayout(width, height);
        boolean inSidebar = localX < layout.sidebarWidth();
        boolean inThreads = localX >= layout.threadsX();
        if (action.getActionType() == MouseActionType.SCROLL_UP
                || action.getActionType() == MouseActionType.SCROLL_DOWN) {
            int delta = action.getActionType() == MouseActionType.SCROLL_DOWN ? 3 : -3;
            if (inSidebar) {
                return () -> {
                    _browsePane = BrowsePane.SIDEBAR;
                    moveSidebarTo(Math.max(0, Math.min(_sidebarSelection + delta, _sidebarRows.size() - 1)));
                };
            }
            if (inThreads) {
                return () -> {
                    _browsePane = BrowsePane.THREADS;
                    moveSelection(delta);
                };
            }
            return null;
        }
        if (localY < layout.bodyTop() || localY >= layout.bodyTop() + layout.bodyHeight()) {
            return null;
        }
        int row = localY - layout.bodyTop();
        if (inSidebar) {
            if (row == 0) {
                return () -> {
                    _browsePane = BrowsePane.SIDEBAR;
                    setNeedsRedraw();
                };
            }
            int index = _sidebarScrollOffset + row - 1;
            if (index < 0 || index >= _sidebarRows.size()) {
                return null;
            }
            return () -> {
                _browsePane = BrowsePane.SIDEBAR;
                moveSidebarTo(index);
            };
        }
        if (inThreads) {
            if (row == 0) {
                return () -> {
                    _browsePane = BrowsePane.THREADS;
                    setNeedsRedraw();
                };
            }
            int index = _threadScrollOffset + row - 1;
            if (index < 0 || index >= visibleThreadRows().size()) {
                return null;
            }
            return () -> {
                _browsePane = BrowsePane.THREADS;
                moveTo(index);
            };
        }
        return null;
    }

    private Runnable composeMouseAction(MouseAction action, int localX, int localY) {
        if (action.getActionType() == MouseActionType.SCROLL_UP
                || action.getActionType() == MouseActionType.SCROLL_DOWN) {
            int delta = action.getActionType() == MouseActionType.SCROLL_DOWN ? 1 : -1;
            return () -> {
                if (_composeField == ComposeField.BODY) {
                    moveComposeVertical(delta);
                } else {
                    advanceComposeField(delta);
                }
            };
        }
        if (localY <= 0) {
            return null;
        }
        int row = localY - 1;
        return () -> selectComposeFieldForMouse(row, localX);
    }

    private void selectComposeFieldForMouse(int row, int localX) {
        switch (row) {
        case 1 -> {
            _composeField = ComposeField.TO;
            _composeToCursor = cursorForMouse(localX, "To: ", _composeTo.length());
        }
        case 2 -> {
            _composeField = ComposeField.CC;
            _composeCcCursor = cursorForMouse(localX, "Cc: ", _composeCc.length());
        }
        case 3 -> {
            _composeField = ComposeField.BCC;
            _composeBccCursor = cursorForMouse(localX, "Bcc: ", _composeBcc.length());
        }
        case 4 -> {
            _composeField = ComposeField.SUBJECT;
            _composeSubjectCursor = cursorForMouse(localX, "Subject: ", _composeSubject.length());
        }
        default -> {
            if (row >= 5) {
                _composeField = ComposeField.BODY;
                int bodyRow = Math.max(0, row - 6);
                _composeBodyRow = Math.max(0, Math.min(bodyRow, _composeBody.size() - 1));
                _composeBodyColumn = Math.max(0, Math.min(localX - 1, _composeBody.get(_composeBodyRow).length()));
            }
        }
        }
        setNeedsRedraw();
    }

    private int cursorForMouse(int localX, String prefix, int length) {
        return Math.max(0, Math.min(localX - 1 - prefix.length(), length));
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (View parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    private void accumulateSidebarJumpDigit(Character character) {
        if (character == null || !Character.isDigit(character)) {
            return;
        }
        if (_sidebarJumpDigits.length() == 0 && character == '0') {
            return;
        }
        _sidebarJumpDigits.append(character);
        setNeedsRedraw();
    }

    private void applySidebarJump() {
        if (_sidebarJumpDigits.isEmpty()) {
            return;
        }
        int index;
        try {
            index = Integer.parseInt(_sidebarJumpDigits.toString()) - 1;
        } catch (NumberFormatException e) {
            _sidebarJumpDigits.setLength(0);
            return;
        }
        _sidebarJumpDigits.setLength(0);
        if (index < 0) {
            return;
        }
        _browsePane = BrowsePane.SIDEBAR;
        moveSidebarTo(index);
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

        if (_mode == Mode.COMPOSE) {
            drawCompose(rect, graphics, width, height);
            return;
        }

        drawBrowse(rect, graphics, width, height);
    }

    private void drawBrowse(
            Rect rect,
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            int width,
            int height) {
        drawMailHeader(graphics, rect.getPoint(), width, "Mail");

        BrowseLayout layout = browseLayout(width, height);
        int bodyTop = rect.getPoint().getY() + layout.bodyTop();
        if (hasSearchLine()) {
            drawSearchLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1), width);
        }
        if (layout.statusVisible()) {
            drawMailStatus(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + layout.statusY()), width);
        }

        for (int row = 0; row < layout.bodyHeight(); row++) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX() + layout.separatorX(), bodyTop + row), 1,
                    AttributedString.create("│", UiTheme.TEXT_SUBTLE, UiTheme.MAIL_SECTION_BACKGROUND),
                    UiTheme.TEXT_SUBTLE, UiTheme.MAIL_SECTION_BACKGROUND);
        }

        drawSidebarColumn(graphics, rect.getPoint().getX(), bodyTop, layout.sidebarWidth(), layout.bodyHeight());
        drawThreadTable(graphics, rect.getPoint().getX() + layout.threadsX(), bodyTop, layout.threadsWidth(), layout.bodyHeight());
        drawAuthOverlay(graphics, rect, width, height);
    }

    private void drawCompose(
            Rect rect,
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            int width,
            int height) {
        drawMailHeader(graphics, rect.getPoint(), width, "Compose");

        var lines = buildComposeLines(width);
        int row = 0;
        while (row < height - 1) {
            String line = row < lines.size() ? lines.get(row) : "";
            boolean active = isActiveComposeRow(row);
            TextColor background = active ? UiTheme.MAIL_COMPOSE_FIELD_BACKGROUND : mailRowBackground(false, row);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + row), width,
                    composeLine(line, background, active),
                    UiTheme.TEXT_MUTED, background);
            row++;
        }

        if (!_statusMessage.isBlank()) {
            drawMailStatus(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + height - 1), width);
        }
    }

    private void drawMailHeader(
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            Point point,
            int width,
            String title) {
        var header = new AttributedString();
        header.append(" " + title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND);
        if (_allUnreadCount > 0) {
            header.append(" " + _allUnreadCount + " unread ", UiTheme.MAIL_UNREAD_FOREGROUND,
                    UiTheme.MAIL_HEADER_BACKGROUND);
        }
        UiTheme.drawLine(graphics, point, width, header, UiTheme.TEXT_MUTED, UiTheme.MAIL_HEADER_BACKGROUND);
    }

    private void drawSearchLine(com.googlecode.lanterna.graphics.TextGraphics graphics, Point point, int width) {
        var line = new AttributedString();
        if (_mode == Mode.SEARCH) {
            line.append(" Search ", UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_STATUS_BACKGROUND);
            line.append(withCursor(_searchInput.toString(), _searchCursor), UiTheme.TEXT_PRIMARY,
                    UiTheme.MAIL_STATUS_BACKGROUND);
        } else {
            line.append(" Filter ", UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_STATUS_BACKGROUND);
            line.append(_searchQuery, UiTheme.MAIL_UNREAD_FOREGROUND, UiTheme.MAIL_STATUS_BACKGROUND);
            line.append("  " + _threads.size() + "/" + _totalThreadCount + " loaded", UiTheme.TEXT_MUTED,
                    UiTheme.MAIL_STATUS_BACKGROUND);
        }
        UiTheme.drawLine(graphics, point, width, line, UiTheme.TEXT_MUTED, UiTheme.MAIL_STATUS_BACKGROUND);
    }

    private void drawMailStatus(com.googlecode.lanterna.graphics.TextGraphics graphics, Point point, int width) {
        var status = new AttributedString();
        status.append(" Status ", UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_STATUS_BACKGROUND);
        status.append(UiTheme.fit(_statusMessage, Math.max(0, width - status.length() - 1)),
                UiTheme.MAIL_UNREAD_FOREGROUND, UiTheme.MAIL_STATUS_BACKGROUND);
        UiTheme.drawLine(graphics, point, width, status, UiTheme.TEXT_MUTED, UiTheme.MAIL_STATUS_BACKGROUND);
    }

    private AttributedString sectionHeader(String title, boolean active) {
        var header = new AttributedString();
        TextColor foreground = active ? UiTheme.TEXT_ON_ACCENT : UiTheme.ACCENT_BLUE;
        TextColor background = active ? UiTheme.PANEL_SELECTION_BACKGROUND : UiTheme.MAIL_SECTION_BACKGROUND;
        header.append(" " + title + " ", foreground, background);
        return header;
    }

    private BrowseLayout browseLayout(int width, int height) {
        int bodyTop = 1;
        int bodyHeight = Math.max(0, height - 1);
        if (hasSearchLine()) {
            bodyTop++;
            bodyHeight = Math.max(0, bodyHeight - 1);
        }
        boolean statusVisible = !_statusMessage.isBlank() && bodyHeight > 1;
        int statusY = Math.max(0, height - 1);
        if (statusVisible) {
            bodyHeight = Math.max(0, bodyHeight - 1);
        }
        int sidebarWidth = sidebarWidth(width);
        int separatorX = sidebarWidth;
        int threadsX = separatorX + 1;
        int threadsWidth = Math.max(0, width - sidebarWidth - 1);
        return new BrowseLayout(bodyTop, bodyHeight, sidebarWidth, separatorX, threadsX, threadsWidth,
                statusVisible, statusY);
    }

    private boolean hasSearchLine() {
        return _mode == Mode.SEARCH || !_searchQuery.isBlank();
    }

    private int sidebarWidth(int width) {
        int sidebarWidth = Math.max(24, Math.min(40, Math.max(24, width / 3)));
        if (width - sidebarWidth <= 8) {
            sidebarWidth = Math.max(1, width / 4);
        }
        return sidebarWidth;
    }

    private TextColor mailRowBackground(boolean selected, int row) {
        if (selected) {
            return UiTheme.PANEL_SELECTION_BACKGROUND;
        }
        return row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
    }

    private boolean isActiveComposeRow(int row) {
        return switch (_composeField) {
        case TO -> row == 1;
        case CC -> row == 2;
        case BCC -> row == 3;
        case SUBJECT -> row == 4;
        case BODY -> row >= 5;
        };
    }

    private AttributedString composeLine(String line, TextColor background, boolean active) {
        var output = new AttributedString();
        output.append(" ", UiTheme.TEXT_MUTED, background);
        int labelEnd = line.indexOf(':');
        if (labelEnd >= 0 && labelEnd < Math.min(line.length(), 12)) {
            output.append(line.substring(0, labelEnd + 1), active ? UiTheme.ACCENT_BLUE : UiTheme.TEXT_MUTED,
                    background);
            if (labelEnd + 1 < line.length()) {
                output.append(line.substring(labelEnd + 1), UiTheme.TEXT_PRIMARY, background);
            }
            return output;
        }
        output.append(line, active ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED, background);
        return output;
    }

    private List<String> buildComposeLines(int width) {
        var lines = new ArrayList<String>();
        lines.add("Account: " + safe(_composeAccountId, "(none)"));
        lines.add(prefixField("To: ", _composeTo.toString(), _composeField == ComposeField.TO, _composeToCursor));
        lines.add(prefixField("Cc: ", _composeCc.toString(), _composeField == ComposeField.CC, _composeCcCursor));
        lines.add(prefixField("Bcc: ", _composeBcc.toString(), _composeField == ComposeField.BCC, _composeBccCursor));
        lines.add(prefixField("Subject: ", _composeSubject.toString(), _composeField == ComposeField.SUBJECT,
                _composeSubjectCursor));
        if (_messageBufferContext != null) {
            lines.add("Body: edit in the right pane");
            return wrapComposeLines(lines, Math.max(1, width - 2));
        }
        lines.add("Body:");
        for (int i = 0; i < _composeBody.size(); i++) {
            String value = _composeBody.get(i).toString();
            if (_composeField == ComposeField.BODY && i == _composeBodyRow) {
                value = withCursor(value, _composeBodyColumn);
            }
            lines.add(value);
        }
        if (lines.size() < Math.max(6, getBounds().getSize().getHeight() - 1)) {
            lines.add("");
        }
        return wrapComposeLines(lines, Math.max(1, width - 2));
    }

    private List<String> wrapComposeLines(List<String> input, int width) {
        var lines = new ArrayList<String>();
        for (String line : input) {
            if (line.length() <= width) {
                lines.add(line);
                continue;
            }
            lines.addAll(TextPanelView.wrapText(line, width));
        }
        return lines;
    }

    private String prefixField(String prefix, String value, boolean active, int cursor) {
        String rendered = active ? withCursor(value, cursor) : value;
        return prefix + rendered;
    }

    private String withCursor(String value, int cursor) {
        int safeCursor = Math.max(0, Math.min(cursor, value.length()));
        return value.substring(0, safeCursor) + "▏" + value.substring(safeCursor);
    }

    private void refreshClient() {
        _awaitingOAuthCompletion = false;
        refreshClientAsync("Refreshing mail...");
    }

    private void close() {
        _awaitingOAuthCompletion = false;
        _oauthPollGeneration++;
        var window = Window.getInstance();
        if (window != null) {
            if (!window.hideCurrentWorkspaceWindow() && !window.closeCurrentWorkspaceWindow()) {
                window.hidePanel();
            }
        }
    }

    private void startComposeFromSelection() {
        _mode = Mode.COMPOSE;
        _composeField = ComposeField.TO;
        _composeTo = new StringBuilder();
        _composeCc = new StringBuilder();
        _composeBcc = new StringBuilder();
        _composeSubject = new StringBuilder();
        _composeBody = new ArrayList<>(List.of(new StringBuilder()));
        _composeToCursor = _composeTo.length();
        _composeCcCursor = _composeCc.length();
        _composeBccCursor = _composeBcc.length();
        _composeSubjectCursor = _composeSubject.length();
        _composeBodyRow = 0;
        _composeBodyColumn = 0;
        _composeInReplyToMessageId = "";
        _composeAccountId = selectedAccountId();
        _statusMessage = "";
        if (_messageBufferContext != null) {
            setMessageBufferContent("", false, true);
        }
        setNeedsRedraw();
    }

    private void focusSelectedMessageBuffer() {
        if (_mode != Mode.BROWSE || _browsePane != BrowsePane.THREADS || _messageBufferContext == null) {
            return;
        }
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.activateView(_messageBufferContext.getBufferView());
        window.switchToMode(window.getNormalMode());
    }

    private void startReply(boolean replyAll) {
        _mode = Mode.COMPOSE;
        _composeField = ComposeField.TO;
        _composeTo = new StringBuilder(replyAll ? replyAllRecipients() : replyRecipient());
        _composeCc = new StringBuilder();
        _composeBcc = new StringBuilder();
        _composeSubject = new StringBuilder(replySubject());
        _composeBody = new ArrayList<>(List.of(new StringBuilder(initialReplyBody())));
        _composeToCursor = _composeTo.length();
        _composeCcCursor = _composeCc.length();
        _composeBccCursor = _composeBcc.length();
        _composeSubjectCursor = _composeSubject.length();
        _composeBodyRow = 0;
        _composeBodyColumn = 0;
        _composeInReplyToMessageId = _selectedMessage == null ? "" : safe(_selectedMessage.internetMessageId(), "");
        _composeAccountId = selectedAccountId();
        _statusMessage = "";
        if (_messageBufferContext != null) {
            setMessageBufferContent(initialReplyBody(), false, true);
        }
        if (_messageBufferContext != null && Window.getInstance() != null) {
            Window.getInstance().activateView(_messageBufferContext.getBufferView());
        }
        setNeedsRedraw();
    }

    private void startSearch() {
        _mode = Mode.SEARCH;
        _searchInput = new StringBuilder(_searchQuery);
        _searchCursor = _searchInput.length();
        setNeedsRedraw();
    }

    private void applySearch() {
        _searchQuery = _searchInput.toString().trim();
        _mode = Mode.BROWSE;
        _selectedIndex = 0;
        _threadScrollOffset = 0;
        _detailScrollOffset = 0;
        reloadThreads();
        updateMessageBuffer();
        setNeedsRedraw();
    }

    private void cancelSearch() {
        _mode = Mode.BROWSE;
        _searchInput = new StringBuilder(_searchQuery);
        _searchCursor = _searchInput.length();
        setNeedsRedraw();
    }

    private void sendCompose() {
        if (!_sendInFlight.compareAndSet(false, true)) {
            announce("Mail send already in progress");
            return;
        }
        _statusMessage = "Sending mail...";
        setNeedsRedraw();
        MailDraft draft = new MailDraft(
                _composeAccountId,
                _composeTo.toString(),
                _composeCc.toString(),
                _composeBcc.toString(),
                _composeSubject.toString(),
                composeBodyText(),
                _composeInReplyToMessageId);
        Thread thread = new Thread(() -> {
            MailSendResult result = _client.sendDraft(draft);
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                _sendInFlight.set(false);
                _statusMessage = result.message();
                if (result.success()) {
                    _mode = Mode.BROWSE;
                    _browsePane = BrowsePane.THREADS;
                    reload();
                    focusThreadList();
                    if (!_refreshInFlight.get()) {
                        refreshClientAsync(result.message());
                    } else {
                        setNeedsRedraw();
                    }
                } else {
                    announce(result.message());
                }
            }));
        }, "mail-send");
        thread.setDaemon(true);
        thread.start();
    }

    private void focusThreadList() {
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.activateView(this);
        window.switchToMode(window.getNormalMode());
    }

    private void cancelCompose() {
        _mode = Mode.BROWSE;
        _statusMessage = "Compose cancelled";
        updateMessageBuffer();
        setNeedsRedraw();
    }

    private void openActionableUrl() {
        String url = actionableUrl();
        if (url == null) {
            announce("No URL available");
            return;
        }
        if (ExternalResourceSupport.openUrl(url)) {
            _awaitingOAuthCompletion = true;
            startOAuthCompletionPoll();
            announce("Opened URL. Waiting for sign-in to complete...");
            return;
        }
        _awaitingOAuthCompletion = false;
        _oauthPollGeneration++;
        announce(ExternalResourceSupport.copyText(url)
                ? "Could not open URL. Copied sign-in link for manual use"
                : "Could not open URL. Press y to copy the sign-in link");
    }

    private void copyActionableUrl() {
        String url = actionableUrl();
        if (url == null) {
            announce("No URL available");
            return;
        }
        announce(ExternalResourceSupport.copyText(url) ? "Copied URL" : "Copied URL to SWIM yank buffer only");
    }

    private void advanceComposeField(int delta) {
        int index = switch (_composeField) {
        case TO -> 0;
        case CC -> 1;
        case BCC -> 2;
        case SUBJECT -> 3;
        case BODY -> 4;
        };
        index = Math.floorMod(index + delta, 5);
        _composeField = switch (index) {
        case 0 -> ComposeField.TO;
        case 1 -> ComposeField.CC;
        case 2 -> ComposeField.BCC;
        case 3 -> ComposeField.SUBJECT;
        default -> ComposeField.BODY;
        };
        if (_composeField == ComposeField.BODY && _messageBufferContext != null && Window.getInstance() != null) {
            Window.getInstance().activateView(_messageBufferContext.getBufferView());
            Window.getInstance().switchToMode(Window.getInstance().getInputMode());
            return;
        }
        setNeedsRedraw();
    }

    private void moveComposeHorizontal(int delta) {
        switch (_composeField) {
        case TO -> _composeToCursor = clamp(_composeToCursor + delta, 0, _composeTo.length());
        case CC -> _composeCcCursor = clamp(_composeCcCursor + delta, 0, _composeCc.length());
        case BCC -> _composeBccCursor = clamp(_composeBccCursor + delta, 0, _composeBcc.length());
        case SUBJECT -> _composeSubjectCursor = clamp(_composeSubjectCursor + delta, 0, _composeSubject.length());
        case BODY -> {
            var line = _composeBody.get(_composeBodyRow);
            if (delta < 0 && _composeBodyColumn == 0 && _composeBodyRow > 0) {
                _composeBodyRow--;
                _composeBodyColumn = _composeBody.get(_composeBodyRow).length();
            } else if (delta > 0 && _composeBodyColumn == line.length() && _composeBodyRow < _composeBody.size() - 1) {
                _composeBodyRow++;
                _composeBodyColumn = 0;
            } else {
                _composeBodyColumn = clamp(_composeBodyColumn + delta, 0, line.length());
            }
        }
        }
        setNeedsRedraw();
    }

    private void moveComposeVertical(int delta) {
        if (_composeField == ComposeField.BODY) {
            _composeBodyRow = clamp(_composeBodyRow + delta, 0, _composeBody.size() - 1);
            _composeBodyColumn = clamp(_composeBodyColumn, 0, _composeBody.get(_composeBodyRow).length());
        } else {
            advanceComposeField(delta > 0 ? 1 : -1);
            return;
        }
        setNeedsRedraw();
    }

    private void insertComposeNewline() {
        if (_composeField != ComposeField.BODY) {
            advanceComposeField(1);
            return;
        }
        StringBuilder current = _composeBody.get(_composeBodyRow);
        String tail = current.substring(_composeBodyColumn);
        current.delete(_composeBodyColumn, current.length());
        _composeBody.add(_composeBodyRow + 1, new StringBuilder(tail));
        _composeBodyRow++;
        _composeBodyColumn = 0;
        setNeedsRedraw();
    }

    private void backspaceCompose() {
        switch (_composeField) {
        case TO -> {
            if (_composeToCursor > 0) {
                _composeTo.deleteCharAt(_composeToCursor - 1);
                _composeToCursor--;
            }
        }
        case CC -> {
            if (_composeCcCursor > 0) {
                _composeCc.deleteCharAt(_composeCcCursor - 1);
                _composeCcCursor--;
            }
        }
        case BCC -> {
            if (_composeBccCursor > 0) {
                _composeBcc.deleteCharAt(_composeBccCursor - 1);
                _composeBccCursor--;
            }
        }
        case SUBJECT -> {
            if (_composeSubjectCursor > 0) {
                _composeSubject.deleteCharAt(_composeSubjectCursor - 1);
                _composeSubjectCursor--;
            }
        }
        case BODY -> {
            if (_composeBodyColumn > 0) {
                _composeBody.get(_composeBodyRow).deleteCharAt(_composeBodyColumn - 1);
                _composeBodyColumn--;
            } else if (_composeBodyRow > 0) {
                int previousLength = _composeBody.get(_composeBodyRow - 1).length();
                _composeBody.get(_composeBodyRow - 1).append(_composeBody.get(_composeBodyRow));
                _composeBody.remove(_composeBodyRow);
                _composeBodyRow--;
                _composeBodyColumn = previousLength;
            }
        }
        }
        setNeedsRedraw();
    }

    private void insertComposeCharacter(Character character) {
        if (character == null) {
            return;
        }
        switch (_composeField) {
        case TO -> {
            _composeTo.insert(_composeToCursor, character);
            _composeToCursor++;
        }
        case CC -> {
            _composeCc.insert(_composeCcCursor, character);
            _composeCcCursor++;
        }
        case BCC -> {
            _composeBcc.insert(_composeBccCursor, character);
            _composeBccCursor++;
        }
        case SUBJECT -> {
            _composeSubject.insert(_composeSubjectCursor, character);
            _composeSubjectCursor++;
        }
        case BODY -> {
            _composeBody.get(_composeBodyRow).insert(_composeBodyColumn, character);
            _composeBodyColumn++;
        }
        }
        setNeedsRedraw();
    }

    private void moveSearchHorizontal(int delta) {
        _searchCursor = clamp(_searchCursor + delta, 0, _searchInput.length());
        setNeedsRedraw();
    }

    private void backspaceSearch() {
        if (_searchCursor <= 0) {
            return;
        }
        _searchInput.deleteCharAt(_searchCursor - 1);
        _searchCursor--;
        setNeedsRedraw();
    }

    private void insertSearchCharacter(Character character) {
        if (character == null) {
            return;
        }
        _searchInput.insert(_searchCursor, character);
        _searchCursor++;
        setNeedsRedraw();
    }

    private void moveSelection(int delta) {
        moveTo(_selectedIndex + delta);
    }

    private void moveSidebarSelection(int delta) {
        if (_sidebarRows.isEmpty()) {
            return;
        }
        moveSidebarTo(Math.floorMod(_sidebarSelection + delta, _sidebarRows.size()));
    }

    private void moveSidebarTo(int index) {
        if (_sidebarRows.isEmpty()) {
            _sidebarSelection = 0;
            return;
        }
        _sidebarSelection = Math.max(0, Math.min(index, _sidebarRows.size() - 1));
        _selectedIndex = 0;
        _threadScrollOffset = 0;
        reloadThreads();
        setNeedsRedraw();
    }

    private void moveToBottom() {
        int targetThreadCount = Math.max(_totalThreadCount, _threads.size());
        while (_threads.size() < targetThreadCount) {
            if (!appendThreadPage()) {
                break;
            }
        }
        int targetThreadIndex = Math.max(0, Math.min(targetThreadCount, _threads.size()) - 1);
        moveTo(rowIndexForThreadPosition(targetThreadIndex));
    }

    private void moveTo(int index) {
        ensureRowsLoadedThrough(index);
        var rows = visibleThreadRows();
        if (rows.isEmpty()) {
            _selectedIndex = 0;
            _selectedMessage = null;
            updateMessageBuffer();
            return;
        }
        _selectedIndex = Math.max(0, Math.min(index, rows.size() - 1));
        _detailScrollOffset = 0;
        loadSelectedMessage();
        prefetchThreadsIfNeeded();
        setNeedsRedraw();
    }

    private void scrollBody(int delta) {
        var lines = detailLines(Math.max(8, getBounds().getSize().getWidth() / 2));
        _detailScrollOffset = Math.max(0, Math.min(_detailScrollOffset + delta, Math.max(0, lines.size() - 1)));
        setNeedsRedraw();
    }

    private void reload() {
        _snapshot = _client.snapshot();
        _availableTags = _client.loadTagNames();
        _tagUnreadCounts = _client.loadTagUnreadCounts();
        _unsortedUnreadCount = _client.loadUnsortedUnreadCount();
        _allUnreadCount = _snapshot.accounts().stream().mapToInt(org.fisk.swim.mail.MailAccountSummary::unreadCount).sum();
        rebuildSidebarRows();
        String actionableStatus = actionableSnapshotStatus();
        _lastActionableUrl = firstUrl(actionableStatus);
        if (_awaitingOAuthCompletion && actionableStatus.contains("Mail sign-in complete")) {
            _awaitingOAuthCompletion = false;
            _oauthPollGeneration++;
            refreshClientAsync("Refreshing mail...");
            return;
        }
        reloadThreads();
        updateMessageBuffer();
        setNeedsRedraw();
    }

    private boolean shouldAutoRefreshOnOpen() {
        if (_snapshot == null) {
            return false;
        }
        if (_snapshot.accounts().isEmpty()) {
            return true;
        }
        return _snapshot.accounts().stream()
                .anyMatch(account -> account.lastSyncAt() == null || account.lastSyncAt().isBlank());
    }

    private void loadSelectedMessage() {
        var rows = visibleThreadRows();
        if (rows.isEmpty()) {
            _selectedMessage = null;
            return;
        }
        ThreadRow row = rows.get(Math.max(0, Math.min(_selectedIndex, rows.size() - 1)));
        long threadId = row.thread().threadId();
        long messageId = row.message().messageId();
        long generation = _messageLoadGeneration.incrementAndGet();
        _selectedMessage = placeholderMessageDetail(row);
        updateMessageBuffer();
        setNeedsRedraw();

        Thread worker = new Thread(() -> {
            if (_messageLoadGeneration.get() != generation) {
                return;
            }
            MailMessageDetail loaded = messageId > 0L
                    ? _client.loadMessageById(messageId)
                    : _client.loadMessage(threadId);
            Runnable applyLoadedMessage = () -> {
                var visibleRows = visibleThreadRows();
                if (_messageLoadGeneration.get() != generation || visibleRows.isEmpty()) {
                    return;
                }
                int safeIndex = Math.max(0, Math.min(_selectedIndex, visibleRows.size() - 1));
                ThreadRow safeRow = visibleRows.get(safeIndex);
                if (safeRow.thread().threadId() != threadId || safeRow.message().messageId() != messageId) {
                    return;
                }
                _selectedMessage = loaded;
                updateMessageBuffer();
                markDisplayedMessageRead(safeRow);
                setNeedsRedraw();
            };
            var eventThread = EventThread.getInstance();
            if (eventThread.isAlive()) {
                eventThread.enqueue(new RunnableEvent(applyLoadedMessage));
            } else {
                applyLoadedMessage.run();
            }
        }, "mail-load-message");
        worker.setDaemon(true);
        worker.start();
    }

    private void reloadThreads() {
        _threads = new ArrayList<>();
        _threadRows = new ArrayList<>();
        _threadMessagesByThreadId.clear();
        _totalThreadCount = 0;
        appendThreadPage();
        var rows = visibleThreadRows();
        if (_selectedIndex >= rows.size()) {
            _selectedIndex = Math.max(0, rows.size() - 1);
        }
        loadSelectedMessage();
    }

    private void ensureRowsLoadedThrough(int index) {
        if (index < 0) {
            return;
        }
        while (index >= visibleThreadRows().size()) {
            if (!appendThreadPage()) {
                break;
            }
        }
    }

    private void prefetchThreadsIfNeeded() {
        if (_selectedIndex >= visibleThreadRows().size() - THREAD_PREFETCH_THRESHOLD) {
            appendThreadPage();
        }
    }

    private int rowIndexForThreadPosition(int threadPosition) {
        var rows = visibleThreadRows();
        if (rows.isEmpty()) {
            return 0;
        }
        if (_threads.isEmpty()) {
            return Math.max(0, rows.size() - 1);
        }
        int safeThreadPosition = Math.max(0, Math.min(threadPosition, _threads.size() - 1));
        long threadId = _threads.get(safeThreadPosition).threadId();
        int rowIndex = -1;
        for (int i = 0; i < rows.size(); i++) {
            long rowThreadId = rows.get(i).thread().threadId();
            if (rowThreadId == threadId) {
                rowIndex = i;
            } else if (rowIndex >= 0) {
                break;
            }
        }
        return rowIndex >= 0 ? rowIndex : Math.max(0, Math.min(safeThreadPosition, rows.size() - 1));
    }

    private boolean appendThreadPage() {
        MailThreadPage page = _client.loadThreads(_searchQuery, _threads.size(), THREAD_PAGE_SIZE, activeThreadFilter());
        _totalThreadCount = page.totalCount();
        if (page.threads().isEmpty()) {
            return false;
        }
        var combined = new ArrayList<>(_threads);
        combined.addAll(page.threads());
        _threads = combined;
        var threadIds = page.threads().stream()
                .map(MailThreadSummary::threadId)
                .toList();
        _threadMessagesByThreadId.putAll(_client.loadThreadMessages(threadIds));
        for (MailThreadSummary thread : page.threads()) {
            _threadMessagesByThreadId.putIfAbsent(thread.threadId(), List.of());
        }
        rebuildThreadRows();
        return true;
    }

    private void rebuildThreadRows() {
        var rows = new ArrayList<ThreadRow>();
        for (MailThreadSummary thread : _threads) {
            rows.addAll(buildThreadRows(thread, _threadMessagesByThreadId.get(thread.threadId())));
        }
        _threadRows = List.copyOf(rows);
    }

    private void rebuildSidebarRows() {
        var rows = new ArrayList<SidebarRow>();
        rows.add(new SidebarRow(SidebarKind.UNSORTED, "unsorted", "#unsorted"));
        rows.add(new SidebarRow(SidebarKind.ALL, "all", "#all"));
        for (var account : _snapshot.accounts()) {
            rows.add(new SidebarRow(SidebarKind.ACCOUNT, account.id(), account.id()));
        }
        for (String tag : _availableTags) {
            rows.add(new SidebarRow(SidebarKind.TAG, tag, "#" + tag));
        }
        _sidebarRows = List.copyOf(rows);
        if (_sidebarSelection >= _sidebarRows.size()) {
            _sidebarSelection = Math.max(0, _sidebarRows.size() - 1);
        }
    }

    private List<ThreadRow> visibleThreadRows() {
        SidebarRow filter = _sidebarRows.isEmpty() ? null : _sidebarRows.get(Math.max(0, Math.min(_sidebarSelection, _sidebarRows.size() - 1)));
        if (filter == null || filter.kind() == SidebarKind.ALL) {
            return _threadRows;
        }
        var rows = new ArrayList<ThreadRow>();
        for (ThreadRow row : _threadRows) {
            if (matchesFilter(row.thread(), filter)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private MailThreadFilter activeThreadFilter() {
        SidebarRow filter = _sidebarRows.isEmpty() ? null : _sidebarRows.get(Math.max(0, Math.min(_sidebarSelection, _sidebarRows.size() - 1)));
        if (filter == null) {
            return MailThreadFilter.all();
        }
        return switch (filter.kind()) {
        case ALL -> MailThreadFilter.all();
        case UNSORTED -> MailThreadFilter.unsorted();
        case ACCOUNT -> MailThreadFilter.account(filter.value());
        case TAG -> MailThreadFilter.tag(filter.value());
        };
    }

    private boolean matchesFilter(MailThreadSummary thread, SidebarRow filter) {
        return switch (filter.kind()) {
        case ALL -> true;
        case UNSORTED -> thread.tags().isEmpty() || thread.addressedToAccount();
        case ACCOUNT -> filter.value().equals(thread.accountId());
        case TAG -> thread.tags().contains(filter.value());
        };
    }

    private List<ThreadRow> buildThreadRows(MailThreadSummary thread, List<MailMessageSummary> messages) {
        List<MailMessageSummary> effectiveMessages = messages == null || messages.isEmpty()
                ? List.of(syntheticMessageSummary(thread))
                : messages;
        var byId = new HashMap<Long, MailMessageSummary>();
        for (MailMessageSummary message : effectiveMessages) {
            byId.put(message.messageId(), message);
        }
        var childrenByParent = new LinkedHashMap<Long, List<MailMessageSummary>>();
        for (MailMessageSummary message : effectiveMessages) {
            long parentId = message.parentMessageId();
            if (parentId > 0L && !byId.containsKey(parentId)) {
                parentId = 0L;
            }
            childrenByParent.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(message);
        }
        var rows = new ArrayList<ThreadRow>();
        appendTreeRows(thread, childrenByParent, 0L, "", rows, effectiveMessages.size());
        return rows;
    }

    private void appendTreeRows(
            MailThreadSummary thread,
            Map<Long, List<MailMessageSummary>> childrenByParent,
            long parentId,
            String ancestorPrefix,
            List<ThreadRow> rows,
            int messageCount) {
        List<MailMessageSummary> children = childrenByParent.get(parentId);
        if (children == null || children.isEmpty()) {
            return;
        }
        children.sort((left, right) -> {
            int byTime = safe(left.receivedAt(), "").compareTo(safe(right.receivedAt(), ""));
            if (byTime != 0) {
                return byTime;
            }
            return Long.compare(left.messageId(), right.messageId());
        });
        for (int i = 0; i < children.size(); i++) {
            MailMessageSummary child = children.get(i);
            boolean last = i == children.size() - 1;
            String treePrefix = parentId == 0L
                    ? ""
                    : ancestorPrefix + (last ? "`- " : "|- ");
            rows.add(new ThreadRow(thread, child, treePrefix));
            String nextPrefix = parentId == 0L
                    ? ""
                    : ancestorPrefix + (last ? "   " : "|  ");
            appendTreeRows(thread, childrenByParent, child.messageId(), nextPrefix, rows, messageCount);
        }
    }

    private MailMessageSummary syntheticMessageSummary(MailThreadSummary thread) {
        return new MailMessageSummary(
                0L,
                thread.threadId(),
                0L,
                thread.subject(),
                thread.participants(),
                "",
                thread.receivedAt(),
                thread.snippet(),
                thread.unread());
    }

    private MailMessageDetail placeholderMessageDetail(ThreadRow row) {
        return new MailMessageDetail(
                row.message().messageId(),
                row.thread().threadId(),
                safe(row.message().subject(), "(no subject)"),
                safe(row.message().from(), "(unknown)"),
                safe(row.message().to(), "(unknown)"),
                safe(row.message().receivedAt(), ""),
                safe(row.message().snippet(), "Loading message..."),
                row.thread().tags());
    }

    private void markDisplayedMessageRead(ThreadRow row) {
        if (row == null || row.message().messageId() <= 0L || (!row.message().unread() && !row.thread().unread())) {
            return;
        }
        long messageId = row.message().messageId();
        long threadId = row.thread().threadId();
        String accountId = row.thread().accountId();
        List<String> tags = row.thread().tags();
        boolean addressedToAccount = row.thread().addressedToAccount();
        boolean wasUnread = row.message().unread() || row.thread().unread();
        Thread worker = new Thread(() -> {
            _client.markMessageRead(messageId);
            var eventThread = EventThread.getInstance();
            Runnable apply = () -> applyLocalReadState(threadId, messageId, accountId, tags, addressedToAccount, wasUnread);
            if (eventThread.isAlive()) {
                eventThread.enqueue(new RunnableEvent(apply));
            } else {
                apply.run();
            }
        }, "mail-mark-read");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyLocalReadState(
            long threadId,
            long messageId,
            String accountId,
            List<String> tags,
            boolean addressedToAccount,
            boolean wasUnread) {
        boolean changed = false;
        var updatedRows = new ArrayList<ThreadRow>(_threadRows.size());
        var threadUnread = new HashMap<Long, Boolean>();
        for (ThreadRow row : _threadRows) {
            MailMessageSummary message = row.message();
            if (message.messageId() == messageId && message.unread()) {
                message = new MailMessageSummary(
                        message.messageId(),
                        message.threadId(),
                        message.parentMessageId(),
                        message.subject(),
                        message.from(),
                        message.to(),
                        message.receivedAt(),
                        message.snippet(),
                        false);
                changed = true;
            }
            updatedRows.add(new ThreadRow(row.thread(), message, row.treePrefix()));
            threadUnread.merge(row.thread().threadId(), message.unread(), Boolean::logicalOr);
        }
        if (!changed && !wasUnread) {
            return;
        }
        var updatedThreads = new ArrayList<MailThreadSummary>(_threads.size());
        for (MailThreadSummary thread : _threads) {
            boolean unread = threadUnread.getOrDefault(thread.threadId(), false);
            updatedThreads.add(new MailThreadSummary(
                    thread.threadId(),
                    thread.accountId(),
                    thread.subject(),
                    thread.participants(),
                    thread.snippet(),
                    thread.receivedAt(),
                    unread,
                    thread.messageCount(),
                    thread.tags(),
                    thread.addressedToAccount()));
        }
        _threads = List.copyOf(updatedThreads);
        var finalRows = new ArrayList<ThreadRow>(updatedRows.size());
        for (ThreadRow row : updatedRows) {
            MailThreadSummary updatedThread = _threads.stream()
                    .filter(thread -> thread.threadId() == row.thread().threadId())
                    .findFirst()
                    .orElse(row.thread());
            finalRows.add(new ThreadRow(updatedThread, row.message(), row.treePrefix()));
        }
        _threadRows = List.copyOf(finalRows);
        _allUnreadCount = Math.max(0, _allUnreadCount - 1);
        _tagUnreadCounts = decrementTagUnreadCounts(_tagUnreadCounts, tags);
        if (tags.isEmpty() || addressedToAccount) {
            _unsortedUnreadCount = Math.max(0, _unsortedUnreadCount - 1);
        }
        _snapshot = new MailSnapshot(adjustAccountUnreadCounts(_snapshot.accounts(), accountId),
                _snapshot.threads(), _snapshot.statusMessage());
    }

    private static Map<String, Integer> decrementTagUnreadCounts(Map<String, Integer> counts, List<String> tags) {
        var updated = new LinkedHashMap<String, Integer>(counts);
        for (String tag : tags) {
            Integer current = updated.get(tag);
            if (current != null && current > 0) {
                updated.put(tag, current - 1);
            }
        }
        return updated;
    }

    private static List<org.fisk.swim.mail.MailAccountSummary> adjustAccountUnreadCounts(
            List<org.fisk.swim.mail.MailAccountSummary> accounts,
            String accountId) {
        var updated = new ArrayList<org.fisk.swim.mail.MailAccountSummary>(accounts.size());
        for (var account : accounts) {
            if (account.id().equals(accountId) && account.unreadCount() > 0) {
                updated.add(new org.fisk.swim.mail.MailAccountSummary(
                        account.id(),
                        account.name(),
                        account.protocol(),
                        account.threadCount(),
                        account.unreadCount() - 1,
                        account.lastSyncAt(),
                        account.syncStatus()));
            } else {
                updated.add(account);
            }
        }
        return List.copyOf(updated);
    }

    private void drawSidebarColumn(com.googlecode.lanterna.graphics.TextGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int row = 0;
        UiTheme.drawLine(graphics, Point.create(x, y + row), width,
                sectionHeader("Accounts & Tags", _browsePane == BrowsePane.SIDEBAR),
                UiTheme.TEXT_MUTED, UiTheme.MAIL_SECTION_BACKGROUND);
        row++;
        int availableRows = Math.max(0, height - row);
        adjustSidebarScroll(availableRows);
        for (int visible = 0; visible < availableRows && _sidebarScrollOffset + visible < _sidebarRows.size(); visible++) {
            int index = _sidebarScrollOffset + visible;
            SidebarRow sidebarRow = _sidebarRows.get(index);
            boolean selected = _browsePane == BrowsePane.SIDEBAR && index == _sidebarSelection;
            TextColor background = mailRowBackground(selected, visible);
            UiTheme.drawLine(graphics, Point.create(x, y + row + visible), width,
                    formatSidebarRow(index, sidebarRow, width, selected, background),
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_MUTED, background);
        }
    }

    private void drawThreadTable(com.googlecode.lanterna.graphics.TextGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        UiTheme.drawLine(graphics, Point.create(x, y), width,
                sectionHeader("Threads", _browsePane == BrowsePane.THREADS),
                UiTheme.TEXT_MUTED, UiTheme.MAIL_SECTION_BACKGROUND);
        int availableRows = Math.max(0, height - 1);
        adjustThreadScroll(availableRows);
        var rows = visibleThreadRows();
        for (int visible = 0; visible < availableRows; visible++) {
            int index = _threadScrollOffset + visible;
            boolean selected = _browsePane == BrowsePane.THREADS && index == _selectedIndex;
            TextColor background = mailRowBackground(selected, visible);
            AttributedString line = index < rows.size()
                    ? formatThreadTableRow(rows.get(index), width, selected, background)
                    : AttributedString.create("", UiTheme.TEXT_MUTED, background);
            UiTheme.drawLine(graphics, Point.create(x, y + 1 + visible), width,
                    line,
                    selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_MUTED, background);
        }
    }

    private List<String> detailLines(int width) {
        var lines = new ArrayList<String>();
        if (_selectedMessage == null) {
            lines.add(" Mail ");
            lines.addAll(TextPanelView.wrapText(emptyStateText(), Math.max(1, width - 2)));
            return lines;
        }
        lines.add(" Message ");
        lines.add("Subject: " + safe(_selectedMessage.subject(), "(no subject)"));
        lines.add("From: " + safe(_selectedMessage.from(), "(unknown)"));
        lines.add("To: " + safe(_selectedMessage.to(), "(unknown)"));
        if (_selectedMessage.cc() != null && !_selectedMessage.cc().isBlank()) {
            lines.add("Cc: " + _selectedMessage.cc());
        }
        lines.add("Date: " + safe(_selectedMessage.sentAt(), "(unknown)"));
        if (!_selectedMessage.tags().isEmpty()) {
            lines.add("Tags: " + String.join(", ", _selectedMessage.tags()));
        }
        lines.add("");
        lines.addAll(TextPanelView.wrapText(safe(_selectedMessage.bodyText(), ""), Math.max(1, width - 2)));
        return lines;
    }

    private String emptyStateText() {
        if (!_searchQuery.isBlank()) {
            return "No mail matched \"" + _searchQuery + "\".";
        }
        if (_snapshot != null && !_snapshot.statusMessage().isBlank()) {
            return _snapshot.statusMessage();
        }
        return "No mail is available yet. Configure accounts in " + _client.getDataPath();
    }

    private void adjustThreadScroll(int availableThreadRows) {
        var rows = visibleThreadRows();
        if (availableThreadRows <= 0) {
            _threadScrollOffset = 0;
            return;
        }
        if (_selectedIndex < _threadScrollOffset) {
            _threadScrollOffset = _selectedIndex;
        } else if (_selectedIndex >= _threadScrollOffset + availableThreadRows) {
            _threadScrollOffset = _selectedIndex - availableThreadRows + 1;
        }
        int maxOffset = Math.max(0, rows.size() - availableThreadRows);
        _threadScrollOffset = Math.max(0, Math.min(_threadScrollOffset, maxOffset));
    }

    private void adjustSidebarScroll(int availableRows) {
        if (availableRows <= 0) {
            _sidebarScrollOffset = 0;
            return;
        }
        if (_sidebarSelection < _sidebarScrollOffset) {
            _sidebarScrollOffset = _sidebarSelection;
        } else if (_sidebarSelection >= _sidebarScrollOffset + availableRows) {
            _sidebarScrollOffset = _sidebarSelection - availableRows + 1;
        }
        int maxOffset = Math.max(0, _sidebarRows.size() - availableRows);
        _sidebarScrollOffset = Math.max(0, Math.min(_sidebarScrollOffset, maxOffset));
    }

    private AttributedString formatSidebarRow(int index, SidebarRow row, int width, boolean selected, TextColor background) {
        String baseLabel = switch (row.kind()) {
        case ALL -> row.label();
        case UNSORTED -> row.label();
        case ACCOUNT -> row.label();
        case TAG -> row.label();
        };
        String label = (index + 1) + " " + baseLabel;
        int unread = switch (row.kind()) {
        case ALL -> _allUnreadCount;
        case UNSORTED -> _unsortedUnreadCount;
        case ACCOUNT -> _snapshot.accounts().stream()
                .filter(account -> row.value().equals(account.id()))
                .mapToInt(org.fisk.swim.mail.MailAccountSummary::unreadCount)
                .findFirst()
                .orElse(0);
        case TAG -> _tagUnreadCounts.getOrDefault(row.value(), 0);
        };
        var output = new AttributedString();
        TextColor labelForeground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND
                : unread > 0 ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED;
        TextColor countForeground = selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.MAIL_UNREAD_FOREGROUND;
        output.append(" ", labelForeground, background);
        if (unread <= 0) {
            output.append(truncateToWidth(label, Math.max(0, width - 1)), labelForeground, background);
            return output;
        }
        String suffix = "(" + unread + ")";
        int contentWidth = Math.max(0, width - 1);
        int leftWidth = Math.max(0, contentWidth - suffix.length() - 1);
        output.append(padRight(truncateToWidth(label, leftWidth), leftWidth), labelForeground, background);
        output.append(" ", UiTheme.TEXT_MUTED, background);
        output.append(suffix, countForeground, background);
        return output;
    }

    private AttributedString formatThreadTableRow(ThreadRow row, int width, boolean selected, TextColor background) {
        String timestamp = safe(row.message().receivedAt(), "");
        boolean unread = row.message().unread() || row.thread().unread();
        String unreadMarker = unread ? "● " : "  ";
        String rootPrefix = row.treePrefix().isEmpty() ? "" : row.treePrefix();
        String countSuffix = row.treePrefix().isEmpty() && row.thread().messageCount() > 1
                ? " (" + row.thread().messageCount() + ")"
                : "";
        String tagSuffix = row.treePrefix().isEmpty() && !row.thread().tags().isEmpty()
                ? " [" + String.join(",", row.thread().tags()) + "]"
                : "";
        String sender = safe(extractDisplayName(row.message().from()), "(unknown)");
        int contentWidth = Math.max(0, width - 1);
        int timestampWidth = timestamp.isBlank() ? 0 : Math.min(timestamp.length(), Math.max(0, width / 3));
        String timestampPart = timestampWidth == 0 ? "" : "  " + truncateToWidth(timestamp, timestampWidth);
        int leftWidth = timestampPart.isBlank() ? contentWidth : Math.max(0, contentWidth - timestampPart.length());
        var output = new AttributedString();
        output.append(" ", selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_MUTED, background);
        int used = 0;
        TextColor subjectForeground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND
                : unread ? UiTheme.MAIL_UNREAD_FOREGROUND : UiTheme.TEXT_PRIMARY;
        TextColor metadataForeground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_SUBTLE;
        TextColor tagForeground = selected ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.MAIL_TAG_FOREGROUND;
        used += appendFitted(output, unreadMarker, leftWidth - used,
                unread && !selected ? UiTheme.MAIL_UNREAD_FOREGROUND : metadataForeground, background);
        used += appendFitted(output, rootPrefix, leftWidth - used, metadataForeground, background);
        used += appendFitted(output, safe(row.message().subject(), "(no subject)"), leftWidth - used,
                subjectForeground, background);
        used += appendFitted(output, countSuffix, leftWidth - used, metadataForeground, background);
        used += appendFitted(output, "  " + sender, leftWidth - used, metadataForeground, background);
        used += appendFitted(output, tagSuffix, leftWidth - used, tagForeground, background);
        if (!timestampPart.isBlank()) {
            if (used < leftWidth) {
                output.append(" ".repeat(leftWidth - used), metadataForeground, background);
            }
            output.append(timestampPart, metadataForeground, background);
        }
        return output;
    }

    private static int appendFitted(
            AttributedString output,
            String text,
            int remaining,
            TextColor foreground,
            TextColor background) {
        String fitted = truncateToWidth(text, remaining);
        if (!fitted.isEmpty()) {
            output.append(fitted, foreground, background);
        }
        return fitted.length();
    }

    private static String extractDisplayName(String from) {
        String text = safe(from, "").trim();
        int start = text.indexOf('<');
        if (start > 0) {
            return text.substring(0, start).trim();
        }
        return text;
    }

    private static String truncateToWidth(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        if (width <= 1) {
            return text.substring(0, width);
        }
        return text.substring(0, width - 1) + "…";
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private int cachedMessageCount() {
        if (_snapshot == null) {
            return 0;
        }
        return _snapshot.threads().stream().mapToInt(MailThreadSummary::messageCount).sum();
    }

    private String selectedAccountId() {
        var rows = visibleThreadRows();
        if (_selectedIndex >= 0 && _selectedIndex < rows.size()) {
            return safe(rows.get(_selectedIndex).thread().accountId(), "");
        }
        if (_sidebarSelection >= 0 && _sidebarSelection < _sidebarRows.size()) {
            SidebarRow row = _sidebarRows.get(_sidebarSelection);
            if (row.kind() == SidebarKind.ACCOUNT) {
                return safe(row.value(), "");
            }
        }
        if (!_snapshot.accounts().isEmpty()) {
            return safe(_snapshot.accounts().getFirst().id(), "");
        }
        return "";
    }

    private String replyRecipient() {
        if (_selectedMessage == null) {
            return "";
        }
        if (_selectedMessage.messageId() == 0L) {
            return "";
        }
        String from = safe(_selectedMessage.from(), "");
        int start = from.indexOf('<');
        int end = from.indexOf('>');
        if (start >= 0 && end > start) {
            return from.substring(start + 1, end).trim();
        }
        return from;
    }

    private String replySubject() {
        if (_selectedMessage == null) {
            return "";
        }
        String subject = safe(_selectedMessage.subject(), "");
        if (subject.toLowerCase(java.util.Locale.ROOT).startsWith("re:")) {
            return subject;
        }
        return subject.isBlank() ? "" : "Re: " + subject;
    }

    private String replyAllRecipients() {
        if (_selectedMessage == null || _selectedMessage.messageId() == 0L) {
            return "";
        }
        var recipients = new java.util.LinkedHashSet<String>();
        addRecipient(recipients, replyRecipient());
        for (String part : safe(_selectedMessage.to(), "").split(",")) {
            addRecipient(recipients, extractEmail(part));
        }
        return String.join(", ", recipients);
    }

    private static void addRecipient(java.util.Set<String> recipients, String value) {
        String email = extractEmail(value);
        if (!email.isBlank()) {
            recipients.add(email);
        }
    }

    private static String extractEmail(String value) {
        String text = safe(value, "").trim();
        int start = text.indexOf('<');
        int end = text.indexOf('>');
        if (start >= 0 && end > start) {
            text = text.substring(start + 1, end).trim();
        }
        return text;
    }

    private String composeBodyText() {
        if (_messageBufferContext != null) {
            return _messageBufferContext.getBuffer().getString();
        }
        return _composeBody.stream().map(StringBuilder::toString).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private void updateMessageBuffer() {
        if (_messageBufferContext == null) {
            return;
        }
        if (_mode == Mode.COMPOSE) {
            return;
        }
        setMessageBufferContent(renderSelectedMessageText(), true, false);
    }

    private String initialReplyBody() {
        if (_selectedMessage == null) {
            return "";
        }
        String header = safe(_selectedMessage.from(), "(unknown)")
                + " wrote this "
                + safe(_selectedMessage.sentAt(), "(unknown)")
                + ":";
        String body = safe(_selectedMessage.bodyText(), "");
        if (body.isBlank()) {
            return header + "\n>";
        }
        var quoted = new ArrayList<String>();
        for (String line : body.split("\\R", -1)) {
            quoted.add("> " + line);
        }
        return header + "\n" + String.join("\n", quoted);
    }

    private String renderSelectedMessageText() {
        if (_selectedMessage == null) {
            return emptyStateText();
        }
        var lines = new ArrayList<String>();
        lines.add("Subject: " + safe(_selectedMessage.subject(), "(no subject)"));
        lines.add("From: " + safe(_selectedMessage.from(), "(unknown)"));
        lines.add("To: " + safe(_selectedMessage.to(), "(unknown)"));
        if (_selectedMessage.cc() != null && !_selectedMessage.cc().isBlank()) {
            lines.add("Cc: " + _selectedMessage.cc());
        }
        lines.add("Date: " + safe(_selectedMessage.sentAt(), "(unknown)"));
        if (!_selectedMessage.tags().isEmpty()) {
            lines.add("Tags: " + String.join(", ", _selectedMessage.tags()));
        }
        lines.add("");
        lines.add(safe(_selectedMessage.bodyText(), ""));
        return String.join("\n", lines);
    }

    private void setMessageBufferContent(String text, boolean readOnly, boolean placeCursorAtEnd) {
        var buffer = _messageBufferContext.getBuffer();
        buffer.setReadOnly(false);
        if (buffer.getLength() > 0) {
            buffer.rawRemove(0, buffer.getLength());
        }
        if (text != null && !text.isEmpty()) {
            buffer.rawInsert(0, text);
        }
        buffer.setReadOnly(readOnly);
        _messageBufferContext.getTextLayout().calculate();
        buffer.getCursor().setPosition(placeCursorAtEnd ? buffer.getLength() : 0);
        _messageBufferContext.getBufferView().adaptViewToCursor();
        _messageBufferContext.getBufferView().setNeedsRedraw();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    static String firstUrl(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        var matcher = java.util.regex.Pattern.compile("https?://\\S+").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String url = matcher.group();
        while (!url.isEmpty()) {
            char tail = url.charAt(url.length() - 1);
            if (tail == '.' || tail == ',' || tail == ')' || tail == ']') {
                url = url.substring(0, url.length() - 1);
                continue;
            }
            break;
        }
        return url;
    }

    private void announce(String message) {
        _statusMessage = message;
        var window = Window.getInstance();
        if (window != null) {
            window.getCommandView().setMessage(message);
        }
        setNeedsRedraw();
    }

    private String actionableUrl() {
        if (_lastActionableUrl != null && !_lastActionableUrl.isBlank()) {
            return _lastActionableUrl;
        }
        return firstUrl(_statusMessage);
    }

    private void refreshClientAsync(String pendingMessage) {
        if (!_refreshInFlight.compareAndSet(false, true)) {
            return;
        }
        _statusMessage = pendingMessage;
        setNeedsRedraw();
        Thread thread = new Thread(() -> {
            try {
                _client.refresh();
                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                    _refreshInFlight.set(false);
                    reload();
                    String actionableStatus = actionableSnapshotStatus();
                    if (!actionableStatus.isBlank()) {
                        _statusMessage = actionableStatus;
                    } else if (_snapshot != null && !_snapshot.statusMessage().isBlank()) {
                        _statusMessage = _snapshot.statusMessage();
                    }
                    setNeedsRedraw();
                }));
            } catch (RuntimeException e) {
                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                    _refreshInFlight.set(false);
                    announce("Mail refresh failed: " + rootMessage(e));
                }));
            }
        }, "mail-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private void startOAuthCompletionPoll() {
        long generation = ++_oauthPollGeneration;
        Thread thread = new Thread(() -> {
            while (_awaitingOAuthCompletion && _oauthPollGeneration == generation) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!_awaitingOAuthCompletion || _oauthPollGeneration != generation) {
                    return;
                }
                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                    if (_awaitingOAuthCompletion && _oauthPollGeneration == generation && !_refreshInFlight.get()) {
                        refreshClientAsync("Checking sign-in...");
                    }
                }));
            }
        }, "mail-oauth-poll");
        thread.setDaemon(true);
        thread.start();
    }

    private String actionableSnapshotStatus() {
        if (_snapshot == null) {
            return "";
        }
        if (_snapshot.statusMessage() != null && !_snapshot.statusMessage().isBlank()) {
            return _snapshot.statusMessage();
        }
        for (var account : _snapshot.accounts()) {
            String status = account.syncStatus();
            if (status == null || status.isBlank()) {
                continue;
            }
            if (firstUrl(status) != null || status.contains("Mail sign-in complete")) {
                return status;
            }
        }
        return "";
    }

    private String authOverlayMessage() {
        String status = actionableSnapshotStatus();
        return status == null || status.isBlank() ? "" : status;
    }

    private void drawAuthOverlay(
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            Rect rect,
            int width,
            int height) {
        String message = authOverlayMessage();
        if (message.isBlank() || width < 24 || height < 8) {
            return;
        }
        int modalWidth = Math.max(24, Math.min(width - 4, Math.max(40, width * 2 / 3)));
        var wrapped = TextPanelView.wrapText(message, Math.max(1, modalWidth - 4));
        String footerText = firstUrl(message) != null
                ? " o open link  y copy link  e refresh "
                : " e refresh ";
        int modalHeight = Math.min(height - 2, wrapped.size() + 4);
        int modalX = rect.getPoint().getX() + Math.max(0, (width - modalWidth) / 2);
        int modalY = rect.getPoint().getY() + Math.max(1, (height - modalHeight) / 2);
        int bodyRows = Math.max(0, modalHeight - 3);

        String top = "┌" + "─".repeat(Math.max(0, modalWidth - 2)) + "┐";
        String bottom = "└" + "─".repeat(Math.max(0, modalWidth - 2)) + "┘";
        UiTheme.drawLine(graphics, Point.create(modalX, modalY), modalWidth,
                AttributedString.create(top, UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND),
                UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND);

        String title = UiTheme.padRight(" Mail Sign-In Required", Math.max(0, modalWidth - 2));
        UiTheme.drawLine(graphics, Point.create(modalX, modalY + 1), modalWidth,
                AttributedString.create("│" + title + "│", UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND),
                UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND);

        for (int i = 0; i < bodyRows; i++) {
            String bodyLine = i < wrapped.size() ? wrapped.get(i) : "";
            bodyLine = UiTheme.padRight(bodyLine, Math.max(0, modalWidth - 2));
            UiTheme.drawLine(graphics, Point.create(modalX, modalY + 2 + i), modalWidth,
                    AttributedString.create("│" + bodyLine + "│", UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED);
        }

        String footer = UiTheme.padRight(footerText, Math.max(0, modalWidth - 2));
        UiTheme.drawLine(graphics, Point.create(modalX, modalY + modalHeight - 2), modalWidth,
                AttributedString.create("│" + footer + "│", UiTheme.MAIL_UNREAD_FOREGROUND,
                        UiTheme.MAIL_STATUS_BACKGROUND),
                UiTheme.MAIL_UNREAD_FOREGROUND, UiTheme.MAIL_STATUS_BACKGROUND);
        UiTheme.drawLine(graphics, Point.create(modalX, modalY + modalHeight - 1), modalWidth,
                AttributedString.create(bottom, UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND),
                UiTheme.TEXT_ON_ACCENT, UiTheme.MAIL_HEADER_BACKGROUND);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
