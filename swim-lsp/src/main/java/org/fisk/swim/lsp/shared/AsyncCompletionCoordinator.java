package org.fisk.swim.lsp.shared;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class AsyncCompletionCoordinator<S extends AsyncCompletionCoordinator.Snapshot, R> {
    public interface Snapshot {
        String uri();

        long generation();
    }

    @FunctionalInterface
    public interface SnapshotFactory<S> {
        S create(long generation);
    }

    private final AsyncLspRequestQueue _requestQueue;
    private final Consumer<Runnable> _applyDispatcher;
    private final Object _lock = new Object();
    private long _generation;

    public AsyncCompletionCoordinator(AsyncLspRequestQueue requestQueue, Consumer<Runnable> applyDispatcher) {
        _requestQueue = Objects.requireNonNull(requestQueue, "requestQueue");
        _applyDispatcher = applyDispatcher == null ? Runnable::run : applyDispatcher;
    }

    public void request(
            String description,
            SnapshotFactory<S> snapshotFactory,
            Consumer<String> flushDocumentChanges,
            Function<S, R> requestCompletion,
            Predicate<S> snapshotStillMatches,
            BiConsumer<S, R> applyResult,
            Runnable cancelResult) {
        Objects.requireNonNull(snapshotFactory, "snapshotFactory");
        Objects.requireNonNull(requestCompletion, "requestCompletion");
        Objects.requireNonNull(applyResult, "applyResult");
        long generation = nextGeneration();
        S snapshot = snapshotFactory.create(generation);
        if (snapshot == null) {
            return;
        }
        _requestQueue.execute(description == null ? "completion" : description, () -> {
            if (!isCurrent(snapshot)) {
                return;
            }
            if (flushDocumentChanges != null) {
                flushDocumentChanges.accept(snapshot.uri());
            }
            R result = requestCompletion.apply(snapshot);
            dispatchApply(snapshot, result, snapshotStillMatches, applyResult, cancelResult);
        });
    }

    public boolean isCurrent(S snapshot) {
        return snapshot != null && isCurrent(snapshot.generation());
    }

    public boolean isCurrent(long generation) {
        synchronized (_lock) {
            return _generation == generation;
        }
    }

    public void cancelPending() {
        nextGeneration();
    }

    private long nextGeneration() {
        synchronized (_lock) {
            return ++_generation;
        }
    }

    private void dispatchApply(
            S snapshot,
            R result,
            Predicate<S> snapshotStillMatches,
            BiConsumer<S, R> applyResult,
            Runnable cancelResult) {
        _applyDispatcher.accept(() -> {
            if (!isCurrent(snapshot)) {
                return;
            }
            if (snapshotStillMatches != null && !snapshotStillMatches.test(snapshot)) {
                return;
            }
            if (result == null) {
                if (cancelResult != null) {
                    cancelResult.run();
                }
                return;
            }
            applyResult.accept(snapshot, result);
        });
    }
}
