package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.Terminal;

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

    @Test
    void wrappedExtendedTerminalEnablesAndDisablesMouseCaptureWithPrivateMode() throws Exception {
        var modes = new ArrayList<MouseCaptureMode>();
        var writes = new ArrayList<String>();
        var delegate = (ExtendedTerminal) Proxy.newProxyInstance(
                ExtendedTerminal.class.getClassLoader(),
                new Class<?>[] { ExtendedTerminal.class },
                (proxy, method, args) -> {
                    if ("setMouseCaptureMode".equals(method.getName())) {
                        modes.add((MouseCaptureMode) args[0]);
                        return null;
                    }
                    if ("putString".equals(method.getName())) {
                        writes.add((String) args[0]);
                        return null;
                    }
                    return defaultValue(proxy, method.getReturnType());
                });
        Terminal wrapped = TerminalContext.wrapTerminal(delegate, () -> new TerminalSize(80, 24));

        wrapped.enterPrivateMode();
        wrapped.exitPrivateMode();

        assertEquals(java.util.Arrays.asList(MouseCaptureMode.CLICK_RELEASE_DRAG, null), modes);
        assertEquals(java.util.Arrays.asList("\u001b[?2004h", "\u001b[?1006h", "\u001b[?1006l", "\u001b[?2004l"),
                writes);
    }

    private static Object defaultValue(Object proxy, Class<?> type) {
        if (type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return false;
        }
        if (type == Integer.TYPE) {
            return 0;
        }
        if (type == Long.TYPE) {
            return 0L;
        }
        if (type == Byte.TYPE) {
            return new byte[0];
        }
        if (type == TerminalSize.class) {
            return new TerminalSize(80, 24);
        }
        if (type == TerminalPosition.class) {
            return new TerminalPosition(0, 0);
        }
        if (type.isInstance(proxy)) {
            return proxy;
        }
        return null;
    }
}
