package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public class KeyMenuView extends View {
    private static final int MIN_HEIGHT = 2;
    private static final long SCROLL_INTERVAL_MILLIS = 220L;
    enum FocusContext {
        BUFFER,
        COMMAND,
        LIST_PANEL,
        SEARCH_PANEL,
        TEXT_PANEL,
        CHAT_PANEL,
        SHELL,
        PANEL,
        OTHER
    }

    private static final String ANY_CHARACTER = "<CHAR>";

    private final List<String> _path = new ArrayList<>();
    private MenuNode _rootNode = createRoot(List.of());
    private MenuNode _globalRootNode = createRoot(List.of());
    private MenuNode _currentNode = _rootNode;
    private boolean _globalChainActive;
    private String _modeName = "NORMAL";
    private boolean _bufferFocused = true;
    private FocusContext _focusContext = FocusContext.BUFFER;
    private String _contextLabel;
    private String _contextHint;
    private List<KeyBindingHint> _contextKeyHints = List.of();
    private List<KeyBindingHint> _globalKeyHints = List.of();
    private String _commandPrompt;
    private String _commandText;
    private boolean _chatPending;
    private List<String> _recentWindows = List.of();
    private Long _animationStepOverride;
    private final Timer _animationTimer;

    public KeyMenuView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.MENU_BACKGROUND);
        _animationTimer = new Timer("key-menu-animation", true);
        _animationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!animationTickApplies()) {
                    return;
                }
                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                    if (animationTickApplies()) {
                        setNeedsRedraw();
                    }
                }));
            }
        }, SCROLL_INTERVAL_MILLIS, SCROLL_INTERVAL_MILLIS);
    }

    public void close() {
        _animationTimer.cancel();
    }

    public void setModeName(String modeName) {
        if (Objects.equals(_modeName, modeName)) {
            return;
        }
        _modeName = modeName;
        resetChain();
        setNeedsRedraw();
    }

    public void setBufferFocused(boolean bufferFocused) {
        if (_bufferFocused == bufferFocused) {
            return;
        }
        _bufferFocused = bufferFocused;
        if (!bufferFocused) {
            resetChain();
        }
        setNeedsRedraw();
    }

    public void setFocusContext(FocusContext focusContext) {
        if (_focusContext == focusContext) {
            return;
        }
        _focusContext = focusContext;
        if (focusContext != FocusContext.BUFFER) {
            resetChain();
        }
        setNeedsRedraw();
    }

    public void setContextLabel(String contextLabel) {
        if (Objects.equals(_contextLabel, contextLabel)) {
            return;
        }
        _contextLabel = contextLabel;
        setNeedsRedraw();
    }

    public void setContextKeyHints(String contextHint, List<KeyBindingHint> keyHints) {
        var nextHints = keyHints == null ? List.<KeyBindingHint>of() : List.copyOf(keyHints);
        if (Objects.equals(_contextHint, contextHint) && Objects.equals(_contextKeyHints, nextHints)) {
            return;
        }
        _contextHint = contextHint;
        _contextKeyHints = nextHints;
        _rootNode = createRoot(combinedKeyHints());
        resetChain();
        setNeedsRedraw();
    }

    public void setGlobalKeyHints(List<KeyBindingHint> keyHints) {
        var nextHints = keyHints == null ? List.<KeyBindingHint>of() : List.copyOf(keyHints);
        if (Objects.equals(_globalKeyHints, nextHints)) {
            return;
        }
        _globalKeyHints = nextHints;
        _rootNode = createRoot(combinedKeyHints());
        _globalRootNode = createRoot(_globalKeyHints);
        resetChain();
        setNeedsRedraw();
    }

    public void setCommandState(String prompt, String commandText) {
        if (Objects.equals(_commandPrompt, prompt) && Objects.equals(_commandText, commandText)) {
            return;
        }
        _commandPrompt = prompt;
        _commandText = commandText;
        setNeedsRedraw();
    }

    public void setChatPending(boolean chatPending) {
        if (_chatPending == chatPending) {
            return;
        }
        _chatPending = chatPending;
        setNeedsRedraw();
    }

    public void setRecentWindows(List<String> recentWindows) {
        var next = recentWindows == null ? List.<String>of() : List.copyOf(recentWindows);
        if (Objects.equals(_recentWindows, next)) {
            return;
        }
        _recentWindows = next;
        setNeedsRedraw();
    }

    void setAnimationStepOverride(Long animationStepOverride) {
        _animationStepOverride = animationStepOverride;
        setNeedsRedraw();
    }

    public void resetChain() {
        _path.clear();
        _currentNode = _rootNode;
        _globalChainActive = false;
    }

    void observe(KeyStroke keyStroke) {
        String token = tokenFor(keyStroke);
        if (token == null) {
            return;
        }
        if (!isDiscoverabilityActive() && !_globalChainActive) {
            if (!_path.isEmpty()) {
                resetChain();
            }
            if (!advanceGlobal(token)) {
                setNeedsRedraw();
                return;
            }
            setNeedsRedraw();
            return;
        }

        if (!advance(token)) {
            resetChain();
            if (isDiscoverabilityActive()) {
                advance(token);
            } else {
                advanceGlobal(token);
            }
        }
        setNeedsRedraw();
    }

    AttributedString buildHeaderLine() {
        int width = Math.max(1, getBounds().getSize().getWidth());

        var line = new AttributedString();
        TextColorTracker colors = new TextColorTracker(UiTheme.MENU_BACKGROUND);

        UiTheme.appendSegment(line, "SWIM", UiTheme.MENU_BACKGROUND, UiTheme.MENU_ACCENT);
        colors.background(UiTheme.MENU_ACCENT);
        appendHeaderSegment(line, colors, _modeName, UiTheme.TEXT_ON_ACCENT, UiTheme.modeColor(_modeName));
        if (isChainDisplayActive()) {
            if (_path.isEmpty()) {
                appendHeaderSegment(line, colors, "discover", UiTheme.TEXT_PRIMARY, UiTheme.MENU_SEGMENT_BACKGROUND);
            } else {
                appendHeaderSegment(line, colors, "chain", UiTheme.TEXT_MUTED, UiTheme.MENU_SEGMENT_BACKGROUND);
                appendHeaderSegment(line, colors, String.join(" ", _path), UiTheme.MENU_CHAIN,
                        UiTheme.MENU_CONTEXT_BACKGROUND);
                if (_currentNode.summary() != null && !_currentNode.summary().isBlank()) {
                    appendHeaderSegment(line, colors, _currentNode.summary(), UiTheme.TEXT_PRIMARY,
                            UiTheme.MENU_SEGMENT_BACKGROUND);
                }
            }
        } else {
            appendHeaderSegment(line, colors, passiveHint(), UiTheme.TEXT_MUTED, UiTheme.MENU_SEGMENT_BACKGROUND);
            if (_contextLabel != null && !_contextLabel.isBlank()) {
                appendHeaderSegment(line, colors, _contextLabel, UiTheme.ACCENT_BLUE, UiTheme.MENU_CONTEXT_BACKGROUND);
            }
        }
        if (!_recentWindows.isEmpty()) {
            appendHeaderSegment(line, colors, String.join("  ", _recentWindows), UiTheme.TEXT_PRIMARY,
                    UiTheme.MENU_CONTEXT_BACKGROUND);
        }
        appendHeaderReset(line, colors);
        return line;
    }

    String bodyText() {
        return bodyContentText();
    }

    int preferredHeight(int width, int totalHeight) {
        if (width <= 0 || totalHeight <= 0) {
            return 0;
        }
        int maxMenuHeight = Math.max(1, totalHeight - 3);
        return Math.max(1, Math.min(MIN_HEIGHT, maxMenuHeight));
    }

    String getBreadcrumb() {
        return String.join(" ", _path);
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getGraphics();
        int width = rect.getSize().getWidth();
        UiTheme.drawLine(graphics, rect.getPoint(), width, buildHeaderLine(), UiTheme.TEXT_MUTED, UiTheme.MENU_BACKGROUND);
        var bodyLines = buildBodyLines(width);
        for (int i = 0; i < rect.getSize().getHeight() - 1 && i < bodyLines.size(); i++) {
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i), width,
                    bodyLines.get(i), UiTheme.TEXT_MUTED, UiTheme.MENU_SECONDARY_BACKGROUND);
        }
    }

    List<AttributedString> buildBodyLines(int width) {
        var lines = new ArrayList<AttributedString>();
        var line = new AttributedString();
        TextColorTracker colors = new TextColorTracker(UiTheme.MENU_SECONDARY_BACKGROUND);
        UiTheme.appendSegment(line, visibleBodyText(width), UiTheme.TEXT_PRIMARY, UiTheme.MENU_SEGMENT_BACKGROUND);
        colors.background(UiTheme.MENU_SEGMENT_BACKGROUND);
        appendBodyReset(line, colors);
        lines.add(line);
        return lines;
    }

    private boolean advance(String token) {
        MenuNode next = _currentNode.match(token);
        if (next == null) {
            return false;
        }
        _path.add(UiTheme.displayKey(token));
        _currentNode = next;
        if (_currentNode.isTerminal()) {
            resetChain();
        }
        return true;
    }

    private boolean advanceGlobal(String token) {
        MenuNode previous = _currentNode;
        boolean previousGlobal = _globalChainActive;
        if (_path.isEmpty()) {
            _currentNode = _globalRootNode;
            _globalChainActive = true;
        }
        boolean advanced = advance(token);
        if (!advanced) {
            _currentNode = previous;
            _globalChainActive = previousGlobal;
        }
        return advanced;
    }

    private String discoveryContentText() {
        var grouped = new LinkedHashMap<String, List<DiscoveryEntry>>();
        for (var entry : _currentNode.children().entrySet()) {
            MenuNode node = entry.getValue();
            if (!node.documented()) {
                continue;
            }
            grouped.computeIfAbsent(node.group(), ignored -> new ArrayList<>())
                    .add(new DiscoveryEntry(UiTheme.displayKey(entry.getKey()), node.summary()));
        }
        return groupedEntriesText(grouped, "No documented continuations for this prefix.");
    }

    private static String groupedEntriesText(Map<String, List<DiscoveryEntry>> grouped, String emptyText) {
        if (grouped.isEmpty()) {
            return emptyText;
        }
        var rows = new ArrayList<String>();
        for (var entry : grouped.entrySet()) {
            rows.add(entry.getKey() + "  " + entriesText(entry.getValue()));
        }
        return String.join("  •  ", rows);
    }

    private static String entriesText(List<DiscoveryEntry> entries) {
        StringBuilder current = new StringBuilder();
        for (var entry : entries) {
            String segment = entry.key() + " " + entry.summary();
            if (!current.isEmpty()) {
                current.append(" • ");
            }
            current.append(segment);
        }
        if (current.isEmpty()) {
            current.append("no documented keys");
        }
        return current.toString();
    }

    private long animationStep() {
        if (_animationStepOverride != null) {
            return _animationStepOverride.longValue();
        }
        return System.currentTimeMillis() / SCROLL_INTERVAL_MILLIS;
    }

    private String scrollText(String text, int width, long step) {
        if (width <= 0) {
            return "";
        }
        String value = text == null ? "" : text;
        if (value.length() <= width) {
            return value;
        }
        String gap = "  •  ";
        String loop = value + gap + value;
        int cycle = value.length() + gap.length();
        int offset = (int) Math.floorMod(step, cycle);
        return loop.substring(offset, Math.min(offset + width, loop.length()));
    }

    private String bodyContentText() {
        if (isChainDisplayActive()) {
            return discoveryContentText();
        }
        if (!_contextKeyHints.isEmpty()) {
            return contextHintText();
        }
        return passiveCommands();
    }

    private String visibleBodyText(int width) {
        return scrollText(bodyContentText(), Math.max(1, width - 2), animationStep());
    }

    private String contextHintText() {
        var grouped = new LinkedHashMap<String, List<DiscoveryEntry>>();
        for (KeyBindingHint hint : _contextKeyHints) {
            grouped.computeIfAbsent(hint.group(), ignored -> new ArrayList<>())
                    .add(new DiscoveryEntry(displayPattern(hint.key()), hint.summary()));
        }
        return groupedEntriesText(grouped, "");
    }

    private boolean animationTickApplies() {
        Window window = Window.getInstance();
        return window != null && window.getKeyMenuView() == this && getParent() != null;
    }

    private boolean isDiscoverabilityActive() {
        return "NORMAL".equals(_modeName) && _bufferFocused && _focusContext == FocusContext.BUFFER;
    }

    private boolean isChainDisplayActive() {
        return isDiscoverabilityActive() || _globalChainActive;
    }

    private String passiveHint() {
        if (_contextHint != null && !_contextHint.isBlank()) {
            return _contextHint;
        }
        if (_focusContext != FocusContext.BUFFER) {
            return switch (_focusContext) {
            case COMMAND -> "command line active";
            case LIST_PANEL -> "list navigation";
            case SEARCH_PANEL -> "project search";
            case TEXT_PANEL -> "panel scroll";
            case CHAT_PANEL -> "chat input active";
            case SHELL -> "shell input active";
            case PANEL -> "panel focus";
            default -> "buffer focus required for key discovery";
            };
        }
        return switch (_modeName) {
        case "INPUT" -> "Esc returns to normal";
        case "VISUAL" -> "selection actions stay one key away";
        default -> "buffer focus required for key discovery";
        };
    }

    private String passiveCommands() {
        if (_focusContext != FocusContext.BUFFER) {
            return switch (_focusContext) {
            case COMMAND -> commandBodyText();
            case LIST_PANEL -> "type to filter  •  arrows move  •  Enter open  •  Esc close";
            case SEARCH_PANEL -> "type to search project  •  arrows move  •  Enter open  •  Esc close";
            case TEXT_PANEL -> "j/k or arrows scroll  •  q or Esc close";
            case CHAT_PANEL -> _chatPending
                    ? "type to chat  •  Enter send  •  :abort while pending  •  Esc close"
                    : "type to chat  •  Enter send  •  Esc close";
            case SHELL -> "Ctrl-g Esc browse output  •  Ctrl-g w <n> Enter switch workspace  •  Ctrl-g c w new shell ws  •  Ctrl-g c v/h split shell  •  Ctrl-g q close";
            case PANEL -> "Esc returns to the buffer";
            default -> "focus the buffer to browse normal-mode key chains";
            };
        }
        return switch (_modeName) {
        case "INPUT" -> "Esc normal  •  arrows move  •  Enter newline  •  type to insert";
        case "VISUAL" -> "Esc cancel  •  o swap edge  •  d delete  •  y yank  •  c change";
        default -> "focus the buffer to browse normal-mode key chains";
        };
    }

    private String commandBodyText() {
        if ("/".equals(_commandPrompt)) {
            return "Enter search forward  •  Esc cancel  •  matches are regex  •  n/N repeat after exit";
        }
        if ("?".equals(_commandPrompt)) {
            return "Enter search backward  •  Esc cancel  •  matches are regex  •  n/N repeat after exit";
        }
        if (":".equals(_commandPrompt)) {
            if (_commandText != null && _commandText.startsWith("focus")) {
                return "Up/down pick  •  Tab complete  •  focus left/right/up/down  •  next/prev  •  Enter run";
            }
            return "Up/down pick  •  Tab complete  •  split/vsplit  •  close/only  •  focus  •  grep  •  Enter run";
        }
        if (_commandText != null && _commandText.startsWith("focus")) {
            return "focus left/right/up/down  •  focus next/prev  •  Enter run  •  Esc cancel";
        }
        return "split/vsplit  •  close/only  •  focus left/right/up/down  •  grep  •  Enter run  •  Esc cancel";
    }

    private static String displayPattern(String pattern) {
        return List.of(pattern.split(" ")).stream()
                .map(UiTheme::displayKey)
                .collect(Collectors.joining(" "));
    }

    private static String tokenFor(KeyStroke keyStroke) {
        if (keyStroke.isCtrlDown()) {
            if (keyStroke.getKeyType() == KeyType.Character) {
                return "<CTRL>-" + keyStroke.getCharacter();
            }
            return switch (keyStroke.getKeyType()) {
            case ArrowUp -> "<CTRL>-<UP>";
            case ArrowDown -> "<CTRL>-<DOWN>";
            case ArrowLeft -> "<CTRL>-<LEFT>";
            case ArrowRight -> "<CTRL>-<RIGHT>";
            default -> null;
            };
        }
        return switch (keyStroke.getKeyType()) {
        case Character -> {
            Character character = keyStroke.getCharacter();
            if (character != null && character >= 1 && character <= 26) {
                yield "<CTRL>-" + Character.toLowerCase((char) ('a' + character - 1));
            }
            yield character == ' ' ? "<SPACE>" : Character.toString(character);
        }
        case Escape -> "<ESC>";
        case Enter -> "<ENTER>";
        case Tab -> "<TAB>";
        case Backspace -> "<BACKSPACE>";
        case ArrowUp -> "<UP>";
        case ArrowDown -> "<DOWN>";
        case ArrowLeft -> "<LEFT>";
        case ArrowRight -> "<RIGHT>";
        default -> null;
        };
    }

    private static MenuNode createRoot(List<KeyBindingHint> keyHints) {
        var root = new MenuNode(null, null, false);
        if (keyHints == null) {
            return root;
        }
        for (KeyBindingHint hint : keyHints) {
            root.register(new MenuDoc(hint.key(), hint.group(), hint.summary(), true));
        }
        return root;
    }

    private List<KeyBindingHint> combinedKeyHints() {
        if (_globalKeyHints.isEmpty()) {
            return _contextKeyHints;
        }
        if (_contextKeyHints.isEmpty()) {
            return _globalKeyHints;
        }
        var combined = new ArrayList<KeyBindingHint>(_contextKeyHints);
        combined.addAll(_globalKeyHints);
        return List.copyOf(combined);
    }

    private record DiscoveryEntry(String key, String summary) {
    }

    private record MenuDoc(String pattern, String group, String summary, boolean terminal) {
        private MenuDoc {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("pattern must not be blank");
            }
            if (group == null || group.isBlank()) {
                throw new IllegalArgumentException("group must not be blank for " + pattern);
            }
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary must not be blank for " + pattern);
            }
        }

        private List<String> tokens() {
            return List.of(pattern.split(" "));
        }
    }

    private static final class MenuNode {
        private String _group;
        private String _summary;
        private boolean _terminal;
        private final Map<String, MenuNode> _children = new LinkedHashMap<>();

        private MenuNode(String group, String summary, boolean terminal) {
            _group = group;
            _summary = summary;
            _terminal = terminal;
        }

        private void register(MenuDoc doc) {
            MenuNode current = this;
            var tokens = doc.tokens();
            for (int i = 0; i < tokens.size(); ++i) {
                String token = tokens.get(i);
                current = current._children.computeIfAbsent(token, ignored -> new MenuNode(null, null, false));
                if (!current.documented()) {
                    current._group = doc.group();
                    current._summary = doc.summary();
                }
                if (i == tokens.size() - 1) {
                    current._group = doc.group();
                    current._summary = doc.summary();
                    current._terminal = doc.terminal();
                }
            }
        }

        private MenuNode match(String token) {
            MenuNode next = _children.get(token);
            if (next != null) {
                return next;
            }
            return _children.get(ANY_CHARACTER);
        }

        private Map<String, MenuNode> children() {
            return _children;
        }

        private String summary() {
            return _summary;
        }

        private String group() {
            return _group;
        }

        private boolean documented() {
            return _group != null && !_group.isBlank() && _summary != null && !_summary.isBlank();
        }

        private boolean isTerminal() {
            return _terminal && _children.isEmpty();
        }
    }

    private void appendHeaderSegment(
            AttributedString line,
            TextColorTracker colors,
            String text,
            com.googlecode.lanterna.TextColor foreground,
            com.googlecode.lanterna.TextColor background) {
        UiTheme.appendRightSeparator(line, colors.background(), background);
        UiTheme.appendSegment(line, text, foreground, background);
        colors.background(background);
    }

    private void appendHeaderReset(AttributedString line, TextColorTracker colors) {
        if (!UiTheme.MENU_BACKGROUND.equals(colors.background())) {
            UiTheme.appendRightSeparator(line, colors.background(), UiTheme.MENU_BACKGROUND);
            colors.background(UiTheme.MENU_BACKGROUND);
        }
    }

    private void appendBodyReset(AttributedString line, TextColorTracker colors) {
        if (!UiTheme.MENU_SECONDARY_BACKGROUND.equals(colors.background())) {
            UiTheme.appendRightSeparator(line, colors.background(), UiTheme.MENU_SECONDARY_BACKGROUND);
            colors.background(UiTheme.MENU_SECONDARY_BACKGROUND);
        }
    }

    private static final class TextColorTracker {
        private com.googlecode.lanterna.TextColor _background;

        private TextColorTracker(com.googlecode.lanterna.TextColor background) {
            _background = background;
        }

        private com.googlecode.lanterna.TextColor background() {
            return _background;
        }

        private void background(com.googlecode.lanterna.TextColor background) {
            _background = background;
        }
    }
}
