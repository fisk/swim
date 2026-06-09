package org.fisk.swim.mode;

import java.util.List;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.FindResponder;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
import org.fisk.swim.event.MotionResponder;
import org.fisk.swim.event.Response;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.TextLayout.Glyph;
import org.fisk.swim.ui.Drawable;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.Window;

public class Mode implements EventResponder, Drawable, KeyBindingHintProvider {
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

    @Override
    public String keyHintContext() {
        return _name == null || _name.isBlank() ? null : _name.toLowerCase() + " mode";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        return _rootResponder.keyBindingHints();
    }

    protected void setupNavigationResponders() {
        var window = _window;
        var bufferContext = window.getBufferContext();
        var buffer = bufferContext.getBuffer();
        var cursor = buffer.getCursor();
        _rootResponder.addEventResponder("<CTRL>-y", "Navigation", "scroll up", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollUp(); }));
        _rootResponder.addEventResponder("<CTRL>-e", "Navigation", "scroll down", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollDown(); }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-d", "Navigation", "half page down", count -> {
            allow("scroll buffer");
            repeat(count * Math.max(1, bufferContext.getBufferView().getViewportHeight() / 2),
                    () -> bufferContext.getBufferView().scrollDown());
        }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-u", "Navigation", "half page up", count -> {
            allow("scroll buffer");
            repeat(count * Math.max(1, bufferContext.getBufferView().getViewportHeight() / 2),
                    () -> bufferContext.getBufferView().scrollUp());
        }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-f", "Navigation", "page down", count -> {
            allow("scroll buffer");
            repeat(count, () -> bufferContext.getBufferView().scrollPageDown());
        }));
        _rootResponder.addEventResponder(new MotionResponder("<CTRL>-b", "Navigation", "page up", count -> {
            allow("scroll buffer");
            repeat(count, () -> bufferContext.getBufferView().scrollPageUp());
        }));
        _rootResponder.addEventResponder(new MotionResponder("$", "Navigation", "line end", count -> {
            allow("cursor motion");
            for (int i = 1; i < Math.max(1, count); i++) {
                cursor.goDown();
            }
            cursor.goEndOfLine();
        }));
        _rootResponder.addEventResponder("0", "Navigation", "column zero", allowed("cursor motion", () -> { cursor.goStartOfLine(); }));
        _rootResponder.addEventResponder("^", "Navigation", "first nonblank", allowed("cursor motion", () -> { cursor.goFirstNonBlankOfLine(); }));
        _rootResponder.addEventResponder("_", "Navigation", "first nonblank", allowed("cursor motion", () -> { cursor.goFirstNonBlankOfLine(); }));
        _rootResponder.addEventResponder(new MotionResponder("h", "Navigation", "left", count -> { allow("cursor motion"); repeat(count, cursor::goLeft); }));
        _rootResponder.addEventResponder(new MotionResponder("l", "Navigation", "right", count -> { allow("cursor motion"); repeat(count, cursor::goRight); }));
        _rootResponder.addEventResponder(new MotionResponder("j", "Navigation", "down", count -> { allow("cursor motion"); repeat(count, cursor::goDown); }));
        _rootResponder.addEventResponder(new MotionResponder("k", "Navigation", "up", count -> { allow("cursor motion"); repeat(count, cursor::goUp); }));
        _rootResponder.addEventResponder(new MotionResponder("<LEFT>", "Navigation", "left", count -> { allow("cursor motion"); repeat(count, cursor::goLeft); }));
        _rootResponder.addEventResponder(new MotionResponder("<RIGHT>", "Navigation", "right", count -> { allow("cursor motion"); repeat(count, cursor::goRight); }));
        _rootResponder.addEventResponder(new MotionResponder("<DOWN>", "Navigation", "down", count -> { allow("cursor motion"); repeat(count, cursor::goDown); }));
        _rootResponder.addEventResponder(new MotionResponder("<UP>", "Navigation", "up", count -> { allow("cursor motion"); repeat(count, cursor::goUp); }));
        _rootResponder.addEventResponder(new MotionResponder("w", "Navigation", "word forward", count -> { allow("cursor motion"); cursor.goWordForward(count, false); }));
        _rootResponder.addEventResponder(new MotionResponder("W", "Navigation", "WORD forward", count -> { allow("cursor motion"); cursor.goWordForward(count, true); }));
        _rootResponder.addEventResponder(new MotionResponder("b", "Navigation", "word back", count -> { allow("cursor motion"); cursor.goWordBackward(count, false); }));
        _rootResponder.addEventResponder(new MotionResponder("B", "Navigation", "WORD back", count -> { allow("cursor motion"); cursor.goWordBackward(count, true); }));
        _rootResponder.addEventResponder(new MotionResponder("e", "Navigation", "word end", count -> { allow("cursor motion"); cursor.goWordEnd(count, false); }));
        _rootResponder.addEventResponder(new MotionResponder("E", "Navigation", "WORD end", count -> { allow("cursor motion"); cursor.goWordEnd(count, true); }));
        _rootResponder.addEventResponder(new MotionResponder("}", "Navigation", "paragraph forward", count -> { allow("cursor motion"); cursor.goParagraphForward(count); }));
        _rootResponder.addEventResponder(new MotionResponder("{", "Navigation", "paragraph back", count -> { allow("cursor motion"); cursor.goParagraphBackward(count); }));
        _rootResponder.addEventResponder(new MotionResponder(")", "Navigation", "sentence forward", count -> { allow("cursor motion"); cursor.goSentenceForward(count); }));
        _rootResponder.addEventResponder(new MotionResponder("(", "Navigation", "sentence back", count -> { allow("cursor motion"); cursor.goSentenceBackward(count); }));
        _rootResponder.addEventResponder("%", "Navigation", "matching bracket", allowed("cursor motion", () -> { cursor.goMatchingBracket(); }));
        _rootResponder.addEventResponder("H", "Navigation", "screen top", allowed("cursor motion", () -> {
            cursor.setPosition(buffer.getLineStartByIndex(bufferContext.getBufferView().getStartLine()));
        }));
        _rootResponder.addEventResponder("M", "Navigation", "screen middle", allowed("cursor motion", () -> {
            cursor.setPosition(buffer.getLineStartByIndex(bufferContext.getBufferView().getStartLine()
                    + Math.max(0, bufferContext.getBufferView().getViewportHeight() / 2)));
        }));
        _rootResponder.addEventResponder("L", "Navigation", "screen bottom", allowed("cursor motion", () -> {
            cursor.setPosition(buffer.getLineStartByIndex(bufferContext.getBufferView().getStartLine()
                    + Math.max(0, bufferContext.getBufferView().getViewportHeight() - 1)));
        }));
        _rootResponder.addEventResponder("<PAGEUP>", "Navigation", "page up", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollPageUp(); }));
        _rootResponder.addEventResponder("<PAGEDOWN>", "Navigation", "page down", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollPageDown(); }));
        _rootResponder.addEventResponder("g g", "Navigation", "buffer start", allowed("cursor motion", () -> { window.performJump(cursor::goStartOfBuffer); }));
        _rootResponder.addEventResponder("G", "Navigation", "buffer end", allowed("cursor motion", () -> { window.performJump(cursor::goEndOfBuffer); }));
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
