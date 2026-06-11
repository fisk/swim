package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LspDocumentChangeBatcherTest {
    @Test
    void queueNotifiesLocalChangeObserverBeforeAsyncFlush() {
        var queue = new AsyncLspRequestQueue(
                LoggerFactory.getLogger(LspDocumentChangeBatcherTest.class),
                "swim-lsp-batcher-test-requests",
                () -> true);
        var observed = new ArrayList<ObservedChange>();
        var batcher = new LspDocumentChangeBatcher(
                queue,
                () -> null,
                1_000,
                (uri, path, change) -> observed.add(new ObservedChange(uri, path, change)));
        String uri = "file:///tmp/Test.java";
        Path path = Path.of("Test.java");
        var change = new TextDocumentContentChangeEvent(
                new Range(new Position(1, 0), new Position(1, 0)),
                0,
                "x");

        try {
            batcher.queue(uri, path, new VersionedTextDocumentIdentifier(uri, 2), List.of(change));

            assertEquals(1, observed.size());
            assertEquals(uri, observed.get(0).uri());
            assertEquals(path, observed.get(0).path());
            assertSame(change, observed.get(0).change());
        } finally {
            queue.shutdown();
        }
    }

    private record ObservedChange(String uri, Path path, TextDocumentContentChangeEvent change) {
    }
}
