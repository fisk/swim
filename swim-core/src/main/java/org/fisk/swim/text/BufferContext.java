package org.fisk.swim.text;

import java.nio.file.Path;

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
