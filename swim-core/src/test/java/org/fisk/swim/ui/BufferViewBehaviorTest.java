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
    void scrollDownHonorsViewportHeightWhenBufferIsShort() throws IOException {
        var context = createContext("one\ntwo", 10, 3);
        HeadlessWindowHarness.installForBufferContext(context);
        var view = context.getBufferView();

        view.scrollDown();

        assertEquals(0, view.getStartLine());
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

    private BufferContext createContext(String text, int width, int height) throws IOException {
        Path path = tempDir.resolve("buffer-view-" + text.hashCode() + ".txt");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, height), path);
    }
}
