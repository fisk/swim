package org.fisk.swim.lsp.shared;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;

public final class AsyncLspRequestQueue implements AutoCloseable {
    private static final long SHUTDOWN_TIMEOUT_MILLIS = 5_000;

    private final Logger _log;
    private final String _threadName;
    private final BooleanSupplier _ready;
    private final Object _lock = new Object();
    private ScheduledExecutorService _executor;
    private Thread _thread;
    private boolean _closed;

    public AsyncLspRequestQueue(Logger log, String threadName, BooleanSupplier ready) {
        _log = Objects.requireNonNull(log, "log");
        _threadName = threadName == null || threadName.isBlank() ? "swim-lsp-requests" : threadName;
        _ready = ready == null ? () -> true : ready;
    }

    public void execute(String description, Runnable action) {
        if (isClosed()) {
            return;
        }
        try {
            executor().execute(() -> run(description, action));
        } catch (RejectedExecutionException e) {
            _log.debug("LSP request queue rejected " + description, e);
        }
    }

    public void schedule(String description, Runnable action, long delayMillis) {
        if (isClosed()) {
            return;
        }
        try {
            executor().schedule(
                    () -> run(description, action),
                    Math.max(0, delayMillis),
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            _log.debug("LSP request queue rejected " + description, e);
        }
    }

    public void shutdown() {
        ScheduledExecutorService executor;
        Thread thread;
        synchronized (_lock) {
            _closed = true;
            executor = _executor;
            thread = _thread;
            if (executor == null) {
                return;
            }
            executor.shutdownNow();
            _executor = null;
            _thread = null;
        }
        if (Thread.currentThread() == thread) {
            return;
        }
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                _log.warn("Timed out waiting for {} to terminate during LSP shutdown", _threadName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            _log.debug("Interrupted while waiting for " + _threadName + " to terminate", e);
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    private ScheduledExecutorService executor() {
        synchronized (_lock) {
            if (_closed) {
                throw new RejectedExecutionException("LSP request queue is closed");
            }
            if (_executor == null || _executor.isShutdown() || _executor.isTerminated()) {
                _executor = Executors.newSingleThreadScheduledExecutor(r -> {
                    var thread = new Thread(r, _threadName);
                    thread.setDaemon(true);
                    synchronized (_lock) {
                        _thread = thread;
                    }
                    return thread;
                });
            }
            return _executor;
        }
    }

    private void run(String description, Runnable action) {
        if (isClosed() || !_ready.getAsBoolean() || action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable e) {
            _log.debug("LSP request failed: " + description, e);
        }
    }

    private boolean isClosed() {
        synchronized (_lock) {
            return _closed;
        }
    }
}
