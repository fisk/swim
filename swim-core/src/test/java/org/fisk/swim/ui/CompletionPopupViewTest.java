package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.fisk.swim.lsp.java.JavaCompletionSession;
import org.fisk.swim.text.BufferContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompletionPopupViewTest {
    @TempDir
    Path tempDir;

    @Test
    void anchorsBelowCursorWhenThereIsRoom() throws IOException {
        var context = createContext("alpha\nbeta\ngamma", 24, 10);
        context.getBuffer().getCursor().setPosition(context.getTextLayout().getIndexForPhysicalLineCharacter(1, 2));
        var root = new View(Rect.create(0, 0, 24, 10));
        root.addSubview(context.getBufferView());
        var popup = new CompletionPopupView(Rect.create(0, 0, 0, 0));
        root.addSubview(popup);

        popup.setSession(session(context));

        assertEquals(2, popup.getBounds().getPoint().getY());
        assertTrue(popup.getBounds().getSize().getWidth() <= 24);
    }

    @Test
    void anchorsAboveCursorWhenViewportBottomIsTight() throws IOException {
        var context = createContext("one\ntwo\nthree\nfour\nfive", 24, 6);
        context.getBuffer().getCursor().setPosition(context.getTextLayout().getIndexForPhysicalLineCharacter(4, 2));
        var root = new View(Rect.create(0, 0, 24, 6));
        root.addSubview(context.getBufferView());
        var popup = new CompletionPopupView(Rect.create(0, 0, 0, 0));
        root.addSubview(popup);

        popup.setSession(session(context));

        assertTrue(popup.getBounds().getPoint().getY() < 4);
        assertTrue(popup.getBounds().getPoint().getY() >= 0);
    }

    private BufferContext createContext(String text, int width, int height) throws IOException {
        Path path = tempDir.resolve("completion-popup-" + text.hashCode() + ".txt");
        Files.writeString(path, text);
        return new BufferContext(Rect.create(0, 0, width, height), path);
    }

    private JavaCompletionSession session(BufferContext context) {
        return JavaCompletionSession.create(
                context,
                "pri",
                0,
                3,
                List.of(
                        new CompletionItem("println"),
                        new CompletionItem("printf"),
                        new CompletionItem("private")),
                false);
    }
}
