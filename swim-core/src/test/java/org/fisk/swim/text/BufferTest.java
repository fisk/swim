package org.fisk.swim.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.eclipse.lsp4j.TextDocumentItem;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LanguagePluginRegistry;
import org.fisk.swim.ui.Cursor;
import org.fisk.swim.ui.Range;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class BufferTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        SwimPluginPreloadRegistry.clearForTests();
    }

    @Test
    void insertCommitUndoAndRedoRestoresText() throws IOException {
        var buffer = createBuffer("abc", 80);
        buffer.getCursor().setPosition(1);

        buffer.insert("X");
        buffer.getUndoLog().commit();

        assertEquals("aXbc", buffer.getString());
        assertEquals(2, buffer.getCursor().getPosition());

        buffer.undo();
        assertEquals("abc", buffer.getString());
        assertEquals(1, buffer.getCursor().getPosition());

        buffer.redo();
        assertEquals("aXbc", buffer.getString());
    }

    @Test
    void deleteInnerWordUpdatesClipboardAndCursor() throws IOException {
        var buffer = createBuffer("foo bar", 80);
        buffer.getCursor().setPosition(1);

        buffer.deleteInnerWord();

        assertEquals(" bar", buffer.getString());
        assertEquals(0, buffer.getCursor().getPosition());
        assertEquals("foo", Copy.getInstance().getText());
        assertFalse(Copy.getInstance().isLine());
    }

    @Test
    void deleteLastLineAlsoRemovesPrecedingNewline() throws IOException {
        var buffer = createBuffer("first\nsecond", 80);
        buffer.getCursor().setPosition(buffer.getLength() - 1);

        buffer.deleteLine();

        assertEquals("first", buffer.getString());
        assertEquals("\nsecond", Copy.getInstance().getText());
        assertEquals(5, buffer.getCursor().getPosition());
    }

    @Test
    void saveTrimsTrailingWhitespaceWhenLanguageModeRequestsIt() throws Exception {
        var mode = new CountingLanguageMode(false, null, true);
        try (var ignored = LanguagePluginRegistry.register("trimtest", "buffer-trim-test", path -> mode)) {
            var context = createBufferContext("first  \nsecond\t\n", 80, "trimtest");
            var buffer = context.getBuffer();

            buffer.writeOrThrow();

            assertEquals("first\nsecond\n", buffer.getString());
            assertEquals("first\nsecond\n", Files.readString(buffer.getPath()));
        }
    }

    @Test
    void saveKeepsTrailingWhitespaceForPlainText() throws IOException {
        var context = createBufferContext("first  \nsecond\t\n", 80);
        var buffer = context.getBuffer();

        buffer.writeOrThrow();

        assertEquals("first  \nsecond\t\n", Files.readString(buffer.getPath()));
    }

    @Test
    void removeBeforeHandlesMultipleCursorsInOrder() throws IOException {
        var context = createBufferContext("abcd", 80);
        var buffer = context.getBuffer();
        var secondCursor = new Cursor(context);
        buffer.addCursor(secondCursor);

        buffer.getCursor().setPosition(1);
        secondCursor.setPosition(3);

        buffer.removeBefore();

        assertEquals("bd", buffer.getString());
        assertEquals(0, buffer.getCursor().getPosition());
        assertEquals(1, secondCursor.getPosition());
    }

    @Test
    void textLayoutTracksWrappedAndPhysicalLinesSeparately() throws IOException {
        var context = createBufferContext("abcd\nef", 3);
        var textLayout = context.getTextLayout();

        assertEquals(3, textLayout.getLogicalLineCount());
        assertEquals(2, textLayout.getPhysicalLineCount());
        assertEquals(Range.create(0, 7).toString(), textLayout.getGlyphRange().toString());
    }

    @Test
    void rawInsertUpdatesLayoutFromAffectedLineOnly() throws IOException {
        var context = createBufferContext("one\ntwo\nthree", 80);
        var buffer = context.getBuffer();
        var textLayout = context.getTextLayout();
        var firstPhysicalLine = textLayout.getPhysicalLineAt(0);
        var firstLogicalLine = textLayout.getLogicalLineAt(0);
        int position = buffer.getString().indexOf("two") + 1;

        buffer.rawInsert(position, "X");

        assertEquals("one\ntXwo\nthree", buffer.getString());
        assertEquals(3, textLayout.getPhysicalLineCount());
        assertEquals(3, textLayout.getLogicalLineCount());
        assertSame(firstPhysicalLine, textLayout.getPhysicalLineAt(0));
        assertSame(firstLogicalLine, textLayout.getLogicalLineAt(0));

        textLayout.calculate();
        assertSame(firstPhysicalLine, textLayout.getPhysicalLineAt(0));
        assertSame(firstLogicalLine, textLayout.getLogicalLineAt(0));
    }

    @Test
    void rawInsertNewlineUpdatesLayoutFromAffectedLineOnly() throws IOException {
        var context = createBufferContext("one\ntwo\nthree", 80);
        var buffer = context.getBuffer();
        var textLayout = context.getTextLayout();
        var firstPhysicalLine = textLayout.getPhysicalLineAt(0);
        var firstLogicalLine = textLayout.getLogicalLineAt(0);
        int position = buffer.getString().indexOf("two");

        buffer.rawInsert(position, "new\n");

        assertEquals("one\nnew\ntwo\nthree", buffer.getString());
        assertEquals(4, textLayout.getPhysicalLineCount());
        assertEquals(4, textLayout.getLogicalLineCount());
        assertSame(firstPhysicalLine, textLayout.getPhysicalLineAt(0));
        assertSame(firstLogicalLine, textLayout.getLogicalLineAt(0));
        assertEquals(3, textLayout.getPhysicalLineAt(buffer.getString().indexOf("three")).getY());
    }

    @Test
    void rawRemoveUpdatesLayoutFromAffectedLineOnly() throws IOException {
        var context = createBufferContext("one\ntwo\nthree", 80);
        var buffer = context.getBuffer();
        var textLayout = context.getTextLayout();
        var firstPhysicalLine = textLayout.getPhysicalLineAt(0);
        var firstLogicalLine = textLayout.getLogicalLineAt(0);
        int start = buffer.getString().indexOf("\nthree");

        buffer.rawRemove(start, start + 1);

        assertEquals("one\ntwothree", buffer.getString());
        assertEquals(2, textLayout.getPhysicalLineCount());
        assertEquals(2, textLayout.getLogicalLineCount());
        assertSame(firstPhysicalLine, textLayout.getPhysicalLineAt(0));
        assertSame(firstLogicalLine, textLayout.getLogicalLineAt(0));
    }

    @Test
    void rawInsertReflowsWrappedLogicalSuffixOnly() throws IOException {
        var context = createBufferContext("abcd\nef", 3);
        var buffer = context.getBuffer();
        var textLayout = context.getTextLayout();
        var firstPhysicalLine = textLayout.getPhysicalLineAt(0);
        var firstLogicalLine = textLayout.getLogicalLineAt(0);
        var secondLogicalLine = textLayout.getLogicalLineAt(3);

        buffer.rawInsert(buffer.getLength(), "ghi");

        assertEquals("abcd\nefghi", buffer.getString());
        assertEquals(2, textLayout.getPhysicalLineCount());
        assertEquals(4, textLayout.getLogicalLineCount());
        assertSame(firstPhysicalLine, textLayout.getPhysicalLineAt(0));
        assertSame(firstLogicalLine, textLayout.getLogicalLineAt(0));
        assertSame(secondLogicalLine, textLayout.getLogicalLineAt(3));

        textLayout.calculate();
        assertSame(firstLogicalLine, textLayout.getLogicalLineAt(0));
        assertSame(secondLogicalLine, textLayout.getLogicalLineAt(3));
    }

    @Test
    void newlineInsideElseBlockSplitsClosingBraceAndKeepsCursorOnIndentedLine() throws IOException {
        var context = createJavaBufferContext("""
                class Demo {
                    void run() {
                        if (flag) {
                        } else {}
                    }
                }
                """, 120);
        var buffer = context.getBuffer();
        int insertPosition = buffer.getString().indexOf("else {}") + "else {".length();
        buffer.getCursor().setPosition(insertPosition);

        buffer.insert("\n");
        buffer.insert("value();");

        assertEquals("""
                class Demo {
                    void run() {
                        if (flag) {
                        } else {
                            value();
                        }
                    }
                }
                """, buffer.getString());
        assertEquals(buffer.getString().indexOf("value();") + "value();".length(), buffer.getCursor().getPosition());
    }

    @Test
    void manualFoldCollapsesLogicalLinesUntilReopened() throws IOException {
        var context = createBufferContext("""
                one
                two
                three
                four
                """, 80);
        var buffer = context.getBuffer();
        int start = buffer.getString().indexOf("two");
        int end = buffer.getString().indexOf("four");

        assertTrue(buffer.createFold(start, end));
        assertEquals(4, context.getTextLayout().getLogicalLineCount());

        assertTrue(buffer.openFoldAt(start));
        assertEquals(5, context.getTextLayout().getLogicalLineCount());
    }

    @Test
    void manualFoldsCanNestOpenCloseAndDeleteRecursively() throws IOException {
        var context = createBufferContext("""
                one
                two
                three
                four
                five
                """, 80);
        var buffer = context.getBuffer();
        int two = buffer.getString().indexOf("two");
        int three = buffer.getString().indexOf("three");
        int five = buffer.getString().indexOf("five");

        assertTrue(buffer.createFold(two, five));
        assertTrue(buffer.openFoldAt(two));
        assertTrue(buffer.createFold(three, five));
        assertEquals(2, buffer.getFolds().size());

        assertTrue(buffer.closeFoldRecursivelyAt(two));
        assertTrue(buffer.getFolds().stream().allMatch(Buffer.Fold::collapsed));

        assertTrue(buffer.openFoldRecursivelyAt(two));
        assertTrue(buffer.getFolds().stream().noneMatch(Buffer.Fold::collapsed));
        assertEquals(6, context.getTextLayout().getLogicalLineCount());

        assertTrue(buffer.toggleFoldRecursivelyAt(two));
        assertTrue(buffer.getFolds().stream().allMatch(Buffer.Fold::collapsed));
        assertTrue(buffer.toggleFoldRecursivelyAt(two));
        assertTrue(buffer.getFolds().stream().noneMatch(Buffer.Fold::collapsed));

        assertTrue(buffer.deleteFoldRecursivelyAt(two));
        assertTrue(buffer.getFolds().isEmpty());
    }

    @Test
    void manualFoldRangesTrackEditsBeforeAndInsideFold() throws IOException {
        var context = createBufferContext("""
                one
                two
                three
                four
                """, 80);
        var buffer = context.getBuffer();
        int two = buffer.getString().indexOf("two");
        int four = buffer.getString().indexOf("four");

        assertTrue(buffer.createFold(two, four));
        var fold = buffer.getFolds().getFirst();
        int originalStart = fold.start();
        int originalEnd = fold.end();

        buffer.rawInsert(0, "zero\n");
        fold = buffer.getFolds().getFirst();
        assertEquals(originalStart + "zero\n".length(), fold.start());
        assertEquals(originalEnd + "zero\n".length(), fold.end());

        buffer.rawInsert(fold.start() + 1, "inner\n");
        fold = buffer.getFolds().getFirst();
        assertEquals(originalStart + "zero\n".length(), fold.start());
        assertEquals(originalEnd + "zero\n".length() + "inner\n".length(), fold.end());

        buffer.rawRemove(0, "zero\n".length());
        fold = buffer.getFolds().getFirst();
        assertEquals(originalStart, fold.start());
        assertEquals(originalEnd + "inner\n".length(), fold.end());
    }

    @Test
    void moveLineRangeAfterMovesTextLinesWithoutCreatingTrailingBlankLines() throws IOException {
        var buffer = createBuffer("""
                one
                two
                three
                """, 80);
        buffer.getCursor().setPosition(buffer.getString().indexOf("one") + 1);

        var result = buffer.moveLineRangeAfter(0, 0, buffer.getLineCount() - 1);
        buffer.getUndoLog().commit();

        assertEquals("""
                two
                three
                one
                """, buffer.getString());
        assertEquals(2, result.startLine());

        buffer.undo();
        assertEquals("""
                one
                two
                three
                """, buffer.getString());
    }

    @Test
    void moveLineRangeAfterMovesRangesBeforeFirstLine() throws IOException {
        var buffer = createBuffer("""
                one
                two
                three
                four
                """, 80);

        var result = buffer.moveLineRangeAfter(1, 2, -1);

        assertEquals("""
                two
                three
                one
                four
                """, buffer.getString());
        assertEquals(0, result.startLine());
        assertEquals(1, result.endLine());
    }

    @Test
    void attributedStringReusesColouringForCurrentVersion() throws Exception {
        var mode = new CountingLanguageMode();
        try (var ignored = LanguagePluginRegistry.register("cachetest", "buffer-cache-test", path -> mode)) {
            var buffer = createBufferContext("alpha beta", 80, "cachetest").getBuffer();

            buffer.getAttributedString();
            buffer.getAttributedString();

            assertEquals(1, mode.colouringCount());
        }
    }

    @Test
    void attributedStringCacheInvalidatesAfterEdit() throws Exception {
        var mode = new CountingLanguageMode();
        try (var ignored = LanguagePluginRegistry.register("cachetest", "buffer-cache-test", path -> mode)) {
            var buffer = createBufferContext("alpha", 80, "cachetest").getBuffer();
            buffer.getAttributedString();

            buffer.insert(0, "z");
            buffer.getAttributedString();
            buffer.getAttributedString();

            assertEquals(2, mode.colouringCount());
        }
    }

    @Test
    void attributedStringCacheCanBeReusedAfterInsertWhenLanguageModeOptsIn() throws Exception {
        var mode = new CountingLanguageMode(true, TextColor.ANSI.RED);
        try (var ignored = LanguagePluginRegistry.register("cachetest", "buffer-cache-test", path -> mode)) {
            var buffer = createBufferContext("alpha", 80, "cachetest").getBuffer();
            buffer.getAttributedString();

            buffer.insert(2, "Z");
            var attributed = buffer.getAttributedString();

            assertEquals("alZpha", attributed.toString());
            assertEquals(TextColor.ANSI.RED, attributed.getCharacter(2).getFragments().get(0).getAttributes().foregroundColour());
            assertEquals(1, mode.colouringCount());
        }
    }

    @Test
    void attributedStringCacheCanBeReusedAfterRemoveWhenLanguageModeOptsIn() throws Exception {
        var mode = new CountingLanguageMode(true, TextColor.ANSI.RED);
        try (var ignored = LanguagePluginRegistry.register("cachetest", "buffer-cache-test", path -> mode)) {
            var buffer = createBufferContext("alpha", 80, "cachetest").getBuffer();
            buffer.getAttributedString();

            buffer.remove(1, 3);
            var attributed = buffer.getAttributedString();

            assertEquals("aha", attributed.toString());
            assertEquals(1, mode.colouringCount());
        }
    }

    @Test
    void attributedStringCacheCanBeInvalidatedForSemanticRefresh() throws Exception {
        var mode = new CountingLanguageMode();
        try (var ignored = LanguagePluginRegistry.register("cachetest", "buffer-cache-test", path -> mode)) {
            var buffer = createBufferContext("alpha", 80, "cachetest").getBuffer();
            buffer.getAttributedString();

            buffer.invalidateAttributedStringCache();
            buffer.getAttributedString();

            assertEquals(2, mode.colouringCount());
        }
    }

    @Test
    void bufferRefreshesLanguageModeWhenPluginRegistersAfterOpen() throws Exception {
        var buffer = createBufferContext("alpha", 80, "lateplugin").getBuffer();
        buffer.getAttributedString();

        var mode = new CountingLanguageMode();
        try (var ignored = LanguagePluginRegistry.register("lateplugin", "buffer-late-plugin", path -> mode)) {
            assertSame(mode, buffer.getLanguageMode());
            buffer.getAttributedString();

            assertEquals(1, mode.openCount());
            assertEquals(1, mode.colouringCount());
        }
    }

    @Test
    void externalContentReplacementResynchronizesTheLanguageMode() throws Exception {
        var mode = new CountingLanguageMode();
        try (var ignored = LanguagePluginRegistry.register("cachetest", "buffer-cache-test", path -> mode)) {
            var buffer = createBufferContext("before", 80, "cachetest").getBuffer();
            buffer.getCursor().setPosition(6);

            buffer.replaceContentsFromExternal("after\ntext");

            assertEquals("after\ntext", buffer.getString());
            assertEquals(1, mode.closeCount());
            assertEquals(2, mode.openCount());
            assertEquals(6, buffer.getCursor().getPosition());
            buffer.undo();
            assertEquals("after\ntext", buffer.getString());
        }
    }

    @Test
    void undoRecalculatesLayoutBetweenBatchedChangesBeforeNotifyingLanguageMode() throws Exception {
        var mode = new LayoutRecordingLanguageMode();
        try (var ignored = LanguagePluginRegistry.register("undoevents", "buffer-undo-events", path -> mode)) {
            var buffer = createBufferContext("aa\nbb\ncc", 80, "undoevents").getBuffer();

            buffer.remove(3, 6);
            buffer.remove(0, 3);
            buffer.getUndoLog().commit();
            mode.clear();

            buffer.undo();

            assertEquals("aa\nbb\ncc", buffer.getString());
            assertEquals(new LayoutRecordingLanguageMode.Event("insert", 0, 0, "aa\n"), mode.events().get(0));
            assertEquals(new LayoutRecordingLanguageMode.Event("insert", 1, 0, "bb\n"), mode.events().get(1));
        }
    }

    private Buffer createBuffer(String text, int width) throws IOException {
        return createBufferContext(text, width).getBuffer();
    }

    private BufferContext createBufferContext(String text, int width) throws IOException {
        return createBufferContext(text, width, "txt");
    }

    private BufferContext createBufferContext(String text, int width, String extension) throws IOException {
        Path path = tempDir.resolve("buffer-" + width + "-" + text.hashCode() + "." + extension);
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, 20), path);
    }

    private BufferContext createJavaBufferContext(String text, int width) throws IOException {
        Path path = tempDir.resolve("buffer-" + width + "-" + text.hashCode() + ".java");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, 20), path);
    }

    private BufferContext createCppBufferContext(String text, int width) throws IOException {
        Path path = tempDir.resolve("buffer-" + width + "-" + text.hashCode() + ".cpp");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, 20), path);
    }

    private static final class CountingLanguageMode implements LanguageMode {
        private int _colouringCount;
        private int _openCount;
        private int _closeCount;
        private final boolean _reuseCacheAfterEdit;
        private final TextColor _foregroundColour;
        private final boolean _trimTrailingWhitespaceOnSave;

        private CountingLanguageMode() {
            this(false, null, false);
        }

        private CountingLanguageMode(boolean reuseCacheAfterEdit, TextColor foregroundColour) {
            this(reuseCacheAfterEdit, foregroundColour, false);
        }

        private CountingLanguageMode(boolean reuseCacheAfterEdit, TextColor foregroundColour,
                boolean trimTrailingWhitespaceOnSave) {
            _reuseCacheAfterEdit = reuseCacheAfterEdit;
            _foregroundColour = foregroundColour;
            _trimTrailingWhitespaceOnSave = trimTrailingWhitespaceOnSave;
        }

        int colouringCount() {
            return _colouringCount;
        }

        int openCount() {
            return _openCount;
        }

        int closeCount() {
            return _closeCount;
        }

        @Override
        public void didInsert(BufferContext bufferContext, int position, String str) {
        }

        @Override
        public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
        }

        @Override
        public void willSave(BufferContext bufferContext) {
        }

        @Override
        public void didSave(BufferContext bufferContext) {
        }

        @Override
        public void didClose(BufferContext bufferContext) {
            _closeCount++;
        }

        @Override
        public void didOpen(BufferContext bufferContext) {
            _openCount++;
        }

        @Override
        public int getIndentationLevel(BufferContext bufferContext) {
            return 0;
        }

        @Override
        public boolean isIndentationEnd(BufferContext bufferContext, String chracter) {
            return false;
        }

        @Override
        public TextDocumentItem getTextDocument(BufferContext bufferContext) {
            return null;
        }

        @Override
        public void applyColouring(BufferContext bufferContext, AttributedString str) {
            _colouringCount++;
            if (_foregroundColour != null) {
                str.format(0, str.length(), _foregroundColour, TextColor.ANSI.DEFAULT);
            }
        }

        @Override
        public boolean canReuseAttributedStringCacheAfterEdit(BufferContext bufferContext) {
            return _reuseCacheAfterEdit;
        }

        @Override
        public boolean trimTrailingWhitespaceOnSave(BufferContext bufferContext) {
            return _trimTrailingWhitespaceOnSave;
        }
    }

    private static final class LayoutRecordingLanguageMode implements LanguageMode {
        private record Event(String kind, int line, int character, String text) {
        }

        private final List<Event> _events = new ArrayList<>();

        List<Event> events() {
            return _events;
        }

        void clear() {
            _events.clear();
        }

        @Override
        public void didInsert(BufferContext bufferContext, int position, String str) {
            var line = bufferContext.getTextLayout().getPhysicalLineAt(position);
            _events.add(new Event("insert", line.getY(), position - line.getStartPosition(), str));
        }

        @Override
        public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
            var line = bufferContext.getTextLayout().getPhysicalLineAt(startPosition);
            _events.add(new Event("remove", line.getY(), startPosition - line.getStartPosition(),
                    bufferContext.getBuffer().getSubstring(startPosition, startPosition)));
        }

        @Override
        public void willSave(BufferContext bufferContext) {
        }

        @Override
        public void didSave(BufferContext bufferContext) {
        }

        @Override
        public void didClose(BufferContext bufferContext) {
        }

        @Override
        public void didOpen(BufferContext bufferContext) {
        }

        @Override
        public int getIndentationLevel(BufferContext bufferContext) {
            return 0;
        }

        @Override
        public boolean isIndentationEnd(BufferContext bufferContext, String chracter) {
            return false;
        }

        @Override
        public TextDocumentItem getTextDocument(BufferContext bufferContext) {
            return null;
        }

        @Override
        public void applyColouring(BufferContext bufferContext, AttributedString str) {
        }
    }
}
