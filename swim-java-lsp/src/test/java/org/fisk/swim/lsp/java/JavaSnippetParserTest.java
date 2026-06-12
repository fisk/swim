package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class JavaSnippetParserTest {
    @Test
    void parsesPlainTabStopsChoicesAndFinalCursor() {
        var result = JavaSnippetParser.parse("call(${1:name}, ${2|left,right|})$0", Path.of("/tmp/Demo.java"));

        assertEquals("call(name, left)", result.text());
        assertEquals(List.of(1, 2), result.tabStops().stream().map(JavaSnippetParser.TabStop::id).toList());
        assertEquals(5, result.tabStops().get(0).start());
        assertEquals(9, result.tabStops().get(0).end());
        assertEquals("call(name, left)".length(), result.finalCursorOffset());
    }

    @Test
    void resolvesCommonVariablesWithFallbacks() {
        var result = JavaSnippetParser.parse(
                "${TM_FILENAME_BASE}:${TM_DIRECTORY}:${UNKNOWN:fallback}",
                Path.of("/tmp/demo/DemoFile.java"));

        assertEquals("DemoFile:/tmp/demo:fallback", result.text());
    }
}
