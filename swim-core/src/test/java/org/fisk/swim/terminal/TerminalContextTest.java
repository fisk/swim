package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.fisk.swim.session.SwimServerSessions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyType;
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
    void serverManagedTerminalCreatesScreenFromProcessStreamsWithoutTty(@TempDir Path tempDir) throws Exception {
        Path socket = tempDir.resolve("server.sock");
        InputStream previousIn = System.in;
        PrintStream previousOut = System.out;
        String previousSocket = System.getProperty(SwimServerSessions.PROPERTY_SOCKET);
        String previousSession = System.getProperty(SwimServerSessions.PROPERTY_SESSION);
        try (var server = FakeSizeServer.start(socket, 30, 100)) {
            System.setProperty(SwimServerSessions.PROPERTY_SOCKET, socket.toString());
            System.setProperty(SwimServerSessions.PROPERTY_SESSION, "default");
            System.setIn(new ByteArrayInputStream(new byte[0]));
            System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

            TerminalContext context = TerminalContext.getInstance();

            assertEquals(100, context.getTerminalSize().getColumns());
            assertEquals(30, context.getTerminalSize().getRows());
            assertTrue(server.requests().get() >= 1);
        } finally {
            TerminalContext.shutdownInstance();
            System.setIn(previousIn);
            System.setOut(previousOut);
            restoreProperty(SwimServerSessions.PROPERTY_SOCKET, previousSocket);
            restoreProperty(SwimServerSessions.PROPERTY_SESSION, previousSession);
        }
    }

    @Test
    void serverStreamTerminalDecodesCarriageReturnAsEnter() throws Exception {
        assertServerStreamDecodesCommandEnter("\r");
    }

    @Test
    void serverStreamTerminalDecodesLineFeedAsEnter() throws Exception {
        assertServerStreamDecodesCommandEnter("\n");
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
        Terminal wrapped = TerminalContext.wrapTerminal(delegate, () -> new TerminalSize(80, 24), writes::add);

        wrapped.enterPrivateMode();
        wrapped.exitPrivateMode();

        assertEquals(java.util.Arrays.asList(MouseCaptureMode.CLICK_RELEASE_DRAG, null), modes);
        assertEquals(java.util.Arrays.asList("\u001b[?2004h", "\u001b[?1006h", "\u001b[?1006l", "\u001b[?2004l"),
                writes);
    }

    @Test
    void cursorShapeWritesDecscusrOnlyWhenShapeChanges() {
        var installed = TerminalContextTestSupport.install(80, 24);

        installed.context().setCursorShape(TerminalCursorShape.BLOCK);
        installed.context().setCursorShape(TerminalCursorShape.BAR);
        installed.context().setCursorShape(TerminalCursorShape.BAR);
        installed.context().setCursorShape(TerminalCursorShape.UNDERLINE);

        assertEquals(java.util.Arrays.asList("\u001b[6 q", "\u001b[4 q"), installed.terminalWrites());
    }

    @Test
    void shutdownRestoresBlockCursorShape() {
        var installed = TerminalContextTestSupport.install(80, 24);

        installed.context().setCursorShape(TerminalCursorShape.BAR);
        TerminalContext.shutdownInstance();

        assertEquals(java.util.Arrays.asList("\u001b[6 q", "\u001b[2 q"), installed.terminalWrites());
    }

    private static void assertServerStreamDecodesCommandEnter(String lineEnding) throws Exception {
        Terminal terminal = TerminalContext.createServerStreamTerminal(
                new ByteArrayInputStream((":w" + lineEnding).getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(),
                () -> new TerminalSize(80, 24));
        try {
            assertEquals(':', terminal.readInput().getCharacter());
            assertEquals('w', terminal.readInput().getCharacter());
            assertEquals(KeyType.Enter, terminal.readInput().getKeyType());
        } finally {
            terminal.close();
        }
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

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private record FakeSizeServer(ServerSocketChannel server, Thread thread, AtomicInteger requests)
            implements AutoCloseable {
        static FakeSizeServer start(Path socket, int rows, int columns) throws IOException {
            ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socket));
            AtomicInteger requests = new AtomicInteger();
            Thread thread = Thread.ofVirtual().start(() -> {
                while (server.isOpen()) {
                    try (var channel = server.accept()) {
                        var input = new DataInputStream(Channels.newInputStream(channel));
                        var output = new DataOutputStream(Channels.newOutputStream(channel));
                        input.readUTF();
                        String request = input.readUTF();
                        if ("size".equals(request)) {
                            input.readUTF();
                            requests.incrementAndGet();
                            output.writeUTF("OK");
                            output.writeBoolean(true);
                            output.writeInt(rows);
                            output.writeInt(columns);
                        } else if ("ping".equals(request)) {
                            output.writeUTF("OK");
                        } else {
                            output.writeUTF("ERR");
                            output.writeUTF("unexpected request: " + request);
                        }
                        output.flush();
                    } catch (IOException e) {
                        if (server.isOpen()) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
            return new FakeSizeServer(server, thread, requests);
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(1000);
        }
    }
}
