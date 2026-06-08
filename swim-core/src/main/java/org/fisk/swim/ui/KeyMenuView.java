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
    private static final int MAX_DISCOVERY_ROWS = 4;
    private static final long PAGE_INTERVAL_MILLIS = 1800L;
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
    private Long _animationStepOverride;

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

    void setAnimationStepOverride(Long animationStepOverride) {
        _animationStepOverride = animationStepOverride;
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
        int width = Math.max(1, getBounds().getSize().getWidth());
        DiscoveryState discoveryState = discoveryState(width);

        var line = new AttributedString();
        TextColorTracker colors = new TextColorTracker(UiTheme.MENU_BACKGROUND);

        UiTheme.appendSegment(line, "SWIM", UiTheme.MENU_BACKGROUND, UiTheme.MENU_ACCENT);
        colors.background(UiTheme.MENU_ACCENT);
        appendHeaderSegment(line, colors, _modeName, UiTheme.TEXT_ON_ACCENT, UiTheme.modeColor(_modeName));
        if (isDiscoverabilityActive()) {
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
            if (discoveryState.pageLabel() != null) {
                appendHeaderSegment(line, colors, discoveryState.pageLabel(), UiTheme.MENU_HINT,
                        UiTheme.MENU_CONTEXT_BACKGROUND);
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
        int width = Math.max(1, getBounds().getSize().getWidth());
        if (!isDiscoverabilityActive()) {
            return passiveCommands();
        }
        return String.join("  •  ", discoveryState(width).rows());
    }

    int preferredHeight(int width, int totalHeight) {
        if (width <= 0 || totalHeight <= 0) {
            return 0;
        }
        int maxMenuHeight = Math.max(1, totalHeight - 3);
        if (isDiscoverabilityActive()) {
            int bodyRows = _path.isEmpty()
                    ? 1
                    : Math.min(MAX_DISCOVERY_ROWS, Math.max(1, formattedDiscoveryRows(width).size()));
            return Math.max(1, Math.min(1 + bodyRows, maxMenuHeight));
        }
        int preferred = 1 + passiveBodyLines(width).size();
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
        var lines = new ArrayList<AttributedString>();
        for (String row : isDiscoverabilityActive() ? discoveryState(width).rows() : passiveBodyLines(width)) {
            var line = new AttributedString();
            TextColorTracker colors = new TextColorTracker(UiTheme.MENU_SECONDARY_BACKGROUND);
            UiTheme.appendSegment(line, row, UiTheme.TEXT_PRIMARY, UiTheme.MENU_SEGMENT_BACKGROUND);
            colors.background(UiTheme.MENU_SEGMENT_BACKGROUND);
            appendBodyReset(line, colors);
            lines.add(line);
        }
        if (lines.isEmpty()) {
            var line = new AttributedString();
            TextColorTracker colors = new TextColorTracker(UiTheme.MENU_SECONDARY_BACKGROUND);
            UiTheme.appendSegment(line, "", UiTheme.TEXT_PRIMARY, UiTheme.MENU_SEGMENT_BACKGROUND);
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

    private DiscoveryState discoveryState(int width) {
        var formattedRows = formattedDiscoveryRows(width);
        if (formattedRows.isEmpty()) {
            return new DiscoveryState(List.of("No documented continuations for this prefix."), null);
        }

        int pageSize = Math.min(MAX_DISCOVERY_ROWS, Math.max(1, formattedRows.size()));
        int pageCount = (formattedRows.size() + pageSize - 1) / pageSize;
        int pageIndex = bouncingPage(pageCount, animationStep());
        int start = pageIndex * pageSize;
        int end = Math.min(formattedRows.size(), start + pageSize);
        String pageLabel = pageCount > 1 ? "groups " + (pageIndex + 1) + "/" + pageCount : null;
        return new DiscoveryState(formattedRows.subList(start, end), pageLabel);
    }

    private List<String> formattedDiscoveryRows(int width) {
        var rows = new ArrayList<GroupRow>();
        for (var entry : _currentNode.children().entrySet()) {
            MenuNode node = entry.getValue();
            if (!node.documented()) {
                continue;
            }
            rows.add(new GroupRow(node.group(),
                    new DiscoveryEntry(UiTheme.displayKey(entry.getKey()), node.summary())));
        }
        if (rows.isEmpty()) {
            return List.of();
        }

        var grouped = new LinkedHashMap<String, List<DiscoveryEntry>>();
        for (var row : rows) {
            grouped.computeIfAbsent(row.group(), ignored -> new ArrayList<>()).add(row.entry());
        }

        var formattedRows = new ArrayList<String>();
        for (var entry : grouped.entrySet()) {
            formattedRows.add(formatGroupRow(entry.getKey(), entry.getValue(), width));
        }

        return formattedRows;
    }

    private String formatGroupRow(String group, List<DiscoveryEntry> entries, int width) {
        String prefix = group + "  ";
        int availableWidth = Math.max(8, width - prefix.length() - 6);
        var pages = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        for (var entry : entries) {
            String segment = entry.key() + " " + entry.summary();
            if (!current.isEmpty() && current.length() + 3 + segment.length() > availableWidth) {
                pages.add(current.toString());
                current = new StringBuilder();
            }
            if (!current.isEmpty()) {
                current.append(" • ");
            }
            current.append(segment);
        }
        if (current.isEmpty()) {
            current.append("no documented keys");
        }
        pages.add(current.toString());

        int pageIndex = bouncingPage(pages.size(), animationStep());
        String pageSuffix = pages.size() > 1 ? " [" + (pageIndex + 1) + "/" + pages.size() + "]" : "";
        return UiTheme.fit(prefix + pages.get(pageIndex) + pageSuffix, width);
    }

    private long animationStep() {
        if (_animationStepOverride != null) {
            return _animationStepOverride.longValue();
        }
        return System.currentTimeMillis() / PAGE_INTERVAL_MILLIS;
    }

    private int bouncingPage(int pageCount, long step) {
        if (pageCount <= 1) {
            return 0;
        }
        int cycle = pageCount * 2 - 2;
        int position = (int) Math.floorMod(step, cycle);
        if (position < pageCount) {
            return position;
        }
        return cycle - position;
    }

    private List<String> passiveBodyLines(int width) {
        var wrapped = TextPanelView.wrapText(passiveCommands(), Math.max(1, width - 2));
        return wrapped.isEmpty() ? List.of("") : wrapped;
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

    private static MenuNode createRoot() {
        var root = new MenuNode(null, null, false);
        for (MenuDoc doc : docs()) {
            root.register(doc);
        }
        validate(root, List.of());
        return root;
    }

    private static void validate(MenuNode node, List<String> path) {
        for (var entry : node.children().entrySet()) {
            var childPath = new ArrayList<>(path);
            childPath.add(entry.getKey());
            MenuNode child = entry.getValue();
            if (!child.documented()) {
                throw new IllegalStateException("Missing discoverability documentation for " + String.join(" ", childPath));
            }
            validate(child, childPath);
        }
    }

    private static List<MenuDoc> docs() {
        return List.of(
                leaf("h", "Navigation", "left"),
                leaf("j", "Navigation", "down"),
                leaf("k", "Navigation", "up"),
                leaf("l", "Navigation", "right"),
                leaf("0", "Navigation", "column zero"),
                leaf("^", "Navigation", "first nonblank"),
                leaf("_", "Navigation", "first nonblank"),
                leaf("$", "Navigation", "line end"),
                leaf("w", "Navigation", "word forward"),
                leaf("W", "Navigation", "WORD forward"),
                leaf("b", "Navigation", "word back"),
                leaf("B", "Navigation", "WORD back"),
                leaf("e", "Navigation", "word end"),
                leaf("E", "Navigation", "WORD end"),
                leaf("{", "Navigation", "paragraph back"),
                leaf("}", "Navigation", "paragraph forward"),
                leaf("(", "Navigation", "sentence back"),
                leaf(")", "Navigation", "sentence forward"),
                leaf("%", "Navigation", "matching bracket"),
                leaf("H", "Navigation", "screen top"),
                leaf("M", "Navigation", "screen middle"),
                leaf("L", "Navigation", "screen bottom"),
                leaf("G", "Navigation", "buffer end"),
                branch("g", "Navigation", "goto, marks, multicursor, and code jumps"),
                leaf("g g", "Navigation", "buffer start"),
                branch("m", "Marks", "set mark"),
                leaf("m <CHAR>", "Marks", "mark register"),
                branch("g m", "Marks", "set mark"),
                leaf("g m <CHAR>", "Marks", "mark register"),
                leaf("g n", "Multicursor", "add next cursor"),
                leaf("g N", "Multicursor", "add previous cursor"),
                leaf("g c", "Multicursor", "clear extra cursors"),
                leaf("g d", "Code", "definition"),
                leaf("g r", "Code", "references"),
                branch("g w", "Navigation", "visible word jump"),
                leaf("g w <CHAR>", "Navigation", "jump to visible word start"),
                branch("f", "Navigation", "find next character"),
                leaf("f <CHAR>", "Navigation", "next matching character"),
                branch("F", "Navigation", "find previous character"),
                leaf("F <CHAR>", "Navigation", "previous matching character"),
                branch("t", "Navigation", "until next character"),
                leaf("t <CHAR>", "Navigation", "before next matching character"),
                branch("T", "Navigation", "until previous character"),
                leaf("T <CHAR>", "Navigation", "after previous matching character"),
                leaf(";", "Navigation", "repeat character find"),
                leaf(",", "Navigation", "reverse character find"),
                leaf("<CTRL>-d", "Navigation", "half page down"),
                leaf("<CTRL>-u", "Navigation", "half page up"),
                leaf("<CTRL>-f", "Navigation", "page down"),
                leaf("<CTRL>-b", "Navigation", "page up"),
                leaf("<CTRL>-o", "Navigation", "jump back"),
                leaf("<TAB>", "Navigation", "jump forward"),
                leaf("*", "Search", "search current word forward"),
                leaf("#", "Search", "search current word backward"),
                leaf("/", "Search", "forward search"),
                leaf("?", "Search", "backward search"),
                leaf("n", "Search", "next match"),
                leaf("N", "Search", "previous match"),
                branch("d", "Editing", "delete"),
                branch("d i", "Editing", "inner text object"),
                leaf("d i w", "Editing", "inner word"),
                leaf("d i (", "Editing", "inside parentheses"),
                leaf("d i [", "Editing", "inside brackets"),
                leaf("d i {", "Editing", "inside braces"),
                leaf("d i \"", "Editing", "inside double quotes"),
                leaf("d i '", "Editing", "inside single quotes"),
                leaf("d i p", "Editing", "inside paragraph"),
                branch("d a", "Editing", "around text object"),
                leaf("d a (", "Editing", "around parentheses"),
                leaf("d a [", "Editing", "around brackets"),
                leaf("d a {", "Editing", "around braces"),
                leaf("d a \"", "Editing", "around double quotes"),
                leaf("d a '", "Editing", "around single quotes"),
                leaf("d a p", "Editing", "around paragraph"),
                leaf("d w", "Editing", "word"),
                leaf("d d", "Editing", "line"),
                branch("c", "Editing", "change"),
                branch("c i", "Editing", "inner text object"),
                leaf("c i w", "Editing", "inner word"),
                leaf("c i (", "Editing", "inside parentheses"),
                leaf("c i [", "Editing", "inside brackets"),
                leaf("c i {", "Editing", "inside braces"),
                leaf("c i \"", "Editing", "inside double quotes"),
                leaf("c i '", "Editing", "inside single quotes"),
                leaf("c i p", "Editing", "inside paragraph"),
                branch("c a", "Editing", "around text object"),
                leaf("c a (", "Editing", "around parentheses"),
                leaf("c a [", "Editing", "around brackets"),
                leaf("c a {", "Editing", "around braces"),
                leaf("c a \"", "Editing", "around double quotes"),
                leaf("c a '", "Editing", "around single quotes"),
                leaf("c a p", "Editing", "around paragraph"),
                leaf("c w", "Editing", "word"),
                branch("y", "Editing", "yank"),
                leaf("y y", "Editing", "line"),
                branch("y i", "Editing", "yank inner text object"),
                leaf("y i (", "Editing", "inside parentheses"),
                leaf("y i [", "Editing", "inside brackets"),
                leaf("y i {", "Editing", "inside braces"),
                leaf("y i \"", "Editing", "inside double quotes"),
                leaf("y i '", "Editing", "inside single quotes"),
                leaf("y i p", "Editing", "inside paragraph"),
                branch("y a", "Editing", "yank around text object"),
                leaf("y a (", "Editing", "around parentheses"),
                leaf("y a [", "Editing", "around brackets"),
                leaf("y a {", "Editing", "around braces"),
                leaf("y a \"", "Editing", "around double quotes"),
                leaf("y a '", "Editing", "around single quotes"),
                leaf("y a p", "Editing", "around paragraph"),
                leaf("x", "Editing", "delete character"),
                leaf("D", "Editing", "delete to line end"),
                leaf("C", "Editing", "change to line end"),
                leaf("Y", "Editing", "yank line"),
                leaf("J", "Editing", "join lines"),
                branch("r", "Editing", "replace character"),
                leaf("r <CHAR>", "Editing", "replace with character"),
                leaf("R", "Editing", "replace mode"),
                leaf("s", "Editing", "substitute character"),
                leaf("S", "Editing", "substitute line"),
                leaf("~", "Editing", "toggle case"),
                branch(">", "Editing", "indent operator"),
                leaf("> >", "Editing", "indent line"),
                branch("<", "Editing", "outdent operator"),
                leaf("< <", "Editing", "outdent line"),
                branch("=", "Editing", "format operator"),
                leaf("= =", "Editing", "format line"),
                branch("g U", "Editing", "uppercase operator"),
                branch("g u", "Editing", "lowercase operator"),
                branch("g ~", "Editing", "toggle-case operator"),
                leaf("p", "Editing", "paste after"),
                leaf("P", "Editing", "paste before"),
                leaf("u", "Editing", "undo"),
                leaf("<CTRL>-r", "Editing", "redo"),
                leaf("i", "Editing", "insert"),
                leaf("a", "Editing", "append"),
                leaf("A", "Editing", "append line end"),
                leaf("o", "Editing", "open below"),
                leaf("O", "Editing", "open above"),
                leaf(".", "Macros", "repeat last edit"),
                branch("q", "Macros", "record macro"),
                leaf("q <CHAR>", "Macros", "record into register"),
                branch("@", "Macros", "play macro"),
                leaf("@ <CHAR>", "Macros", "play register"),
                leaf("v", "Selection", "visual"),
                leaf("V", "Selection", "visual line"),
                leaf("<CTRL>-v", "Selection", "visual block"),
                branch("z", "Folds", "manual folds"),
                branch("z f", "Folds", "fold motion"),
                leaf("z F", "Folds", "fold lines"),
                leaf("z a", "Folds", "toggle fold"),
                leaf("z A", "Folds", "toggle recursive"),
                leaf("z c", "Folds", "close fold"),
                leaf("z o", "Folds", "open fold"),
                leaf("z v", "Folds", "open at cursor"),
                leaf("z C", "Folds", "close recursive"),
                leaf("z O", "Folds", "open recursive"),
                leaf("z M", "Folds", "close all"),
                leaf("z R", "Folds", "open all"),
                leaf("z d", "Folds", "delete fold"),
                leaf("z D", "Folds", "delete recursive"),
                leaf("z E", "Folds", "delete all folds"),
                leaf("z j", "Folds", "next fold"),
                leaf("z k", "Folds", "previous fold"),
                leaf("<CTRL>-t", "Workspace", "quick todo"),
                leaf(":", "Workspace", "command line"),
                leaf("!", "Tools", "Nemo"),
                leaf("B", "Tools", "toggle breakpoint"),
                branch("<CTRL>-w", "Panes", "split and focus panes"),
                leaf("<CTRL>-w s", "Panes", "split below"),
                leaf("<CTRL>-w v", "Panes", "split right"),
                leaf("<CTRL>-w h", "Panes", "focus left"),
                leaf("<CTRL>-w j", "Panes", "focus down"),
                leaf("<CTRL>-w k", "Panes", "focus up"),
                leaf("<CTRL>-w l", "Panes", "focus right"),
                leaf("<CTRL>-w >", "Panes", "wider"),
                leaf("<CTRL>-w <", "Panes", "narrower"),
                leaf("<CTRL>-w +", "Panes", "taller"),
                leaf("<CTRL>-w -", "Panes", "shorter"),
                leaf("<CTRL>-w =", "Panes", "equalize"),
                leaf("<CTRL>-w w", "Panes", "next pane"),
                leaf("<CTRL>-w W", "Panes", "previous pane"),
                leaf("<CTRL>-w q", "Panes", "close pane"),
                leaf("<CTRL>-w o", "Panes", "only pane"),
                branch("<CTRL>-g", "Shell", "shell workspace commands"),
                branch("<CTRL>-g c", "Shell", "create shell"),
                leaf("<CTRL>-g c w", "Shell", "new shell workspace"),
                leaf("<CTRL>-g c v", "Shell", "shell in split right"),
                leaf("<CTRL>-g c h", "Shell", "shell in split below"),
                leaf("<CTRL>-g w", "Shell", "switch workspace"),
                branch("<SPACE>", "Code", "code commands"),
                branch("<SPACE> e", "Code", "language actions"),
                leaf("<SPACE> e i", "Code", "organize imports"),
                leaf("<SPACE> e f", "Code", "make final"),
                leaf("<SPACE> e a", "Code", "generate accessors"),
                leaf("<SPACE> e s", "Code", "generate toString"),
                leaf("<SPACE> e l", "Code", "code lens"));
    }

    private static MenuDoc branch(String pattern, String group, String summary) {
        return new MenuDoc(pattern, group, summary, false);
    }

    private static MenuDoc leaf(String pattern, String group, String summary) {
        return new MenuDoc(pattern, group, summary, true);
    }

    private record DiscoveryEntry(String key, String summary) {
    }

    private record GroupRow(String group, DiscoveryEntry entry) {
    }

    private record DiscoveryState(List<String> rows, String pageLabel) {
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
