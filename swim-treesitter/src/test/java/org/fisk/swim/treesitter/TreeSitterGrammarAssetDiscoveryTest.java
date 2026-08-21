package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.api.SwimPluginWorkers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeSitterGrammarAssetDiscoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void discoversGrammarAndQueriesAsOneLanguageAsset() throws Exception {
        Path language = tempDir.resolve("tree-sitter-cpp");
        Files.createDirectories(language.resolve("queries"));
        Files.createDirectories(language.resolve("src"));
        Files.writeString(language.resolve("src/grammar.json"), "{\"name\":\"cpp\",\"rules\":{}}");
        Files.writeString(language.resolve("queries/highlights.scm"), "(identifier) @variable");
        Files.writeString(language.resolve("queries/injections.scm"), "(comment) @injection.content");

        var assets = TreeSitterGrammarAssetDiscovery.discover(tempDir);

        assertEquals(1, assets.size());
        assertEquals(language.toAbsolutePath().normalize(), assets.getFirst().root());
        assertEquals("grammar.json", assets.getFirst().grammarJson().getFileName().toString());
        assertEquals("highlights.scm", assets.getFirst().highlightsQuery().getFileName().toString());
        assertEquals("injections.scm", assets.getFirst().injectionsQuery().getFileName().toString());
    }

    @Test
    void keepsQueryOnlyAssetsDiscoverableForGrammarDevelopment() throws Exception {
        Path language = tempDir.resolve("tree-sitter-markdown");
        Files.createDirectories(language.resolve("queries"));
        Files.writeString(language.resolve("queries/highlights.scm"), "(atx_heading) @markup.heading");

        var assets = TreeSitterGrammarAssetDiscovery.discover(tempDir);

        assertEquals(1, assets.size());
        assertNull(assets.getFirst().grammarJson());
        assertEquals("highlights.scm", assets.getFirst().highlightsQuery().getFileName().toString());
    }

    @Test
    void asynchronousDiscoveryPublishesOnlyTheNewestRoot() throws Exception {
        Path older = Files.createDirectories(tempDir.resolve("older"));
        Path newer = Files.createDirectories(tempDir.resolve("newer/queries"));
        Files.writeString(newer.resolve("highlights.scm"), "(heading) @markup.heading");
        var workers = new ControlledWorkers();
        var results = new ArrayList<TreeSitterGrammarAssetDiscoveryService.Result>();
        var service = new TreeSitterGrammarAssetDiscoveryService(workers);

        service.discover(older, results::add);
        service.discover(newer.getParent(), results::add);
        workers.runAll();

        assertEquals(1, results.size());
        assertEquals(newer.getParent().toAbsolutePath().normalize(), results.getFirst().root());
        assertEquals(1, results.getFirst().assets().size());
    }

    private static final class ControlledWorkers implements SwimPluginWorkers {
        private final List<Runnable> _tasks = new ArrayList<>();

        @Override public Thread start(Runnable task) { _tasks.add(task); return new Thread(task); }
        @Override public boolean isClosed() { return false; }
        @Override public void close() { }

        private void runAll() {
            for (Runnable task : _tasks) task.run();
        }
    }
}
