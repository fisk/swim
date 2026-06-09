package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.text.AttributedString;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class ClangdLspClientTest {
    @Test
    void fallbackColouringScansLargeBlockCommentsWithoutRegexOverflow() {
        String source = "int before = 0;\n/*" + "*".repeat(50_000) + "\nint after = 1;\n";
        var attributed = AttributedString.create(source, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        var client = new ClangdLspClient(new ClangdLspProvider(Path.of("/missing/clangd")));

        assertTimeout(Duration.ofSeconds(2), () -> client.applyColouring(null, attributed));

        assertEquals(ClangdLspClient.SEMANTIC_KEYWORD, foreground(attributed, 0));
        assertEquals(ClangdLspClient.SEMANTIC_COMMENT, foreground(attributed, source.indexOf("/*")));
    }

    @Test
    void fallbackColouringHighlightsCppLexicalTokens() {
        String source = "#define TEXT \"value\"\nint main() { return '// not comment'; } // real comment\n";
        var attributed = AttributedString.create(source, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        var client = new ClangdLspClient(new ClangdLspProvider(Path.of("/missing/clangd")));

        client.applyColouring(null, attributed);

        assertEquals(ClangdLspClient.SEMANTIC_MACRO, foreground(attributed, 0));
        assertEquals(ClangdLspClient.SEMANTIC_KEYWORD, foreground(attributed, source.indexOf("int")));
        assertEquals(ClangdLspClient.SEMANTIC_STRING, foreground(attributed, source.indexOf("'// not comment'")));
        assertEquals(ClangdLspClient.SEMANTIC_COMMENT, foreground(attributed, source.lastIndexOf("// real comment")));
    }

    private static TextColor foreground(AttributedString string, int position) {
        return string.getCharacter(position).getFragments().get(0).getAttributes().foregroundColour();
    }
}
