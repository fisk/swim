package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.terminal.TerminalContext;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class ChatPanelView extends View {
    private static final String ME_PREFIX = "me> ";
    private static final String NEMO_PREFIX = "nemo> ";
    private static final String THINKING_TEXT = "*thinking*";

    public record ChatMessage(String speaker, String text) {
    }

    private final String _title;
    private final Consumer<String> _onSubmit;
    private final Consumer<String> _onCommand;
    private final List<ChatMessage> _messages = new ArrayList<>();
    private final StringBuilder _input = new StringBuilder();
    private int _cursorOffset;
    private int _inputScrollLine;
    private int _startLine;
    private boolean _pending;
    private long _pendingStartedAtMillis;
    private Integer _contextUsagePercent;
    private long _pendingRefreshGeneration;
    private Runnable _responseAction;

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit) {
        this(bounds, title, onSubmit, ignored -> {});
    }

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit, Consumer<String> onCommand) {
        super(bounds);
        _title = title;
        _onSubmit = onSubmit;
        _onCommand = onCommand;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);
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

    Integer getContextUsagePercent() {
        return _contextUsagePercent;
    }

    List<String> getDisplayLines() {
        var lines = new ArrayList<String>();
        int width = Math.max(1, getBounds().getSize().getWidth());
        for (var message : _messages) {
            String prefix = prefixForSpeaker(message.speaker());
            var wrapped = TextPanelView.wrapText(message.text(), Math.max(1, width - prefix.length()));
            if (wrapped.isEmpty()) {
                lines.add(prefix);
                continue;
            }
            lines.add(prefix + wrapped.get(0));
            for (int i = 1; i < wrapped.size(); i++) {
                lines.add(" ".repeat(prefix.length()) + wrapped.get(i));
            }
        }
        if (_pending) {
            lines.add(NEMO_PREFIX + formatThinkingText(elapsedSeconds()));
        }
        return lines;
    }

    public void appendMessage(String speaker, String text) {
        _messages.add(new ChatMessage(speaker, text));
        scrollToBottom();
        setNeedsRedraw();
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

    private static String prefixForSpeaker(String speaker) {
        return switch (speaker) {
        case "me" -> ME_PREFIX;
        case "nemo" -> NEMO_PREFIX;
        default -> speaker + "> ";
        };
    }

    private AttributedString renderLine(String line) {
        return renderLine(line, _backgroundColour);
    }

    private AttributedString renderLine(String line, TextColor background) {
        if (line.startsWith(ME_PREFIX)) {
            return renderPromptLine(ME_PREFIX, line.substring(ME_PREFIX.length()), UiTheme.CHAT_ME, background);
        }
        if (line.startsWith(NEMO_PREFIX)) {
            return renderPromptLine(NEMO_PREFIX, line.substring(NEMO_PREFIX.length()), UiTheme.CHAT_NEMO, background);
        }
        return AttributedString.create(line, UiTheme.TEXT_MUTED, background);
    }

    private AttributedString renderPromptLine(String prompt, String text, TextColor promptColour) {
        return renderPromptLine(prompt, text, promptColour, _backgroundColour);
    }

    private AttributedString renderPromptLine(String prompt, String text, TextColor promptColour, TextColor background) {
        var result = new AttributedString();
        result.append(prompt, promptColour, background);
        result.append(text, UiTheme.TEXT_PRIMARY, background);
        return result;
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            window.hidePanel();
        }
    }

    List<String> inputLines() {
        int width = Math.max(1, getBounds().getSize().getWidth() - (ME_PREFIX.length() + 1));
        String text = _input.length() == 0 ? "type a message or :abort" : _input.toString();
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
        int width = Math.max(1, getBounds().getSize().getWidth() - (ME_PREFIX.length() + 1));
        return TextPanelView.wrapText(_input.substring(0, _cursorOffset), width).size() - 1;
    }

    private int cursorColumn(int cursorLine) {
        if (_cursorOffset <= 0) {
            return 0;
        }
        int width = Math.max(1, getBounds().getSize().getWidth() - (ME_PREFIX.length() + 1));
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

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0) {
            return Response.NO;
        }

        var event = events.current();
        _responseAction = null;
        switch (event.getKeyType()) {
        case Escape:
            _responseAction = this::close;
            return Response.YES;
        case ArrowDown:
            _responseAction = () -> scrollDown(1);
            return Response.YES;
        case ArrowUp:
            _responseAction = () -> scrollUp(1);
            return Response.YES;
        case ArrowLeft:
            if (_cursorOffset == 0) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset--;
                ensureInputVisible();
                setNeedsRedraw();
            };
            return Response.YES;
        case ArrowRight:
            if (_cursorOffset >= _input.length()) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset++;
                ensureInputVisible();
                setNeedsRedraw();
            };
            return Response.YES;
        case Home:
            if (_cursorOffset == 0) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset = 0;
                ensureInputVisible();
                setNeedsRedraw();
            };
            return Response.YES;
        case End:
            if (_cursorOffset >= _input.length()) {
                return Response.NO;
            }
            _responseAction = () -> {
                _cursorOffset = _input.length();
                ensureInputVisible();
                setNeedsRedraw();
            };
            return Response.YES;
        case Enter:
            if (event.isShiftDown()) {
                _responseAction = () -> {
                    _input.insert(_cursorOffset, '\n');
                    _cursorOffset++;
                    ensureInputVisible();
                    setNeedsRedraw();
                };
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
                setNeedsRedraw();
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
                setNeedsRedraw();
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
                        setNeedsRedraw();
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
                        setNeedsRedraw();
                    };
                    return Response.YES;
                }
                return Response.NO;
            }
            _responseAction = () -> {
                _input.insert(_cursorOffset, character);
                _cursorOffset++;
                ensureInputVisible();
                setNeedsRedraw();
            };
            return Response.YES;
        default:
            return Response.NO;
        }
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
        title.append(_pending ? " waiting for Nemo " : " ready ", _pending ? UiTheme.ACCENT_GOLD : UiTheme.ACCENT_GREEN,
                UiTheme.SURFACE_ACCENT);
        if (_contextUsagePercent != null) {
            title.append(" ctx " + _contextUsagePercent + "% ", contextUsageColour(_contextUsagePercent),
                    UiTheme.SURFACE_ACCENT);
        }
        title.append(" esc close ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, title, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var lines = getDisplayLines();
        int bodyHeight = bodyHeight();
        for (int i = 0; i < bodyHeight && _startLine + i < lines.size(); i++) {
            TextColor background = i % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i), width,
                    renderLine(lines.get(_startLine + i), background), UiTheme.TEXT_MUTED, background);
        }

        var inputLines = inputLines();
        int inputHeight = Math.max(1, rect.getSize().getHeight() - 1 - bodyHeight);
        int inputStart = Math.min(_inputScrollLine, Math.max(0, inputLines.size() - 1));
        int inputY = rect.getPoint().getY() + rect.getSize().getHeight() - inputHeight;
        int cursorLine = cursorLine();
        int cursorColumn = cursorColumn(cursorLine);
        for (int i = 0; i < inputHeight && inputStart + i < inputLines.size(); i++) {
            var input = new AttributedString();
            String prefix = i == 0 ? " me> " : " ".repeat(ME_PREFIX.length() + 1);
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
