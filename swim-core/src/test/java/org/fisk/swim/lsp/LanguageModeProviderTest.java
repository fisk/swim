package org.fisk.swim.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.lsp.latex.LatexLSPClient;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class LanguageModeProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsLatexModeForTexFiles() {
        var mode = LanguageModeProvider.getInstance().getLanguageMode(Path.of("doc.tex"));

        assertInstanceOf(LatexLSPClient.class, mode);
    }

    @Test
    void returnsPlainModeForUnknownFiles() throws IOException {
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "hello");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var mode = LanguageModeProvider.getInstance().getLanguageMode(file);

        assertNotNull(mode);
        assertEquals(0, mode.getIndentationLevel(context));
        assertEquals(false, mode.isIndentationEnd(context, "}"));
        assertNull(mode.getTextDocument(context));

        var string = AttributedString.create("hello", TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        mode.applyColouring(context, string);
        assertEquals("hello", string.toString());
    }
}
