package org.fisk.swim.ui;

import com.googlecode.lanterna.TextColor;

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
            var character = attrString.getCharacter(glyph.getPosition());
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
}
