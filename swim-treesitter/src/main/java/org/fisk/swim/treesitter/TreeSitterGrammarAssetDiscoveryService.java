package org.fisk.swim.treesitter;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.fisk.swim.api.SwimPluginWorkers;

/** Worker-owned discovery with newest-request-wins publication. */
public final class TreeSitterGrammarAssetDiscoveryService implements AutoCloseable {
    public record Result(Path root, List<TreeSitterGrammarAssets> assets, Throwable failure) {
        public boolean succeeded() { return failure == null; }
    }

    private final SwimPluginWorkers _workers;
    private final AtomicLong _latestRequest = new AtomicLong();
    private final AtomicBoolean _closed = new AtomicBoolean();

    public TreeSitterGrammarAssetDiscoveryService(SwimPluginWorkers workers) {
        _workers = workers == null ? SwimPluginWorkers.unmanaged() : workers;
    }

    public long discover(Path root, Consumer<Result> consumer) {
        if (_closed.get() || _workers.isClosed()) throw new IllegalStateException("Tree-sitter asset discovery is closed");
        long request = _latestRequest.incrementAndGet();
        Path snapshotRoot = root == null ? null : root.toAbsolutePath().normalize();
        _workers.start(() -> {
            Result result;
            try {
                result = new Result(snapshotRoot, TreeSitterGrammarAssetDiscovery.discover(snapshotRoot), null);
            } catch (Throwable failure) {
                result = new Result(snapshotRoot, List.of(), failure);
            }
            if (!_closed.get() && request == _latestRequest.get()) consumer.accept(result);
        });
        return request;
    }

    @Override
    public void close() {
        _closed.set(true);
        _latestRequest.incrementAndGet();
    }
}
