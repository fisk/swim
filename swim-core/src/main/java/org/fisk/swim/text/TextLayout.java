package org.fisk.swim.text;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.fisk.swim.ui.Range;

public class TextLayout {
    public static class Glyph {
        private int _x;
        private int _y;
        private int _position;
        private String _character;

        public Glyph(int x, int y, int position, String character) {
            _x = x;
            _y = y;
            _position = position;
            _character = character;
        }

        public int getX() {
            return _x;
        }

        public int getY() {
            return _y;
        }

        public int getPosition() {
            return _position;
        }

        public String getCharacter() {
            return _character;
        }

        public boolean isSynthetic() {
            return _position < 0;
        }
    }

    public static class Line {
        private int _y;
        private int _startPosition;
        private boolean _isNewline;
        private Line _prev;
        private Line _next;
        private List<Glyph> _glyphs = new ArrayList<>();

        public List<Glyph> getGlyphs() {
            return _glyphs;
        }

        private Line(int y, int startPosition, Line prev, boolean isNewline) {
            _y = y;
            _startPosition = startPosition;
            _prev = prev;
            _isNewline = isNewline;
        }

        private void setNext(Line line) {
            _next = line;
        }

        public boolean isNewline() {
            return _isNewline;
        }

        public int getY() {
            return _y;
        }

        public Line getPrev() {
            return _prev;
        }

        public Line getNext() {
            return _next;
        }

        public int getIndex(int position) {
            return position - _startPosition;
        }

        public Glyph getGlyphAt(int index) {
            if (index < 0 || index >= _glyphs.size()) {
                return null;
            }
            return _glyphs.get(index);
        }

        public Glyph getLastGlyph(boolean newline) {
            if (_glyphs.size() == 0) {
                return null;
            }
            var result = _glyphs.get(_glyphs.size() - 1);
            if (!newline && result._character.equals("\n")) {
                if (_glyphs.size() == 1) {
                    return null;
                }
                result = _glyphs.get(_glyphs.size() - 2);
            }
            return result;
        }

        public String getCharacterAt(int index) {
            if (index < 0 || index >= _glyphs.size()) {
                return null;
            }
            return _glyphs.get(index).getCharacter();
        }

        public int getStartPosition() {
            return _startPosition;
        }

        public int getEndPosition(boolean newline) {
            var glyph = getLastGlyph(newline);
            if (glyph == null) {
                return getStartPosition();
            } else {
                return glyph.getPosition() + 1;
            }
        }
    }

    private ArrayList<Line> _logicalLines;
    private TreeMap<Integer, Line> _logicalLineAtPosition;
    private ArrayList<Line> _physicalLines;
    private TreeMap<Integer, Line> _physicalLineAtPosition;
    private BufferContext _bufferContext;
    private int _layoutBufferVersion = -1;
    private int _layoutWidth = -1;
    private long _layoutFoldSignature = Long.MIN_VALUE;

    public TextLayout(BufferContext bufferContext) {
        _bufferContext = bufferContext;
        calculate();
    }

    public int getIndexForPhysicalLineCharacter(int lineIndex, int characterIndex) {
        var line = _physicalLines.get((Integer)lineIndex);
        var lineStart = line.getStartPosition();
        return lineStart + characterIndex;
    }

    public Line getLogicalLineAt(int position) {
        if (position < 0) {
            position = 0;
        }
        var entry = _logicalLineAtPosition.floorEntry(position);
        if (entry != null) {
            return entry.getValue();
        }
        if (!_logicalLineAtPosition.isEmpty()) {
            return _logicalLineAtPosition.firstEntry().getValue();
        }
        throw new IllegalStateException("Logical line map is empty");
    }

    public Line getPhysicalLineAt(int position) {
        if (position < 0) {
            position = 0;
        }
        var entry = _physicalLineAtPosition.floorEntry(position);
        if (entry != null) {
            return entry.getValue();
        }
        if (!_physicalLineAtPosition.isEmpty()) {
            return _physicalLineAtPosition.firstEntry().getValue();
        }
        throw new IllegalStateException("Physical line map is empty");
    }

    public Line getLastPhysicalLine() {
        return _physicalLines.get(_physicalLines.size() - 1);
    }

    private static class LayoutIterator {
        Line _line;
        ArrayList<Line> _lines = new ArrayList<>();
        TreeMap<Integer, Line> _lineAtPosition = new TreeMap<>();
        int _x = 0;
        int _y = -1;
        int _position = 0;
        String _text;
        String _character;
        boolean _isNewline;
        boolean _isWrapped;
        int _width;

        LayoutIterator(String text, int width) {
            _text = text;
            _width = width;
            newLine();
        }

        void newLine() {
            ++_y;
            _x = 0;
            int position = _position;
            if (_isNewline) {
                position++;
            }
            var line  = new Line(_y, position, _line, _isNewline);
            if (_line != null) {
                _line.setNext(line);
            }
            _line = line;
            _lines.add(_y, line);
            _lineAtPosition.put(position, line);
        }

        void insertGlyph() {
            _line.getGlyphs().add(new Glyph(_x, _y, _position, _character));
        }

        void next() {
            ++_position;
        }

        void incX() {
            ++_x;
        }

        boolean hasNext() {
            if (_position < _text.length()) {
                _character = _text.substring(_position, _position + 1);
                _isNewline = _character.equals("\n");
                _isWrapped = _x == _width;
                return true;
            } else {
                return false;
            }
        }

        boolean isNewline() {
            return _isNewline;
        }

        boolean isWrapped() {
            return _isWrapped;
        }

        ArrayList<Line> getLines() {
            return _lines;
        }

        TreeMap<Integer, Line> getLineAtPosition() {
            return _lineAtPosition;
        }
    }

    private void calculateLogicalLines(int width) {
        var string = _bufferContext.getBuffer().getString();
        _logicalLines = new ArrayList<>();
        _logicalLineAtPosition = new TreeMap<>();
        Line line = new Line(0, 0, null, false);
        _logicalLines.add(line);
        _logicalLineAtPosition.putIfAbsent(0, line);
        int x = 0;
        int y = 0;
        int position = 0;
        while (position < string.length()) {
            var fold = _bufferContext.getBuffer().collapsedFoldStartingAt(position);
            if (fold != null) {
                int firstLineEnd = string.indexOf('\n', position);
                if (firstLineEnd < 0 || firstLineEnd > fold.end()) {
                    firstLineEnd = Math.min(fold.end(), string.length());
                }
                for (int i = position; i < firstLineEnd && x < width - 3; i++) {
                    line.getGlyphs().add(new Glyph(x++, y, i, string.substring(i, i + 1)));
                }
                if (x < width) {
                    line.getGlyphs().add(new Glyph(-1, y, -1, " "));
                    x++;
                }
                if (x < width) {
                    line.getGlyphs().add(new Glyph(-1, y, -1, "…"));
                    x++;
                }
                if (x < width) {
                    line.getGlyphs().add(new Glyph(-1, y, -1, " "));
                    x++;
                }
                position = fold.end();
                if (position < string.length()) {
                    Line next = new Line(++y, position, line, false);
                    line.setNext(next);
                    line = next;
                    _logicalLines.add(line);
                    _logicalLineAtPosition.putIfAbsent(position, line);
                    x = 0;
                }
                continue;
            }
            String character = string.substring(position, position + 1);
            boolean newline = character.equals("\n");
            if (newline) {
                Line next = new Line(++y, position + 1, line, true);
                line.setNext(next);
                line = next;
                _logicalLines.add(line);
                _logicalLineAtPosition.putIfAbsent(position + 1, line);
                x = 0;
                position++;
                continue;
            }
            if (x == width) {
                Line next = new Line(++y, position, line, false);
                line.setNext(next);
                line = next;
                _logicalLines.add(line);
                _logicalLineAtPosition.putIfAbsent(position, line);
                x = 0;
            }
            line.getGlyphs().add(new Glyph(x++, y, position, character));
            position++;
        }
    }

    private void calculatePhysicalLines(int width) {
        var string = _bufferContext.getBuffer().getString();
        var iter = new LayoutIterator(string, width);
        while (iter.hasNext()) {
            if (iter.isNewline()) {
                iter.insertGlyph();
                iter.newLine();
            } else {
                iter.insertGlyph();
                iter.incX();
            }
            iter.next();
        }
        _physicalLines = iter.getLines();
        _physicalLineAtPosition = iter.getLineAtPosition();
    }

    public void calculate() {
        int version = _bufferContext.getBuffer().getVersion();
        int width = currentWidth();
        long foldSignature = collapsedFoldSignature();
        if (isCurrent(version, width, foldSignature)) {
            return;
        }
        calculatePhysicalLines(width);
        calculateLogicalLines(width);
        markCurrent(version, width, foldSignature);
        _bufferContext.getBufferView().setNeedsRedraw();
    }

    public void didInsert(int position, String text) {
        if (text == null || text.isEmpty()) {
            calculate();
            return;
        }
        updateIncrementally(position);
    }

    public void didRemove(int startPosition, int endPosition, String removedText) {
        if (removedText == null || removedText.isEmpty()) {
            calculate();
            return;
        }
        updateIncrementally(startPosition);
    }

    private void updateIncrementally(int editPosition) {
        int version = _bufferContext.getBuffer().getVersion();
        int width = currentWidth();
        long foldSignature = collapsedFoldSignature();
        if (!canUpdateIncrementally(version, width, foldSignature)) {
            calculate();
            return;
        }

        int oldPosition = Math.max(0, Math.min(editPosition, oldDocumentLength()));
        Line physicalStartLine = getPhysicalLineAt(oldPosition);
        Line logicalStartLine = getLogicalLineAt(oldPosition);

        _physicalLines = rebuildPhysicalSuffix(physicalStartLine);
        _physicalLineAtPosition = lineMap(_physicalLines);
        _logicalLines = rebuildLogicalSuffix(logicalStartLine, width);
        _logicalLineAtPosition = lineMap(_logicalLines);

        markCurrent(version, width, foldSignature);
        _bufferContext.getBufferView().setNeedsRedraw();
    }

    private boolean canUpdateIncrementally(int version, int width, long foldSignature) {
        return _layoutBufferVersion == version - 1
                && _layoutWidth == width
                && _layoutFoldSignature == foldSignature
                && !hasCollapsedFolds()
                && _physicalLines != null
                && !_physicalLines.isEmpty()
                && _logicalLines != null
                && !_logicalLines.isEmpty();
    }

    private ArrayList<Line> rebuildPhysicalSuffix(Line startLine) {
        var result = new ArrayList<Line>(_physicalLines.subList(0, startLine.getY()));
        Line previous = result.isEmpty() ? null : result.get(result.size() - 1);
        result.addAll(buildPhysicalLinesFrom(startLine.getStartPosition(), startLine.getY(), previous));
        return result;
    }

    private ArrayList<Line> buildPhysicalLinesFrom(int startPosition, int startY, Line previous) {
        var string = _bufferContext.getBuffer().getString();
        int position = Math.max(0, Math.min(startPosition, string.length()));
        int y = startY;
        int x = 0;
        var result = new ArrayList<Line>();
        Line line = new Line(y, position, previous, position > 0 && string.charAt(position - 1) == '\n');
        if (previous != null) {
            previous.setNext(line);
        }
        result.add(line);
        while (position < string.length()) {
            String character = string.substring(position, position + 1);
            line.getGlyphs().add(new Glyph(x, y, position, character));
            position++;
            if (character.equals("\n")) {
                x = 0;
                Line next = new Line(++y, position, line, true);
                line.setNext(next);
                line = next;
                result.add(line);
            } else {
                x++;
            }
        }
        return result;
    }

    private ArrayList<Line> rebuildLogicalSuffix(Line startLine, int width) {
        var result = new ArrayList<Line>(_logicalLines.subList(0, startLine.getY()));
        Line previous = result.isEmpty() ? null : result.get(result.size() - 1);
        result.addAll(buildLogicalLinesFrom(startLine.getStartPosition(), startLine.getY(), previous, width));
        return result;
    }

    private ArrayList<Line> buildLogicalLinesFrom(int startPosition, int startY, Line previous, int width) {
        var string = _bufferContext.getBuffer().getString();
        int position = Math.max(0, Math.min(startPosition, string.length()));
        int y = startY;
        int x = 0;
        var result = new ArrayList<Line>();
        Line line = new Line(y, position, previous, position > 0 && string.charAt(position - 1) == '\n');
        if (previous != null) {
            previous.setNext(line);
        }
        result.add(line);
        while (position < string.length()) {
            String character = string.substring(position, position + 1);
            if (character.equals("\n")) {
                Line next = new Line(++y, position + 1, line, true);
                line.setNext(next);
                line = next;
                result.add(line);
                x = 0;
                position++;
                continue;
            }
            if (x == width) {
                Line next = new Line(++y, position, line, false);
                line.setNext(next);
                line = next;
                result.add(line);
                x = 0;
            }
            line.getGlyphs().add(new Glyph(x++, y, position, character));
            position++;
        }
        return result;
    }

    private TreeMap<Integer, Line> lineMap(ArrayList<Line> lines) {
        var result = new TreeMap<Integer, Line>();
        for (var line : lines) {
            result.putIfAbsent(line.getStartPosition(), line);
        }
        return result;
    }

    private int currentWidth() {
        return Math.max(1, _bufferContext.getBufferView().getTextWidth());
    }

    private boolean isCurrent(int version, int width, long foldSignature) {
        return _layoutBufferVersion == version
                && _layoutWidth == width
                && _layoutFoldSignature == foldSignature;
    }

    private void markCurrent(int version, int width, long foldSignature) {
        _layoutBufferVersion = version;
        _layoutWidth = width;
        _layoutFoldSignature = foldSignature;
    }

    private int oldDocumentLength() {
        if (_physicalLines == null || _physicalLines.isEmpty()) {
            return 0;
        }
        return getLastPhysicalLine().getEndPosition(true);
    }

    private boolean hasCollapsedFolds() {
        return _bufferContext.getBuffer().getFolds().stream().anyMatch(Buffer.Fold::collapsed);
    }

    private long collapsedFoldSignature() {
        long signature = 1125899906842597L;
        for (var fold : _bufferContext.getBuffer().getFolds()) {
            if (!fold.collapsed()) {
                continue;
            }
            signature = 31 * signature + fold.start();
            signature = 31 * signature + fold.end();
        }
        return signature;
    }

    public List<Line> getVisibleLogicalLines() {
        var bufferView = _bufferContext.getBufferView();
        int start = bufferView.getStartLine();
        int end = Math.min(start + bufferView.getViewportHeight(), _logicalLines.size());
        return _logicalLines.subList(start, end);
    }

    public Stream<Glyph> getGlyphs() {
        return getVisibleLogicalLines().stream().map((line) -> line.getGlyphs()).flatMap((list) -> list.stream());
    }
    
    public Range getGlyphRange() {
        int start = -1;
        int end = -1;
        for (var glyph: getGlyphs().collect(Collectors.toList())) {
            if (start == -1) {
                start = glyph._position;
            }
            end = glyph._position;
        }
        if (end == -1) {
            return Range.create(0, 0);
        } else {
            return Range.create(start, end + 1);
        }
    }

    public int getLogicalLineCount() {
        return _logicalLines.size();
    }

    public int getPhysicalLineCount() {
        return _physicalLines.size();
    }
}
