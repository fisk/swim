package org.fisk.swim.treesitter;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.fisk.swim.api.SwimPluginWorkers;

/** Concurrent syntax parsing boundary for future buffer-to-snapshot integration. */
public final class TreeSitterSyntaxAnalysisService implements AutoCloseable {
    public record Result(TreeSitterSyntaxSnapshot snapshot, TreeSitterSyntaxTree tree, Throwable failure) {
        public boolean succeeded() { return failure == null && tree != null && tree.succeeded(); }
    }

    private final SwimPluginWorkers _workers;
    private final TreeSitterSubsetRuntime _runtime;
    private final AtomicLong _requestSequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> _latestRequestByDocument = new ConcurrentHashMap<>();
    private final AtomicBoolean _closed = new AtomicBoolean();

    public TreeSitterSyntaxAnalysisService(SwimPluginWorkers workers) {
        this(workers, new TreeSitterSubsetRuntime());
    }

    TreeSitterSyntaxAnalysisService(SwimPluginWorkers workers, TreeSitterSubsetRuntime runtime) {
        _workers = workers == null ? SwimPluginWorkers.unmanaged() : workers;
        _runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public long parse(TreeSitterSyntaxSnapshot snapshot, Consumer<Result> consumer) {
        return parse(documentKey(snapshot), snapshot, consumer);
    }

    /**
     * Schedules a snapshot under a stable document key. Callers with untitled buffers should use
     * their buffer URI rather than relying on a filesystem path.
     */
    public long parse(String documentKey, TreeSitterSyntaxSnapshot snapshot, Consumer<Result> consumer) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(consumer, "consumer");
        if (documentKey == null || documentKey.isBlank()) throw new IllegalArgumentException("documentKey is required");
        if (_closed.get() || _workers.isClosed()) throw new IllegalStateException("Tree-sitter syntax analysis is closed");
        long request = _requestSequence.incrementAndGet();
        _latestRequestByDocument.put(documentKey, request);
        _workers.start(() -> {
            Result result;
            try {
                result = new Result(snapshot, _runtime.parse(snapshot), null);
            } catch (Throwable failure) {
                result = new Result(snapshot, null, failure);
            }
            if (!_closed.get() && Long.valueOf(request).equals(_latestRequestByDocument.get(documentKey))) consumer.accept(result);
        });
        return request;
    }

    @Override
    public void close() {
        _closed.set(true);
        _requestSequence.incrementAndGet();
        _latestRequestByDocument.clear();
    }

    private static String documentKey(TreeSitterSyntaxSnapshot snapshot) {
        return snapshot.path() == null ? "untitled:" + System.identityHashCode(snapshot) : snapshot.path().toString();
    }
}
