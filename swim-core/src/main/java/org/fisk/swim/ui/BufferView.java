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
    private BufferContext _bufferContext;
    private static final Logger _log = LogFactory.createLog();
    private int _startLine = 0;

    public int getStartLine() {
        return _startLine;
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
            int maxStartLine = Math.max(0, textLayout.getLogicalLineCount() - getBounds().getSize().getHeight());
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
        _bufferContext.getTextLayout().getGlyphs().forEach((glyph) -> {
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
            var point = Point.create(rect.getPoint().getX() + glyph.getX(), rect.getPoint().getY() + glyph.getY() - _startLine);
            character.drawAt(point, textGraphics);
        });
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
        int maxStartLine = Math.max(0, textLayout.getLogicalLineCount() - getBounds().getSize().getHeight());
        if (_startLine >= maxStartLine) {
            return;
        }
        _startLine++;
        adaptCursorToView();
        setNeedsRedraw();
    }

    private AttributedString applyDiagnosticBackground(org.fisk.swim.text.TextLayout.Glyph glyph, AttributedString character) {
        if (glyph.isSynthetic() || character.getFragments().isEmpty()) {
            return character;
        }
        int logicalLine = _bufferContext.getTextLayout().getLogicalLineAt(glyph.getPosition()).getY();
        var severity = DiagnosticService.getInstance().lineSeverity(_bufferContext, logicalLine);
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
        if (localX < 0 || localY < 0
                || localX >= getBounds().getSize().getWidth()
                || localY >= getBounds().getSize().getHeight()) {
            window.hideHoverDiagnostics();
            return;
        }
        int physicalLine = localY + _startLine;
        if (physicalLine < 0 || physicalLine >= _bufferContext.getTextLayout().getPhysicalLineCount()) {
            window.hideHoverDiagnostics();
            return;
        }
        int index = _bufferContext.getTextLayout().getIndexForPhysicalLineCharacter(physicalLine, 0);
        int logicalLine = _bufferContext.getTextLayout().getLogicalLineAt(index).getY();
        window.updateHoveredDiagnostics(_bufferContext,
                logicalLine,
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
}
