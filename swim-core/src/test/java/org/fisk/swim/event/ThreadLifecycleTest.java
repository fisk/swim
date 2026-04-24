package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.fisk.swim.EventThread;
import org.junit.jupiter.api.Test;

class ThreadLifecycleTest {
    @Test
    void ioThreadRunsAsDaemon() {
        assertTrue(new IOThread(null).isDaemon());
    }

    @Test
    void eventThreadRunsAsDaemon() {
        assertTrue(new EventThread().isDaemon());
    }
}
