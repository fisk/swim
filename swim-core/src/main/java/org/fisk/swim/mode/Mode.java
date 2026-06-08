package org.fisk.swim.mode;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.FindResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.ListEventResponder;
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
        _rootResponder.addEventResponder("$", allowed("cursor motion", () -> { cursor.goEndOfLine(); }));
        _rootResponder.addEventResponder("^", allowed("cursor motion", () -> { cursor.goStartOfLine(); }));
        _rootResponder.addEventResponder("h", allowed("cursor motion", () -> { cursor.goLeft(); }));
        _rootResponder.addEventResponder("l", allowed("cursor motion", () -> { cursor.goRight(); }));
        _rootResponder.addEventResponder("j", allowed("cursor motion", () -> { cursor.goDown(); }));
        _rootResponder.addEventResponder("k", allowed("cursor motion", () -> { cursor.goUp(); }));
        _rootResponder.addEventResponder("<LEFT>", allowed("cursor motion", () -> { cursor.goLeft(); }));
        _rootResponder.addEventResponder("<RIGHT>", allowed("cursor motion", () -> { cursor.goRight(); }));
        _rootResponder.addEventResponder("<DOWN>", allowed("cursor motion", () -> { cursor.goDown(); }));
        _rootResponder.addEventResponder("<UP>", allowed("cursor motion", () -> { cursor.goUp(); }));
        _rootResponder.addEventResponder("<PAGEUP>", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollPageUp(); }));
        _rootResponder.addEventResponder("<PAGEDOWN>", allowed("scroll buffer", () -> { bufferContext.getBufferView().scrollPageDown(); }));
        _rootResponder.addEventResponder("g g", allowed("cursor motion", () -> { window.performJump(cursor::goStartOfBuffer); }));
        _rootResponder.addEventResponder("G", allowed("cursor motion", () -> { window.performJump(cursor::goEndOfBuffer); }));
        _rootResponder.addEventResponder(new FindResponder(bufferContext, "f", true));
        _rootResponder.addEventResponder(new FindResponder(bufferContext, "F", false));
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
