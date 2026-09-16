package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import org.fisk.swim.terminal.TextColor;

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

    @Test
    void clampsStaleHighlightsToTheCurrentDocument() {
        var highlights = AsyncSemanticTokenHighlighter.clampHighlights(
                List.of(new AsyncSemanticTokenHighlighter.Highlight(7095, 7104, TextColor.ANSI.BLUE)), 313);

        assertEquals(List.of(), highlights);
    }

    @Test
    void decodesTokensAgainstTheDocumentFlushedBeforeTheRequest() throws InterruptedException {
        var queue = new AsyncLspRequestQueue(
                LoggerFactory.getLogger(AsyncSemanticTokenHighlighterTest.class),
                "semantic-token-test-requests",
                () -> true);
        var flushStarted = new CountDownLatch(1);
        var allowFlushToFinish = new CountDownLatch(1);
        var fetched = new CountDownLatch(1);
        var document = new MutableTestDocument("file:///tmp/Test.cpp", 1, "int");
        var highlighter = new AsyncSemanticTokenHighlighter(
                queue,
                LoggerFactory.getLogger(AsyncSemanticTokenHighlighterTest.class),
                "semantic token refresh",
                () -> true,
                ignored -> {
                    flushStarted.countDown();
                    try {
                        assertTrue(allowFlushToFinish.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                snapshot -> {
                    assertEquals(2, snapshot.version());
                    assertEquals("new int", snapshot.text());
                    fetched.countDown();
                    return List.of(new AsyncSemanticTokenHighlighter.Highlight(4, 7, TextColor.ANSI.BLUE));
                },
                0,
                1);
        try {
            highlighter.scheduleRefresh(document);
            assertTrue(flushStarted.await(2, TimeUnit.SECONDS));

            document.update(2, "new int");
            highlighter.recordInsert(document, 0, 4);
            allowFlushToFinish.countDown();

            assertTrue(fetched.await(2, TimeUnit.SECONDS));
            var cached = highlighter.cacheView().get(document.uri());
            assertEquals(2, cached.version());
            assertEquals(List.of(new AsyncSemanticTokenHighlighter.Highlight(4, 7, TextColor.ANSI.BLUE)),
                    cached.highlights());
        } finally {
            allowFlushToFinish.countDown();
            queue.shutdown();
        }
    }

    private record TestDocument(String uri, int version, String text) implements AsyncSemanticTokenHighlighter.Document {
        @Override
        public void requestSemanticRedraw() {
        }
    }

    private static final class MutableTestDocument implements AsyncSemanticTokenHighlighter.Document {
        private final String _uri;
        private volatile int _version;
        private volatile String _text;

        private MutableTestDocument(String uri, int version, String text) {
            _uri = uri;
            _version = version;
            _text = text;
        }

        private void update(int version, String text) {
            _version = version;
            _text = text;
        }

        @Override
        public String uri() {
            return _uri;
        }

        @Override
        public int version() {
            return _version;
        }

        @Override
        public String text() {
            return _text;
        }

        @Override
        public void requestSemanticRedraw() {
        }
    }
}
