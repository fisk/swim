package org.fisk.swim.text;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
        _path = path == null ? null : path.toAbsolutePath();
        _uri = _path == null
                ? URI.create("untitled:swim-buffer-" + UNTITLED_COUNTER.incrementAndGet())
                : _path.toFile().toURI();
        _bufferContext = bufferContext;
        _cursors.add(new Cursor(bufferContext));
        _undoLog = new UndoLog(bufferContext);
        if (_path != null) {
            try {
                _string.append(Files.readString(_path));
                var decoration = new Decoration();
                decoration._str = AttributedString.create(_string.toString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
                decoration._version = _version;
                _decorations.add(decoration);
            } catch (IOException e) {
            }
        }
        _languageMode = LanguageModeProvider.getInstance().getLanguageMode(_path);
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
        _string.delete(startPosition, endPosition);
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
        Copy.getInstance().setText(getSubstring(position, position + 1), false /* isLine */,
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
        Copy.getInstance().setText(getSubstring(start, end), false /* isLine */,
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
        Copy.getInstance().setText(getSubstring(start, end), false /* isLine */,
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
        Copy.getInstance().setText(getSubstring(start, end), true /* isLine */,
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
        var startLine = _bufferContext.getTextLayout().getPhysicalLineAt(selectionStart);
        var endLine = _bufferContext.getTextLayout().getPhysicalLineAt(Math.max(selectionStart, selectionEnd - 1));
        int start = startLine.getStartPosition();
        int end = endLine.getEndPosition(true);
        if (end <= start || startLine == endLine) {
            return false;
        }
        _folds.removeIf(fold -> overlaps(fold.start(), fold.end(), start, end));
        _folds.add(new Fold(start, end, true));
        _folds.sort(java.util.Comparator.comparingInt(Fold::start));
        _bufferContext.getTextLayout().calculate();
        return true;
    }

    public boolean toggleFoldAt(int position) {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (fold.contains(position)) {
                _folds.set(i, new Fold(fold.start(), fold.end(), !fold.collapsed()));
                _bufferContext.getTextLayout().calculate();
                return true;
            }
        }
        return false;
    }

    public boolean closeFoldAt(int position) {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (fold.contains(position)) {
                _folds.set(i, new Fold(fold.start(), fold.end(), true));
                _bufferContext.getTextLayout().calculate();
                return true;
            }
        }
        return false;
    }

    public boolean openFoldAt(int position) {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            if (fold.contains(position)) {
                _folds.set(i, new Fold(fold.start(), fold.end(), false));
                _bufferContext.getTextLayout().calculate();
                return true;
            }
        }
        return false;
    }

    public void openAllFolds() {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            _folds.set(i, new Fold(fold.start(), fold.end(), false));
        }
        _bufferContext.getTextLayout().calculate();
    }

    public void closeAllFolds() {
        for (int i = 0; i < _folds.size(); i++) {
            var fold = _folds.get(i);
            _folds.set(i, new Fold(fold.start(), fold.end(), true));
        }
        _bufferContext.getTextLayout().calculate();
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

    private static boolean overlaps(int leftStart, int leftEnd, int rightStart, int rightEnd) {
        return leftStart < rightEnd && rightStart < leftEnd;
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
