package org.fisk.swim.mode;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.event.FancyJumpResponder;
import org.fisk.swim.event.MotionResponder;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.BufferView;
import org.fisk.swim.ui.Cursor;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.UiTheme;
import org.fisk.swim.ui.Window;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;

public class VisualMode extends Mode {
    private FancyJumpResponder _fancyJump;
    
    protected Cursor getOtherCursor() {
        return _window.getBufferContext().getBuffer().getCursors().get(1);
    }

    public VisualMode(Window window) {
        super("VISUAL", window);
        _fancyJump = new FancyJumpResponder(window.getBufferContext(), "g w");
        _rootResponder.addEventResponder(_fancyJump);
        setupBasicResponders();
        setupNavigationResponders();
    }

    protected Cursor minCursor() {
        var cursor = _window.getBufferContext().getBuffer().getCursor();
        return cursor.getPosition() < getOtherCursor().getPosition() ? cursor : getOtherCursor();
    }

    protected Cursor maxCursor() {
        var cursor = _window.getBufferContext().getBuffer().getCursor();
        return cursor.getPosition() >= getOtherCursor().getPosition() ? cursor : getOtherCursor();
    }

    protected void setupBasicResponders() {
        var window = _window;
        var bufferContext = window.getBufferContext();
        var buffer = bufferContext.getBuffer();
        var cursor = buffer.getCursor();
        _rootResponder.addEventResponder("<ESC>", allowed("exit visual mode", () -> { window.switchToMode(window.getNormalMode()); }));
        _rootResponder.addEventResponder("o", () -> {
            allow("visual selection");
            var position = cursor.getPosition();
            cursor.setPosition(getOtherCursor().getPosition());
            getOtherCursor().setPosition(position);
            bufferContext.getBufferView().adaptViewToCursor();
        });
        _rootResponder.addEventResponder("d", () -> {
            allow("buffer edit");
            buffer.deleteRange(minCursor().getPosition(), maxCursor().getPosition() + 1, false,
                    Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
            window.switchToMode(window.getNormalMode());
        });
        _rootResponder.addEventResponder("c", () -> {
            allow("buffer edit");
            buffer.changeRange(minCursor().getPosition(), maxCursor().getPosition() + 1, false,
                    Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
            window.switchToMode(window.getInputMode());
        });
        _rootResponder.addEventResponder("y", () -> {
            allow("yank");
            var text = buffer.getSubstring(minCursor().getPosition(), maxCursor().getPosition() + 1);
            Copy.getInstance().setYank(text, false /* isLine */,
                    Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
            window.switchToMode(window.getNormalMode());
        });
        installSelectionMoveResponders();
    }

    protected void installSelectionMoveResponders() {
        _rootResponder.addEventResponder(new MotionResponder("<SPACE> h", count -> indentSelectedLines(-count)));
        _rootResponder.addEventResponder(new MotionResponder("<SPACE> j", count -> moveSelectedLines(count)));
        _rootResponder.addEventResponder(new MotionResponder("<SPACE> k", count -> moveSelectedLines(-count)));
        _rootResponder.addEventResponder(new MotionResponder("<SPACE> l", count -> indentSelectedLines(count)));
        _rootResponder.addKeyBindingHint("<SPACE> h", "Selection", "outdent selection");
        _rootResponder.addKeyBindingHint("<SPACE> j", "Selection", "move selection down");
        _rootResponder.addKeyBindingHint("<SPACE> k", "Selection", "move selection up");
        _rootResponder.addKeyBindingHint("<SPACE> l", "Selection", "indent selection");
    }

    protected void moveSelectedLines(int delta) {
        allow("buffer edit");
        var buffer = _window.getBufferContext().getBuffer();
        int startLine = buffer.getLineIndexAt(minCursor().getPosition());
        int endLine = buffer.getLineIndexAt(maxCursor().getPosition());
        var result = buffer.moveLineRangeBy(startLine, endLine, delta);
        if (result == null) {
            _window.getCommandView().setMessage(delta > 0 ? "Cannot move selection down" : "Cannot move selection up");
            return;
        }
        buffer.getUndoLog().commit();
        selectLineRange(result.startLine(), result.endLine());
    }

    protected void indentSelectedLines(int levels) {
        allow("buffer edit");
        var buffer = _window.getBufferContext().getBuffer();
        int startLine = buffer.getLineIndexAt(minCursor().getPosition());
        int endLine = buffer.getLineIndexAt(maxCursor().getPosition());
        int start = buffer.getLineStartByIndex(startLine);
        int end = buffer.getLineEndByIndex(endLine, true);
        buffer.indentLines(start, end, levels);
        buffer.getUndoLog().commit();
        selectLineRange(startLine, endLine);
    }

    protected void selectLineRange(int startLine, int endLine) {
        var buffer = _window.getBufferContext().getBuffer();
        int start = buffer.getLineStartByIndex(startLine);
        int end = Math.max(start, buffer.getLineEndByIndex(endLine, false) - 1);
        getOtherCursor().setPosition(start);
        buffer.getCursor().setPosition(end);
        _window.getBufferContext().getBufferView().adaptViewToCursor();
    }

    @Override
    public void activate() {
        var other = new Cursor(_window.getBufferContext());
        other.setPosition(_window.getBufferContext().getBuffer().getCursor().getPosition());
        _window.getBufferContext().getBuffer().addCursor(other);
    }

    @Override
    public void deactivate() {
        _window.getBufferContext().getBuffer().clearCursors();
    }

    @Override
    public void draw(Rect rect) {
        var terminalContext = TerminalContext.getInstance();
        var graphics = terminalContext.getGraphics();
        var minCursor = minCursor();
        var maxCursor = maxCursor();
        if (maxCursor.getPosition() - minCursor.getPosition() == 0) {
            return;
        }
        BufferView bufferView = _window.getBufferContext().getBufferView();
        int textX = rect.getPoint().getX() + bufferView.getTextColumnStart();
        int textRight = textX + bufferView.getTextWidth();
        int height = rect.getSize().getHeight();
        int minY = minCursor.getYRelative();
        int maxY = maxCursor.getYRelative();
        for (int row = Math.max(0, minY); row <= Math.min(maxY, height - 1); ++row) {
            int fromColumn = textX;
            int toColumn = textRight;
            if (row == minY) {
                fromColumn = textX + minCursor.getX();
            }
            if (row == maxY) {
                toColumn = textX + maxCursor.getX() + 1;
            }
            fromColumn = Math.max(textX, Math.min(fromColumn, textRight));
            toColumn = Math.max(fromColumn, Math.min(toColumn, textRight));
            if (toColumn <= fromColumn) {
                continue;
            }
            graphics.setBackgroundColor(UiTheme.VISUAL_SELECTION_BACKGROUND);
            graphics.drawRectangle(new TerminalPosition(fromColumn, rect.getPoint().getY() + row),
                    new TerminalSize(toColumn - fromColumn, 1), ' ');
        }
    }

    public boolean isSelected(int position) {
        var cursor = _window.getBufferContext().getBuffer().getCursor();
        var minCursor = cursor.getPosition() < getOtherCursor().getPosition() ? cursor : getOtherCursor();
        var maxCursor = cursor.getPosition() >= getOtherCursor().getPosition() ? cursor : getOtherCursor();
        return position >= minCursor.getPosition() && position <= maxCursor.getPosition();
    }
    
    @Override
    public AttributedString decorate(Glyph glyph, AttributedString character) {
        if (isSelected(glyph.getPosition())) {
            character = AttributedString.create(glyph.getCharacter(), UiTheme.VISUAL_SELECTION_FOREGROUND,
                    UiTheme.VISUAL_SELECTION_BACKGROUND);
        }
        character = _fancyJump.decorate(glyph, character);
        return character;
    }
}
