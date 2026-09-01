package org.fisk.swim.ui;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.event.KeyType;
import org.fisk.swim.text.BufferContext;

/** A temporary editable buffer displayed above, rather than inside, the workspace layout. */
final class EditableSearchResultsBufferView extends BufferView {
    private Runnable _onClose;

    EditableSearchResultsBufferView(Rect bounds, BufferContext context) {
        super(bounds, context);
        setBackgroundColour(UiTheme.SURFACE_ELEVATED);
        setFirstResponderDecorator(CloseOnEscapeResponder::new);
    }

    void setOnClose(Runnable onClose) {
        _onClose = onClose;
    }

    private final class CloseOnEscapeResponder implements EventResponder {
        private final EventResponder _delegate;
        private boolean _close;

        private CloseOnEscapeResponder(EventResponder delegate) {
            _delegate = delegate;
        }

        @Override
        public Response processEvent(KeyStrokes events) {
            _close = events.remaining() == 0 && events.current().getKeyType() == KeyType.Escape;
            return _close ? Response.YES : _delegate == null ? Response.NO : _delegate.processEvent(events);
        }

        @Override
        public void respond() {
            if (_close) {
                if (_onClose != null) _onClose.run();
            } else if (_delegate != null) {
                _delegate.respond();
            }
            _close = false;
        }
    }
}
