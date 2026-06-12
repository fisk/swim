package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.EventThread;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

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
    void runnableEventExecutesOnVirtualThread() throws Exception {
        var thread = new EventThread();
        var eventRan = new CountDownLatch(1);
        var virtualThread = new AtomicReference<Boolean>();
        thread.start();

        thread.enqueue(new RunnableEvent(() -> {
            virtualThread.set(Thread.currentThread().isVirtual());
            eventRan.countDown();
        }));

        assertTrue(eventRan.await(2, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, virtualThread.get());

        thread.shutdown();
        thread.join(2000);
    }

    @Test
    void postEventHookErrorDoesNotStopEventThread() throws Exception {
        var thread = new EventThread();
        var firstEventRan = new CountDownLatch(1);
        var secondEventRan = new CountDownLatch(1);
        var hookCalls = new AtomicInteger();
        thread.addOnEvent(() -> {
            if (hookCalls.incrementAndGet() == 1) {
                throw new StackOverflowError("render overflow");
            }
        });
        thread.start();

        thread.enqueue(new RunnableEvent(firstEventRan::countDown));
        assertTrue(firstEventRan.await(2, TimeUnit.SECONDS));

        thread.enqueue(new RunnableEvent(secondEventRan::countDown));
        assertTrue(secondEventRan.await(2, TimeUnit.SECONDS));
        assertTrue(thread.isAlive());

        thread.shutdown();
        thread.join(2000);
    }

    @Test
    void enqueueReturnsWhenThreadIsStopped() {
        var thread = new EventThread();
        thread.shutdown();

        long start = System.nanoTime();
        thread.enqueue(new RunnableEvent(() -> {
        }));

        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(elapsedMillis < 200, "enqueue should not wait on a stopped event thread");
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

    @Test
    void ctrlQCancelsRunningRunnableEventAndIgnoresCancelEvent() throws Exception {
        var thread = new EventThread();
        var started = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var afterCancelRan = new CountDownLatch(1);
        var ctrlQCalls = new AtomicInteger();
        thread.getResponder().addEventResponder(new TextEventResponder("<CTRL>-q", ctrlQCalls::incrementAndGet));
        thread.start();

        thread.enqueue(new RunnableEvent(() -> {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                cancelled.countDown();
                Thread.currentThread().interrupt();
            }
        }));

        assertTrue(started.await(2, TimeUnit.SECONDS));

        thread.enqueue(new KeyStrokeEvent(new KeyStroke('q', true, false)));
        thread.enqueue(new RunnableEvent(afterCancelRan::countDown));

        assertTrue(cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(afterCancelRan.await(2, TimeUnit.SECONDS));
        assertEquals(0, ctrlQCalls.get());

        thread.shutdown();
        thread.join(2000);
    }

    @Test
    void ctrlQCancelsWrappedInterruptedFailureWithoutStoppingEventThread() throws Exception {
        var thread = new EventThread();
        var started = new CountDownLatch(1);
        var afterCancelRan = new CountDownLatch(1);
        thread.start();

        thread.enqueue(new RunnableEvent(() -> {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));

        assertTrue(started.await(2, TimeUnit.SECONDS));

        thread.enqueue(new KeyStrokeEvent(new KeyStroke('q', true, false)));
        thread.enqueue(new RunnableEvent(afterCancelRan::countDown));

        assertTrue(afterCancelRan.await(2, TimeUnit.SECONDS));

        thread.shutdown();
        thread.join(2000);
    }

    @Test
    void mouseClearsPendingKeyPrefixAndDoesNotNotifyKeyObservers() throws Exception {
        var thread = new EventThread();
        var matched = new AtomicInteger();
        var observed = new AtomicInteger();
        var completed = new CountDownLatch(1);
        thread.getResponder().addEventResponder(new TextEventResponder("g g", matched::incrementAndGet));
        thread.addKeyStrokeObserver(ignored -> observed.incrementAndGet());
        thread.start();

        thread.enqueue(new KeyStrokeEvent(new KeyStroke('g', false, false)));
        thread.enqueue(new KeyStrokeEvent(new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(0, 0))));
        thread.enqueue(new KeyStrokeEvent(new KeyStroke('g', false, false)));
        thread.enqueue(new RunnableEvent(completed::countDown));

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(0, matched.get());
        assertEquals(2, observed.get());

        thread.shutdown();
        thread.join(2000);
    }
}
