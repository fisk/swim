package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.api.SwimPluginWorkers;
import org.junit.jupiter.api.Test;

class TreeSitterSyntaxAnalysisServiceTest {
    @Test
    void publishesOnlyTheNewestSyntaxSnapshot() {
        var workers = new ControlledWorkers();
        var results = new ArrayList<TreeSitterSyntaxAnalysisService.Result>();
        var service = new TreeSitterSyntaxAnalysisService(workers);
        var grammar = TreeSitterSubsetRuntimeTest.castGrammar();

        service.parse(TreeSitterSubsetRuntimeTest.snapshot(grammar, "static_cast<int>(old)"), results::add);
        service.parse(TreeSitterSubsetRuntimeTest.snapshot(grammar, "static_cast<int>(newer)"), results::add);
        workers.runAll();

        assertEquals(1, results.size());
        assertEquals("static_cast<int>(newer)", results.getFirst().snapshot().text());
        assertEquals(true, results.getFirst().tree().succeeded());
    }

    @Test
    void keepsIndependentDocumentsInFlight() {
        var workers = new ControlledWorkers();
        var results = new ArrayList<TreeSitterSyntaxAnalysisService.Result>();
        var service = new TreeSitterSyntaxAnalysisService(workers);
        var grammar = TreeSitterSubsetRuntimeTest.castGrammar();

        service.parse(new TreeSitterSyntaxSnapshot(java.nio.file.Path.of("one.cpp"), 1,
                "static_cast<int>(one)", grammar, "source_file"), results::add);
        service.parse(new TreeSitterSyntaxSnapshot(java.nio.file.Path.of("two.cpp"), 1,
                "static_cast<long>(two)", grammar, "source_file"), results::add);
        workers.runAll();

        assertEquals(2, results.size());
    }

    private static final class ControlledWorkers implements SwimPluginWorkers {
        private final List<Runnable> _tasks = new ArrayList<>();
        @Override public Thread start(Runnable task) { _tasks.add(task); return new Thread(task); }
        @Override public boolean isClosed() { return false; }
        @Override public void close() { }
        private void runAll() { for (Runnable task : _tasks) task.run(); }
    }
}
