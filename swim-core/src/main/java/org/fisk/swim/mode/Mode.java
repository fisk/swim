package org.fisk.swim.mode;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.FindResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.MotionResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.Drawable;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.Window;

public class Mode implements EventResponder, Drawable {
    protected Window _window;
    protected ListEventResponder _rootResponder = new ListEventResponder();
    private String _name;

    public Mode(String name, Window window) {
        _name = name;
        _window = window;
    }

    public String getName() {
        return _name;
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        return _rootResponder.processEvent(events);
    }

    @Override
    public void respond() {
        _rootResponder.respond();
    }

    public ListEventResponder.Layer addKeybindingLayer() {
        return _rootResponder.addLayer();
    }

    protected void setupNavigationResponders() {
        var window = _window;
        var bufferContext = window.getBufferContext();
        var buffer = bufferContext.getBuffer();
        var cursor = buffer.getCursor();
        _rootResponder.addEventResponder("<CTRL>-y", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollUp(); }));
        _rootResponder.addEventResponder("<CTRL>-e", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollDown(); }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-d", count -> {
            allow("scroll buffer");
            repeat(count * Math.max(1, bufferContext.getBufferView().getViewportHeight() / 2),
                    () -> bufferContext.getBufferView().scrollDown());
        }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-u", count -> {
            allow("scroll buffer");
            repeat(count * Math.max(1, bufferContext.getBufferView().getViewportHeight() / 2),
                    () -> bufferContext.getBufferView().scrollUp());
        }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-f", count -> {
            allow("scroll buffer");
            repeat(count, () -> bufferContext.getBufferView().scrollPageDown());
        }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-b", count -> {
            allow("scroll buffer");
            repeat(count, () -> bufferContext.getBufferView().scrollPageUp());
        }));
        _rootResponder.addEventResponder(new MotionResponder("$", count -> {
            allow("cursor motion");
            for (int i = 1; i < Math.max(1, count); i++) {
                cursor.goDown();
            }
            cursor.goEndOfLine();
        }));
        _rootResponder.addEventResponder("0", allowed("cursor motion", () -> { cursor.goStartOfLine(); }));
        _rootResponder.addEventResponder("^", allowed("cursor motion", () -> { cursor.goFirstNonBlankOfLine(); }));
        _rootResponder.addEventResponder("_", allowed("cursor motion", () -> { cursor.goFirstNonBlankOfLine(); }));
        _rootResponder.addEventResponder(new MotionResponder("h", count -> { allow("cursor motion"); repeat(count, cursor::goLeft); }));
        _rootResponder.addEventResponder(new MotionResponder("l", count -> { allow("cursor motion"); repeat(count, cursor::goRight); }));
        _rootResponder.addEventResponder(new MotionResponder("j", count -> { allow("cursor motion"); repeat(count, cursor::goDown); }));
        _rootResponder.addEventResponder(new MotionResponder("k", count -> { allow("cursor motion"); repeat(count, cursor::goUp); }));
        _rootResponder.addEventResponder(new MotionResponder("<LEFT>", count -> { allow("cursor motion"); repeat(count, cursor::goLeft); }));
        _rootResponder.addEventResponder(new MotionResponder("<RIGHT>", count -> { allow("cursor motion"); repeat(count, cursor::goRight); }));
        _rootResponder.addEventResponder(new MotionResponder("<DOWN>", count -> { allow("cursor motion"); repeat(count, cursor::goDown); }));
        _rootResponder.addEventResponder(new MotionResponder("<UP>", count -> { allow("cursor motion"); repeat(count, cursor::goUp); }));
        _rootResponder.addEventResponder(new MotionResponder("w", count -> { allow("cursor motion"); cursor.goWordForward(count, false); }));
        _rootResponder.addEventResponder(new MotionResponder("W", count -> { allow("cursor motion"); cursor.goWordForward(count, true); }));
        _rootResponder.addEventResponder(new MotionResponder("b", count -> { allow("cursor motion"); cursor.goWordBackward(count, false); }));
        _rootResponder.addEventResponder(new MotionResponder("B", count -> { allow("cursor motion"); cursor.goWordBackward(count, true); }));
        _rootResponder.addEventResponder(new MotionResponder("e", count -> { allow("cursor motion"); cursor.goWordEnd(count, false); }));
        _rootResponder.addEventResponder(new MotionResponder("E", count -> { allow("cursor motion"); cursor.goWordEnd(count, true); }));
        _rootResponder.addEventResponder(new MotionResponder("}", count -> { allow("cursor motion"); cursor.goParagraphForward(count); }));
        _rootResponder.addEventResponder(new MotionResponder("{", count -> { allow("cursor motion"); cursor.goParagraphBackward(count); }));
        _rootResponder.addEventResponder(new MotionResponder(")", count -> { allow("cursor motion"); cursor.goSentenceForward(count); }));
        _rootResponder.addEventResponder(new MotionResponder("(", count -> { allow("cursor motion"); cursor.goSentenceBackward(count); }));
        _rootResponder.addEventResponder("%", allowed("cursor motion", () -> { cursor.goMatchingBracket(); }));
        _rootResponder.addEventResponder("H", allowed("cursor motion", () -> {
            cursor.setPosition(buffer.getLineStartByIndex(bufferContext.getBufferView().getStartLine()));
        }));
        _rootResponder.addEventResponder("M", allowed("cursor motion", () -> {
            cursor.setPosition(buffer.getLineStartByIndex(bufferContext.getBufferView().getStartLine()
                    + Math.max(0, bufferContext.getBufferView().getViewportHeight() / 2)));
        }));
        _rootResponder.addEventResponder("L", allowed("cursor motion", () -> {
            cursor.setPosition(buffer.getLineStartByIndex(bufferContext.getBufferView().getStartLine()
                    + Math.max(0, bufferContext.getBufferView().getViewportHeight() - 1)));
        }));
        _rootResponder.addEventResponder("<PAGEUP>", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollPageUp(); }));
        _rootResponder.addEventResponder("<PAGEDOWN>", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollPageDown(); }));
        _rootResponder.addEventResponder("g g", allowed("cursor motion", () -> { window.performJump(cursor::goStartOfBuffer); }));
        _rootResponder.addEventResponder("G", allowed("cursor motion", () -> { window.performJump(cursor::goEndOfBuffer); }));
        _rootResponder.addEventResponder(new FindResponder(bufferContext, "f", true));
        _rootResponder.addEventResponder(new FindResponder(bufferContext, "F", false));
    }

    private static void repeat(int count, Runnable action) {
        for (int i = 0; i < Math.max(1, count); i++) {
            action.run();
        }
    }

    protected Runnable allowed(String action, Runnable runnable) {
        return () -> {
            _window.allowEditorDriveAction(action);
            runnable.run();
        };
    }

    protected void allow(String action) {
        _window.allowEditorDriveAction(action);
    }

    public void activate() {
    }

    public void deactivate() {
    }

    @Override
    public void draw(Rect rect) {
    }
    
    public AttributedString decorate(Glyph glyph, AttributedString character) {
        return character;
    }
}
