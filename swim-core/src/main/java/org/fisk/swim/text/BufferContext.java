package org.fisk.swim.text;

import java.nio.file.Path;
import java.util.function.BiFunction;

import org.fisk.swim.ui.BufferView;
import org.fisk.swim.ui.Rect;

public class BufferContext {
    private Buffer _buffer;
    private BufferView _bufferView;
    private TextLayout _textLayout;

    public BufferContext(Rect rect, Path path) {
        _buffer = new Buffer(path, this);
        _bufferView = new BufferView(rect, this);
        _textLayout = new TextLayout(this);
        _buffer.open();
    }

    public BufferContext(Rect rect, String initialText, boolean readOnly) {
        this(rect, initialText, readOnly, BufferView::new);
    }

    public BufferContext(Rect rect, String initialText, boolean readOnly,
            BiFunction<Rect, BufferContext, BufferView> bufferViewFactory) {
        _buffer = new Buffer(null, this, initialText, readOnly);
        _bufferView = bufferViewFactory.apply(rect, this);
        _textLayout = new TextLayout(this);
        _buffer.open();
    }

    public Buffer getBuffer() {
        return _buffer;
    }

    public BufferView getBufferView() {
        return _bufferView;
    }

    public TextLayout getTextLayout() {
        return _textLayout;
    }
}
