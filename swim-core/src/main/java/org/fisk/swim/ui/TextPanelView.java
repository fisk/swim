package org.fisk.swim.ui;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.terminal.TerminalContext;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyType;

public class TextPanelView extends View {
    private final String _title;
    private final String _text;
    private final ListEventResponder _responders = new ListEventResponder();
    private int _startLine;

    public TextPanelView(Rect bounds, String title, String text) {
        super(bounds);
        _title = title;
        _text = text;
        setBackgroundColour(UiTheme.SURFACE_BACKGROUND);

        _responders.addEventResponder("<ESC>", this::close);
        _responders.addEventResponder("q", this::close);
        _responders.addEventResponder("<DOWN>", () -> scrollDown(1));
        _responders.addEventResponder("j", () -> scrollDown(1));
        _responders.addEventResponder("<UP>", () -> scrollUp(1));
        _responders.addEventResponder("k", () -> scrollUp(1));
    }

    int getStartLine() {
        return _startLine;
    }

    String getTitle() {
        return _title;
    }

    static List<String> wrapText(String text, int width) {
        var lines = new ArrayList<String>();
        if (width <= 0) {
            return lines;
        }
        for (String rawLine : text.split("\\R", -1)) {
            if (rawLine.isEmpty()) {
                lines.add("");
                continue;
            }
            String line = rawLine;
            while (line.length() > width) {
                int split = line.lastIndexOf(' ', width);
                if (split <= 0) {
                    split = width;
                }
                lines.add(line.substring(0, split));
                line = line.substring(Math.min(split + 1, line.length()));
            }
            lines.add(line);
        }
        return lines;
    }

    private void close() {
        var window = Window.getInstance();
        if (window != null) {
            window.hidePanel();
        }
    }

    private List<String> getWrappedLines() {
        return wrapText(_text, getBounds().getSize().getWidth());
    }

    private void scrollDown(int amount) {
        int maxStart = Math.max(0, getWrappedLines().size() - Math.max(0, getBounds().getSize().getHeight() - 2));
        _startLine = Math.min(maxStart, _startLine + amount);
        setNeedsRedraw();
    }

    private void scrollUp(int amount) {
        _startLine = Math.max(0, _startLine - amount);
        setNeedsRedraw();
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        return _responders.processEvent(events);
    }

    @Override
    public void respond() {
        _responders.respond();
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var graphics = terminalContext.getGraphics();
        int width = rect.getSize().getWidth();

        var title = new AttributedString();
        title.append(" " + _title + " ", UiTheme.TEXT_ON_ACCENT, UiTheme.SURFACE_ACCENT);
        title.append(" j/k scroll  q close ", UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);
        UiTheme.drawLine(graphics, rect.getPoint(), width, title, UiTheme.TEXT_MUTED, UiTheme.SURFACE_ACCENT);

        var lines = getWrappedLines();
        int bodyHeight = Math.max(0, rect.getSize().getHeight() - 2);
        for (int i = 0; i < bodyHeight && _startLine + i < lines.size(); i++) {
            TextColor background = i % 2 == 0 ? UiTheme.SURFACE_BACKGROUND : UiTheme.SURFACE_ELEVATED;
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(), rect.getPoint().getY() + 1 + i), width,
                    AttributedString.create(" " + lines.get(_startLine + i), UiTheme.TEXT_PRIMARY, background),
                    UiTheme.TEXT_MUTED, background);
        }

        if (rect.getSize().getHeight() > 2) {
            int visibleEnd = Math.min(lines.size(), _startLine + bodyHeight);
            String footerText = " " + visibleEnd + "/" + lines.size() + " lines";
            var footer = new AttributedString();
            footer.append(footerText, UiTheme.ACCENT_BLUE, UiTheme.SURFACE_MUTED);
            footer.append("  Esc dismiss", UiTheme.TEXT_MUTED, UiTheme.SURFACE_MUTED);
            UiTheme.drawLine(graphics, Point.create(rect.getPoint().getX(),
                    rect.getPoint().getY() + rect.getSize().getHeight() - 1), width, footer, UiTheme.TEXT_MUTED,
                    UiTheme.SURFACE_MUTED);
        }
    }
}
