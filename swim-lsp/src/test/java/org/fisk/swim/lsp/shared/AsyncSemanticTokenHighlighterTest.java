package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.googlecode.lanterna.TextColor;

class AsyncSemanticTokenHighlighterTest {
    @Test
    void editInvalidatesCachedTokensInsteadOfTransformingThemOnCallerThread() {
        var queue = new AsyncLspRequestQueue(
                LoggerFactory.getLogger(AsyncSemanticTokenHighlighterTest.class),
                "semantic-token-test-requests",
                () -> true);
        var highlighter = new AsyncSemanticTokenHighlighter(
                queue,
                LoggerFactory.getLogger(AsyncSemanticTokenHighlighterTest.class),
                "semantic token refresh",
                () -> true,
                ignored -> {},
                ignored -> List.of(),
                0,
                1);
        var document = new TestDocument("file:///tmp/Test.cpp", 2, "int value;");
        try {
            highlighter.cacheView().put(document.uri(), new AsyncSemanticTokenHighlighter.CachedSemanticTokens(
                    1,
                    List.of(new AsyncSemanticTokenHighlighter.Highlight(0, 3, TextColor.ANSI.BLUE))));

            highlighter.recordInsert(document, 4, 1);

            assertFalse(highlighter.cacheView().containsKey(document.uri()));
        } finally {
            queue.shutdown();
        }
    }

    private record TestDocument(String uri, int version, String text) implements AsyncSemanticTokenHighlighter.Document {
        @Override
        public void requestSemanticRedraw() {
        }
    }
}
