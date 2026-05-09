package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.terminal.TerminalContext;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class ChatPanelView extends View {
    record ChatMessage(String speaker, String text) {
    }

    private final String _title;
    private final Consumer<String> _onSubmit;
    private final List<ChatMessage> _messages = new ArrayList<>();
    private final StringBuilder _input = new StringBuilder();
    private int _startLine;
    private boolean _pending;
    private Runnable _responseAction;

    public ChatPanelView(Rect bounds, String title, Consumer<String> onSubmit) {
        super(bounds);
        _title = title;
        _onSubmit = onSubmit;
        setBackgroundColour(TextColor.ANSI.DEFAULT);
    }

    int getStartLine() {
        return _startLine;
    }

    String getInputText() {
        return _input.toString();
    }

    boolean isPending() {
        return _pending;
    }

    List<String> getDisplayLines() {
        var lines = new ArrayList<String>();
        int width = Math.max(1, getBounds().getSize().getWidth());
        for (var message : _messages) {
            String prefix = message.speaker() + "> ";
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
        return lines;
    }

    public void appendMessage(String speaker, String text) {
        _messages.add(new ChatMessage(speaker, text));
        scrollToBottom();
        setNeedsRedraw();
    }

    public void setPending(boolean pending) {
        _pending = pending;
        setNeedsRedraw();
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            window.hidePanel();
        }
    }

    private int bodyHeight() {
        return Math.max(0, getBounds().getSize().getHeight() - 2);
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
        case Backspace:
            if (_pending || _input.isEmpty()) {
                return Response.NO;
            }
            _responseAction = () -> {
                _input.deleteCharAt(_input.length() - 1);
                setNeedsRedraw();
            };
            return Response.YES;
        case Enter:
            if (_pending) {
                return Response.NO;
            }
            String message = _input.toString().trim();
            if (message.isEmpty()) {
                return Response.NO;
            }
            _responseAction = () -> {
                _input.setLength(0);
                _onSubmit.accept(message);
                setNeedsRedraw();
            };
            return Response.YES;
        case Character:
            if (_pending) {
                return Response.NO;
            }
            char character = event.getCharacter();
            _responseAction = () -> {
                _input.append(character);
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

        graphics.setBackgroundColor(TextColor.ANSI.GREEN);
        graphics.drawRectangle(new TerminalPosition(rect.getPoint().getX(), rect.getPoint().getY()),
                new TerminalSize(rect.getSize().getWidth(), 1), ' ');
        AttributedString.create(_title, TextColor.ANSI.BLACK, TextColor.ANSI.GREEN)
                .drawAt(rect.getPoint(), graphics);

        var lines = getDisplayLines();
        int bodyHeight = bodyHeight();
        for (int i = 0; i < bodyHeight && _startLine + i < lines.size(); i++) {
            AttributedString.create(lines.get(_startLine + i), TextColor.ANSI.DEFAULT, _backgroundColour)
                    .drawAt(Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i), graphics);
        }

        int inputY = rect.getPoint().getY() + rect.getSize().getHeight() - 1;
        graphics.setBackgroundColor(TextColor.ANSI.BLACK);
        graphics.drawRectangle(new TerminalPosition(rect.getPoint().getX(), inputY),
                new TerminalSize(rect.getSize().getWidth(), 1), ' ');
        String inputLabel = _pending ? "nemo> ..." : "me> " + _input;
        AttributedString.create(inputLabel, TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK)
                .drawAt(Point.create(rect.getPoint().getX(), inputY), graphics);
    }
}
