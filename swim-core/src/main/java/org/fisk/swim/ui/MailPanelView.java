package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.fisk.swim.EventThread;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailDraft;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSendResult;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadPage;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class MailPanelView extends View {
    private static final int THREAD_PAGE_SIZE = 100;
    private static final int THREAD_PREFETCH_THRESHOLD = 10;

    private enum Mode {
        BROWSE,
        SEARCH,
        COMPOSE
    }

    private enum ComposeField {
        TO,
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
        INSERT_NEWLINE,
        BACKSPACE,
        INSERT_CHAR
    }

    private final MailClient _client;
    private MailSnapshot _snapshot;
    private List<MailThreadSummary> _threads = List.of();
    private int _totalThreadCount;
    private MailMessageDetail _selectedMessage;
    private int _selectedIndex;
    private int _threadScrollOffset;
    private int _detailScrollOffset;
    private Mode _mode = Mode.BROWSE;
    private ComposeField _composeField = ComposeField.TO;
    private String _composeAccountId = "";
    private StringBuilder _composeTo = new StringBuilder();
    private StringBuilder _composeSubject = new StringBuilder();
    private List<StringBuilder> _composeBody = new ArrayList<>(List.of(new StringBuilder()));
    private int _composeToCursor;
    private int _composeSubjectCursor;
    private int _composeBodyRow;
    private int _composeBodyColumn;
    private String _statusMessage = "";
    private String _searchQuery = "";
    private StringBuilder _searchInput = new StringBuilder();
    private int _searchCursor;
    private Action _pendingAction = Action.NONE;
    private Character _pendingCharacter;
    private final AtomicBoolean _refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean _sendInFlight = new AtomicBoolean();
    private final AtomicLong _messageLoadGeneration = new AtomicLong();
    private String _lastActionableUrl;
    private boolean _awaitingOAuthCompletion;
    private long _oauthPollGeneration;

    public MailPanelView(Rect bounds, MailClient client) {
        super(bounds);
        _client = client;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        reload();
        if (shouldAutoRefreshOnOpen()) {
            refreshClientAsync("Refreshing mail...");
        }
    }

    @Override
    public org.fisk.swim.event.Response processEvent(org.fisk.swim.event.KeyStrokes events) {
        _pendingAction = Action.NONE;
        _pendingCharacter = null;
        if (events.remaining() != 0) {
            return org.fisk.swim.event.Response.NO;
        }
        var event = events.current();
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

        _pendingAction = switch (event.getKeyType()) {
        case ArrowDown -> Action.MOVE_DOWN;
        case ArrowUp -> Action.MOVE_UP;
        case Escape -> Action.CLOSE;
        case Character -> switch (event.getCharacter()) {
        case 'j' -> Action.MOVE_DOWN;
        case 'k' -> Action.MOVE_UP;
        case 'g' -> Action.TOP;
        case 'G' -> Action.BOTTOM;
        case '/', '?' -> Action.START_SEARCH;
        case 'r' -> Action.REFRESH;
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
        switch (_pendingAction) {
        case MOVE_DOWN -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeVertical(1);
            } else {
                moveSelection(1);
            }
        }
        case MOVE_UP -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeVertical(-1);
            } else {
                moveSelection(-1);
            }
        }
        case TOP -> moveTo(0);
        case BOTTOM -> moveTo(Math.max(0, Math.max(_totalThreadCount, _threads.size()) - 1));
        case REFRESH -> refreshClient();
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
            }
        }
        case MOVE_RIGHT -> {
            if (_mode == Mode.COMPOSE) {
                moveComposeHorizontal(1);
            } else if (_mode == Mode.SEARCH) {
                moveSearchHorizontal(1);
            }
        }
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
        var header = new AttributedString();
        header.append(" Mail ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" j/k select  / search  c compose/reply  r refresh  d/u scroll body  o open-link  y copy-link  q close ",
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        int bodyTop = rect.getPoint().getY() + 1;
        int bodyHeight = Math.max(0, height - 1);
        if (_mode == Mode.SEARCH || !_searchQuery.isBlank()) {
            String line = _mode == Mode.SEARCH
                    ? " Search: " + withCursor(_searchInput.toString(), _searchCursor)
                    : " Filter: " + _searchQuery + "  (" + _threads.size() + "/" + _totalThreadCount + " loaded)";
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), bodyTop), width,
                    AttributedString.create(line, UiTheme.TEXT_PRIMARY, UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
            bodyTop++;
            bodyHeight = Math.max(0, bodyHeight - 1);
        }
        int leftWidth = Math.max(26, Math.min(width / 2, (int) Math.round(width * 0.38)));
        if (width - leftWidth <= 2) {
            leftWidth = Math.max(1, width / 2);
        }
        int separatorX = rect.getPoint().getX() + leftWidth;
        int rightX = separatorX + 1;
        int rightWidth = Math.max(0, width - leftWidth - 1);

        for (int row = 0; row < bodyHeight; row++) {
            UiTheme.drawLine(graphics, Point.create(separatorX, bodyTop + row), 1,
                    AttributedString.create("│", UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        }

        drawThreadColumn(graphics, rect.getPoint().getX(), bodyTop, leftWidth, bodyHeight);
        drawMessageColumn(graphics, rightX, bodyTop, rightWidth, bodyHeight);
    }

    private void drawCompose(
            Rect rect,
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            int width,
            int height) {
        var header = new AttributedString();
        header.append(" Compose ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        header.append(" Tab next  Shift-Tab prev  Ctrl-s send  Esc cancel ",
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var lines = buildComposeLines(width);
        int row = 0;
        while (row < height - 1) {
            String line = row < lines.size() ? lines.get(row) : "";
            TextColor background = row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + row), width,
                    AttributedString.create(" " + line, UiTheme.TEXT_PRIMARY, background),
                    UiTheme.TEXT_MUTED, background);
            row++;
        }

        if (!_statusMessage.isBlank()) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + height - 1), width,
                    AttributedString.create(" " + _statusMessage, UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        }
    }

    private List<String> buildComposeLines(int width) {
        var lines = new ArrayList<String>();
        lines.add("Account: " + safe(_composeAccountId, "(none)"));
        lines.add(prefixField("To: ", _composeTo.toString(), _composeField == ComposeField.TO, _composeToCursor));
        lines.add(prefixField("Subject: ", _composeSubject.toString(), _composeField == ComposeField.SUBJECT,
                _composeSubjectCursor));
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
            window.hidePanel();
        }
    }

    private void startComposeFromSelection() {
        _mode = Mode.COMPOSE;
        _composeField = ComposeField.TO;
        _composeTo = new StringBuilder(replyRecipient());
        _composeSubject = new StringBuilder(replySubject());
        _composeBody = new ArrayList<>(List.of(new StringBuilder()));
        _composeToCursor = _composeTo.length();
        _composeSubjectCursor = _composeSubject.length();
        _composeBodyRow = 0;
        _composeBodyColumn = 0;
        _composeAccountId = selectedAccountId();
        _statusMessage = "";
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
                _composeSubject.toString(),
                composeBodyText());
        Thread thread = new Thread(() -> {
            MailSendResult result = _client.sendDraft(draft);
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                _sendInFlight.set(false);
                _statusMessage = result.message();
                if (result.success()) {
                    _mode = Mode.BROWSE;
                    reload();
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

    private void cancelCompose() {
        _mode = Mode.BROWSE;
        _statusMessage = "Compose cancelled";
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
        case SUBJECT -> 1;
        case BODY -> 2;
        };
        index = Math.floorMod(index + delta, 3);
        _composeField = switch (index) {
        case 0 -> ComposeField.TO;
        case 1 -> ComposeField.SUBJECT;
        default -> ComposeField.BODY;
        };
        setNeedsRedraw();
    }

    private void moveComposeHorizontal(int delta) {
        switch (_composeField) {
        case TO -> _composeToCursor = clamp(_composeToCursor + delta, 0, _composeTo.length());
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

    private void moveTo(int index) {
        ensureThreadsLoadedThrough(index);
        if (_threads.isEmpty()) {
            _selectedIndex = 0;
            _selectedMessage = null;
            return;
        }
        _selectedIndex = Math.max(0, Math.min(index, _threads.size() - 1));
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
        _lastActionableUrl = firstUrl(_snapshot.statusMessage());
        if (_awaitingOAuthCompletion && _snapshot != null && _snapshot.statusMessage().contains("Mail sign-in complete")) {
            _awaitingOAuthCompletion = false;
            _oauthPollGeneration++;
            refreshClientAsync("Refreshing mail...");
            return;
        }
        reloadThreads();
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
        if (_threads.isEmpty()) {
            _selectedMessage = null;
            return;
        }
        MailThreadSummary thread = _threads.get(_selectedIndex);
        long threadId = thread.threadId();
        long generation = _messageLoadGeneration.incrementAndGet();
        _selectedMessage = placeholderMessageDetail(thread);
        setNeedsRedraw();

        Thread worker = new Thread(() -> {
            MailMessageDetail loaded = _client.loadMessage(threadId);
            Runnable applyLoadedMessage = () -> {
                if (_messageLoadGeneration.get() != generation || _threads.isEmpty()) {
                    return;
                }
                int safeIndex = Math.max(0, Math.min(_selectedIndex, _threads.size() - 1));
                if (_threads.get(safeIndex).threadId() != threadId) {
                    return;
                }
                _selectedMessage = loaded;
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
        _totalThreadCount = 0;
        appendThreadPage();
        if (_selectedIndex >= _threads.size()) {
            _selectedIndex = Math.max(0, _threads.size() - 1);
        }
        loadSelectedMessage();
    }

    private void ensureThreadsLoadedThrough(int index) {
        if (index < 0) {
            return;
        }
        while (index >= _threads.size() && hasMoreThreads()) {
            if (!appendThreadPage()) {
                break;
            }
        }
    }

    private void prefetchThreadsIfNeeded() {
        if (_selectedIndex >= _threads.size() - THREAD_PREFETCH_THRESHOLD) {
            appendThreadPage();
        }
    }

    private boolean appendThreadPage() {
        if (!_threads.isEmpty() && !hasMoreThreads()) {
            return false;
        }
        MailThreadPage page = _client.loadThreads(_searchQuery, _threads.size(), THREAD_PAGE_SIZE);
        _totalThreadCount = page.totalCount();
        if (page.threads().isEmpty()) {
            return false;
        }
        var combined = new ArrayList<>(_threads);
        combined.addAll(page.threads());
        _threads = combined;
        return true;
    }

    private boolean hasMoreThreads() {
        return _threads.size() < _totalThreadCount;
    }

    private MailMessageDetail placeholderMessageDetail(MailThreadSummary thread) {
        return new MailMessageDetail(
                0L,
                thread.threadId(),
                safe(thread.subject(), "(no subject)"),
                safe(thread.participants(), "(unknown)"),
                "",
                safe(thread.receivedAt(), ""),
                safe(thread.snippet(), "Loading message..."),
                thread.tags());
    }

    private void drawThreadColumn(com.googlecode.lanterna.graphics.TextGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        var accountLines = buildAccountLines();
        int row = 0;
        UiTheme.drawLine(graphics, Point.create(x, y + row), width,
                AttributedString.create(" Accounts", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_MUTED),
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        row++;
        for (String line : accountLines) {
            if (row >= height) {
                return;
            }
            TextColor background = row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.drawLine(graphics, Point.create(x, y + row), width,
                    AttributedString.create(" " + line, UiTheme.TEXT_PRIMARY, background),
                    UiTheme.TEXT_MUTED, background);
            row++;
        }
        if (row >= height) {
            return;
        }
        UiTheme.drawLine(graphics, Point.create(x, y + row), width,
                AttributedString.create(" Threads", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_MUTED),
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        row++;

        int availableThreadRows = Math.max(0, height - row);
        adjustThreadScroll(availableThreadRows);
        for (int visible = 0; visible < availableThreadRows && _threadScrollOffset + visible < _threads.size(); visible++) {
            int threadIndex = _threadScrollOffset + visible;
            MailThreadSummary thread = _threads.get(threadIndex);
            boolean selected = threadIndex == _selectedIndex;
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : visible % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            TextColor foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
            String line = formatThread(thread, width);
            UiTheme.drawLine(graphics, Point.create(x, y + row + visible), width,
                    AttributedString.create(line, foreground, background), foreground, background);
        }
    }

    private void drawMessageColumn(com.googlecode.lanterna.graphics.TextGraphics graphics, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        var lines = detailLines(width);
        int row = 0;
        while (row < height) {
            int lineIndex = _detailScrollOffset + row;
            String line = lineIndex < lines.size() ? lines.get(lineIndex) : "";
            TextColor background = row == 0 ? UiTheme.SURFACE_MUTED
                    : row % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            TextColor foreground = row == 0 ? UiTheme.TEXT_ON_ACCENT : UiTheme.TEXT_PRIMARY;
            UiTheme.drawLine(graphics, Point.create(x, y + row), width,
                    AttributedString.create((row == 0 ? "" : " ") + line, foreground, background),
                    foreground, background);
            row++;
        }
    }

    private List<String> buildAccountLines() {
        if (_snapshot.accounts().isEmpty()) {
            return List.of("No accounts configured");
        }
        var lines = new ArrayList<String>();
        for (var account : _snapshot.accounts()) {
            StringBuilder line = new StringBuilder()
                    .append(account.name())
                    .append(" [")
                    .append(account.protocol())
                    .append("]  ")
                    .append(account.unreadCount())
                    .append("/")
                    .append(account.threadCount());
            if (account.syncStatus() != null && !account.syncStatus().isBlank()) {
                line.append("  ").append(account.syncStatus());
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String formatThread(MailThreadSummary thread, int width) {
        String unreadMarker = thread.unread() ? "● " : "  ";
        String countSuffix = thread.messageCount() > 1 ? " (" + thread.messageCount() + ")" : "";
        String tagSuffix = thread.tags().isEmpty() ? "" : " [" + String.join(",", thread.tags()) + "]";
        String received = thread.receivedAt() == null || thread.receivedAt().isBlank() ? "" : " • " + thread.receivedAt();
        String base = unreadMarker + safe(thread.subject(), "(no subject)") + countSuffix + received + tagSuffix;
        if (base.length() > width - 1) {
            return base.substring(0, Math.max(0, width - 2));
        }
        return base;
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
        if (availableThreadRows <= 0) {
            _threadScrollOffset = 0;
            return;
        }
        if (_selectedIndex < _threadScrollOffset) {
            _threadScrollOffset = _selectedIndex;
        } else if (_selectedIndex >= _threadScrollOffset + availableThreadRows) {
            _threadScrollOffset = _selectedIndex - availableThreadRows + 1;
        }
        int maxOffset = Math.max(0, _threads.size() - availableThreadRows);
        _threadScrollOffset = Math.max(0, Math.min(_threadScrollOffset, maxOffset));
    }

    private int cachedMessageCount() {
        if (_snapshot == null) {
            return 0;
        }
        return _snapshot.threads().stream().mapToInt(MailThreadSummary::messageCount).sum();
    }

    private String selectedAccountId() {
        if (_selectedIndex >= 0 && _selectedIndex < _threads.size()) {
            return safe(_threads.get(_selectedIndex).accountId(), "");
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

    private String composeBodyText() {
        return _composeBody.stream().map(StringBuilder::toString).reduce((a, b) -> a + "\n" + b).orElse("");
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
                    if (_snapshot != null && !_snapshot.statusMessage().isBlank()) {
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
