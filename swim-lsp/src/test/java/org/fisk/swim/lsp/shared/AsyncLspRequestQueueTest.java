package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AsyncLspRequestQueueTest {
    @Test
    void shutdownInterruptsAndWaitsForWorkerTermination() throws Exception {
        var queue = new AsyncLspRequestQueue(
                LoggerFactory.getLogger(AsyncLspRequestQueueTest.class),
                "swim-lsp-test-requests",
                () -> true);
        var started = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();

        queue.execute("blocking request", () -> {
            started.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        });

        assertTrue(started.await(5, TimeUnit.SECONDS));

        queue.shutdown();

        assertTrue(stopped.await(100, TimeUnit.MILLISECONDS));
        assertTrue(interrupted.get());
    }

    @Test
    void shutdownRejectsFutureWork() throws Exception {
        var queue = new AsyncLspRequestQueue(
                LoggerFactory.getLogger(AsyncLspRequestQueueTest.class),
                "swim-lsp-test-requests",
                () -> true);
        var ran = new AtomicBoolean();

        queue.shutdown();
        queue.execute("after shutdown", () -> ran.set(true));
        Thread.sleep(50);

        assertFalse(ran.get());
    }
}
