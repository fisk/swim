package org.fisk.swim.treesitter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.api.SwimPluginWorkers;
import org.junit.jupiter.api.Test;

class TreeSitterGrammarAnalysisServiceTest {
    @Test
    void publishesOnlyTheNewestImmutableSnapshot() {
        var workers = new ControlledWorkers();
        var results = new ArrayList<TreeSitterGrammarAnalysisService.Result>();
        var service = new TreeSitterGrammarAnalysisService(workers);

        service.inspect(snapshot(1, "older"), results::add);
        service.inspect(snapshot(2, "newer"), results::add);
        workers.runAll();

        assertEquals(1, results.size());
        assertEquals(2, results.getFirst().snapshot().version());
        assertEquals("newer", results.getFirst().inspection().name());
    }

    private static TreeSitterGrammarSnapshot snapshot(long version, String name) {
        return new TreeSitterGrammarSnapshot(Path.of(name + ".json"), version,
                "{\"name\":\"" + name + "\",\"rules\":{}}");
    }

    private static final class ControlledWorkers implements SwimPluginWorkers {
        private final List<Runnable> _tasks = new ArrayList<>();

        @Override
        public Thread start(Runnable task) {
            _tasks.add(task);
            return new Thread(task);
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }

        void runAll() {
            for (Runnable task : _tasks) {
                task.run();
            }
        }
    }
}
