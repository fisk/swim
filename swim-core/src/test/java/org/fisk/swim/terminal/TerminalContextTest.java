package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TerminalContextTest {
    @AfterEach void tearDown() { TerminalContext.shutdownInstance(); }

    @Test
    void installedContextIsReturnedAndStoppedOnce() {
        var installed = TerminalContextTestSupport.install(80, 24);
        assertSame(installed.context(), TerminalContext.getInstance());
        TerminalContext.shutdownInstance();
        TerminalContext.shutdownInstance();
        assertEquals(1, installed.stopCalls().get());
    }

    @Test
    void parseSttySizeParsesRowsAndColumns() {
        assertEquals(new TerminalDimensions(80, 24), TerminalContext.parseSttySize("24 80\n"));
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
        assertEquals(80, TerminalContext.parsePositiveInt("80\n"));
    }
}
