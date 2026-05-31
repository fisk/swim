package org.fisk.swim.terminal;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

public final class TerminalScreenBuffer {
    private TerminalCell[][] _normal;
    private TerminalCell[][] _alternate;
    private boolean[] _tabStops;
    private final ArrayList<String> _scrollback = new ArrayList<>();
    private boolean _usingAlternate;
    private int _width;
    private int _height;
    private int _row;
    private int _column;
    private int _savedRow;
    private int _savedColumn;
    private int _scrollTop;
    private int _scrollBottom;
    private boolean _originMode;
    private boolean _autoWrap = true;
    private boolean _insertMode;
    private boolean _wrapPending;

    public TerminalScreenBuffer(int width, int height) {
        resize(width, height);
    }

    public void resize(int width, int height) {
        _width = Math.max(1, width);
        _height = Math.max(1, height);
        _normal = resizeCells(_normal, _width, _height);
        _alternate = resizeCells(_alternate, _width, _height);
        _tabStops = resizeTabStops(_tabStops, _width);
        _row = Math.max(0, Math.min(_row, _height - 1));
        _column = Math.max(0, Math.min(_column, _width - 1));
        _savedRow = Math.max(0, Math.min(_savedRow, _height - 1));
        _savedColumn = Math.max(0, Math.min(_savedColumn, _width - 1));
        _scrollTop = Math.max(0, Math.min(_scrollTop, _height - 1));
        _scrollBottom = Math.max(_scrollTop, Math.min(_scrollBottom == 0 ? _height - 1 : _scrollBottom, _height - 1));
    }

    private static TerminalCell[][] resizeCells(TerminalCell[][] existing, int width, int height) {
        var next = new TerminalCell[height][width];
        for (int row = 0; row < height; row++) {
            Arrays.fill(next[row], TerminalCell.BLANK);
        }
        if (existing != null) {
            for (int row = 0; row < Math.min(existing.length, height); row++) {
                System.arraycopy(existing[row], 0, next[row], 0, Math.min(existing[row].length, width));
            }
        }
        return next;
    }

    private static boolean[] resizeTabStops(boolean[] existing, int width) {
        var next = new boolean[width];
        if (existing != null) {
            System.arraycopy(existing, 0, next, 0, Math.min(existing.length, width));
        }
        for (int column = 8; column < width; column += 8) {
            next[column] = true;
        }
        return next;
    }

    public int width() {
        return _width;
    }

    public int height() {
        return _height;
    }

    public int row() {
        return _row;
    }

    public int column() {
        return _column;
    }

    public TerminalCell cellAt(int row, int column) {
        return cells()[row][column];
    }

    public void reset() {
        _normal = resizeCells(null, _width, _height);
        _alternate = resizeCells(null, _width, _height);
        _tabStops = resizeTabStops(null, _width);
        _scrollback.clear();
        _usingAlternate = false;
        _row = 0;
        _column = 0;
        _savedRow = 0;
        _savedColumn = 0;
        _scrollTop = 0;
        _scrollBottom = _height - 1;
        _originMode = false;
        _autoWrap = true;
        _insertMode = false;
        _wrapPending = false;
    }

    public void saveCursor() {
        _savedRow = _row;
        _savedColumn = _column;
    }

    public void restoreCursor() {
        _row = _savedRow;
        _column = _savedColumn;
        _wrapPending = false;
    }

    public void useAlternateBuffer(boolean enabled) {
        _usingAlternate = enabled;
        _row = 0;
        _column = 0;
        _wrapPending = false;
        if (enabled) {
            _alternate = resizeCells(null, _width, _height);
        }
    }

    public List<String> snapshotLines() {
        var lines = new ArrayList<String>(_scrollback.size() + _height);
        lines.addAll(_scrollback);
        for (int row = 0; row < _height; row++) {
            lines.add(renderRow(cells()[row]));
        }
        return List.copyOf(lines);
    }

    public void setOriginMode(boolean enabled) {
        _originMode = enabled;
        cursorPosition(0, 0);
    }

    public void setAutoWrap(boolean enabled) {
        _autoWrap = enabled;
        _wrapPending = false;
    }

    public void setInsertMode(boolean enabled) {
        _insertMode = enabled;
    }

    public void setScrollRegion(int top, int bottom) {
        int normalizedTop = Math.max(0, Math.min(top, _height - 1));
        int normalizedBottom = Math.max(0, Math.min(bottom, _height - 1));
        if (normalizedTop >= normalizedBottom) {
            _scrollTop = 0;
            _scrollBottom = _height - 1;
        } else {
            _scrollTop = normalizedTop;
            _scrollBottom = normalizedBottom;
        }
        cursorPosition(0, 0);
    }

    public void putChar(char character, TerminalStyle style) {
        if (_wrapPending && _autoWrap) {
            _column = 0;
            lineFeed(style);
        }
        if (_row < 0 || _row >= _height || _column < 0 || _column >= _width) {
            return;
        }
        if (_insertMode) {
            insertBlankChars(1, style);
        }
        cells()[_row][_column] = new TerminalCell(character, style);
        _wrapPending = false;
        if (_column == _width - 1) {
            if (_autoWrap) {
                _wrapPending = true;
            }
            return;
        }
        _column++;
    }

    public void carriageReturn() {
        _column = 0;
        _wrapPending = false;
    }

    public void backspace() {
        _column = Math.max(0, _column - 1);
        _wrapPending = false;
    }

    public void tab() {
        for (int column = _column + 1; column < _width; column++) {
            if (_tabStops[column]) {
                _column = column;
                _wrapPending = false;
                return;
            }
        }
        _column = _width - 1;
        _wrapPending = false;
    }

    public void setTabStopAtCursor() {
        if (_column >= 0 && _column < _tabStops.length) {
            _tabStops[_column] = true;
        }
    }

    public void clearTabStopAtCursor() {
        if (_column >= 0 && _column < _tabStops.length) {
            _tabStops[_column] = false;
        }
    }

    public void clearAllTabStops() {
        Arrays.fill(_tabStops, false);
    }

    public void lineFeed(TerminalStyle blankStyle) {
        int top = withinScrollRegion() ? _scrollTop : 0;
        int bottom = withinScrollRegion() ? _scrollBottom : _height - 1;
        if (_row == bottom) {
            scrollUp(top, bottom, 1, blankStyle);
        } else {
            _row++;
        }
        _wrapPending = false;
    }

    public void reverseIndex(TerminalStyle blankStyle) {
        int top = withinScrollRegion() ? _scrollTop : 0;
        int bottom = withinScrollRegion() ? _scrollBottom : _height - 1;
        if (_row == top) {
            scrollDown(top, bottom, 1, blankStyle);
        } else {
            _row--;
        }
        _wrapPending = false;
    }

    public void cursorPosition(int row, int column) {
        int targetRow = _originMode ? _scrollTop + row : row;
        int minRow = _originMode ? _scrollTop : 0;
        int maxRow = _originMode ? _scrollBottom : _height - 1;
        _row = Math.max(minRow, Math.min(targetRow, maxRow));
        _column = Math.max(0, Math.min(column, _width - 1));
        _wrapPending = false;
    }

    public void cursorLineAbsolute(int row) {
        cursorPosition(row, _column);
    }

    public void cursorUp(int count) {
        _row = Math.max(_originMode ? _scrollTop : 0, _row - Math.max(1, count));
        _wrapPending = false;
    }

    public void cursorDown(int count) {
        _row = Math.min(_originMode ? _scrollBottom : _height - 1, _row + Math.max(1, count));
        _wrapPending = false;
    }

    public void cursorForward(int count) {
        _column = Math.min(_width - 1, _column + Math.max(1, count));
        _wrapPending = false;
    }

    public void cursorBackward(int count) {
        _column = Math.max(0, _column - Math.max(1, count));
        _wrapPending = false;
    }

    public void clearScreen(int mode, TerminalStyle blankStyle) {
        switch (mode) {
        case 0 -> {
            eraseRange(_row, _column, _height - 1, _width - 1, blankStyle);
        }
        case 1 -> {
            eraseRange(0, 0, _row, _column, blankStyle);
        }
        default -> eraseRange(0, 0, _height - 1, _width - 1, blankStyle);
        }
    }

    public void clearLine(int mode, TerminalStyle blankStyle) {
        switch (mode) {
        case 0 -> eraseRange(_row, _column, _row, _width - 1, blankStyle);
        case 1 -> eraseRange(_row, 0, _row, _column, blankStyle);
        default -> eraseRange(_row, 0, _row, _width - 1, blankStyle);
        }
    }

    public void eraseChars(int count, TerminalStyle blankStyle) {
        int endColumn = Math.min(_width - 1, _column + Math.max(1, count) - 1);
        eraseRange(_row, _column, _row, endColumn, blankStyle);
    }

    private void eraseRange(int startRow, int startColumn, int endRow, int endColumn, TerminalStyle blankStyle) {
        for (int row = startRow; row <= endRow; row++) {
            int colStart = row == startRow ? startColumn : 0;
            int colEnd = row == endRow ? endColumn : _width - 1;
            for (int column = colStart; column <= colEnd; column++) {
                cells()[row][column] = blank(blankStyle);
            }
        }
    }

    public void insertBlankChars(int count, TerminalStyle blankStyle) {
        count = Math.max(1, count);
        var row = cells()[_row];
        for (int column = _width - 1; column >= _column + count; column--) {
            row[column] = row[column - count];
        }
        for (int column = _column; column < Math.min(_width, _column + count); column++) {
            row[column] = blank(blankStyle);
        }
        _wrapPending = false;
    }

    public void deleteChars(int count, TerminalStyle blankStyle) {
        count = Math.max(1, count);
        var row = cells()[_row];
        for (int column = _column; column < _width - count; column++) {
            row[column] = row[column + count];
        }
        for (int column = Math.max(_column, _width - count); column < _width; column++) {
            row[column] = blank(blankStyle);
        }
        _wrapPending = false;
    }

    public void insertLines(int count, TerminalStyle blankStyle) {
        if (!withinScrollRegion()) {
            return;
        }
        count = Math.max(1, count);
        var cells = cells();
        for (int row = _scrollBottom; row >= _row + count; row--) {
            cells[row] = cells[row - count];
        }
        for (int row = _row; row <= Math.min(_scrollBottom, _row + count - 1); row++) {
            cells[row] = blankRow(blankStyle);
        }
        _wrapPending = false;
    }

    public void deleteLines(int count, TerminalStyle blankStyle) {
        if (!withinScrollRegion()) {
            return;
        }
        count = Math.max(1, count);
        var cells = cells();
        for (int row = _row; row <= _scrollBottom - count; row++) {
            cells[row] = cells[row + count];
        }
        for (int row = Math.max(_row, _scrollBottom - count + 1); row <= _scrollBottom; row++) {
            cells[row] = blankRow(blankStyle);
        }
        _wrapPending = false;
    }

    public void scrollUp(int count, TerminalStyle blankStyle) {
        scrollUp(0, _height - 1, count, blankStyle);
    }

    public void scrollDown(int count, TerminalStyle blankStyle) {
        scrollDown(0, _height - 1, count, blankStyle);
    }

    private void scrollUp(int top, int bottom, int count, TerminalStyle blankStyle) {
        count = Math.max(1, count);
        var cells = cells();
        for (int i = 0; i < count; i++) {
            if (!_usingAlternate && top == 0 && bottom == _height - 1) {
                _scrollback.add(renderRow(cells[top]));
            }
            for (int row = top; row < bottom; row++) {
                cells[row] = cells[row + 1];
            }
            cells[bottom] = blankRow(blankStyle);
        }
    }

    private void scrollDown(int top, int bottom, int count, TerminalStyle blankStyle) {
        count = Math.max(1, count);
        var cells = cells();
        for (int i = 0; i < count; i++) {
            for (int row = bottom; row > top; row--) {
                cells[row] = cells[row - 1];
            }
            cells[top] = blankRow(blankStyle);
        }
    }

    private TerminalCell[] blankRow(TerminalStyle blankStyle) {
        var row = new TerminalCell[_width];
        Arrays.fill(row, blank(blankStyle));
        return row;
    }

    private TerminalCell[][] cells() {
        return _usingAlternate ? _alternate : _normal;
    }

    private boolean withinScrollRegion() {
        return _row >= _scrollTop && _row <= _scrollBottom;
    }

    private static TerminalCell blank(TerminalStyle blankStyle) {
        return new TerminalCell(' ', blankStyle);
    }

    private static String renderRow(TerminalCell[] row) {
        int end = row.length;
        while (end > 0 && row[end - 1].character() == ' ') {
            end--;
        }
        if (end == 0) {
            return "";
        }
        var builder = new StringBuilder(end);
        for (int index = 0; index < end; index++) {
            builder.append(row[index].character());
        }
        return builder.toString();
    }
}
