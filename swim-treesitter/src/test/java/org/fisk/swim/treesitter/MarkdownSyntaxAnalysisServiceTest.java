package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.api.SwimPluginWorkers;
import org.junit.jupiter.api.Test;

class MarkdownSyntaxAnalysisServiceTest {
    @Test
    void executesBundledHighlightAndInjectionQueriesAgainstMarkdownSnapshot() throws Exception {
        try (var service = new MarkdownSyntaxAnalysisService(SwimPluginWorkers.unmanaged())) {
            var result = new AtomicReference<MarkdownSyntaxAnalysisService.Result>();
            var done = new CountDownLatch(1);
            service.analyse("markdown", new MarkdownSyntaxAnalysisService.Snapshot(Path.of("notes.md"), 1,
                    "# Title\n\n```java\nclass Demo {}\n```\n",
                    TreeSitterBundledGrammars.load("markdown"), TreeSitterBundledGrammars.load("markdown_inline")), value -> {
                        result.set(value); done.countDown();
                    });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(result.get().captures().stream().anyMatch(capture -> "text.title".equals(capture.name())));
            assertTrue(result.get().captures().stream().anyMatch(capture -> "injection.language".equals(capture.name())));
        }
    }
}
