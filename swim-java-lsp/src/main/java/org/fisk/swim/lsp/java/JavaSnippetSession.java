package org.fisk.swim.lsp.java;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.text.BufferContext;

final class JavaSnippetSession {
    static final class Stop {
        private int _start;
        private int _end;

        Stop(int start, int end) {
            _start = start;
            _end = end;
        }
    }

    private final List<Stop> _stops;
    private int _finalCursorPosition;
    private int _activeIndex = -1;
    private boolean _replaceOnType;

    private JavaSnippetSession(List<Stop> stops, int finalCursorPosition) {
        _stops = stops;
        _finalCursorPosition = finalCursorPosition;
        if (!_stops.isEmpty()) {
            _activeIndex = 0;
            _replaceOnType = _stops.get(0)._end > _stops.get(0)._start;
        }
    }

    static JavaSnippetSession fromParseResult(int insertionStart, JavaSnippetParser.ParseResult result) {
        var stops = new ArrayList<Stop>();
        for (var stop : result.tabStops()) {
            stops.add(new Stop(insertionStart + stop.start(), insertionStart + stop.end()));
        }
        return new JavaSnippetSession(stops, insertionStart + result.finalCursorOffset());
    }

    boolean isActive() {
        return _activeIndex >= 0 && _activeIndex < _stops.size();
    }

    boolean hasStops() {
        return !_stops.isEmpty();
    }

    void activate(BufferContext bufferContext) {
        if (!isActive()) {
            bufferContext.getBuffer().getCursor().setPosition(_finalCursorPosition);
            return;
        }
        var stop = _stops.get(_activeIndex);
        bufferContext.getBuffer().getCursor().setPosition(stop._start);
        _replaceOnType = stop._end > stop._start;
    }

    boolean moveNext(BufferContext bufferContext) {
        if (!hasStops()) {
            bufferContext.getBuffer().getCursor().setPosition(_finalCursorPosition);
            return false;
        }
        if (_activeIndex + 1 >= _stops.size()) {
            complete(bufferContext);
            return false;
        }
        ++_activeIndex;
        activate(bufferContext);
        return true;
    }

    boolean movePrevious(BufferContext bufferContext) {
        if (!hasStops() || _activeIndex <= 0) {
            return false;
        }
        --_activeIndex;
        activate(bufferContext);
        return true;
    }

    boolean replaceActivePlaceholder(BufferContext bufferContext, char character) {
        if (!isActive() || !_replaceOnType) {
            return false;
        }
        var buffer = bufferContext.getBuffer();
        var stop = _stops.get(_activeIndex);
        buffer.remove(stop._start, stop._end);
        onRemove(stop._start, stop._end);
        buffer.insert(stop._start, Character.toString(character));
        onInsert(stop._start, 1);
        _replaceOnType = false;
        return true;
    }

    void onInsert(int position, int length) {
        if (position <= _finalCursorPosition) {
            _finalCursorPosition += length;
        }
        for (var stop : _stops) {
            if (position < stop._start) {
                stop._start += length;
                stop._end += length;
            } else if (position <= stop._end) {
                stop._end += length;
            }
        }
    }

    void onRemove(int start, int end) {
        _finalCursorPosition = mapAfterRemoval(_finalCursorPosition, start, end);
        for (var stop : _stops) {
            stop._start = mapAfterRemoval(stop._start, start, end);
            stop._end = mapAfterRemoval(stop._end, start, end);
            if (stop._end < stop._start) {
                stop._end = stop._start;
            }
        }
    }

    void complete(BufferContext bufferContext) {
        _activeIndex = -1;
        _replaceOnType = false;
        bufferContext.getBuffer().getCursor().setPosition(_finalCursorPosition);
    }

    private static int mapAfterRemoval(int position, int start, int end) {
        int delta = end - start;
        if (position <= start) {
            return position;
        }
        if (position >= end) {
            return position - delta;
        }
        return start;
    }
}
