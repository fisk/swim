package org.fisk.swim.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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

    private Buffer createBuffer(String text, int width) throws IOException {
        return createBufferContext(text, width).getBuffer();
    }

    private BufferContext createBufferContext(String text, int width) throws IOException {
        Path path = tempDir.resolve("buffer-" + width + "-" + text.hashCode() + ".txt");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, 20), path);
    }
}
