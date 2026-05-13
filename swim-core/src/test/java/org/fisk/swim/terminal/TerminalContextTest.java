package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void parseSttySizeParsesRowsAndColumns() {
        assertEquals(80, TerminalContext.parseSttySize("24 80\n").getColumns());
        assertEquals(24, TerminalContext.parseSttySize("24 80\n").getRows());
    }

    @Test
    void parseSttySizeRejectsInvalidOutput() {
        assertNull(TerminalContext.parseSttySize(null));
        assertNull(TerminalContext.parseSttySize(""));
        assertNull(TerminalContext.parseSttySize("80"));
        assertNull(TerminalContext.parseSttySize("rows cols"));
        assertNull(TerminalContext.parseSttySize("0 80"));
    }

    @Test
    void parsePositiveIntRejectsMissingZeroAndInvalidValues() {
        assertNull(TerminalContext.parsePositiveInt(null));
        assertNull(TerminalContext.parsePositiveInt(""));
        assertNull(TerminalContext.parsePositiveInt("0"));
        assertNull(TerminalContext.parsePositiveInt("-1"));
        assertNull(TerminalContext.parsePositiveInt("cols"));
        assertEquals(80, TerminalContext.parsePositiveInt("80\n"));
    }
}
