package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TerminalContextTest {
    @AfterEach
    void tearDown() {
        TerminalContext.shutdownInstance();
    }

    @Test
    void getInstanceReturnsInstalledInstanceAndShutdownStopsScreenOnce() {
        var installed = TerminalContextTestSupport.install(80, 24);

        assertSame(installed.context(), TerminalContext.getInstance());
        assertSame(installed.context().getScreen(), TerminalContext.getInstance().getScreen());
        assertSame(installed.context().getTerminal(), TerminalContext.getInstance().getTerminal());
        assertSame(installed.context().getGraphics(), TerminalContext.getInstance().getGraphics());

        TerminalContext.shutdownInstance();
        TerminalContext.shutdownInstance();

        assertEquals(1, installed.stopCalls().get());
        assertEquals(1, installed.closeCalls().get());
    }

    @Test
    void shutdownSwallowsIoFailuresFromScreenStop() throws IOException {
        var installed = TerminalContextTestSupport.install(80, 24, new IOException("boom"));

        TerminalContext.shutdownInstance();

        assertEquals(1, installed.stopCalls().get());
        assertEquals(1, installed.closeCalls().get());
    }
}
