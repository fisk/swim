package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.text.BufferContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufferViewBehaviorTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
    }

    @Test
    void scrollDownAllowsBlankRowsBelowEofButStopsAtTheFinalLine() throws IOException {
        var context = createContext("one\ntwo", 10, 3);
        HeadlessWindowHarness.installForBufferContext(context);
        var view = context.getBufferView();

        for (int i = 0; i < 10; i++) {
            view.scrollDown();
        }

        assertEquals(1, view.getStartLine());
        assertEquals(1, context.getBuffer().getCursor().getYAbsolute());
    }

    @Test
    void adaptViewToCursorMovesViewportToKeepCursorVisible() throws IOException {
        var context = createContext("one\ntwo\nthree\nfour", 10, 2);
        HeadlessWindowHarness.installForBufferContext(context);
        var view = context.getBufferView();
        var cursor = context.getBuffer().getCursor();

        cursor.setPosition(context.getTextLayout().getIndexForPhysicalLineCharacter(3, 0));
        view.adaptViewToCursor();
        assertEquals(2, view.getStartLine());

        cursor.setPosition(0);
        view.adaptViewToCursor();
        assertEquals(0, view.getStartLine());
    }

    @Test
    void restoringCursorBeforeLayoutDoesNotScrollAZeroHeightViewport() throws IOException {
        Path path = tempDir.resolve("restore-cursor.txt");
        Files.writeString(path, "one\ntwo\nthree\nfour");
        var context = new BufferContext(Rect.create(0, 0, 0, 0), path);

        context.getBuffer().getCursor().restorePosition(context.getBuffer().getLength());

        assertEquals(0, context.getBufferView().getStartLine());
    }

    @Test
    void restoringCursorBeforeLayoutRecalculatesItsVisualRowAtRealWidth() throws IOException {
        Path path = tempDir.resolve("restore-wrap-width.txt");
        Files.writeString(path, "abcdefghijklmnopqrstuvwxyz\nsecond line");
        var context = new BufferContext(Rect.create(0, 0, 0, 0), path);

        context.getBuffer().getCursor().restorePosition(27);
        assertEquals(26, context.getBuffer().getCursor().getYAbsolute());

        context.getBufferView().setBounds(Rect.create(0, 0, 80, 8));

        assertEquals(1, context.getBuffer().getCursor().getYAbsolute());
    }

    private BufferContext createContext(String text, int width, int height) throws IOException {
        Path path = tempDir.resolve("buffer-view-" + text.hashCode() + ".txt");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, height), path);
    }
}
