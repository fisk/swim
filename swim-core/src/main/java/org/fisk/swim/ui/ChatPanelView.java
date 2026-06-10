package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalCursorShape;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

public class ChatPanelView extends View implements KeyBindingHintProvider {
    private static final String HISTORY_ME_PREFIX = "me> ";
    private static final String INPUT_PREFIX = "! ";
    private static final String NEMO_PREFIX = "nemo> ";
    private static final String THINKING_TEXT = "*thinking*";
    private static final List<String> CODE_KEYWORDS = List.of(
            "abstract", "boolean", "break", "case", "catch", "class", "const", "continue", "def", "else",
            "enum", "extends", "false", "final", "finally", "for", "fun", "function", "if", "implements",
            "import", "interface", "let", "new", "null", "package", "private", "protected", "public",
            "record", "return", "static", "struct", "switch", "this", "throw", "true", "try", "var",
            "void", "while");

    public record ChatMessage(String speaker, String text) {
    }

    private enum DisplayKind {
        NORMAL,
        CODE_HEADER,
        CODE,
        DIFF_HEADER,
        DIFF_HUNK,
        DIFF_ADDED,
        DIFF_REMOVED,
        DIFF_CONTEXT
    }

    private record DisplayLine(String text, DisplayKind kind) {
    }

    private static final class ChatCursor extends Cursor {
        private final ChatPanelView _owner;

        private ChatCursor(ChatPanelView owner) {
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

    public record PromptStyle(String inputPrefix, String historyPrefix, String emptyHint, String titleReadyLabel,
            String titlePendingLabel) {
        public static PromptStyle nemo() {
            return new PromptStyle(INPUT_PREFIX, HISTORY_ME_PREFIX, "type a message or :abort", " ready ",
                    " waiting for Nemo ");
        }

        public static PromptStyle shell() {
            return new PromptStyle("> ", "> ", "type a shell command", " shell ready ", " shell busy ");
        }
    }

    private final String _title;
    private final Consumer<String> _onSubmit;
    private final Consumer<String> _onCommand;
    private final Consumer<String> _onCommandInputChanged;
    private final java.util.function.Function<String, CommandView.CommandMenuState> _commandMenuStateProvider;
    private final PromptStyle _promptStyle;
    private final ChatCursor _cursor;
    private final List<ChatMessage> _messages = new ArrayList<>();
    private final StringBuilder _input = new StringBuilder();
    private int _cursorOffset;
    private int _inputScrollLine;
    private int _startLine;
    private int _commandSelection;
    private boolean _pending;
    private boolean _bracketedPasteActive;
    private long _pendingStartedAtMillis;
    private Integer _contextUsagePercent;
    private long _pendingRefreshGeneration;
    private Runnable _responseAction;

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit) {
        this(bounds, title, onSubmit, ignored -> {}, ignored -> {}, CommandView.CommandMenuState::forCommandText,
                PromptStyle.nemo());
    }

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit, Consumer<String> onCommand) {
        this(bounds, title, onSubmit, onCommand, ignored -> {}, CommandView.CommandMenuState::forCommandText,
                PromptStyle.nemo());
    }

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit, Consumer<String> onCommand,
            Consumer<String> onCommandInputChanged) {
        this(bounds, title, onSubmit, onCommand, onCommandInputChanged, CommandView.CommandMenuState::forCommandText,
                PromptStyle.nemo());
    }

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit, Consumer<String> onCommand,
            Consumer<String> onCommandInputChanged,
            java.util.function.Function<String, CommandView.CommandMenuState> commandMenuStateProvider) {
        this(bounds, title, onSubmit, onCommand, onCommandInputChanged, commandMenuStateProvider, PromptStyle.nemo());
    }

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit, Consumer<String> onCommand,
            Consumer<String> onCommandInputChanged,
            java.util.function.Function<String, CommandView.CommandMenuState> commandMenuStateProvider,
            PromptStyle promptStyle) {
        super(bounds);
        _title = title;
        _onSubmit = onSubmit;
        _onCommand = onCommand;
        _onCommandInputChanged = onCommandInputChanged;
        _commandMenuStateProvider = commandMenuStateProvider;
        _promptStyle = promptStyle == null ? PromptStyle.nemo() : promptStyle;
        _cursor = new ChatCursor(this);
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
    }

    @Override
    public Cursor getCursor() {
        return _cursor;
    }

    @Override
    public void setBounds(Rect rect) {
        super.setBounds(rect);
        scrollToBottom();
        ensureInputVisible();
    }

    public void activatePrompt() {
        scrollToBottom();
        ensureInputVisible();
        setNeedsRedraw();
    }

    int getStartLine() {
        return _startLine;
    }

    String getTitle() {
        return _title;
    }

    String getInputText() {
        return _input.toString();
    }

    int getCursorOffset() {
        return _cursorOffset;
    }

    int getInputScrollLine() {
        return _inputScrollLine;
    }

    boolean isPending() {
        return _pending;
    }

    @Override
    public String keyHintContext() {
        return _pending ? "chat pending" : "chat input";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        var hints = new ArrayList<KeyBindingHint>();
        hints.add(KeyBindingHint.of("<ENTER>", "Input", "send"));
        hints.add(KeyBindingHint.of("<CTRL>-j", "Input", "newline"));
        hints.add(KeyBindingHint.of("<SHIFT>-<ENTER>", "Input", "newline"));
        hints.add(KeyBindingHint.of("<BACKSPACE>", "Input", "delete character"));
        hints.add(KeyBindingHint.of("<LEFT>", "Input", "cursor left"));
        hints.add(KeyBindingHint.of("<RIGHT>", "Input", "cursor right"));
        hints.add(KeyBindingHint.of("<CTRL>-a", "Input", "start of input"));
        hints.add(KeyBindingHint.of("<CTRL>-e", "Input", "end of input"));
        hints.add(KeyBindingHint.of("<CHAR>", "Input", "type text"));
        hints.add(KeyBindingHint.of("<UP>", "History", "scroll up"));
        hints.add(KeyBindingHint.of("<DOWN>", "History", "scroll down"));
        hints.add(KeyBindingHint.of("<TAB>", "Commands", "complete command"));
        hints.add(KeyBindingHint.of("<REVERSE-TAB>", "Commands", "previous match"));
        if (_pending) {
            hints.add(KeyBindingHint.of(":abort", "Commands", "stop pending work"));
        }
        hints.add(KeyBindingHint.of("<ESC>", "Panel", "close"));
        return List.copyOf(hints);
    }

    Integer getContextUsagePercent() {
        return _contextUsagePercent;
    }

    List<String> getDisplayLines() {
        return getDisplayRows().stream()
                .map(DisplayLine::text)
                .toList();
    }

    private List<DisplayLine> getDisplayRows() {
        var rows = new ArrayList<DisplayLine>();
        int width = Math.max(1, getBounds().getSize().getWidth());
        for (var message : _messages) {
            appendMessageRows(rows, message, width);
        }
        if (_pending) {
            rows.add(new DisplayLine(NEMO_PREFIX + formatThinkingText(elapsedSeconds()), DisplayKind.NORMAL));
        }
        return rows;
    }

    private void appendMessageRows(List<DisplayLine> rows, ChatMessage message, int width) {
        String prefix = prefixForSpeaker(message.speaker());
        String continuation = " ".repeat(prefix.length());
        boolean first = true;
        boolean inCodeBlock = false;
        boolean inDiffBlock = false;
        boolean inLooseDiff = false;
        for (String rawLine : message.text().split("\\R", -1)) {
            String trimmed = rawLine.stripLeading();
            if (trimmed.startsWith("```")) {
                String language = trimmed.substring(Math.min(3, trimmed.length())).trim();
                if (!inCodeBlock) {
                    inCodeBlock = true;
                    inDiffBlock = isDiffLanguage(language);
                    inLooseDiff = false;
                    first = appendWrappedRows(rows, first ? prefix : continuation, continuation,
                            codeHeader(language), width, inDiffBlock ? DisplayKind.DIFF_HEADER : DisplayKind.CODE_HEADER);
                } else {
                    inCodeBlock = false;
                    inDiffBlock = false;
                }
                continue;
            }
            DisplayKind kind = inCodeBlock
                    ? (inDiffBlock ? diffKind(rawLine) : DisplayKind.CODE)
                    : looseDiffKind(rawLine, inLooseDiff);
            if (!inCodeBlock && rawLine.startsWith("diff --git")) {
                inLooseDiff = true;
            }
            boolean codeLike = inCodeBlock || kind != DisplayKind.NORMAL;
            first = appendWrappedRows(rows, first ? prefix : continuation, continuation, rawLine, width, kind, codeLike);
        }
    }

    private boolean appendWrappedRows(List<DisplayLine> rows, String prefix, String continuation,
            String text, int width, DisplayKind kind) {
        return appendWrappedRows(rows, prefix, continuation, text, width, kind, false);
    }

    private boolean appendWrappedRows(List<DisplayLine> rows, String prefix, String continuation,
            String text, int width, DisplayKind kind, boolean fixedWidth) {
        int firstWidth = Math.max(1, width - prefix.length());
        int laterWidth = Math.max(1, width - continuation.length());
        List<String> wrapped = fixedWidth ? wrapFixed(text, firstWidth) : TextPanelView.wrapText(text, firstWidth);
        if (wrapped.isEmpty()) {
            rows.add(new DisplayLine(prefix, kind));
            return false;
        }
        rows.add(new DisplayLine(prefix + wrapped.get(0), kind));
        for (int i = 1; i < wrapped.size(); i++) {
            List<String> continuationWrapped = fixedWidth
                    ? wrapFixed(wrapped.get(i), laterWidth)
                    : TextPanelView.wrapText(wrapped.get(i), laterWidth);
            if (continuationWrapped.isEmpty()) {
                rows.add(new DisplayLine(continuation, kind));
                continue;
            }
            for (String line : continuationWrapped) {
                rows.add(new DisplayLine(continuation + line, kind));
            }
        }
        return false;
    }

    private static List<String> wrapFixed(String text, int width) {
        width = Math.max(1, width);
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        var lines = new ArrayList<String>();
        for (int index = 0; index < text.length(); index += width) {
            lines.add(text.substring(index, Math.min(text.length(), index + width)));
        }
        return lines;
    }

    private static String codeHeader(String language) {
        return language == null || language.isBlank() ? "code" : "code " + language.strip();
    }

    private static boolean isDiffLanguage(String language) {
        String normalized = language == null ? "" : language.toLowerCase();
        return normalized.equals("diff") || normalized.equals("patch") || normalized.equals("udiff");
    }

    private static DisplayKind diffKind(String line) {
        if (line == null || line.isEmpty()) {
            return DisplayKind.NORMAL;
        }
        if (line.startsWith("diff --git") || line.startsWith("--- ") || line.startsWith("+++ ")) {
            return DisplayKind.DIFF_HEADER;
        }
        if (line.startsWith("@@")) {
            return DisplayKind.DIFF_HUNK;
        }
        if (line.startsWith("+")) {
            return DisplayKind.DIFF_ADDED;
        }
        if (line.startsWith("-")) {
            return DisplayKind.DIFF_REMOVED;
        }
        if (line.startsWith(" ")) {
            return DisplayKind.DIFF_CONTEXT;
        }
        return DisplayKind.NORMAL;
    }

    private static DisplayKind looseDiffKind(String line, boolean inLooseDiff) {
        if (line != null && line.startsWith("diff --git")) {
            return DisplayKind.DIFF_HEADER;
        }
        return inLooseDiff ? diffKind(line) : DisplayKind.NORMAL;
    }

    private static TextColor rowBackground(DisplayLine line, int rowIndex) {
        return switch (line.kind()) {
        case CODE_HEADER, CODE -> UiTheme.SURFACE_MUTED;
        case DIFF_ADDED -> UiTheme.DIFF_ADDED_BACKGROUND;
        case DIFF_REMOVED -> UiTheme.DIFF_REMOVED_BACKGROUND;
        case DIFF_HEADER, DIFF_HUNK, DIFF_CONTEXT -> UiTheme.SURFACE_MUTED;
        case NORMAL -> rowIndex % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
        };
    }

    public void appendMessage(String speaker, String text) {
        _messages.add(new ChatMessage(speaker, text));
        scrollToBottom();
        setNeedsRedraw();
    }

    String buildBrowseText() {
        var lines = new ArrayList<String>(getDisplayLines());
        if (_input.length() > 0) {
            lines.add("");
            lines.add("draft> " + _input);
        }
        return String.join("\n", lines);
    }

    public void setMessages(List<ChatMessage> messages) {
        _messages.clear();
        _messages.addAll(messages);
        scrollToBottom();
        setNeedsRedraw();
    }

    public void setPending(boolean pending) {
        setPending(pending, System.currentTimeMillis());
    }

    public void setPending(boolean pending, long startedAtMillis) {
        _pending = pending;
        if (pending) {
            _pendingStartedAtMillis = startedAtMillis > 0 ? startedAtMillis : System.currentTimeMillis();
            startPendingRefreshLoop();
        } else {
            _pendingStartedAtMillis = 0;
            _pendingRefreshGeneration++;
        }
        scrollToBottom();
        setNeedsRedraw();
    }

    public void setContextUsagePercent(Integer contextUsagePercent) {
        if (contextUsagePercent == null) {
            _contextUsagePercent = null;
        } else {
            _contextUsagePercent = Math.max(0, Math.min(100, contextUsagePercent));
        }
        setNeedsRedraw();
    }

    public CommandView.CommandMenuState getCommandMenuState() {
        String text = _input.toString();
        if (!text.startsWith(":")) {
            return CommandView.CommandMenuState.hidden();
        }
        var state = _commandMenuStateProvider.apply(text.substring(1));
        if (state == null || !state.visible()) {
            return CommandView.CommandMenuState.hidden();
        }
        int selection = state.matches().isEmpty()
                ? 0
                : Math.max(0, Math.min(_commandSelection, state.matches().size() - 1));
        return new CommandView.CommandMenuState(state.visible(), state.prefix(), state.matches(), selection, state.title());
    }

    public boolean isCommandInputActive() {
        return _input.toString().startsWith(":");
    }

    public boolean openCommandInputIfEmpty() {
        if (_input.length() > 0) {
            return false;
        }
        _input.append(':');
        _cursorOffset = _input.length();
        _inputScrollLine = 0;
        notifyCommandInputChanged();
        return true;
    }

    static String formatThinkingText(long elapsedSeconds) {
        long minutes = elapsedSeconds / 60;
        long seconds = elapsedSeconds % 60;
        String elapsed = minutes > 0
                ? minutes + " minutes, " + seconds + " seconds"
                : seconds + " seconds";
        return THINKING_TEXT + " (" + elapsed + ", type :abort to stop)";
    }

    private long elapsedSeconds() {
        if (!_pending || _pendingStartedAtMillis == 0) {
            return 0;
        }
        return Math.max(0, (System.currentTimeMillis() - _pendingStartedAtMillis) / 1000);
    }

    private void startPendingRefreshLoop() {
        long generation = ++_pendingRefreshGeneration;
        var thread = new Thread(() -> {
            while (_pending && _pendingRefreshGeneration == generation) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!_pending || _pendingRefreshGeneration != generation || getParent() == null) {
                    return;
                }
                EventThread.getInstance().enqueue(new RunnableEvent(this::setNeedsRedraw));
            }
        }, "swim-nemo-thinking");
        thread.setDaemon(true);
        thread.start();
    }

    private String prefixForSpeaker(String speaker) {
        return switch (speaker) {
        case "me" -> _promptStyle.historyPrefix();
        case "nemo" -> NEMO_PREFIX;
        default -> speaker + "> ";
        };
    }

    private AttributedString renderLine(String line) {
        return renderLine(line, _backgroundColour);
    }

    private AttributedString renderLine(String line, TextColor background) {
        if (line.startsWith(_promptStyle.historyPrefix())) {
            return renderPromptLine(_promptStyle.historyPrefix(), line.substring(_promptStyle.historyPrefix().length()),
                    UiTheme.CHAT_ME, background);
        }
        if (line.startsWith(NEMO_PREFIX)) {
            return renderPromptLine(NEMO_PREFIX, line.substring(NEMO_PREFIX.length()), UiTheme.CHAT_NEMO, background);
        }
        return AttributedString.create(line, UiTheme.TEXT_MUTED, background);
    }

    private AttributedString renderLine(DisplayLine line, TextColor background) {
        return switch (line.kind()) {
        case NORMAL -> renderLine(line.text(), background);
        case CODE_HEADER -> renderDecoratedLine(line.text(), UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        case CODE -> renderCodeLine(line.text(), background);
        case DIFF_HEADER -> renderDecoratedLine(line.text(), UiTheme.ACCENT_BLUE, UiTheme.SURFACE_ACCENT);
        case DIFF_HUNK -> renderDecoratedLine(line.text(), UiTheme.ACCENT_GOLD, background);
        case DIFF_ADDED -> renderDecoratedLine(line.text(), UiTheme.ACCENT_GREEN, background);
        case DIFF_REMOVED -> renderDecoratedLine(line.text(), UiTheme.ACCENT_RED, background);
        case DIFF_CONTEXT -> renderDecoratedLine(line.text(), UiTheme.TEXT_MUTED, background);
        };
    }

    private AttributedString renderDecoratedLine(String line, TextColor textColour, TextColor background) {
        if (line.startsWith(_promptStyle.historyPrefix())) {
            return renderPromptLine(_promptStyle.historyPrefix(), line.substring(_promptStyle.historyPrefix().length()),
                    UiTheme.CHAT_ME, textColour, background);
        }
        if (line.startsWith(NEMO_PREFIX)) {
            return renderPromptLine(NEMO_PREFIX, line.substring(NEMO_PREFIX.length()), UiTheme.CHAT_NEMO,
                    textColour, background);
        }
        return AttributedString.create(line, textColour, background);
    }

    private AttributedString renderCodeLine(String line, TextColor background) {
        var result = AttributedString.create(line, UiTheme.TEXT_PRIMARY, background);
        formatCodeKeywords(result, line, background);
        formatQuotedStrings(result, line, background);
        formatCodeComment(result, line, background);
        return result;
    }

    private static void formatCodeKeywords(AttributedString result, String line, TextColor background) {
        for (String keyword : CODE_KEYWORDS) {
            int index = line.indexOf(keyword);
            while (index >= 0) {
                int end = index + keyword.length();
                if (isWordBoundary(line, index - 1) && isWordBoundary(line, end)) {
                    result.format(index, end, UiTheme.ACCENT_BLUE, background);
                }
                index = line.indexOf(keyword, end);
            }
        }
    }

    private static boolean isWordBoundary(String line, int index) {
        return index < 0 || index >= line.length()
                || !(Character.isLetterOrDigit(line.charAt(index)) || line.charAt(index) == '_');
    }

    private static void formatQuotedStrings(AttributedString result, String line, TextColor background) {
        for (int index = 0; index < line.length(); index++) {
            char quote = line.charAt(index);
            if (quote != '"' && quote != '\'') {
                continue;
            }
            int start = index++;
            boolean escaped = false;
            while (index < line.length()) {
                char c = line.charAt(index++);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == quote) {
                    break;
                }
            }
            result.format(start, Math.min(index, line.length()), UiTheme.ACCENT_GREEN, background);
        }
    }

    private static void formatCodeComment(AttributedString result, String line, TextColor background) {
        int slashComment = line.indexOf("//");
        int hashComment = line.indexOf("#");
        int index = slashComment < 0 ? hashComment
                : hashComment < 0 ? slashComment
                : Math.min(slashComment, hashComment);
        if (index >= 0) {
            result.format(index, line.length(), UiTheme.TEXT_MUTED, background);
        }
    }

    private AttributedString renderPromptLine(String prompt, String text, TextColor promptColour) {
        return renderPromptLine(prompt, text, promptColour, _backgroundColour);
    }

    private AttributedString renderPromptLine(String prompt, String text, TextColor promptColour, TextColor background) {
        return renderPromptLine(prompt, text, promptColour, UiTheme.TEXT_PRIMARY, background);
    }

    private AttributedString renderPromptLine(String prompt, String text, TextColor promptColour, TextColor textColour,
            TextColor background) {
        var result = new AttributedString();
        result.append(prompt, promptColour, background);
        result.append(text, textColour, background);
        return result;
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            if (!window.enterNemoBrowse(this)) {
                window.hidePanel();
            }
        }
    }

    private void refreshChrome() {
        var window = Window.getInstance();
        if (window != null) {
            window.refreshChromeState();
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
            return;
        }
        setNeedsRedraw();
    }

    private void notifyCommandInputChanged() {
        resetCommandSelection();
        _onCommandInputChanged.accept(_input.toString());
        refreshChrome();
    }

    private boolean hasCommandMenuMatches() {
        if (!isCommandInputActive()) {
            return false;
        }
        var state = getCommandMenuState();
        return state.visible() && !state.matches().isEmpty();
    }

    private void moveCommandSelection(int delta) {
        var state = getCommandMenuState();
        if (state.matches().isEmpty()) {
            return;
        }
        _commandSelection = Math.floorMod(_commandSelection + delta, state.matches().size());
        refreshChrome();
    }

    private void completeSelectedCommand() {
        var state = getCommandMenuState();
        var spec = state.selectedMatch();
        if (spec == null) {
            return;
        }
        applyCommandSpec(spec);
        resetCommandSelection();
        ensureInputVisible();
        _onCommandInputChanged.accept(_input.toString());
        refreshChrome();
    }

    private boolean canSubmitSelectedCommand() {
        if (!hasCommandMenuMatches()) {
            return false;
        }
        var spec = getCommandMenuState().selectedMatch();
        return spec != null && (spec.replaceEntireInput() || !spec.expectsArgument());
    }

    private void submitSelectedCommand() {
        var state = getCommandMenuState();
        var spec = state.selectedMatch();
        if (spec == null) {
            return;
        }
        applyCommandSpec(spec);
        String command = _input.toString().trim();
        if (!command.isBlank()) {
            _onCommand.accept(command);
        }
        _input.setLength(0);
        _cursorOffset = 0;
        _inputScrollLine = 0;
        resetCommandSelection();
        _onCommandInputChanged.accept(_input.toString());
        refreshChrome();
    }

    private void applyCommandSpec(CommandView.CommandSpec spec) {
        if (!isCommandInputActive()) {
            return;
        }
        if (spec.replaceEntireInput()) {
            replaceCommandInput(spec.replacement());
            return;
        }

        String current = _input.substring(1);
        int tokenStart = 0;
        while (tokenStart < current.length() && Character.isWhitespace(current.charAt(tokenStart))) {
            tokenStart++;
        }
        int tokenEnd = tokenStart;
        while (tokenEnd < current.length() && !Character.isWhitespace(current.charAt(tokenEnd))) {
            tokenEnd++;
        }
        String before = current.substring(0, tokenStart);
        String after = current.substring(tokenEnd);
        boolean hasArguments = !after.isBlank();
        String replacement = spec.replacement();
        if (!hasArguments && spec.expectsArgument()) {
            replacement += " ";
        }
        replaceCommandInput(before + replacement + after);
    }

    private void replaceCommandInput(String commandText) {
        _input.setLength(0);
        _input.append(':').append(commandText == null ? "" : commandText);
        _cursorOffset = _input.length();
    }

    private void resetCommandSelection() {
        _commandSelection = 0;
    }

    List<String> inputLines() {
        int width = Math.max(1, getBounds().getSize().getWidth() - (_promptStyle.inputPrefix().length() + 1));
        String text = _input.length() == 0 ? _promptStyle.emptyHint() : _input.toString();
        var wrapped = TextPanelView.wrapText(text, width);
        return wrapped.isEmpty() ? List.of("") : wrapped;
    }

    private int inputHeight() {
        return Math.max(1, inputLines().size());
    }

    private int bodyHeight() {
        return Math.max(0, getBounds().getSize().getHeight() - inputHeight() - 1);
    }

    private void ensureInputVisible() {
        int maxVisibleInputLines = Math.max(1, getBounds().getSize().getHeight() - 1);
        int cursorLine = cursorLine();
        if (cursorLine < _inputScrollLine) {
            _inputScrollLine = cursorLine;
            return;
        }
        int maxScroll = Math.max(0, inputLines().size() - maxVisibleInputLines);
        int visibleBottomExclusive = _inputScrollLine + maxVisibleInputLines;
        if (cursorLine >= visibleBottomExclusive) {
            _inputScrollLine = Math.min(maxScroll, cursorLine - maxVisibleInputLines + 1);
            return;
        }
        _inputScrollLine = Math.min(_inputScrollLine, maxScroll);
    }

    private int cursorLine() {
        if (_cursorOffset <= 0) {
            return 0;
        }
        int width = Math.max(1, getBounds().getSize().getWidth() - (_promptStyle.inputPrefix().length() + 1));
        return TextPanelView.wrapText(_input.substring(0, _cursorOffset), width).size() - 1;
    }

    private int cursorColumn(int cursorLine) {
        if (_cursorOffset <= 0) {
            return 0;
        }
        int width = Math.max(1, getBounds().getSize().getWidth() - (_promptStyle.inputPrefix().length() + 1));
        var wrapped = TextPanelView.wrapText(_input.substring(0, _cursorOffset), width);
        if (wrapped.isEmpty()) {
            return 0;
        }
        return wrapped.get(Math.max(0, Math.min(cursorLine, wrapped.size() - 1))).length();
    }

    private void scrollToBottom() {
        _startLine = Math.max(0, getDisplayLines().size() - bodyHeight());
    }

    private void scrollDown(int amount) {
        int maxStart = Math.max(0, getDisplayLines().size() - bodyHeight());
        _startLine = Math.min(maxStart, _startLine + amount);
        setNeedsRedraw();
    }

    private void scrollUp(int amount) {
        _startLine = Math.max(0, _startLine - amount);
        setNeedsRedraw();
    }

    private int visibleInputHeight() {
        return Math.max(1, getBounds().getSize().getHeight() - 1 - bodyHeight());
    }

    private int visibleInputStart(List<String> inputLines) {
        return Math.min(_inputScrollLine, Math.max(0, inputLines.size() - visibleInputHeight()));
    }

    private Point cursorScreenPosition() {
        var lines = inputLines();
        int inputStart = visibleInputStart(lines);
        int inputTop = getBounds().getSize().getHeight() - visibleInputHeight();
        int cursorLine = cursorLine();
        int visibleLine = Math.max(0, Math.min(visibleInputHeight() - 1, cursorLine - inputStart));
        int prefixLength = _promptStyle.inputPrefix().length() + 1;
        int cursorColumn = cursorColumn(cursorLine);
        int maxColumn = Math.max(0, getBounds().getSize().getWidth() - 1);
        int x = Math.min(maxColumn, prefixLength + cursorColumn);
        Point origin = absoluteOrigin();
        return Point.create(origin.getX() + x, origin.getY() + Math.max(0, inputTop + visibleLine));
    }

    private boolean canScrollInput() {
        return inputLines().size() > visibleInputHeight();
    }

    private void scrollInput(int amount) {
        var lines = inputLines();
        int maxStart = Math.max(0, lines.size() - visibleInputHeight());
        _inputScrollLine = Math.max(0, Math.min(maxStart, _inputScrollLine + amount));
        setNeedsRedraw();
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        _responseAction = null;
        if (events.remaining() != 0) {
            String pastedText = pasteText(events);
            if (pastedText == null) {
                return Response.NO;
            }
            _responseAction = () -> insertInputText(pastedText);
            return Response.YES;
        }

        var event = events.current();
        if (event instanceof MouseAction mouseAction) {
            return processMouseAction(mouseAction);
        }
        if (event.getKeyType() == TerminalContext.BRACKETED_PASTE_START_KEY) {
            _responseAction = () -> _bracketedPasteActive = true;
            return Response.YES;
        }
        if (event.getKeyType() == TerminalContext.BRACKETED_PASTE_END_KEY) {
            _responseAction = () -> _bracketedPasteActive = false;
            return Response.YES;
        }
        switch (event.getKeyType()) {
        case Escape:
            _responseAction = this::close;
            return Response.YES;
        case ArrowDown:
            if (hasCommandMenuMatches()) {
                _responseAction = () -> moveCommandSelection(1);
                return Response.YES;
            }
            _responseAction = () -> scrollDown(1);
            return Response.YES;
        case ArrowUp:
            if (hasCommandMenuMatches()) {
                _responseAction = () -> moveCommandSelection(-1);
                return Response.YES;
            }
            _responseAction = () -> scrollUp(1);
            return Response.YES;
        case ReverseTab:
            if (!hasCommandMenuMatches()) {
                return Response.NO;
            }
            _responseAction = () -> moveCommandSelection(-1);
            return Response.YES;
        case Tab:
            if (_bracketedPasteActive) {
                _responseAction = () -> insertInputText("\t");
                return Response.YES;
            }
            if (!hasCommandMenuMatches()) {
                return Response.NO;
            }
            _responseAction = this::completeSelectedCommand;
            return Response.YES;
        case ArrowLeft:
            if (_cursorOffset == 0) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset--;
                ensureInputVisible();
                refreshChrome();
            };
            return Response.YES;
        case ArrowRight:
            if (_cursorOffset >= _input.length()) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset++;
                ensureInputVisible();
                refreshChrome();
            };
            return Response.YES;
        case Home:
            if (_cursorOffset == 0) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset = 0;
                ensureInputVisible();
                refreshChrome();
            };
            return Response.YES;
        case End:
            if (_cursorOffset >= _input.length()) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset = _input.length();
                ensureInputVisible();
                refreshChrome();
            };
            return Response.YES;
        case Enter:
            if (_bracketedPasteActive || event.isShiftDown() || event.isCtrlDown() || event.isAltDown()) {
                _responseAction = () -> insertInputText("\n");
                return Response.YES;
            }
            if (canSubmitSelectedCommand()) {
                _responseAction = this::submitSelectedCommand;
                return Response.YES;
            }
            String message = _input.toString().trim();
            if (message.equals("")) {
                return Response.NO;
            }
            _responseAction = () -> {
                if (message.startsWith(":")) {
                    _onCommand.accept(message);
                } else {
                    _onSubmit.accept(message);
                }
                _input.setLength(0);
                _cursorOffset = 0;
                _inputScrollLine = 0;
                notifyCommandInputChanged();
            };
            return Response.YES;
        case Backspace:
            if (_cursorOffset == 0) {
                return Response.NO;
            }
            _responseAction = () -> {
                _input.deleteCharAt(_cursorOffset - 1);
                _cursorOffset--;
                ensureInputVisible();
                notifyCommandInputChanged();
            };
            return Response.YES;
        case Character:
            Character character = event.getCharacter();
            if (character == null) {
                return Response.NO;
            }
            if (event.isCtrlDown()) {
                if (character == 'a' || character == 'A') {
                    if (_cursorOffset == 0) {
                        return Response.NO;
                    }
                    _responseAction = () -> {
                        _cursorOffset = 0;
                        ensureInputVisible();
                        refreshChrome();
                    };
                    return Response.YES;
                }
                if (character == 'e' || character == 'E') {
                    if (_cursorOffset >= _input.length()) {
                        return Response.NO;
                    }
                    _responseAction = () -> {
                        _cursorOffset = _input.length();
                        ensureInputVisible();
                        refreshChrome();
                    };
                    return Response.YES;
                }
                if (character == 'j' || character == 'J') {
                    _responseAction = () -> insertInputText("\n");
                    return Response.YES;
                }
                return Response.NO;
            }
            _responseAction = () -> insertInputText(normalizeInputCharacter(character));
            return Response.YES;
        default:
            return Response.NO;
        }
    }

    private Response processMouseAction(MouseAction action) {
        if (action.getPosition() == null) {
            return Response.NO;
        }
        Point origin = absoluteOrigin();
        int localX = action.getPosition().getColumn() - origin.getX();
        int localY = action.getPosition().getRow() - origin.getY();
        int width = getBounds().getSize().getWidth();
        int height = getBounds().getSize().getHeight();
        if (localX < 0 || localY < 0 || localX >= width || localY >= height) {
            return Response.NO;
        }

        if (action.getActionType() == MouseActionType.SCROLL_UP
                || action.getActionType() == MouseActionType.SCROLL_DOWN) {
            int delta = action.getActionType() == MouseActionType.SCROLL_DOWN ? 3 : -3;
            _responseAction = () -> scrollForMouse(localY, delta);
            return Response.YES;
        }
        if (action.getActionType() == MouseActionType.CLICK_DOWN) {
            _responseAction = () -> {
                var window = Window.getInstance();
                if (window != null) {
                    window.activateView(this);
                }
                moveCursorForMouse(localX, localY);
            };
            return Response.YES;
        }
        if (action.getActionType() == MouseActionType.CLICK_RELEASE) {
            _responseAction = () -> {};
            return Response.YES;
        }
        return Response.NO;
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

    private void scrollForMouse(int localY, int delta) {
        int inputTop = getBounds().getSize().getHeight() - visibleInputHeight();
        if (localY >= inputTop && canScrollInput()) {
            scrollInput(delta);
        } else if (delta < 0) {
            scrollUp(-delta);
        } else {
            scrollDown(delta);
        }
    }

    private void moveCursorForMouse(int localX, int localY) {
        int inputTop = getBounds().getSize().getHeight() - visibleInputHeight();
        if (localY < inputTop) {
            return;
        }
        var lines = inputLines();
        int lineIndex = visibleInputStart(lines) + localY - inputTop;
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            return;
        }
        int prefixLength = _promptStyle.inputPrefix().length() + 1;
        int column = Math.max(0, localX - prefixLength);
        int offset = 0;
        for (int i = 0; i < lineIndex; i++) {
            offset += lines.get(i).length();
        }
        offset += Math.min(column, lines.get(lineIndex).length());
        _cursorOffset = Math.max(0, Math.min(_input.length(), offset));
        ensureInputVisible();
        refreshChrome();
    }

    private static String pasteText(KeyStrokes events) {
        var text = new StringBuilder();
        for (var event : events) {
            if (event.getKeyType() == TerminalContext.BRACKETED_PASTE_START_KEY
                    || event.getKeyType() == TerminalContext.BRACKETED_PASTE_END_KEY) {
                continue;
            }
            switch (event.getKeyType()) {
            case Character:
                Character character = event.getCharacter();
                if (character == null || event.isCtrlDown() || event.isAltDown()) {
                    return null;
                }
                text.append(normalizeInputCharacter(character));
                break;
            case Enter:
                text.append('\n');
                break;
            case Tab:
                text.append('\t');
                break;
            default:
                return null;
            }
        }
        return text.toString();
    }

    private static String normalizeInputCharacter(char character) {
        return switch (character) {
        case '\r' -> "\n";
        default -> String.valueOf(character);
        };
    }

    private void insertInputText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        _input.insert(_cursorOffset, text);
        _cursorOffset += text.length();
        ensureInputVisible();
        notifyCommandInputChanged();
    }

    @Override
    public void respond() {
        if (_responseAction != null) {
            _responseAction.run();
            _responseAction = null;
        }
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var graphics = terminalContext.getGraphics();
        int width = rect.getSize().getWidth();

        var title = new AttributedString();
        title.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        title.append(_pending ? _promptStyle.titlePendingLabel() : _promptStyle.titleReadyLabel(),
                _pending ? UiTheme.ACCENT_GOLD : UiTheme.ACCENT_GREEN,
                UiTheme.SURFACE_ACCENT);
        if (_contextUsagePercent != null) {
            title.append(" ctx " + _contextUsagePercent + "% ", contextUsageColour(_contextUsagePercent),
                    UiTheme.SURFACE_ACCENT);
        }
        UiTheme.drawLine(graphics, rect.getPoint(), width, title, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var lines = getDisplayRows();
        int bodyHeight = bodyHeight();
        for (int i = 0; i < bodyHeight && _startLine + i < lines.size(); i++) {
            DisplayLine line = lines.get(_startLine + i);
            TextColor background = rowBackground(line, i);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i), width,
                    renderLine(line, background), UiTheme.TEXT_MUTED, background);
        }

        var inputLines = inputLines();
        int inputHeight = visibleInputHeight();
        int inputStart = visibleInputStart(inputLines);
        int inputY = rect.getPoint().getY() + rect.getSize().getHeight() - inputHeight;
        int cursorLine = cursorLine();
        int cursorColumn = cursorColumn(cursorLine);
        for (int i = 0; i < inputHeight && inputStart + i < inputLines.size(); i++) {
            var input = new AttributedString();
            String prefix = i == 0 ? " " + _promptStyle.inputPrefix() : " ".repeat(_promptStyle.inputPrefix().length() + 1);
            input.append(prefix, UiTheme.CHAT_ME, UiTheme.COMMAND_BACKGROUND);
            TextColor textColour = _input.length() == 0 ? UiTheme.TEXT_SUBTLE : UiTheme.TEXT_PRIMARY;
            String inputLine = inputLines.get(inputStart + i);
            input.append(inputLine, textColour, UiTheme.COMMAND_BACKGROUND);
            if (_input.length() > 0 && inputStart + i == cursorLine && cursorColumn < inputLine.length()) {
                int caretIndex = Math.max(0, cursorColumn);
                input.format(prefix.length() + caretIndex, prefix.length() + caretIndex + 1,
                        UiTheme.COMMAND_BACKGROUND, textColour);
            }
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), inputY + i), width, input,
                    UiTheme.TEXT_MUTED, UiTheme.COMMAND_BACKGROUND);
        }
    }

    private static TextColor contextUsageColour(int usagePercent) {
        if (usagePercent >= 90) {
            return UiTheme.ACCENT_RED;
        }
        if (usagePercent >= 75) {
            return UiTheme.ACCENT_GOLD;
        }
        return UiTheme.ACCENT_GREEN;
    }
}
