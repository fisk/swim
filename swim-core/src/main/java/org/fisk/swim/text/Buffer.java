package org.fisk.swim.text;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.fisk.swim.copy.Copy;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LanguageModeProvider;
import org.fisk.swim.ui.Cursor;
import org.fisk.swim.ui.Range;
import org.fisk.swim.ui.Window;
import org.fisk.swim.undo.UndoLog;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

import com.googlecode.lanterna.TextColor;

public class Buffer {
    private static final AtomicLong UNTITLED_COUNTER = new AtomicLong();

    private static final class InsertPlan {
        private final String _text;
        private final int _cursorAdvance;

        private InsertPlan(String text, int cursorAdvance) {
            _text = text;
            _cursorAdvance = cursorAdvance;
        }
    }

    private StringBuilder _string = new StringBuilder();
    private Path _path;
    private final URI _uri;
    private List<Cursor> _cursors = new ArrayList<>();
    private BufferContext _bufferContext;
    private UndoLog _undoLog;
    private int _version = 1;
    private boolean _readOnly;
    private static Logger _log = LogFactory.createLog();
    private final List<Fold> _folds = new ArrayList<>();

    public record Fold(int start, int end, boolean collapsed) {
        boolean contains(int position) {
            return position >= start && position < end;
        }
    }

    public interface CharacterTransform {
        String apply(String text);
    }

    public record LineMoveResult(int startLine, int endLine) {
    }

    public Cursor getCursor() {
        return _cursors.get(0);
    }

    public void addCursor(Cursor cursor) {
        _cursors.add(cursor);
    }

    public List<Cursor> getCursors() {
        return _cursors;
    }

    public void clearCursors() {
        var cursor = _cursors.get(0);
        _cursors.clear();
        _cursors.add(cursor);
    }

    public List<Cursor> getCursorsOrdered() {
        var result = new ArrayList<Cursor>();
        result.addAll(_cursors);
        result.sort((Cursor c1, Cursor c2) -> {
            return c1.getPosition() - c2.getPosition();
        });
        return result;
    }

    private LanguageMode _languageMode;

    public Buffer(Path path, BufferContext bufferContext) {
        this(path, bufferContext, null, false);
    }

    public Buffer(Path path, BufferContext bufferContext, String initialText, boolean readOnly) {
        _path = path == null ? null : path.toAbsolutePath();
        _uri = _path == null
                ? URI.create("untitled:swim-buffer-" + UNTITLED_COUNTER.incrementAndGet())
                : _path.toFile().toURI();
        _bufferContext = bufferContext;
        _cursors.add(new Cursor(bufferContext));
        _undoLog = new UndoLog(bufferContext);
        if (_path != null) {
            try {
                setInitialString(Files.readString(_path));
            } catch (IOException e) {
            }
        } else if (initialText != null) {
            setInitialString(initialText);
        }
        _readOnly = readOnly;
        _languageMode = LanguageModeProvider.getInstance().getLanguageMode(_path);
    }

    private void setInitialString(String string) {
        _string.append(string);
        var decoration = new Decoration();
        decoration._str = AttributedString.create(_string.toString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        decoration._version = _version;
        _decorations.add(decoration);
    }

    public String getCharacter(int position) {
        if (position < 0 || _string.length() == 0 || position >= _string.length()) {
            return "";
        }
        return _string.substring(position, position + 1);
    }

    public LanguageMode getLanguageMode() {
        return _languageMode;
    }

    public void setReadOnly(boolean readOnly) {
        _readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return _readOnly;
    }

    public void undo() {
        int position = _undoLog.undo();
        if (position == -1) {
            return;
        }
        getCursor().setPosition(position);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void redo() {
        int position = _undoLog.redo();
        if (position == -1) {
            return;
        }
        getCursor().setPosition(position);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public UndoLog getUndoLog() {
        return _undoLog;
    }

    public void rawInsert(int position, String str) {
        if (_readOnly) {
            return;
        }
        adjustFoldsForInsert(position, str.length());
        _string.insert(position, str);
        _version++;
        var decoration = new Decoration();
        decoration._str = AttributedString.create(_string.toString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        decoration._version = _version;
        decoration._didInsert = true;
        decoration._insertPosition = position;
        decoration._insertString = str;
        _decorations.add(decoration);
        _languageMode.didInsert(_bufferContext, position, str);
    }

    public void rawRemove(int startPosition, int endPosition) {
        if (_readOnly) {
            return;
        }
        adjustFoldsForRemove(startPosition, endPosition);
        _string.delete(startPosition, endPosition);
        removeInvalidFolds();
        _version++;
        var decoration = new Decoration();
        decoration._str = AttributedString.create(_string.toString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        decoration._version = _version;
        decoration._didRemove = true;
        decoration._removeStart = startPosition;
        decoration._removeEnd = endPosition;
        _decorations.add(decoration);
        _languageMode.didRemove(_bufferContext, startPosition, endPosition);
    }

    public void remove(int startPosition, int endPosition) {
        if (_readOnly) {
            return;
        }
        if (endPosition - startPosition <= 0) {
            return;
        }
        _undoLog.recordRemove(startPosition, endPosition);
        rawRemove(startPosition, endPosition);
        getCursor().setPosition(startPosition);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void reindentLine() {
        var textLayout = _bufferContext.getTextLayout();
        var position = getCursor().getPosition();
        var line = textLayout.getPhysicalLineAt(position);
        int lineStartPosition = line.getStartPosition();
        if (lineStartPosition >= position) {
            return;
        }
        var substring = getString().substring(lineStartPosition, position - 1);
        if (substring.trim().equals("")) {
            remove(lineStartPosition, position - 1);
            var str = "";
            for (int i = 0; i < getIndentationLevel() - 1; ++i) {
                str += getIndentationString();
            }
            insert(str);
        }
    }


    public void insert(String str) {
        if (_readOnly) {
            return;
        }
        int inserted = 0;
        for (var cursor: getCursorsOrdered()) {
            int position = cursor.getPosition() + inserted;
            var plan = createInsertPlan(position, str);
            _undoLog.recordInsert(position, plan._text);
            rawInsert(position, plan._text);
            _bufferContext.getTextLayout().calculate();
            cursor.setPosition(position + plan._cursorAdvance);
            _bufferContext.getBufferView().adaptViewToCursor();
            inserted += plan._text.length();
        }
        if (isIndentationEnd(str)) {
            reindentLine();
        }
    }

    private InsertPlan createInsertPlan(int position, String str) {
        if (!"\n".equals(str)) {
            return new InsertPlan(str, str.length());
        }

        String currentIndent = indentationOfLineAt(position);
        char previous = previousNonWhitespaceOnLine(position);
        char next = nextNonWhitespaceOnLine(position);
        String indentationString = getIndentationString();

        if (next == '}') {
            String closingIndent = currentIndent;
            String indent = closingIndent + indentationString;
            String text = "\n" + indent + "\n" + closingIndent;
            return new InsertPlan(text, 1 + indent.length());
        }
        String indent = previous == '{' ? currentIndent + indentationString : currentIndent;
        String text = "\n" + indent;
        return new InsertPlan(text, text.length());
    }

    private String getIndentationString() {
        return _languageMode == null ? Settings.getIndentationString() : _languageMode.getIndentationString(_bufferContext);
    }

    private char previousNonWhitespaceOnLine(int position) {
        for (int i = position - 1; i >= 0; --i) {
            char character = _string.charAt(i);
            if (character == '\n') {
                break;
            }
            if (!Character.isWhitespace(character)) {
                return character;
            }
        }
        return 0;
    }

    private char nextNonWhitespaceOnLine(int position) {
        for (int i = position; i < _string.length(); ++i) {
            char character = _string.charAt(i);
            if (character == '\n') {
                break;
            }
            if (!Character.isWhitespace(character)) {
                return character;
            }
        }
        return 0;
    }

    private String indentationOfLineAt(int position) {
        int start = Math.max(0, Math.min(position, _string.length()));
        while (start > 0 && _string.charAt(start - 1) != '\n') {
            --start;
        }
        int end = start;
        while (end < _string.length()) {
            char character = _string.charAt(end);
            if (character == ' ' || character == '\t') {
                ++end;
                continue;
            }
            break;
        }
        return _string.substring(start, end);
    }

    public void insert(int position, String str) {
        if (_readOnly) {
            return;
        }
        _undoLog.recordInsert(position, str);
        rawInsert(position, str);
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(position + str.length());
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void removeBefore() {
        if (_readOnly) {
            return;
        }
        int removed = 0;
        for (var cursor: getCursorsOrdered()) {
            int position = cursor.getPosition() - removed - 1;
            if (position < 0 || _string.length() == 0) {
                continue;
            }
            _undoLog.recordRemove(position, position + 1);
            rawRemove(position, position + 1);
            cursor.setPosition(position);
            removed++;
        }
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void removeAt() {
        if (_readOnly) {
            return;
        }
        if (_string.length() == 0) {
            return;
        }
        int position = getCursor().getPosition();
        if (position >= _string.length()) {
            return;
        }
        Copy.getInstance().setDelete(getSubstring(position, position + 1), false /* isLine */,
                Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
        _undoLog.recordRemove(position, position + 1);
        rawRemove(getCursor().getPosition(), getCursor().getPosition() + 1);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void deleteInnerWord() {
        if (_readOnly) {
            return;
        }
        int start = findStartOfWord();
        int end = findEndOfWord();
        if (start == -1 || end == -1) {
            return;
        }
        Copy.getInstance().setDelete(getSubstring(start, end), false /* isLine */,
                Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
        _undoLog.recordRemove(start, end);
        rawRemove(start, end);
        getCursor().setPosition(start);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public String getInnerWord() {
        int start = findStartOfWord();
        int end = findEndOfWord();
        if (start == -1 || end == -1) {
            return "";
        }
        return getSubstring(start, end);
    }

    public void deleteWord() {
        if (_readOnly) {
            return;
        }
        int start = getCursor().getPosition();
        if (!_wordPattern.matcher(getCharacter(start)).matches()) {
            return;
        }
        int end = findEndOfWord();
        if (end == -1) {
            return;
        }
        Copy.getInstance().setDelete(getSubstring(start, end), false /* isLine */,
                Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
        _undoLog.recordRemove(start, end);
        rawRemove(start, end);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void deleteLine() {
        if (_readOnly) {
            return;
        }
        var textLayout = _bufferContext.getTextLayout();
        var line = textLayout.getPhysicalLineAt(getCursor().getPosition());
        int start = line.getStartPosition();
        var glyph = line.getLastGlyph(true);
        int end;
        if (glyph != null) {
            end = glyph.getPosition() + 1;
        } else {
            end = line.getStartPosition();
        }
        if (line.getNext() == null) {
            // Last line is special
            start = Math.max(0, start - 1);
        }
        Copy.getInstance().setDelete(getSubstring(start, end), true /* isLine */,
                Window.getInstance() == null ? null : Window.getInstance().consumeSelectedRegister());
        _undoLog.recordRemove(start, end);
        rawRemove(start, end);
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(start);
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public String getCurrentLineText() {
        var textLayout = _bufferContext.getTextLayout();
        var line = textLayout.getPhysicalLineAt(getCursor().getPosition());
        int start = line.getStartPosition();
        var glyph = line.getLastGlyph(true);
        int end;
        if (glyph != null) {
            end = glyph.getPosition() + 1;
        } else {
            end = line.getStartPosition();
        }
        if (line.getNext() == null) {
            // Last line is special
            start = Math.max(0, start - 1);
        }
        return getSubstring(start, end);
    }

    public int getLineStartPosition(int position) {
        int safe = Math.max(0, Math.min(position, getLength()));
        while (safe > 0 && _string.charAt(safe - 1) != '\n') {
            safe--;
        }
        return safe;
    }

    public int getLineEndPosition(int position, boolean includeNewline) {
        int safe = Math.max(0, Math.min(position, getLength()));
        while (safe < getLength() && _string.charAt(safe) != '\n') {
            safe++;
        }
        if (includeNewline && safe < getLength()) {
            safe++;
        }
        return safe;
    }

    public int getLineIndexAt(int position) {
        int safe = Math.max(0, Math.min(position, getLength()));
        int line = 0;
        for (int i = 0; i < safe; i++) {
            if (_string.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    public int getLineCount() {
        int lines = 1;
        for (int i = 0; i < getLength(); i++) {
            if (_string.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    public int getLineStartByIndex(int lineIndex) {
        int target = Math.max(0, lineIndex);
        if (target == 0) {
            return 0;
        }
        int line = 0;
        for (int i = 0; i < getLength(); i++) {
            if (_string.charAt(i) == '\n') {
                line++;
                if (line == target) {
                    return i + 1;
                }
            }
        }
        return getLength();
    }

    public int getLineEndByIndex(int lineIndex, boolean includeNewline) {
        return getLineEndPosition(getLineStartByIndex(lineIndex), includeNewline);
    }

    public int getColumnAt(int position) {
        return Math.max(0, Math.min(position, getLength()) - getLineStartPosition(position));
    }

    public int getPositionAtLineColumn(int lineIndex, int column) {
        int start = getLineStartByIndex(lineIndex);
        int end = getLineEndPosition(start, false);
        return Math.max(start, Math.min(start + Math.max(0, column), end));
    }

    public int nextWordPosition(int position, int count, boolean bigWord) {
        int result = Math.max(0, Math.min(position, getLength()));
        for (int i = 0; i < Math.max(1, count); i++) {
            result = nextWordPositionOnce(result, bigWord);
        }
        return result;
    }

    public int previousWordPosition(int position, int count, boolean bigWord) {
        int result = Math.max(0, Math.min(position, getLength()));
        for (int i = 0; i < Math.max(1, count); i++) {
            result = previousWordPositionOnce(result, bigWord);
        }
        return result;
    }

    public int wordEndPosition(int position, int count, boolean bigWord) {
        int result = Math.max(0, Math.min(position, Math.max(0, getLength() - 1)));
        for (int i = 0; i < Math.max(1, count); i++) {
            result = wordEndPositionOnce(result, bigWord);
        }
        return result;
    }

    public int paragraphForwardPosition(int position, int count) {
        int line = getLineIndexAt(position);
        for (int i = 0; i < Math.max(1, count); i++) {
            int current = line + 1;
            while (current < getLineCount() && !lineText(current).isBlank()) {
                current++;
            }
            while (current < getLineCount() && lineText(current).isBlank()) {
                current++;
            }
            line = Math.min(current, getLineCount() - 1);
        }
        return getLineStartByIndex(line);
    }

    public int paragraphBackwardPosition(int position, int count) {
        int line = getLineIndexAt(position);
        for (int i = 0; i < Math.max(1, count); i++) {
            int current = Math.max(0, line - 1);
            while (current > 0 && lineText(current).isBlank()) {
                current--;
            }
            while (current > 0 && !lineText(current - 1).isBlank()) {
                current--;
            }
            line = current;
        }
        return getLineStartByIndex(line);
    }

    public int sentenceForwardPosition(int position, int count) {
        int result = Math.max(0, Math.min(position, getLength()));
        for (int i = 0; i < Math.max(1, count); i++) {
            int next = result + 1;
            while (next < getLength()) {
                char c = _string.charAt(next);
                if ((c == '.' || c == '!' || c == '?') && (next + 1 == getLength() || Character.isWhitespace(_string.charAt(next + 1)))) {
                    result = Math.min(getLength(), next + 1);
                    break;
                }
                next++;
            }
            if (next >= getLength()) {
                result = getLength();
            }
        }
        return result;
    }

    public int sentenceBackwardPosition(int position, int count) {
        int result = Math.max(0, Math.min(position, getLength()));
        for (int i = 0; i < Math.max(1, count); i++) {
            int previous = Math.max(0, result - 2);
            while (previous > 0) {
                char c = _string.charAt(previous);
                if ((c == '.' || c == '!' || c == '?') && Character.isWhitespace(_string.charAt(previous + 1))) {
                    result = Math.min(getLength(), previous + 2);
                    while (result < getLength() && Character.isWhitespace(_string.charAt(result))) {
                        result++;
                    }
                    break;
                }
                previous--;
            }
            if (previous <= 0) {
                result = 0;
            }
        }
        return result;
    }

    public int matchingBracketPosition(int position) {
        if (getLength() == 0) {
            return -1;
        }
        int safe = Math.max(0, Math.min(position, getLength() - 1));
        int lineEnd = getLineEndPosition(safe, false);
        int candidate = safe;
        while (candidate < lineEnd && bracketPartner(_string.charAt(candidate)) == 0) {
            candidate++;
        }
        if (candidate >= getLength() || bracketPartner(_string.charAt(candidate)) == 0) {
            return -1;
        }
        char openOrClose = _string.charAt(candidate);
        char partner = bracketPartner(openOrClose);
        int direction = isOpenBracket(openOrClose) ? 1 : -1;
        int depth = 0;
        for (int i = candidate; i >= 0 && i < getLength(); i += direction) {
            char c = _string.charAt(i);
            if (c == openOrClose) {
                depth++;
            } else if (c == partner) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    public int findCharacterOnLine(int position, char character, boolean forward, boolean till, int count) {
        int start = getLineStartPosition(position);
        int end = getLineEndPosition(position, false);
        int remaining = Math.max(1, count);
        if (forward) {
            for (int i = Math.min(position + 1, end); i < end; i++) {
                if (_string.charAt(i) == character && --remaining == 0) {
                    return till ? Math.max(position, i - 1) : i;
                }
            }
        } else {
            for (int i = Math.max(start, position - 1); i >= start; i--) {
                if (_string.charAt(i) == character && --remaining == 0) {
                    return till ? Math.min(position, i + 1) : i;
                }
            }
        }
        return -1;
    }

    public Range lineRangeForPositions(int startPosition, int endPosition) {
        int start = getLineStartPosition(Math.min(startPosition, endPosition));
        int end = getLineEndPosition(Math.max(startPosition, endPosition), true);
        if (end <= start && start > 0) {
            start = Math.max(0, start - 1);
        }
        return Range.create(start, end);
    }

    public Range lineRangeForCount(int count) {
        int startLine = getLineIndexAt(getCursor().getPosition());
        int endLine = Math.min(getLineCount() - 1, startLine + Math.max(1, count) - 1);
        int start = getLineStartByIndex(startLine);
        int end = getLineEndByIndex(endLine, true);
        return Range.create(start, end);
    }

    public void yankRange(int startPosition, int endPosition, boolean lineWise, Character register) {
        var range = normalizedRange(startPosition, endPosition);
        if (lineWise) {
            range = lineRangeForPositions(range.getStart(), Math.max(range.getStart(), range.getEnd() - 1));
        }
        Copy.getInstance().setYank(getSubstring(range.getStart(), range.getEnd()), lineWise, register);
    }

    public void deleteRange(int startPosition, int endPosition, boolean lineWise, Character register) {
        if (_readOnly) {
            return;
        }
        var range = normalizedRange(startPosition, endPosition);
        if (lineWise) {
            range = lineRangeForPositions(range.getStart(), Math.max(range.getStart(), range.getEnd() - 1));
        }
        if (range.getLength() <= 0) {
            return;
        }
        Copy.getInstance().setDelete(getSubstring(range.getStart(), range.getEnd()), lineWise, register);
        _undoLog.recordRemove(range.getStart(), range.getEnd());
        rawRemove(range.getStart(), range.getEnd());
        getCursor().setPosition(Math.min(range.getStart(), getLength()));
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void changeRange(int startPosition, int endPosition, boolean lineWise, Character register) {
        deleteRange(startPosition, endPosition, lineWise, register);
    }

    public void transformRange(int startPosition, int endPosition, boolean lineWise, CharacterTransform transform) {
        if (_readOnly || transform == null) {
            return;
        }
        var range = normalizedRange(startPosition, endPosition);
        if (lineWise) {
            range = lineRangeForPositions(range.getStart(), Math.max(range.getStart(), range.getEnd() - 1));
        }
        if (range.getLength() <= 0) {
            return;
        }
        String original = getSubstring(range.getStart(), range.getEnd());
        String transformed = transform.apply(original);
        if (original.equals(transformed)) {
            return;
        }
        _undoLog.recordRemove(range.getStart(), range.getEnd());
        rawRemove(range.getStart(), range.getEnd());
        _undoLog.recordInsert(range.getStart(), transformed);
        rawInsert(range.getStart(), transformed);
        getCursor().setPosition(Math.min(range.getStart(), getLength()));
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public boolean replaceAtCursor(char character, int count) {
        if (_readOnly) {
            return false;
        }
        int start = getCursor().getPosition();
        int end = Math.min(getLineEndPosition(start, false), start + Math.max(1, count));
        if (end <= start) {
            return false;
        }
        String replacement = Character.toString(character).repeat(end - start);
        _undoLog.recordRemove(start, end);
        rawRemove(start, end);
        _undoLog.recordInsert(start, replacement);
        rawInsert(start, replacement);
        getCursor().setPosition(start);
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
        return true;
    }

    public boolean joinLines(int count) {
        if (_readOnly) {
            return false;
        }
        int joins = Math.max(1, count);
        int position = getCursor().getPosition();
        boolean changed = false;
        for (int i = 0; i < joins; i++) {
            int newline = _string.indexOf("\n", getLineStartPosition(position));
            if (newline < 0 || newline >= getLength()) {
                break;
            }
            int nextStart = newline + 1;
            int nextNonWhitespace = nextStart;
            while (nextNonWhitespace < getLength()
                    && _string.charAt(nextNonWhitespace) != '\n'
                    && Character.isWhitespace(_string.charAt(nextNonWhitespace))) {
                nextNonWhitespace++;
            }
            _undoLog.recordRemove(newline, nextNonWhitespace);
            rawRemove(newline, nextNonWhitespace);
            _undoLog.recordInsert(newline, " ");
            rawInsert(newline, " ");
            changed = true;
            position = newline;
        }
        if (changed) {
            getCursor().setPosition(position);
            _bufferContext.getTextLayout().calculate();
            _bufferContext.getBufferView().adaptViewToCursor();
        }
        return changed;
    }

    public void indentLines(int startPosition, int endPosition, int levels) {
        if (_readOnly || levels == 0) {
            return;
        }
        var range = lineRangeForPositions(startPosition, Math.max(startPosition, endPosition - 1));
        int startLine = getLineIndexAt(range.getStart());
        int endLine = Math.max(startLine, getLineIndexAt(Math.max(range.getStart(), range.getEnd() - 1)));
        String indent = getIndentationString();
        if (levels > 0) {
            for (int line = startLine; line <= endLine; line++) {
                int position = getLineStartByIndex(line);
                String text = indent.repeat(levels);
                _undoLog.recordInsert(position, text);
                rawInsert(position, text);
            }
        } else {
            for (int line = endLine; line >= startLine; line--) {
                int start = getLineStartByIndex(line);
                int end = Math.min(getLineEndPosition(start, false), start + indent.length() * -levels);
                int removeEnd = start;
                while (removeEnd < end && (_string.charAt(removeEnd) == ' ' || _string.charAt(removeEnd) == '\t')) {
                    removeEnd++;
                }
                if (removeEnd > start) {
                    _undoLog.recordRemove(start, removeEnd);
                    rawRemove(start, removeEnd);
                }
            }
        }
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(Math.min(getCursor().getPosition(), getLength()));
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public LineMoveResult moveLineRangeAfter(int startLine, int endLine, int destinationLine) {
        if (_readOnly) {
            return null;
        }
        String original = getString();
        boolean trailingNewline = original.endsWith("\n");
        var lines = new ArrayList<>(List.of(original.split("\n", -1)));
        if (trailingNewline && lines.size() > 1) {
            lines.remove(lines.size() - 1);
        }
        int lineCount = lines.size();
        if (lineCount <= 1) {
            return null;
        }
        int first = Math.max(0, Math.min(startLine, endLine));
        int last = Math.min(lineCount - 1, Math.max(startLine, endLine));
        int destination = Math.max(-1, Math.min(destinationLine, lineCount - 1));
        if (first >= lineCount) {
            return null;
        }
        if ((destination >= first && destination <= last) || destination == first - 1) {
            return null;
        }

        var moved = new ArrayList<String>(lines.subList(first, last + 1));
        lines.subList(first, last + 1).clear();
        int insertionIndex = destination < first
                ? destination + 1
                : destination - moved.size() + 1;
        insertionIndex = Math.max(0, Math.min(insertionIndex, lines.size()));
        lines.addAll(insertionIndex, moved);

        String updated = String.join("\n", lines);
        if (trailingNewline) {
            updated += "\n";
        }
        if (updated.equals(original)) {
            return null;
        }

        int column = getColumnAt(getCursor().getPosition());
        if (!original.isEmpty()) {
            _undoLog.recordRemove(0, original.length());
            rawRemove(0, original.length());
        }
        if (!updated.isEmpty()) {
            _undoLog.recordInsert(0, updated);
            rawInsert(0, updated);
        }
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(getPositionAtLineColumn(insertionIndex, column));
        _bufferContext.getBufferView().adaptViewToCursor();
        return new LineMoveResult(insertionIndex, insertionIndex + moved.size() - 1);
    }

    public LineMoveResult moveLineRangeBy(int startLine, int endLine, int delta) {
        if (delta == 0) {
            return null;
        }
        int first = Math.max(0, Math.min(startLine, endLine));
        int last = Math.min(getLineCount() - 1, Math.max(startLine, endLine));
        int destination = delta < 0 ? first - 2 : last + 1;
        LineMoveResult result = null;
        for (int i = 0; i < Math.abs(delta); i++) {
            var next = moveLineRangeAfter(first, last, destination);
            if (next == null) {
                return result;
            }
            result = next;
            first = result.startLine();
            last = result.endLine();
            destination = delta < 0 ? first - 2 : last + 1;
        }
        return result;
    }

    public void autoIndentLines(int startPosition, int endPosition) {
        if (_readOnly) {
            return;
        }
        var range = lineRangeForPositions(startPosition, Math.max(startPosition, endPosition - 1));
        int startLine = getLineIndexAt(range.getStart());
        int endLine = Math.max(startLine, getLineIndexAt(Math.max(range.getStart(), range.getEnd() - 1)));
        String indent = getIndentationString();
        int depth = indentationDepthBeforeLine(startLine);
        for (int line = startLine; line <= endLine; line++) {
            int start = getLineStartByIndex(line);
            int end = getLineEndPosition(start, false);
            String text = getSubstring(start, end).stripLeading();
            int effectiveDepth = text.startsWith("}") || text.startsWith("]") || text.startsWith(")") ? Math.max(0, depth - 1) : depth;
            int whitespaceEnd = start;
            while (whitespaceEnd < end && (_string.charAt(whitespaceEnd) == ' ' || _string.charAt(whitespaceEnd) == '\t')) {
                whitespaceEnd++;
            }
            String replacement = indent.repeat(effectiveDepth);
            if (!getSubstring(start, whitespaceEnd).equals(replacement)) {
                if (whitespaceEnd > start) {
                    _undoLog.recordRemove(start, whitespaceEnd);
                    rawRemove(start, whitespaceEnd);
                }
                if (!replacement.isEmpty()) {
                    _undoLog.recordInsert(start, replacement);
                    rawInsert(start, replacement);
                }
            }
            depth = Math.max(0, effectiveDepth + netIndentDelta(text));
        }
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(Math.min(getCursor().getPosition(), getLength()));
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public void insertBlock(List<String> lines, boolean after) {
        if (_readOnly || lines == null || lines.isEmpty()) {
            return;
        }
        int baseLine = getLineIndexAt(getCursor().getPosition());
        int column = getColumnAt(getCursor().getPosition()) + (after ? 1 : 0);
        ensureLineCount(baseLine + lines.size());
        for (int i = 0; i < lines.size(); i++) {
            int line = baseLine + i;
            int start = getLineStartByIndex(line);
            int end = getLineEndPosition(start, false);
            int target = Math.min(start + column, end);
            if (target < start + column) {
                String padding = " ".repeat(start + column - target);
                _undoLog.recordInsert(target, padding);
                rawInsert(target, padding);
                target += padding.length();
            }
            String text = lines.get(i);
            _undoLog.recordInsert(target, text);
            rawInsert(target, text);
        }
        getCursor().setPosition(getPositionAtLineColumn(baseLine, column));
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public List<String> getBlockText(int startPosition, int endPosition) {
        int startLine = getLineIndexAt(Math.min(startPosition, endPosition));
        int endLine = getLineIndexAt(Math.max(startPosition, endPosition));
        int startColumn = Math.min(getColumnAt(startPosition), getColumnAt(endPosition));
        int endColumn = Math.max(getColumnAt(startPosition), getColumnAt(endPosition)) + 1;
        var lines = new ArrayList<String>();
        for (int line = startLine; line <= endLine; line++) {
            int start = getPositionAtLineColumn(line, startColumn);
            int end = getPositionAtLineColumn(line, endColumn);
            lines.add(getSubstring(start, end));
        }
        return lines;
    }

    public void deleteBlock(int startPosition, int endPosition, Character register) {
        if (_readOnly) {
            return;
        }
        var lines = getBlockText(startPosition, endPosition);
        Copy.getInstance().setBlock(lines, register);
        int startLine = getLineIndexAt(Math.min(startPosition, endPosition));
        int endLine = getLineIndexAt(Math.max(startPosition, endPosition));
        int startColumn = Math.min(getColumnAt(startPosition), getColumnAt(endPosition));
        int endColumn = Math.max(getColumnAt(startPosition), getColumnAt(endPosition)) + 1;
        for (int line = endLine; line >= startLine; line--) {
            int start = getPositionAtLineColumn(line, startColumn);
            int end = getPositionAtLineColumn(line, endColumn);
            if (end > start) {
                _undoLog.recordRemove(start, end);
                rawRemove(start, end);
            }
        }
        getCursor().setPosition(getPositionAtLineColumn(startLine, startColumn));
        _bufferContext.getTextLayout().calculate();
        _bufferContext.getBufferView().adaptViewToCursor();
    }

    public List<Integer> findLiteralMatches(String text) {
        var matches = new ArrayList<Integer>();
        if (text == null || text.isEmpty()) {
            return matches;
        }
        int from = 0;
        String haystack = getString();
        while (from <= haystack.length() - text.length()) {
            int index = haystack.indexOf(text, from);
            if (index < 0) {
                break;
            }
            matches.add(index);
            from = index + Math.max(1, text.length());
        }
        return matches;
    }

    public List<Fold> getFolds() {
        return List.copyOf(_folds);
    }

    public Fold collapsedFoldStartingAt(int position) {
        for (var fold : _folds) {
            if (fold.collapsed() && fold.start() == position) {
                return fold;
            }
        }
        return null;
    }

    public boolean createFold(int selectionStart, int selectionEnd) {
        var range = foldLineRange(selectionStart, selectionEnd);
        int start = range.getStart();
        int end = range.getEnd();
        if (end <= start) {
            return false;
        }
        if (hasCrossingFold(start, end)) {
            return false;
        }
        _folds.removeIf(fold -> fold.start() == start && fold.end() == end);
        _folds.add(new Fold(start, end, true));
        sortFolds();
        _bufferContext.getTextLayout().calculate();
        return true;
    }

    public boolean createFoldForLineCount(int count) {
        var range = lineRangeForCount(Math.max(1, count));
        return createFold(range.getStart(), range.getEnd());
    }

    public boolean toggleFoldAt(int position) {
        int index = foldIndexAt(position);
        if (index >= 0) {
            var fold = _folds.get(index);
            _folds.set(index, new Fold(fold.start(), fold.end(), !fold.collapsed()));
            _bufferContext.getTextLayout().calculate();
            return true;
        }
        return false;
    }

    public boolean closeFoldAt(int position) {
        int index = foldIndexAt(position);
        if (index >= 0) {
            var fold = _folds.get(index);
            _folds.set(index, new Fold(fold.start(), fold.end(), true));
            _bufferContext.getTextLayout().calculate();
            return true;
        }
        return false;
    }

    public boolean openFoldAt(int position) {
        int index = foldIndexAt(position);
        if (index >= 0) {
            var fold = _folds.get(index);
            _folds.set(index, new Fold(fold.start(), fold.end(), false));
            _bufferContext.getTextLayout().calculate();
            return true;
        }
        return false;
    }

    public boolean closeFoldRecursivelyAt(int position) {
        return setFoldTreeCollapsedAt(position, true);
    }

    public boolean openFoldRecursivelyAt(int position) {
        return setFoldTreeCollapsedAt(position, false);
    }

    public boolean toggleFoldRecursivelyAt(int position) {
        int index = foldIndexAt(position);
        if (index < 0) {
            return false;
        }
        return setFoldTreeCollapsedAt(position, !_folds.get(index).collapsed());
    }

    public void openAllFolds() {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            _folds.set(i, new Fold(fold.start(), fold.end(), false));
        }
        _bufferContext.getTextLayout().calculate();
    }

    public boolean deleteFoldAt(int position) {
        int index = foldIndexAt(position);
        if (index < 0) {
            return false;
        }
        _folds.remove(index);
        _bufferContext.getTextLayout().calculate();
        return true;
    }

    public boolean deleteFoldRecursivelyAt(int position) {
        int index = foldIndexAt(position);
        if (index < 0) {
            return false;
        }
        var root = _folds.get(index);
        _folds.removeIf(fold -> isContainedBy(fold, root));
        _bufferContext.getTextLayout().calculate();
        return true;
    }

    public void deleteAllFolds() {
        if (_folds.isEmpty()) {
            return;
        }
        _folds.clear();
        _bufferContext.getTextLayout().calculate();
    }

    public int nextFoldStartPosition(int position, int count) {
        int remaining = Math.max(1, count);
        int last = -1;
        for (var fold : _folds) {
            if (fold.start() <= position || fold.start() == last) {
                continue;
            }
            last = fold.start();
            if (--remaining == 0) {
                return fold.start();
            }
        }
        return -1;
    }

    public int previousFoldStartPosition(int position, int count) {
        int remaining = Math.max(1, count);
        int last = -1;
        for (int i = _folds.size() - 1; i >= 0; i--) {
            var fold = _folds.get(i);
            if (fold.start() >= position || fold.start() == last) {
                continue;
            }
            last = fold.start();
            if (--remaining == 0) {
                return fold.start();
            }
        }
        return -1;
    }

    public void closeAllFolds() {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            _folds.set(i, new Fold(fold.start(), fold.end(), true));
        }
        _bufferContext.getTextLayout().calculate();
    }

    private Range foldLineRange(int selectionStart, int selectionEnd) {
        int startPosition = Math.min(selectionStart, selectionEnd);
        int endPosition = Math.max(selectionStart, selectionEnd);
        var startLine = _bufferContext.getTextLayout().getPhysicalLineAt(startPosition);
        var endLine = _bufferContext.getTextLayout().getPhysicalLineAt(Math.max(startPosition, endPosition - 1));
        return Range.create(startLine.getStartPosition(), endLine.getEndPosition(true));
    }

    private boolean setFoldTreeCollapsedAt(int position, boolean collapsed) {
        int index = foldIndexAt(position);
        if (index < 0) {
            return false;
        }
        var root = _folds.get(index);
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (isContainedBy(fold, root)) {
                _folds.set(i, new Fold(fold.start(), fold.end(), collapsed));
            }
        }
        _bufferContext.getTextLayout().calculate();
        return true;
    }

    private int foldIndexAt(int position) {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (fold.collapsed() && fold.start() == position) {
                return i;
            }
        }
        int bestIndex = -1;
        int bestLength = Integer.MAX_VALUE;
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (fold.contains(position)) {
                int length = fold.end() - fold.start();
                if (length < bestLength) {
                    bestLength = length;
                    bestIndex = i;
                }
            }
        }
        return bestIndex;
    }

    private boolean hasCrossingFold(int start, int end) {
        for (var fold : _folds) {
            boolean overlaps = start < fold.end() && fold.start() < end;
            boolean nested = isContainedBy(start, end, fold.start(), fold.end())
                    || isContainedBy(fold.start(), fold.end(), start, end);
            if (overlaps && !nested) {
                return true;
            }
        }
        return false;
    }

    private void adjustFoldsForInsert(int position, int length) {
        if (length <= 0 || _folds.isEmpty()) {
            return;
        }
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (position < fold.start()) {
                _folds.set(i, new Fold(fold.start() + length, fold.end() + length, fold.collapsed()));
            } else if (position <= fold.end()) {
                _folds.set(i, new Fold(fold.start(), fold.end() + length, fold.collapsed()));
            }
        }
        sortFolds();
    }

    private void adjustFoldsForRemove(int start, int end) {
        int length = end - start;
        if (length <= 0 || _folds.isEmpty()) {
            return;
        }
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            int newStart = translateFoldPositionAfterRemove(fold.start(), start, end);
            int newEnd = translateFoldPositionAfterRemove(fold.end(), start, end);
            _folds.set(i, new Fold(newStart, newEnd, fold.collapsed()));
        }
        sortFolds();
    }

    private int translateFoldPositionAfterRemove(int position, int start, int end) {
        if (position <= start) {
            return position;
        }
        if (position >= end) {
            return position - (end - start);
        }
        return start;
    }

    private void removeInvalidFolds() {
        _folds.removeIf(fold -> fold.end() <= fold.start() || hasCrossingFoldExcluding(fold));
        sortFolds();
    }

    private boolean hasCrossingFoldExcluding(Fold target) {
        for (var fold : _folds) {
            if (fold == target || fold.start() == target.start() && fold.end() == target.end()) {
                continue;
            }
            boolean overlaps = target.start() < fold.end() && fold.start() < target.end();
            boolean nested = isContainedBy(target, fold) || isContainedBy(fold, target);
            if (overlaps && !nested) {
                return true;
            }
        }
        return false;
    }

    private void sortFolds() {
        _folds.sort(java.util.Comparator.comparingInt(Fold::start).thenComparing((left, right) -> right.end() - left.end()));
    }

    private static boolean isContainedBy(Fold fold, Fold container) {
        return isContainedBy(fold.start(), fold.end(), container.start(), container.end());
    }

    private static boolean isContainedBy(int start, int end, int containerStart, int containerEnd) {
        return start >= containerStart && end <= containerEnd;
    }

    public Range textObjectRange(String object, boolean around) {
        if (object == null || object.isBlank()) {
            return null;
        }
        return switch (object) {
        case "w" -> wordRange(around);
        case "p" -> paragraphRange(around);
        case "(", ")" -> enclosedRange('(', ')', around);
        case "{", "}" -> enclosedRange('{', '}', around);
        case "[", "]" -> enclosedRange('[', ']', around);
        case "\"", "'" -> quoteRange(object.charAt(0), around);
        default -> null;
        };
    }

    public boolean addNextCursorForLiteral(String text, boolean forward) {
        var matches = findLiteralMatches(text);
        if (matches.isEmpty()) {
            return false;
        }
        var taken = new HashSet<Integer>();
        int anchor = getCursor().getPosition();
        for (var cursor : getCursors()) {
            taken.add(cursor.getPosition());
            if (forward) {
                anchor = Math.max(anchor, cursor.getPosition());
            } else {
                anchor = Math.min(anchor, cursor.getPosition());
            }
        }
        if (forward) {
            for (int position : matches) {
                if (position > anchor && !taken.contains(position)) {
                    var cursor = new org.fisk.swim.ui.Cursor(_bufferContext);
                    cursor.setPosition(position);
                    addCursor(cursor);
                    return true;
                }
            }
            return false;
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            int position = matches.get(i);
            if (position < anchor && !taken.contains(position)) {
                var cursor = new org.fisk.swim.ui.Cursor(_bufferContext);
                cursor.setPosition(position);
                addCursor(cursor);
                return true;
            }
        }
        return false;
    }

    public int substitute(Pattern pattern, String replacement, boolean global, boolean wholeBuffer) {
        if (_readOnly || pattern == null) {
            return 0;
        }
        int start;
        int end;
        if (wholeBuffer) {
            start = 0;
            end = getLength();
        } else {
            var line = _bufferContext.getTextLayout().getPhysicalLineAt(getCursor().getPosition());
            start = line.getStartPosition();
            end = line.getEndPosition(false);
        }
        String source = getSubstring(start, end);
        var matcher = pattern.matcher(source);
        int matches = 0;
        while (matcher.find()) {
            matches++;
            if (!global) {
                break;
            }
        }
        if (matches == 0) {
            return 0;
        }
        matcher = pattern.matcher(source);
        String replaced = global ? matcher.replaceAll(replacement) : matcher.replaceFirst(replacement);
        _undoLog.recordRemove(start, end);
        rawRemove(start, end);
        if (!replaced.isEmpty()) {
            _undoLog.recordInsert(start, replaced);
            rawInsert(start, replaced);
        }
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(Math.min(start + replaced.length(), getLength()));
        _bufferContext.getBufferView().adaptViewToCursor();
        return matches;
    }

    public int substitute(Pattern pattern, String replacement, boolean global, int startLine, int endLine) {
        if (_readOnly || pattern == null) {
            return 0;
        }
        int first = Math.max(0, Math.min(startLine, endLine));
        int last = Math.min(getLineCount() - 1, Math.max(startLine, endLine));
        int start = getLineStartByIndex(first);
        int end = getLineEndByIndex(last, false);
        String source = getSubstring(start, end);
        var matcher = pattern.matcher(source);
        int matches = 0;
        while (matcher.find()) {
            matches++;
            if (!global) {
                break;
            }
        }
        if (matches == 0) {
            return 0;
        }
        matcher = pattern.matcher(source);
        String replaced = global ? matcher.replaceAll(replacement) : matcher.replaceFirst(replacement);
        _undoLog.recordRemove(start, end);
        rawRemove(start, end);
        if (!replaced.isEmpty()) {
            _undoLog.recordInsert(start, replaced);
            rawInsert(start, replaced);
        }
        _bufferContext.getTextLayout().calculate();
        getCursor().setPosition(Math.min(start + replaced.length(), getLength()));
        _bufferContext.getBufferView().adaptViewToCursor();
        return matches;
    }

    public int deleteMatchingLines(Pattern pattern, boolean invert) {
        if (_readOnly || pattern == null) {
            return 0;
        }
        int deleted = 0;
        for (int line = getLineCount() - 1; line >= 0; line--) {
            int start = getLineStartByIndex(line);
            int end = getLineEndByIndex(line, true);
            String text = getSubstring(start, getLineEndByIndex(line, false));
            boolean matches = pattern.matcher(text).find();
            if (matches == invert) {
                continue;
            }
            _undoLog.recordRemove(start, end);
            rawRemove(start, end);
            deleted++;
        }
        if (deleted > 0) {
            _bufferContext.getTextLayout().calculate();
            getCursor().setPosition(Math.min(getCursor().getPosition(), getLength()));
            _bufferContext.getBufferView().adaptViewToCursor();
        }
        return deleted;
    }

    private Range normalizedRange(int startPosition, int endPosition) {
        int start = Math.max(0, Math.min(startPosition, endPosition));
        int end = Math.max(0, Math.max(startPosition, endPosition));
        return Range.create(Math.min(start, getLength()), Math.min(end, getLength()));
    }

    private void ensureLineCount(int desiredLineCount) {
        while (getLineCount() < desiredLineCount) {
            int position = getLength();
            _undoLog.recordInsert(position, "\n");
            rawInsert(position, "\n");
        }
        _bufferContext.getTextLayout().calculate();
    }

    private int indentationDepthBeforeLine(int lineIndex) {
        int depth = 0;
        for (int line = 0; line < lineIndex; line++) {
            int start = getLineStartByIndex(line);
            int end = getLineEndPosition(start, false);
            depth = Math.max(0, depth + netIndentDelta(getSubstring(start, end).trim()));
        }
        return depth;
    }

    private static int netIndentDelta(String text) {
        int delta = 0;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '{' || character == '[' || character == '(') {
                delta++;
            } else if (character == '}' || character == ']' || character == ')') {
                delta--;
            }
        }
        return delta;
    }

    private int nextWordPositionOnce(int position, boolean bigWord) {
        int length = getLength();
        int current = Math.max(0, Math.min(position, length));
        if (current >= length) {
            return length;
        }
        WordClass startClass = wordClassAt(current, bigWord);
        if (startClass != WordClass.WHITESPACE) {
            while (current < length && wordClassAt(current, bigWord) == startClass) {
                current++;
            }
        }
        while (current < length && wordClassAt(current, bigWord) == WordClass.WHITESPACE) {
            current++;
        }
        return Math.min(current, length);
    }

    private int previousWordPositionOnce(int position, boolean bigWord) {
        int current = Math.max(0, Math.min(position - 1, Math.max(0, getLength() - 1)));
        while (current > 0 && wordClassAt(current, bigWord) == WordClass.WHITESPACE) {
            current--;
        }
        WordClass target = wordClassAt(current, bigWord);
        while (current > 0 && wordClassAt(current - 1, bigWord) == target) {
            current--;
        }
        return current;
    }

    private int wordEndPositionOnce(int position, boolean bigWord) {
        int length = getLength();
        int current = Math.max(0, Math.min(position, Math.max(0, length - 1)));
        if (current >= length) {
            return length;
        }
        WordClass currentClass = wordClassAt(current, bigWord);
        if (currentClass == WordClass.WHITESPACE || (current + 1 < length && wordClassAt(current + 1, bigWord) != currentClass)) {
            current++;
            while (current < length && wordClassAt(current, bigWord) == WordClass.WHITESPACE) {
                current++;
            }
            currentClass = current < length ? wordClassAt(current, bigWord) : WordClass.WHITESPACE;
        }
        while (current + 1 < length && wordClassAt(current + 1, bigWord) == currentClass) {
            current++;
        }
        return current;
    }

    private WordClass wordClassAt(int position, boolean bigWord) {
        if (position < 0 || position >= getLength()) {
            return WordClass.WHITESPACE;
        }
        char character = _string.charAt(position);
        if (Character.isWhitespace(character)) {
            return WordClass.WHITESPACE;
        }
        if (bigWord) {
            return WordClass.WORD;
        }
        if (Character.isLetterOrDigit(character) || character == '_') {
            return WordClass.WORD;
        }
        return WordClass.PUNCTUATION;
    }

    private String lineText(int lineIndex) {
        int start = getLineStartByIndex(lineIndex);
        return getSubstring(start, getLineEndPosition(start, false));
    }

    private static char bracketPartner(char character) {
        return switch (character) {
        case '(' -> ')';
        case ')' -> '(';
        case '[' -> ']';
        case ']' -> '[';
        case '{' -> '}';
        case '}' -> '{';
        default -> 0;
        };
    }

    private static boolean isOpenBracket(char character) {
        return character == '(' || character == '[' || character == '{';
    }

    private enum WordClass {
        WHITESPACE,
        WORD,
        PUNCTUATION
    }

    private Range wordRange(boolean around) {
        int start = findStartOfWord();
        int end = findEndOfWord();
        if (start < 0 || end < 0) {
            return null;
        }
        if (around) {
            while (start > 0 && Character.isWhitespace(getCharacter(start - 1).charAt(0))) {
                start--;
            }
            while (end < getLength() && Character.isWhitespace(getCharacter(end).charAt(0))) {
                end++;
            }
        }
        return Range.create(start, end);
    }

    private Range paragraphRange(boolean around) {
        var line = _bufferContext.getTextLayout().getPhysicalLineAt(getCursor().getPosition());
        var current = line;
        while (current.getPrev() != null && !lineText(current.getPrev()).isBlank()) {
            current = current.getPrev();
        }
        int start = current.getStartPosition();
        current = line;
        while (current.getNext() != null && !lineText(current.getNext()).isBlank()) {
            current = current.getNext();
        }
        int end = current.getEndPosition(true);
        if (around) {
            if (line.getPrev() != null && lineText(line.getPrev()).isBlank()) {
                start = line.getPrev().getStartPosition();
            }
            if (current.getNext() != null && lineText(current.getNext()).isBlank()) {
                end = current.getNext().getEndPosition(true);
            }
        }
        return Range.create(start, end);
    }

    private String lineText(org.fisk.swim.text.TextLayout.Line line) {
        if (line == null) {
            return "";
        }
        int start = line.getStartPosition();
        int end = line.getEndPosition(false);
        return getSubstring(start, end);
    }

    private Range quoteRange(char quote, boolean around) {
        String text = getString();
        int position = Math.min(getCursor().getPosition(), Math.max(0, text.length() - 1));
        int start = -1;
        for (int i = position; i >= 0; i--) {
            if (text.charAt(i) == quote && !isEscaped(text, i)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        int end = -1;
        for (int i = start + 1; i < text.length(); i++) {
            if (text.charAt(i) == quote && !isEscaped(text, i)) {
                end = i;
                break;
            }
        }
        if (end < 0 || end <= start) {
            return null;
        }
        return around ? Range.create(start, end + 1) : Range.create(start + 1, end);
    }

    private Range enclosedRange(char open, char close, boolean around) {
        String text = getString();
        int position = Math.min(getCursor().getPosition(), Math.max(0, text.length() - 1));
        int depth = 0;
        int start = -1;
        for (int i = position; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == close) {
                depth++;
            } else if (c == open) {
                if (depth == 0) {
                    start = i;
                    break;
                }
                depth--;
            }
        }
        if (start < 0) {
            return null;
        }
        depth = 0;
        int end = -1;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                if (depth == 0) {
                    end = i;
                    break;
                }
                depth--;
            }
        }
        if (end < 0 || end <= start) {
            return null;
        }
        return around ? Range.create(start, end + 1) : Range.create(start + 1, end);
    }

    private static boolean isEscaped(String text, int index) {
        int escapes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            escapes++;
        }
        return escapes % 2 == 1;
    }

    static Pattern _wordPattern = Pattern.compile("\\w");

    private int findStartOfWord() {
        int position = getCursor().getPosition();
        if (!_wordPattern.matcher(getCharacter(position)).matches()) {
            return -1;
        }
        for (int i = position; i >= 0; --i) {
            if (!_wordPattern.matcher(getCharacter(i)).matches()) {
                return i + 1;
            }
        }
        return 0;
    }

    private int findEndOfWord() {
        int position = getCursor().getPosition();
        if (!_wordPattern.matcher(getCharacter(position)).matches()) {
            return -1;
        }
        for (int i = position; i < getLength(); ++i) {
            if (!_wordPattern.matcher(getCharacter(i)).matches()) {
                return i;
            }
        }
        return getLength();
    }

    public void write() {
        if (_readOnly) {
            return;
        }
        try {
            writeOrThrow();
        } catch (IOException e) {
            var window = Window.getInstance();
            if (window != null && window.getCommandView() != null) {
                window.getCommandView().setMessage(e.getMessage());
            }
        }
    }

    public void writeOrThrow() throws IOException {
        if (_path == null) {
            throw new IOException("Buffer has no file path");
        }
        _languageMode.willSave(_bufferContext);
        Files.writeString(_path, _string.toString());
        var window = Window.getInstance();
        if (window != null && window.getCommandView() != null) {
            window.getCommandView().setMessage("Saved file");
        }
        _languageMode.didSave(_bufferContext);
    }

    public void close() {
        _languageMode.didClose(_bufferContext);
    }

    public void open() {
        _languageMode.didOpen(_bufferContext);
    }

    public int getLength() {
        return _string.length();
    }

    public String getString() {
        return _string.toString();
    }

    public String getSubstring(int start, int end) {
        return _string.substring(start, end);
    }

    public URI getURI() {
        return _uri;
    }

    public int getIndentationLevel() {
        return _languageMode.getIndentationLevel(_bufferContext);
    }

    public boolean isIndentationEnd(String character) {
        return _languageMode.isIndentationEnd(_bufferContext, character);
    }

    private static class Decoration {
        private volatile AttributedString _str;
        private int _version;

        private boolean _didInsert;
        private int _insertPosition;
        private String _insertString;

        private boolean _didRemove;
        private int _removeStart;
        private int _removeEnd;

        private volatile boolean _isDecorated;
    }

    private CopyOnWriteArrayList<Decoration> _decorations = new CopyOnWriteArrayList<>();

    // TODO: Fix this
    public void applyDecorations(int version) { //, List<SemanticHighlightingInformation> info) {
        //        _log.info("Applying decorations for version " + version);
        //        AttributedString str = null;
        //        for (var decoration: _decorations) {
        //            if (decoration._isDecorated) {
        //                _log.info("Found decorated string for version " + decoration._version);
        //                str = AttributedString.create(decoration._str);
        //            } else if (str != null) {
        //                if (decoration._didInsert) {
        //                    _log.info("Inserting string for version " + decoration._version);
        //                    str.insert(decoration._insertString, decoration._insertPosition, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        //                }
        //                if (decoration._didRemove) {
        //                    _log.info("Removing string for version " + decoration._version);
        //                    str.remove(decoration._removeStart, decoration._removeEnd);
        //                }
        //            }
        //            if (decoration._version != version) {
        //                _log.info("Skipping version " + decoration._version);
        //            } else {
        //                _log.info("Found version " + version);
        //                if (str != null) {
        //                    if (!decoration._str.toString().equals(str.toString())) {
        //                        throw new RuntimeException("Strings do not match: 1) " + decoration._str.toString() + "\n2) " + str.toString());
        //                    }
        //                    decoration._str = AttributedString.create(str);
        //                }
        //                _log.info("String length: " + decoration._str.length());
        //                for (var line: info) {
        //                    var decodedTokens = SemanticHighlightingTokens.decode(line.getTokens());
        //                    var lineNum = line.getLine();
        //                    for (var token: decodedTokens) {
        //                        var charNum = token.character;
        //                        int index = _bufferContext.getTextLayout().getIndexForPhysicalLineCharacter(lineNum, charNum);
        //                        _log.info("Format range [" + index + ", " + (index + token.length) + ")");
        //                        decoration._str.format(index, index + token.length,
        //                                JavaLSPClient.getInstance().foregroundColourForScope(token.scope),
        //                                TextColor.ANSI.DEFAULT);
        //                    }
        //                }
        //                _languageMode.applyColouring(_bufferContext, decoration._str);
        //                decoration._isDecorated = true;
        //                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
        //                    _log.info("Redrawing version " + version);
        //                    _bufferContext.getBufferView().setNeedsRedraw();
        //                }));
        //                break;
        //            }
        //        }
    }

    public AttributedString getAttributedString() {
        Decoration lastAttributedDecoration = null;
        for (var decoration: _decorations) {
            if (decoration._isDecorated) {
                lastAttributedDecoration = decoration;
            }
        }
        if (lastAttributedDecoration != null) {
            for (var decoration: _decorations) {
                if (decoration == lastAttributedDecoration) {
                    break;
                } else {
                    _decorations.remove(decoration);
                }
            }
            AttributedString str = null;
            for (var decoration: _decorations) {
                if (decoration._isDecorated) {
                    _log.info("Found decorated string for version " + decoration._version);
                    str = AttributedString.create(decoration._str);
                } else if (str != null) {
                    if (decoration._didInsert) {
                        _log.info("Inserting string for version " + decoration._version);
                        str.insert(decoration._insertString, decoration._insertPosition, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
                    }
                    if (decoration._didRemove) {
                        _log.info("Removing string for version " + decoration._version);
                        str.remove(decoration._removeStart, decoration._removeEnd);
                    }
                }
            }
            return str;
        } else {
            var str = AttributedString.create(_string.toString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
            _languageMode.applyColouring(_bufferContext, str);
            return str;
        }
    }

    public TextDocumentItem getTextDocument() {
        return _languageMode.getTextDocument(_bufferContext);
    }

    public TextDocumentIdentifier getTextDocumentID() {
        return new TextDocumentIdentifier(_uri.toString());
    }

    public VersionedTextDocumentIdentifier getVersionedTextDocumentID() {
        return new VersionedTextDocumentIdentifier(_uri.toString(), _version);
    }

    public Path getPath() {
        return _path;
    }
}
