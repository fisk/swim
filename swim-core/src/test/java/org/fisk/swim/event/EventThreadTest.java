package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
