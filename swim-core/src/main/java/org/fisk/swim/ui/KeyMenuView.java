package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

public class KeyMenuView extends View {
    private static final int MIN_HEIGHT = 2;
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
    private static final MenuNode ROOT = createRoot();

    private final List<String> _path = new ArrayList<>();
    private MenuNode _currentNode = ROOT;
    private String _modeName = "NORMAL";
    private boolean _bufferFocused = true;
    private FocusContext _focusContext = FocusContext.BUFFER;
    private String _contextLabel;
    private String _commandPrompt;
    private String _commandText;
    private boolean _chatPending;
    private List<String> _recentWindows = List.of();

    public KeyMenuView(Rect bounds) {
        super(bounds);
        setBackgroundColour(UiTheme.MENU_BACKGROUND);
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

    public void resetChain() {
        _path.clear();
        _currentNode = ROOT;
    }

    void observe(KeyStroke keyStroke) {
        if (!isDiscoverabilityActive()) {
            if (!_path.isEmpty()) {
                resetChain();
                setNeedsRedraw();
            }
            return;
        }

        String token = tokenFor(keyStroke);
        if (token == null) {
            return;
        }

        if (!advance(token)) {
            resetChain();
            advance(token);
        }
        setNeedsRedraw();
    }

    AttributedString buildHeaderLine() {
        var line = new AttributedString();
        TextColorTracker colors = new TextColorTracker(UiTheme.MENU_BACKGROUND);

        UiTheme.appendSegment(line, "SWIM", UiTheme.MENU_BACKGROUND, UiTheme.MENU_ACCENT);
        colors.background(UiTheme.MENU_ACCENT);
        appendHeaderSegment(line, colors, _modeName, UiTheme.TEXT_ON_ACCENT, UiTheme.modeColor(_modeName));
        if (isDiscoverabilityActive()) {
            if (_path.isEmpty()) {
                appendHeaderSegment(line, colors, "explore key chains", UiTheme.TEXT_PRIMARY, UiTheme.MENU_SEGMENT_BACKGROUND);
            } else {
                appendHeaderSegment(line, colors, "chain", UiTheme.TEXT_MUTED, UiTheme.MENU_SEGMENT_BACKGROUND);
                appendHeaderSegment(line, colors, String.join(" ", _path), UiTheme.MENU_CHAIN, UiTheme.MENU_CONTEXT_BACKGROUND);
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
        if (!isDiscoverabilityActive()) {
            return passiveCommands();
        }
        if (_path.isEmpty()) {
            return "Esc Nemo chat  •  move h/j/k/l  •  files m  •  grep M  •  mail e  •  windows w <n> Enter  •  panes Ctrl-w  •  edit d c y p  •  goto g  •  tools SPC e  •  search / ? n *  •  modes i v V";
        }
        return describeCurrentNode();
    }

    int preferredHeight(int width, int totalHeight) {
        if (width <= 0 || totalHeight <= 0) {
            return 0;
        }
        if (width >= 24) {
            return Math.min(MIN_HEIGHT, totalHeight);
        }
        int maxMenuHeight = Math.max(1, totalHeight - 3);
        int preferred = 1 + buildBodyLines(width).size();
        return Math.max(1, Math.min(Math.max(MIN_HEIGHT, preferred), maxMenuHeight));
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
        var wrapped = TextPanelView.wrapText(bodyText(), Math.max(1, width - 2));
        if (wrapped.isEmpty()) {
            wrapped = List.of("");
        }
        var lines = new ArrayList<AttributedString>();
        for (String wrappedLine : wrapped) {
            var line = new AttributedString();
            TextColorTracker colors = new TextColorTracker(UiTheme.MENU_SECONDARY_BACKGROUND);
            UiTheme.appendSegment(line, wrappedLine, UiTheme.TEXT_PRIMARY, UiTheme.MENU_SEGMENT_BACKGROUND);
            colors.background(UiTheme.MENU_SEGMENT_BACKGROUND);
            appendBodyReset(line, colors);
            lines.add(line);
        }
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

    private String describeCurrentNode() {
        var suggestions = new ArrayList<String>();
        for (var entry : _currentNode.children().entrySet()) {
            suggestions.add(UiTheme.displayKey(entry.getKey()) + " " + entry.getValue().summary());
        }
        return String.join("  •  ", suggestions);
    }

    private boolean isDiscoverabilityActive() {
        return "NORMAL".equals(_modeName) && _bufferFocused && _focusContext == FocusContext.BUFFER;
    }

    private String passiveHint() {
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
            case SHELL -> "Ctrl-g Esc browse output  •  Ctrl-g w <n> Enter switch workspace  •  Ctrl-g c new shell  •  Ctrl-g q close";
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
            return "Enter search forward  •  Esc cancel  •  matches are literal  •  n/N repeat after exit";
        }
        if ("?".equals(_commandPrompt)) {
            return "Enter search backward  •  Esc cancel  •  matches are literal  •  n/N repeat after exit";
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
        case Backspace -> "<BACKSPACE>";
        case ArrowUp -> "<UP>";
        case ArrowDown -> "<DOWN>";
        case ArrowLeft -> "<LEFT>";
        case ArrowRight -> "<RIGHT>";
        default -> null;
        };
    }

    private static MenuNode createRoot() {
        var root = branch("root");
        root.child("h", leaf("left"));
        root.child("j", leaf("down"));
        root.child("k", leaf("up"));
        root.child("l", leaf("right"));
        root.child("g", branch("goto")
                .child("g", leaf("top of buffer"))
                .child("d", leaf("definition"))
                .child("w", leaf("jump hints")));
        root.child("d", branch("delete")
                .child("i", branch("inner").child("w", leaf("inner word")))
                .child("w", leaf("word"))
                .child("d", leaf("line")));
        root.child("c", branch("change")
                .child("i", branch("inner").child("w", leaf("inner word")))
                .child("w", leaf("word")));
        root.child("y", branch("yank").child("y", leaf("line")));
        root.child("p", leaf("paste after"));
        root.child("P", leaf("paste before"));
        root.child("u", leaf("undo"));
        root.child("<CTRL>-r", leaf("redo"));
        root.child("i", leaf("insert"));
        root.child("a", leaf("append"));
        root.child("A", leaf("append line end"));
        root.child("o", leaf("open below"));
        root.child("O", leaf("open above"));
        root.child("v", leaf("visual"));
        root.child("V", leaf("visual line"));
        root.child("<CTRL>-v", leaf("visual block"));
        root.child("m", leaf("project files"));
        root.child("M", leaf("project search"));
        root.child("e", leaf("email"));
        root.child("<ESC>", leaf("start Nemo chat"));
        root.child("/", leaf("search forward"));
        root.child("?", leaf("search backward"));
        root.child("n", leaf("next match"));
        root.child("N", leaf("previous match"));
        root.child("*", leaf("word forward"));
        root.child("#", leaf("word backward"));
        root.child(":", leaf("command line"));
        root.child("f", branch("find next").child(ANY_CHARACTER, leaf("type a character")));
        root.child("F", branch("find previous").child(ANY_CHARACTER, leaf("type a character")));
        root.child("<CTRL>-w", branch("panes")
                .child("s", leaf("split below"))
                .child("v", leaf("split right"))
                .child("h", leaf("focus left"))
                .child("j", leaf("focus down"))
                .child("k", leaf("focus up"))
                .child("l", leaf("focus right"))
                .child("w", leaf("next pane"))
                .child("W", leaf("previous pane"))
                .child("q", leaf("close pane"))
                .child("o", leaf("only this pane")));
        root.child("<CTRL>-g", branch("shell")
                .child("c", leaf("new shell workspace"))
                .child("w", leaf("workspace switch")));
        root.child("<SPACE>", branch("code")
                .child("e", branch("actions")
                        .child("i", leaf("organize imports"))
                        .child("f", leaf("make final"))
                        .child("a", leaf("generate accessors"))
                        .child("s", leaf("generate toString"))
                        .child("l", leaf("code lens"))));
        return root;
    }

    private static MenuNode branch(String summary) {
        return new MenuNode(summary, false);
    }

    private static MenuNode leaf(String summary) {
        return new MenuNode(summary, true);
    }

    private static final class MenuNode {
        private final String _summary;
        private final boolean _terminal;
        private final Map<String, MenuNode> _children = new LinkedHashMap<>();

        private MenuNode(String summary, boolean terminal) {
            _summary = summary;
            _terminal = terminal;
        }

        private MenuNode child(String token, MenuNode node) {
            _children.put(token, node);
            return this;
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

        private boolean isTerminal() {
            return _terminal || _children.isEmpty();
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
