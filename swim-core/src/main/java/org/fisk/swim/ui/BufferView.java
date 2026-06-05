package org.fisk.swim.ui;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.DiagnosticService;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class BufferView extends View {
    private static final int MIN_DECORATED_WIDTH = 6;
    private static final String GUTTER_SEPARATOR = "│";
    private static final String SCROLLBAR_TRACK = "│";
    private static final String SCROLLBAR_THUMB = "█";
    private BufferContext _bufferContext;
    private static final Logger _log = LogFactory.createLog();
    private int _startLine = 0;

    public int getStartLine() {
        return _startLine;
    }

    public int getViewportHeight() {
        return getBounds().getSize().getHeight();
    }

    public int getTextColumnStart() {
        return getLineNumberGutterWidth();
    }

    public int getTextWidth() {
        int totalWidth = getBounds().getSize().getWidth();
        return Math.max(1, totalWidth - getLineNumberGutterWidth() - getScrollbarWidth());
    }

    public BufferView(Rect rect, BufferContext bufferContext) {
        super(rect);
        _bufferContext = bufferContext;
    }

    public AttributedString getString() {
        return AttributedString.create(_bufferContext.getBuffer().getString(), _backgroundColour, TextColor.ANSI.DEFAULT);
    }

    BufferContext getBufferContext() {
        return _bufferContext;
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() == 1 && events.current() instanceof MouseAction mouseAction) {
            handleMouseAction(mouseAction);
            return Response.NO;
        }
        if (Window.getInstance() != null) {
            Window.getInstance().hideHoverDiagnostics();
        }
        return super.processEvent(events);
    }

    @Override
    public void setBounds(Rect rect) {
        var previous = getBounds();
        super.setBounds(rect);
        if (previous != null
                && previous.getSize().equals(rect.getSize())
                && previous.getPoint().equals(rect.getPoint())) {
            return;
        }
        var textLayout = _bufferContext.getTextLayout();
        if (textLayout != null) {
            textLayout.calculate();
            int maxStartLine = maxStartLine(textLayout);
            _startLine = Math.max(0, Math.min(_startLine, maxStartLine));
        }
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var terminalContext = TerminalContext.getInstance();
        var textGraphics = terminalContext.getGraphics();
        _log.debug("Draw buffer view");
        var mode = Window.getInstance().getCurrentMode();
        mode.draw(rect);
        var attrString = _bufferContext.getBuffer().getAttributedString();
        _log.debug("Attributed string length: " + attrString.length());
        var textLayout = _bufferContext.getTextLayout();
        var visibleLines = textLayout.getVisibleLogicalLines();
        drawLineNumberGutter(rect, textGraphics, textLayout, visibleLines);
        drawScrollbar(rect, textGraphics, textLayout);
        int textX = rect.getPoint().getX() + getTextColumnStart();
        for (var line : visibleLines) {
            for (var glyph : line.getGlyphs()) {
                AttributedString character;
                if (!glyph.isSynthetic()
                        && glyph.getPosition() >= 0
                        && glyph.getPosition() < attrString.length()
                        && attrString.getCharacter(glyph.getPosition()).toString().equals(glyph.getCharacter())) {
                    character = attrString.getCharacter(glyph.getPosition());
                } else {
                    character = AttributedString.create(glyph.getCharacter(), UiTheme.TEXT_MUTED, _backgroundColour);
                }
                character = applyDiagnosticBackground(glyph, character);
                character = mode.decorate(glyph, character);
                var point = Point.create(textX + glyph.getX(), rect.getPoint().getY() + glyph.getY() - _startLine);
                character.drawAt(point, textGraphics);
            }
        }
    }

    @Override
    public Cursor getCursor() {
        return _bufferContext.getBuffer().getCursor();
    }

    public void adaptCursorToView() {
        int cursorY = getCursor().getYAbsolute();
        var height = getBounds().getSize().getHeight();
        if (cursorY >= _startLine + height) {
            getCursor().goUp();
        } else if (cursorY < _startLine) {
            getCursor().goDown();
        }
    }

    public void adaptViewToCursor() {
        int cursorY = getCursor().getYAbsolute();
        var height = getBounds().getSize().getHeight();
        _log.debug("Cursor Y" + cursorY  + " height: " + height + " _startLine: " + _startLine);
        if (cursorY >= _startLine + height) {
            _startLine = cursorY - height + 1;
        } else if (cursorY < _startLine) {
            _startLine = cursorY;
        }
    }

    public void scrollUp() {
        if (_startLine <= 0) {
            return;
        }
        _startLine--;
        adaptCursorToView();
        setNeedsRedraw();
    }

    public void scrollDown() {
        var textLayout = _bufferContext.getTextLayout();
        int maxStartLine = maxStartLine(textLayout);
        if (_startLine >= maxStartLine) {
            return;
        }
        _startLine++;
        adaptCursorToView();
        setNeedsRedraw();
    }

    public void scrollPageUp() {
        int amount = Math.max(1, getBounds().getSize().getHeight() - 1);
        for (int i = 0; i < amount; i++) {
            scrollUp();
        }
    }

    public void scrollPageDown() {
        int amount = Math.max(1, getBounds().getSize().getHeight() - 1);
        for (int i = 0; i < amount; i++) {
            scrollDown();
        }
    }

    private AttributedString applyDiagnosticBackground(org.fisk.swim.text.TextLayout.Glyph glyph, AttributedString character) {
        if (glyph.isSynthetic() || character.getFragments().isEmpty()) {
            return character;
        }
        int sourceLine = _bufferContext.getTextLayout().getPhysicalLineAt(glyph.getPosition()).getY();
        var severity = DiagnosticService.getInstance().lineSeverity(_bufferContext, sourceLine);
        TextColor background = null;
        if (DiagnosticSeverity.Error.equals(severity)) {
            background = UiTheme.DIAGNOSTIC_ERROR_BACKGROUND;
        } else if (DiagnosticSeverity.Warning.equals(severity)) {
            background = UiTheme.DIAGNOSTIC_WARNING_BACKGROUND;
        }
        if (background == null) {
            return character;
        }
        var attributes = character.getFragments().get(0).getAttributes();
        return AttributedString.create(character.toString(), attributes.foregroundColour(), background);
    }

    private void handleMouseAction(MouseAction action) {
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        if (action.getActionType() != MouseActionType.MOVE
                && action.getActionType() != MouseActionType.DRAG
                && action.getActionType() != MouseActionType.CLICK_DOWN) {
            return;
        }
        Point origin = absoluteOrigin();
        int localX = action.getPosition().getColumn() - origin.getX();
        int localY = action.getPosition().getRow() - origin.getY();
        int textStart = getTextColumnStart();
        if (localX < 0 || localY < 0
                || localX >= getBounds().getSize().getWidth()
                || localY >= getBounds().getSize().getHeight()
                || localX < textStart
                || localX >= textStart + getTextWidth()) {
            window.hideHoverDiagnostics();
            return;
        }
        var visibleLines = _bufferContext.getTextLayout().getVisibleLogicalLines();
        if (localY < 0 || localY >= visibleLines.size()) {
            window.hideHoverDiagnostics();
            return;
        }
        var wrappedLine = visibleLines.get(localY);
        int sourceLine = _bufferContext.getTextLayout().getPhysicalLineAt(wrappedLine.getStartPosition()).getY();
        window.updateHoveredDiagnostics(_bufferContext,
                sourceLine,
                Point.create(action.getPosition().getColumn(), action.getPosition().getRow()));
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (var parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    private int maxStartLine(org.fisk.swim.text.TextLayout textLayout) {
        return Math.max(0, textLayout.getLogicalLineCount() - getViewportHeight());
    }

    private int getScrollbarWidth() {
        return decorationsEnabled() ? 1 : 0;
    }

    private int getLineNumberGutterWidth() {
        int totalWidth = getBounds().getSize().getWidth();
        if (!decorationsEnabled()) {
            return 0;
        }
        int scrollbarWidth = getScrollbarWidth();
        int maxGutterWidth = Math.max(0, totalWidth - scrollbarWidth - 1);
        if (maxGutterWidth <= 0) {
            return 0;
        }
        return Math.min(requestedLineNumberGutterWidth(), maxGutterWidth);
    }

    private int requestedLineNumberGutterWidth() {
        var textLayout = _bufferContext.getTextLayout();
        int lineCount;
        if (textLayout != null) {
            lineCount = textLayout.getPhysicalLineCount();
        } else {
            lineCount = countPhysicalLines(_bufferContext.getBuffer().getString());
        }
        return digitCount(Math.max(1, lineCount)) + 1;
    }

    private void drawLineNumberGutter(Rect rect, com.googlecode.lanterna.graphics.TextGraphics graphics,
            org.fisk.swim.text.TextLayout textLayout,
            java.util.List<org.fisk.swim.text.TextLayout.Line> visibleLines) {
        int gutterWidth = getLineNumberGutterWidth();
        if (gutterWidth <= 0) {
            return;
        }
        int currentPhysicalLine = _bufferContext.getBuffer().getCursor().getPhysicalLine().getY();
        for (int row = 0; row < visibleLines.size(); row++) {
            var line = visibleLines.get(row);
            var physicalLine = textLayout.getPhysicalLineAt(line.getStartPosition());
            boolean firstSegment = line.getStartPosition() == physicalLine.getStartPosition();
            TextColor foreground = lineNumberForeground(physicalLine.getY(), currentPhysicalLine);
            AttributedString gutter = createLineNumberString(firstSegment ? physicalLine.getY() + 1 : null, gutterWidth, foreground);
            gutter.drawAt(Point.create(rect.getPoint().getX(), rect.getPoint().getY() + row), graphics);
        }
    }

    private AttributedString createLineNumberString(Integer lineNumber, int gutterWidth, TextColor foreground) {
        var gutter = new AttributedString();
        if (gutterWidth <= 0) {
            return gutter;
        }
        if (gutterWidth == 1) {
            gutter.append(lineNumber == null ? " " : Integer.toString(lineNumber).substring(Integer.toString(lineNumber).length() - 1),
                    foreground, UiTheme.SURFACE_MUTED);
            return gutter;
        }
        int numberWidth = gutterWidth - 1;
        String number = lineNumber == null ? "" : Integer.toString(lineNumber);
        if (number.length() > numberWidth) {
            number = number.substring(number.length() - numberWidth);
        }
        String padded = " ".repeat(Math.max(0, numberWidth - number.length())) + number;
        gutter.append(padded, foreground, UiTheme.SURFACE_MUTED);
        gutter.append(GUTTER_SEPARATOR, UiTheme.TEXT_SUBTLE, UiTheme.SURFACE_MUTED);
        return gutter;
    }

    private void drawScrollbar(Rect rect, com.googlecode.lanterna.graphics.TextGraphics graphics,
            org.fisk.swim.text.TextLayout textLayout) {
        int scrollbarWidth = getScrollbarWidth();
        if (scrollbarWidth <= 0) {
            return;
        }
        int height = getViewportHeight();
        int totalLines = Math.max(1, textLayout.getLogicalLineCount());
        int visibleLines = Math.min(height, totalLines);
        int thumbHeight = totalLines <= height ? height : Math.max(1, (int) Math.round((visibleLines * (double) height) / totalLines));
        int maxStart = Math.max(0, totalLines - height);
        int thumbStart = maxStart == 0 ? 0
                : (int) Math.round((_startLine * (double) Math.max(0, height - thumbHeight)) / maxStart);
        int x = rect.getPoint().getX() + getTextColumnStart() + getTextWidth();
        for (int row = 0; row < height; row++) {
            boolean thumb = row >= thumbStart && row < thumbStart + thumbHeight;
            AttributedString column = AttributedString.create(thumb ? SCROLLBAR_THUMB : SCROLLBAR_TRACK,
                    thumb ? UiTheme.ACCENT_BLUE : UiTheme.TEXT_SUBTLE,
                    UiTheme.SURFACE_MUTED);
            column.drawAt(Point.create(x, rect.getPoint().getY() + row), graphics);
        }
    }

    private static int digitCount(int value) {
        return Integer.toString(Math.max(1, value)).length();
    }

    private boolean decorationsEnabled() {
        return getBounds().getSize().getWidth() >= MIN_DECORATED_WIDTH;
    }

    private TextColor lineNumberForeground(int physicalLine, int currentPhysicalLine) {
        var severity = DiagnosticService.getInstance().lineSeverity(_bufferContext, physicalLine);
        if (DiagnosticSeverity.Error.equals(severity)) {
            return TextColor.ANSI.RED_BRIGHT;
        }
        if (DiagnosticSeverity.Warning.equals(severity)) {
            return TextColor.ANSI.YELLOW_BRIGHT;
        }
        return currentPhysicalLine == physicalLine ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED;
    }

    private static int countPhysicalLines(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int lines = 1;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
