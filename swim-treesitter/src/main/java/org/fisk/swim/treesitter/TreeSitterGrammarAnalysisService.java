package org.fisk.swim.treesitter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.fisk.swim.api.SwimPluginWorkers;

/**
 * Runs grammar analysis on immutable snapshots. A result is published only if
 * it still belongs to the newest submitted snapshot, so slow work can never
 * overwrite a newer editor state.
 */
public final class TreeSitterGrammarAnalysisService implements AutoCloseable {
    public record Result(TreeSitterGrammarSnapshot snapshot, TreeSitterGrammarInspection inspection, Throwable failure) {
        public boolean succeeded() {
            return failure == null;
        }
    }

    private final SwimPluginWorkers _workers;
    private final AtomicLong _latestRequest = new AtomicLong();
    private final AtomicBoolean _closed = new AtomicBoolean();

    public TreeSitterGrammarAnalysisService(SwimPluginWorkers workers) {
        _workers = workers == null ? SwimPluginWorkers.unmanaged() : workers;
    }

    public long inspect(TreeSitterGrammarSnapshot snapshot, Consumer<Result> consumer) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(consumer, "consumer");
        if (_closed.get() || _workers.isClosed()) {
            throw new IllegalStateException("Tree-sitter grammar analysis is closed");
        }
        long request = _latestRequest.incrementAndGet();
        _workers.start(() -> analyze(request, snapshot, consumer));
        return request;
    }

    private void analyze(long request, TreeSitterGrammarSnapshot snapshot, Consumer<Result> consumer) {
        Result result;
        try {
            result = new Result(snapshot, TreeSitterGrammarInspector.inspect(snapshot), null);
        } catch (Throwable failure) {
            result = new Result(snapshot, null, failure);
        }
        if (!_closed.get() && request == _latestRequest.get()) {
            consumer.accept(result);
        }
    }

    @Override
    public void close() {
        _closed.set(true);
        _latestRequest.incrementAndGet();
    }
}
