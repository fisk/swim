package org.fisk.swim.lsp.latex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class LatexLSPClientTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsAreNoops() throws IOException {
        Path file = tempDir.resolve("paper.tex");
        Files.writeString(file, "\\section{Intro}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new LatexLSPClient();

        client.didOpen(context);
        client.didInsert(context, 0, "x");
        client.didRemove(context, 0, 0);
        client.willSave(context);
        client.didSave(context);
        client.didClose(context);

        assertEquals(0, client.getIndentationLevel(context));
        assertFalse(client.isIndentationEnd(context, "}"));
        assertNull(client.getTextDocument(context));
    }

    @Test
    void appliesColouringWithoutChangingText() {
        var client = new LatexLSPClient();
        var string = AttributedString.create("\\section{Intro}", TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);

        client.applyColouring(null, string);

        assertEquals("\\section{Intro}", string.toString());
    }
}
