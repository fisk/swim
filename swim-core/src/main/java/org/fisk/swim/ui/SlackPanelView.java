package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.slack.SlackChannelSummary;
import org.fisk.swim.slack.SlackClient;
import org.fisk.swim.slack.SlackDraft;
import org.fisk.swim.slack.SlackMessageEntry;
import org.fisk.swim.slack.SlackMessagePage;
import org.fisk.swim.slack.SlackMessageSummary;
import org.fisk.swim.slack.SlackSendResult;
import org.fisk.swim.slack.SlackSnapshot;
import org.fisk.swim.slack.SlackThreadDetail;
import org.fisk.swim.slack.SlackWorkspaceSummary;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class SlackPanelView extends View implements KeyBindingHintProvider {
    private static final int MESSAGE_PAGE_SIZE = 120;
    private static final int MESSAGE_PREFETCH_THRESHOLD = 20;

    private enum BrowsePane {
        SIDEBAR,
        MESSAGES
    }

    private enum SidebarRowKind {
        HEADER,
        WORKSPACE,
        CHANNEL
    }

    private enum Mode {
        BROWSE,
        COMPOSE
    }

    private record SidebarRow(SidebarRowKind kind, String workspaceId, String conversationId, String label) {
        boolean selectable() {
            return kind != SidebarRowKind.HEADER;
        }
    }

    private enum Action {
        NONE,
        MOVE_DOWN,
        MOVE_UP,
        MOVE_LEFT,
        MOVE_RIGHT,
        TOP,
        BOTTOM,
        REFRESH,
        CLOSE,
        OPEN_MESSAGE_BUFFER,
        START_COMPOSE,
        START_REPLY,
        SEND_COMPOSE,
        CANCEL_COMPOSE
    }

    private final SlackClient _client;
    private SlackSnapshot _snapshot = SlackSnapshot.empty();
    private final Map<String, List<SlackChannelSummary>> _channelsByWorkspaceId = new LinkedHashMap<>();
    private List<SidebarRow> _sidebarRows = List.of();
    private List<SlackMessageSummary> _messages = List.of();
    private boolean _messagePageHasMore;
    private int _totalMessageCount;
    private SlackThreadDetail _selectedThread;
    private int _selectedIndex;
    private int _sidebarSelection;
    private int _sidebarScrollOffset;
    private int _messageScrollOffset;
    private BrowsePane _browsePane = BrowsePane.MESSAGES;
    private Mode _mode = Mode.BROWSE;
    private String _selectedWorkspaceId = "";
    private String _selectedConversationId = "";
    private String _composeThreadTs = "";
    private String _statusMessage = "";
    private Action _pendingAction = Action.NONE;
    private final AtomicBoolean _refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean _messagePageInFlight = new AtomicBoolean();
    private final AtomicBoolean _sendInFlight = new AtomicBoolean();
    private final AtomicLong _messageLoadGeneration = new AtomicLong();
    private final AtomicLong _threadLoadGeneration = new AtomicLong();
    private BufferContext _messageBufferContext;

    public SlackPanelView(Rect bounds, SlackClient client) {
        super(bounds);
        _client = client;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
        reloadFromClient();
        refreshClientAsync("Loading Slack...");
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
        return _mode == Mode.COMPOSE ? "slack compose" : "slack browse";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        if (_mode == Mode.COMPOSE) {
            return List.of(
                    KeyBindingHint.of("<CTRL>-s", "Compose", "send"),
                    KeyBindingHint.of("<ESC>", "Compose", "cancel"),
                    KeyBindingHint.of("q", "Compose", "cancel"));
        }
        return List.of(
                KeyBindingHint.of("j", "Navigation", "move down"),
                KeyBindingHint.of("k", "Navigation", "move up"),
                KeyBindingHint.of("<DOWN>", "Navigation", "move down"),
                KeyBindingHint.of("<UP>", "Navigation", "move up"),
                KeyBindingHint.of("h", "Navigation", "sidebar"),
                KeyBindingHint.of("l", "Navigation", "messages"),
                KeyBindingHint.of("<LEFT>", "Navigation", "sidebar"),
                KeyBindingHint.of("<RIGHT>", "Navigation", "messages"),
                KeyBindingHint.of("g", "Navigation", "top"),
                KeyBindingHint.of("G", "Navigation", "bottom"),
                KeyBindingHint.of("<ENTER>", "Messages", "open buffer"),
                KeyBindingHint.of("c", "Compose", "new message"),
                KeyBindingHint.of("r", "Compose", "reply"),
                KeyBindingHint.of("e", "Workspace", "refresh"),
                KeyBindingHint.of("q", "Workspace", "close"),
                KeyBindingHint.of("<ESC>", "Workspace", "close"));
    }

    void triggerSend() {
        sendCompose();
    }

    @Override
    public org.fisk.swim.event.Response processEvent(org.fisk.swim.event.KeyStrokes events) {
        _pendingAction = Action.NONE;
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
                case Character -> switch (event.getCharacter()) {
                case 'q' -> Action.CANCEL_COMPOSE;
                default -> Action.NONE;
                };
                default -> Action.NONE;
                };
            }
            return _pendingAction == Action.NONE ? org.fisk.swim.event.Response.NO : org.fisk.swim.event.Response.YES;
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
        case 'h' -> Action.MOVE_LEFT;
        case 'l' -> Action.MOVE_RIGHT;
        case 'g' -> Action.TOP;
        case 'G' -> Action.BOTTOM;
        case 'e' -> Action.REFRESH;
        case 'c' -> Action.START_COMPOSE;
        case 'r' -> Action.START_REPLY;
        case 'q' -> Action.CLOSE;
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
            if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarSelection(1);
            } else {
                moveSelection(1);
            }
        }
        case MOVE_UP -> {
            if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarSelection(-1);
            } else {
                moveSelection(-1);
            }
        }
        case MOVE_LEFT -> {
            _browsePane = BrowsePane.SIDEBAR;
            setNeedsRedraw();
        }
        case MOVE_RIGHT -> {
            _browsePane = BrowsePane.MESSAGES;
            setNeedsRedraw();
        }
        case TOP -> {
            if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarTo(nextSelectableSidebarIndex(0, 1));
            } else {
                moveTo(0);
            }
        }
        case BOTTOM -> {
            if (_browsePane == BrowsePane.SIDEBAR) {
                moveSidebarTo(nextSelectableSidebarIndex(_sidebarRows.size() - 1, -1));
            } else {
                moveToBottom();
            }
        }
        case REFRESH -> refreshClientAsync("Refreshing Slack...");
        case CLOSE -> close();
        case OPEN_MESSAGE_BUFFER -> focusSelectedMessageBuffer();
        case START_COMPOSE -> startCompose(false);
        case START_REPLY -> startCompose(true);
        case SEND_COMPOSE -> sendCompose();
        case CANCEL_COMPOSE -> cancelCompose();
        default -> {
        }
        }
        _pendingAction = Action.NONE;
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

        var header = new AttributedString();
        header.append(_mode == Mode.COMPOSE ? " Slack Compose " : " Slack ",
                UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        String status = headerStatus();
        if (!status.isBlank()) {
            header.append(status, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        }
        UiTheme.drawLine(graphics, rect.getPoint(), width, header, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        int bodyTop = rect.getPoint().getY() + 1;
        int bodyHeight = Math.max(0, height - 1);
        int sidebarWidth = Math.max(28, Math.min(38, Math.max(28, width / 3)));
        if (width - sidebarWidth <= 12) {
            sidebarWidth = Math.max(1, width / 4);
        }
        int separatorX = rect.getPoint().getX() + sidebarWidth;
        int messagesX = separatorX + 1;
        int messagesWidth = Math.max(0, width - sidebarWidth - 1);

        for (int row = 0; row < bodyHeight; row++) {
            UiTheme.drawLine(graphics, Point.create(separatorX, bodyTop + row), 1,
                    AttributedString.create("│", UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED),
                    UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        }

        drawSidebarColumn(graphics, rect.getPoint().getX(), bodyTop, sidebarWidth, bodyHeight);
        drawMessageTable(graphics, messagesX, bodyTop, messagesWidth, bodyHeight);
    }

    private String headerStatus() {
        String status = _statusMessage == null ? "" : _statusMessage;
        if (_mode == Mode.COMPOSE) {
            String target = selectedConversationLabel();
            String suffix = _composeThreadTs.isBlank() ? "" : " thread";
            return " target " + target + suffix + joinStatus(status);
        }
        return joinStatus(status).stripLeading();
    }

    private static String joinStatus(String status) {
        return status == null || status.isBlank() ? "" : "  •  " + status;
    }

    private void refreshClientAsync(String statusMessage) {
        if (!_refreshInFlight.compareAndSet(false, true)) {
            announce("Slack refresh already in progress");
            return;
        }
        announce(statusMessage);
        Thread worker = new Thread(() -> {
            try {
                _client.refresh();
            } finally {
                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                    _refreshInFlight.set(false);
                    reloadFromClient();
                    if (_statusMessage.isBlank()) {
                        _statusMessage = "Slack refreshed";
                    }
                    setNeedsRedraw();
                }));
            }
        }, "slack-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void reloadFromClient() {
        _snapshot = _client.snapshot();
        _channelsByWorkspaceId.clear();
        for (SlackWorkspaceSummary workspace : safe(_snapshot.workspaces())) {
            var channels = new ArrayList<>(safe(_client.loadChannels(workspace.id())));
            channels.sort(Comparator
                    .comparing(SlackChannelSummary::kind, Comparator.nullsLast(String::compareTo))
                    .thenComparing(channel -> safe(channel.displayName(), safe(channel.name(), ""))));
            _channelsByWorkspaceId.put(workspace.id(), List.copyOf(channels));
        }
        if (_selectedWorkspaceId.isBlank() || workspaceSummary(_selectedWorkspaceId) == null) {
            _selectedWorkspaceId = firstWorkspaceId();
        }
        if (_selectedConversationId.isBlank() || channelSummary(_selectedWorkspaceId, _selectedConversationId) == null) {
            _selectedConversationId = firstConversationId(_selectedWorkspaceId);
        }
        rebuildSidebarRows();
        alignSidebarSelection();
        reloadMessagesAsync();
        setNeedsRedraw();
    }

    private void rebuildSidebarRows() {
        var rows = new ArrayList<SidebarRow>();
        rows.add(new SidebarRow(SidebarRowKind.HEADER, "", "", " Workspaces"));
        for (SlackWorkspaceSummary workspace : safe(_snapshot.workspaces())) {
            rows.add(new SidebarRow(SidebarRowKind.WORKSPACE, workspace.id(), "",
                    safe(workspace.label(), workspace.id())));
        }
        rows.add(new SidebarRow(SidebarRowKind.HEADER, "", "", " Channels"));
        for (SlackChannelSummary channel : safe(_channelsByWorkspaceId.get(_selectedWorkspaceId))) {
            rows.add(new SidebarRow(SidebarRowKind.CHANNEL, channel.workspaceId(), channel.channelId(),
                    safe(channel.displayName(), safe(channel.name(), channel.channelId()))));
        }
        _sidebarRows = List.copyOf(rows);
        if (_sidebarRows.size() == 2) {
            _sidebarSelection = 0;
        }
    }

    private void alignSidebarSelection() {
        if (_sidebarRows.isEmpty()) {
            _sidebarSelection = 0;
            return;
        }
        for (int i = 0; i < _sidebarRows.size(); i++) {
            SidebarRow row = _sidebarRows.get(i);
            if (row.kind() == SidebarRowKind.CHANNEL
                    && row.workspaceId().equals(_selectedWorkspaceId)
                    && row.conversationId().equals(_selectedConversationId)) {
                _sidebarSelection = i;
                return;
            }
        }
        for (int i = 0; i < _sidebarRows.size(); i++) {
            SidebarRow row = _sidebarRows.get(i);
            if (row.kind() == SidebarRowKind.WORKSPACE && row.workspaceId().equals(_selectedWorkspaceId)) {
                _sidebarSelection = i;
                return;
            }
        }
        _sidebarSelection = nextSelectableSidebarIndex(0, 1);
    }

    private void reloadMessagesAsync() {
        _messages = List.of();
        _messagePageHasMore = false;
        _totalMessageCount = 0;
        _selectedIndex = 0;
        _selectedThread = null;
        updateMessageBuffer();
        if (_selectedWorkspaceId.isBlank() || _selectedConversationId.isBlank()) {
            setNeedsRedraw();
            return;
        }
        long generation = _messageLoadGeneration.incrementAndGet();
        _messagePageInFlight.set(true);
        announce("Loading messages...");
        Thread worker = new Thread(() -> {
            SlackMessagePage page = _client.loadMessages(_selectedWorkspaceId, _selectedConversationId, 0, MESSAGE_PAGE_SIZE);
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                if (_messageLoadGeneration.get() != generation) {
                    return;
                }
                _messagePageInFlight.set(false);
                _messages = List.copyOf(safe(page.messages()));
                _messagePageHasMore = page.hasMore();
                _totalMessageCount = page.totalCount();
                _selectedIndex = Math.max(0, Math.min(_selectedIndex, Math.max(0, _messages.size() - 1)));
                loadSelectedThreadAsync();
                setNeedsRedraw();
            }));
        }, "slack-load-messages");
        worker.setDaemon(true);
        worker.start();
    }

    private void appendMessagePageAsync() {
        if (_messagePageInFlight.get() || !_messagePageHasMore || _selectedWorkspaceId.isBlank() || _selectedConversationId.isBlank()) {
            return;
        }
        long generation = _messageLoadGeneration.get();
        int offset = _messages.size();
        _messagePageInFlight.set(true);
        Thread worker = new Thread(() -> {
            SlackMessagePage page = _client.loadMessages(_selectedWorkspaceId, _selectedConversationId, offset, MESSAGE_PAGE_SIZE);
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                if (_messageLoadGeneration.get() != generation) {
                    return;
                }
                _messagePageInFlight.set(false);
                if (page.messages().isEmpty()) {
                    _messagePageHasMore = false;
                    return;
                }
                var combined = new ArrayList<>(_messages);
                combined.addAll(page.messages());
                _messages = List.copyOf(combined);
                _messagePageHasMore = page.hasMore();
                _totalMessageCount = page.totalCount();
                setNeedsRedraw();
            }));
        }, "slack-append-messages");
        worker.setDaemon(true);
        worker.start();
    }

    private void loadSelectedThreadAsync() {
        if (_messages.isEmpty()) {
            _selectedThread = null;
            updateMessageBuffer();
            return;
        }
        SlackMessageSummary message = _messages.get(Math.max(0, Math.min(_selectedIndex, _messages.size() - 1)));
        String threadTs = rootThreadTs(message);
        long generation = _threadLoadGeneration.incrementAndGet();
        _selectedThread = placeholderThread(message);
        updateMessageBuffer();
        setNeedsRedraw();
        Thread worker = new Thread(() -> {
            SlackThreadDetail thread = _client.loadThread(message.workspaceId(), message.conversationId(), threadTs);
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                if (_threadLoadGeneration.get() != generation || _messages.isEmpty()) {
                    return;
                }
                SlackMessageSummary selected = _messages.get(Math.max(0, Math.min(_selectedIndex, _messages.size() - 1)));
                if (!selected.workspaceId().equals(message.workspaceId())
                        || !selected.conversationId().equals(message.conversationId())
                        || !rootThreadTs(selected).equals(threadTs)) {
                    return;
                }
                _selectedThread = thread;
                updateMessageBuffer();
                setNeedsRedraw();
            }));
        }, "slack-load-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private SlackThreadDetail placeholderThread(SlackMessageSummary message) {
        return new SlackThreadDetail(
                message.workspaceId(),
                message.conversationId(),
                selectedConversationLabel(),
                rootThreadTs(message),
                List.of(new SlackMessageEntry(
                        message.ts(),
                        safe(message.userDisplayName(), "(unknown)"),
                        safe(message.sentAt(), ""),
                        safe(message.text(), ""))));
    }

    private void moveSidebarSelection(int delta) {
        moveSidebarTo(nextSelectableSidebarIndex(_sidebarSelection + delta, delta >= 0 ? 1 : -1));
    }

    private void moveSidebarTo(int index) {
        if (_sidebarRows.isEmpty()) {
            _sidebarSelection = 0;
            return;
        }
        int safeIndex = Math.max(0, Math.min(index, _sidebarRows.size() - 1));
        SidebarRow row = _sidebarRows.get(safeIndex);
        if (!row.selectable()) {
            return;
        }
        _sidebarSelection = safeIndex;
        boolean reloadMessages = false;
        if (row.kind() == SidebarRowKind.WORKSPACE) {
            if (!row.workspaceId().equals(_selectedWorkspaceId)) {
                _selectedWorkspaceId = row.workspaceId();
                _selectedConversationId = firstConversationId(_selectedWorkspaceId);
                rebuildSidebarRows();
                alignSidebarSelection();
                reloadMessages = true;
            }
        } else if (row.kind() == SidebarRowKind.CHANNEL) {
            if (!row.workspaceId().equals(_selectedWorkspaceId)) {
                _selectedWorkspaceId = row.workspaceId();
                reloadMessages = true;
            }
            if (!row.conversationId().equals(_selectedConversationId)) {
                _selectedConversationId = row.conversationId();
                reloadMessages = true;
            }
        }
        if (reloadMessages) {
            reloadMessagesAsync();
        } else {
            setNeedsRedraw();
        }
    }

    private int nextSelectableSidebarIndex(int candidate, int direction) {
        if (_sidebarRows.isEmpty()) {
            return 0;
        }
        int step = direction >= 0 ? 1 : -1;
        int index = Math.max(0, Math.min(candidate, _sidebarRows.size() - 1));
        while (index >= 0 && index < _sidebarRows.size()) {
            if (_sidebarRows.get(index).selectable()) {
                return index;
            }
            index += step;
        }
        return step > 0 ? Math.max(0, _sidebarRows.size() - 1) : 0;
    }

    private void moveSelection(int delta) {
        moveTo(_selectedIndex + delta);
    }

    private void moveToBottom() {
        if (_messages.isEmpty()) {
            return;
        }
        if (_messagePageHasMore) {
            appendMessagePageAsync();
        }
        moveTo(Math.max(0, _messages.size() - 1));
    }

    private void moveTo(int index) {
        if (_messages.isEmpty()) {
            _selectedIndex = 0;
            _selectedThread = null;
            updateMessageBuffer();
            return;
        }
        int safeIndex = Math.max(0, Math.min(index, _messages.size() - 1));
        _selectedIndex = safeIndex;
        loadSelectedThreadAsync();
        if (_selectedIndex >= _messages.size() - MESSAGE_PREFETCH_THRESHOLD) {
            appendMessagePageAsync();
        }
        setNeedsRedraw();
    }

    private void drawSidebarColumn(
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            int x,
            int y,
            int width,
            int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int availableRows = Math.max(0, height);
        adjustSidebarScroll(availableRows);
        for (int visible = 0; visible < availableRows; visible++) {
            int index = _sidebarScrollOffset + visible;
            SidebarRow row = index < _sidebarRows.size() ? _sidebarRows.get(index) : null;
            boolean selected = row != null && _browsePane == BrowsePane.SIDEBAR && index == _sidebarSelection;
            boolean activeWorkspace = row != null && row.kind() == SidebarRowKind.WORKSPACE
                    && row.workspaceId().equals(_selectedWorkspaceId);
            boolean activeChannel = row != null && row.kind() == SidebarRowKind.CHANNEL
                    && row.workspaceId().equals(_selectedWorkspaceId)
                    && row.conversationId().equals(_selectedConversationId);
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : row != null && row.kind() == SidebarRowKind.HEADER ? UiTheme.SURFACE_MUTED
                    : visible % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            TextColor foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND
                    : row != null && row.kind() == SidebarRowKind.HEADER ? UiTheme.TEXT_ON_ACCENT
                    : activeWorkspace || activeChannel ? UiTheme.PANEL_SELECTION_ACCENT : UiTheme.TEXT_PRIMARY;
            String line = row == null ? "" : formatSidebarRow(row, width - 1, activeWorkspace || activeChannel);
            UiTheme.drawLine(graphics, Point.create(x, y + visible), width,
                    AttributedString.create(" " + line, foreground, background),
                    foreground, background);
        }
    }

    private void drawMessageTable(
            com.googlecode.lanterna.graphics.TextGraphics graphics,
            int x,
            int y,
            int width,
            int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        UiTheme.drawLine(graphics, Point.create(x, y), width,
                AttributedString.create(" " + selectedConversationLabel(), UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_MUTED),
                UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
        int availableRows = Math.max(0, height - 1);
        adjustMessageScroll(availableRows);
        for (int visible = 0; visible < availableRows; visible++) {
            int index = _messageScrollOffset + visible;
            boolean selected = _browsePane == BrowsePane.MESSAGES && index == _selectedIndex;
            TextColor background = selected ? UiTheme.PANEL_SELECTION_BACKGROUND
                    : visible % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            TextColor foreground = selected ? UiTheme.PANEL_SELECTION_FOREGROUND : UiTheme.TEXT_PRIMARY;
            String line = index < _messages.size() ? formatMessageRow(_messages.get(index), width - 1) : "";
            if (_messages.isEmpty() && visible == 0) {
                line = truncateToWidth(emptyStateText(), width - 1);
            }
            UiTheme.drawLine(graphics, Point.create(x, y + 1 + visible), width,
                    AttributedString.create(" " + line, foreground, background),
                    foreground, background);
        }
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

    private void adjustMessageScroll(int availableRows) {
        if (availableRows <= 0) {
            _messageScrollOffset = 0;
            return;
        }
        if (_selectedIndex < _messageScrollOffset) {
            _messageScrollOffset = _selectedIndex;
        } else if (_selectedIndex >= _messageScrollOffset + availableRows) {
            _messageScrollOffset = _selectedIndex - availableRows + 1;
        }
        int maxOffset = Math.max(0, _messages.size() - availableRows);
        _messageScrollOffset = Math.max(0, Math.min(_messageScrollOffset, maxOffset));
    }

    private void startCompose(boolean reply) {
        if (_selectedWorkspaceId.isBlank() || _selectedConversationId.isBlank()) {
            announce("No Slack conversation selected");
            return;
        }
        _mode = Mode.COMPOSE;
        _composeThreadTs = reply ? selectedThreadTs() : "";
        _statusMessage = reply ? "Replying in thread..." : "Composing message...";
        if (_messageBufferContext != null) {
            setMessageBufferContent("", false, true);
            focusComposeBuffer();
        }
        setNeedsRedraw();
    }

    private void sendCompose() {
        if (_mode != Mode.COMPOSE) {
            return;
        }
        if (!_sendInFlight.compareAndSet(false, true)) {
            announce("Slack send already in progress");
            return;
        }
        String text = composeBodyText().trim();
        if (text.isBlank()) {
            _sendInFlight.set(false);
            announce("Slack message is empty");
            return;
        }
        announce("Sending Slack message...");
        SlackDraft draft = new SlackDraft(_selectedWorkspaceId, _selectedConversationId, _composeThreadTs, text);
        Thread worker = new Thread(() -> {
            SlackSendResult result = _client.sendMessage(draft);
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                _sendInFlight.set(false);
                _statusMessage = result.message();
                if (result.success()) {
                    _mode = Mode.BROWSE;
                    focusMessageList();
                    reloadMessagesAsync();
                } else {
                    setNeedsRedraw();
                }
            }));
        }, "slack-send");
        worker.setDaemon(true);
        worker.start();
    }

    private void cancelCompose() {
        _mode = Mode.BROWSE;
        _statusMessage = "Compose cancelled";
        updateMessageBuffer();
        focusMessageList();
        setNeedsRedraw();
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            if (!window.hideCurrentWorkspaceWindow() && !window.closeCurrentWorkspaceWindow()) {
                window.hidePanel();
            }
        }
    }

    private void focusSelectedMessageBuffer() {
        if (_mode != Mode.BROWSE || _messageBufferContext == null) {
            return;
        }
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.activateView(_messageBufferContext.getBufferView());
        window.switchToMode(window.getNormalMode());
    }

    private void focusComposeBuffer() {
        if (_messageBufferContext == null || Window.getInstance() == null) {
            return;
        }
        Window.getInstance().activateView(_messageBufferContext.getBufferView());
        Window.getInstance().switchToMode(Window.getInstance().getInputMode());
    }

    private void focusMessageList() {
        if (Window.getInstance() == null) {
            return;
        }
        Window.getInstance().activateView(this);
        Window.getInstance().switchToMode(Window.getInstance().getNormalMode());
    }

    private void updateMessageBuffer() {
        if (_messageBufferContext == null || _mode == Mode.COMPOSE) {
            return;
        }
        setMessageBufferContent(renderSelectedThreadText(), true, false);
    }

    private String composeBodyText() {
        return _messageBufferContext == null ? "" : _messageBufferContext.getBuffer().getString();
    }

    private String renderSelectedThreadText() {
        if (_selectedThread == null) {
            return emptyStateText();
        }
        var lines = new ArrayList<String>();
        lines.add("Workspace: " + selectedWorkspaceLabel());
        lines.add("Channel: " + selectedConversationLabel());
        lines.add("");
        for (SlackMessageEntry entry : safe(_selectedThread.messages())) {
            lines.add(safe(entry.userDisplayName(), "(unknown)") + "  " + safe(entry.sentAt(), ""));
            lines.add(safe(entry.text(), ""));
            lines.add("");
        }
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

    private String emptyStateText() {
        if (_snapshot.workspaces().isEmpty()) {
            return "No Slack workspaces are configured. Configure " + _client.getDataPath();
        }
        if (_selectedConversationId.isBlank()) {
            return "No Slack channels are available for " + selectedWorkspaceLabel() + ".";
        }
        if (_messagePageInFlight.get()) {
            return "Loading messages...";
        }
        if (_messages.isEmpty()) {
            return "No messages are available in " + selectedConversationLabel() + ".";
        }
        return safe(_snapshot.statusMessage(), "No Slack content is available.");
    }

    private String selectedThreadTs() {
        if (_messages.isEmpty()) {
            return "";
        }
        return rootThreadTs(_messages.get(Math.max(0, Math.min(_selectedIndex, _messages.size() - 1))));
    }

    private static String rootThreadTs(SlackMessageSummary message) {
        return safe(message.threadTs(), message.ts());
    }

    private String selectedWorkspaceLabel() {
        SlackWorkspaceSummary workspace = workspaceSummary(_selectedWorkspaceId);
        if (workspace == null) {
            return "(none)";
        }
        return safe(workspace.label(), workspace.id());
    }

    private String selectedConversationLabel() {
        SlackChannelSummary channel = channelSummary(_selectedWorkspaceId, _selectedConversationId);
        if (channel == null) {
            return "(no channel)";
        }
        return safe(channel.displayName(), safe(channel.name(), channel.channelId()));
    }

    private SlackWorkspaceSummary workspaceSummary(String workspaceId) {
        for (SlackWorkspaceSummary workspace : safe(_snapshot.workspaces())) {
            if (workspace.id().equals(workspaceId)) {
                return workspace;
            }
        }
        return null;
    }

    private SlackChannelSummary channelSummary(String workspaceId, String conversationId) {
        for (SlackChannelSummary channel : safe(_channelsByWorkspaceId.get(workspaceId))) {
            if (channel.channelId().equals(conversationId)) {
                return channel;
            }
        }
        return null;
    }

    private String firstWorkspaceId() {
        for (SlackWorkspaceSummary workspace : safe(_snapshot.workspaces())) {
            return workspace.id();
        }
        return "";
    }

    private String firstConversationId(String workspaceId) {
        for (SlackChannelSummary channel : safe(_channelsByWorkspaceId.get(workspaceId))) {
            return channel.channelId();
        }
        return "";
    }

    private String formatSidebarRow(SidebarRow row, int width, boolean active) {
        if (row.kind() == SidebarRowKind.HEADER) {
            return truncateToWidth(row.label(), width);
        }
        String marker = active ? "* " : "  ";
        return truncateToWidth(marker + row.label(), width);
    }

    private String formatMessageRow(SlackMessageSummary message, int width) {
        String timestamp = safe(message.sentAt(), "");
        String prefix = safe(message.userDisplayName(), "(unknown)");
        String replySuffix = message.replyCount() > 0 ? " (" + message.replyCount() + ")" : "";
        String left = prefix + replySuffix + "  " + safe(message.text(), "");
        if (timestamp.isBlank()) {
            return truncateToWidth(left, width);
        }
        int timestampWidth = Math.min(timestamp.length(), Math.max(0, width / 3));
        int leftWidth = Math.max(0, width - timestampWidth - 2);
        return padRight(truncateToWidth(left, leftWidth), leftWidth) + "  " + timestamp;
    }

    private void announce(String message) {
        _statusMessage = safe(message, "");
        setNeedsRedraw();
    }

    private static String truncateToWidth(String text, int width) {
        if (width <= 0) {
            return "";
        }
        if (text.length() <= width) {
            return text;
        }
        if (width == 1) {
            return text.substring(0, 1);
        }
        return text.substring(0, width - 1) + "…";
    }

    private static String padRight(String text, int width) {
        if (text.length() >= width) {
            return text;
        }
        return text + " ".repeat(width - text.length());
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
