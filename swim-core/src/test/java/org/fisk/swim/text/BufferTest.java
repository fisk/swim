package org.fisk.swim.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.ui.Cursor;
import org.fisk.swim.ui.Range;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferTest {
    @TempDir
    Path tempDir;

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
    void newlineInsideCppBlockUsesTwoSpaceIndentation() throws IOException {
        var context = createCppBufferContext("""
                int main() {}
                """, 120);
        var buffer = context.getBuffer();
        int insertPosition = buffer.getString().indexOf('{') + 1;
        buffer.getCursor().setPosition(insertPosition);

        buffer.insert("\n");
        buffer.insert("return 0;");

        assertEquals("""
                int main() {
                  return 0;
                }
                """, buffer.getString());
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

    private Buffer createBuffer(String text, int width) throws IOException {
        return createBufferContext(text, width).getBuffer();
    }

    private BufferContext createBufferContext(String text, int width) throws IOException {
        Path path = tempDir.resolve("buffer-" + width + "-" + text.hashCode() + ".txt");
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
}
