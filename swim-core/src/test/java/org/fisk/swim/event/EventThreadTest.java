package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.EventThread;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;

class EventThreadTest {
    @Test
    void runnableEventExecutesAndPostEventHookRuns() throws Exception {
        var thread = new EventThread();
        var executed = new AtomicInteger();
        var hookRan = new CountDownLatch(1);
        thread.addOnEvent(hookRan::countDown);
        thread.start();

        thread.enqueue(new RunnableEvent(executed::incrementAndGet));

        assertTrue(hookRan.await(2, TimeUnit.SECONDS));
        assertEquals(1, executed.get());

        thread.shutdown();
        thread.join(2000);
    }

    @Test
    void keyStrokeEventDispatchesResponder() throws Exception {
        var thread = new EventThread();
        var responded = new CountDownLatch(1);
        thread.getResponder().addEventResponder(new TextEventResponder("a", responded::countDown));
        thread.start();

        thread.enqueue(new KeyStrokeEvent(new KeyStroke('a', false, false)));

        assertTrue(responded.await(2, TimeUnit.SECONDS));

        thread.shutdown();
        thread.join(2000);
    }

    @Test
    void shutdownCanClearHooksWhileHooksAreRunning() throws Exception {
        var thread = new EventThread();
        var enteredHook = new CountDownLatch(1);
        var releaseHook = new CountDownLatch(1);
        var uncaught = new AtomicReference<Throwable>();
        thread.setUncaughtExceptionHandler((ignored, throwable) -> uncaught.set(throwable));
        thread.addOnEvent(() -> {
            enteredHook.countDown();
            try {
                releaseHook.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        thread.start();

        thread.enqueue(new RunnableEvent(() -> {
        }));

        assertTrue(enteredHook.await(2, TimeUnit.SECONDS));
        thread.shutdown();
        releaseHook.countDown();
        thread.join(2000);

        assertEquals(null, uncaught.get());
    }

    @Test
    void queuedBurstRunsPostEventHookOnce() throws Exception {
        var thread = new EventThread();
        var executed = new AtomicInteger();
        var hookCount = new AtomicInteger();
        var executedLatch = new CountDownLatch(2);
        var hookLatch = new CountDownLatch(1);
        thread.addOnEvent(() -> {
            hookCount.incrementAndGet();
            hookLatch.countDown();
        });

        thread.enqueue(new RunnableEvent(() -> {
            executed.incrementAndGet();
            executedLatch.countDown();
        }));
        thread.enqueue(new RunnableEvent(() -> {
            executed.incrementAndGet();
            executedLatch.countDown();
        }));

        thread.start();

        assertTrue(executedLatch.await(2, TimeUnit.SECONDS));
        assertTrue(hookLatch.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(2, executed.get());
        assertEquals(1, hookCount.get());

        thread.shutdown();
        thread.join(2000);
    }
}
