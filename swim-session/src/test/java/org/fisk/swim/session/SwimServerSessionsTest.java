package org.fisk.swim.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwimServerSessionsTest {
    @TempDir
    Path tempDir;

    @Test
    void listUsesControlProtocolAndParsesSessions() throws Exception {
        Path socket = tempDir.resolve("sessions.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.writeInt(2);
            writeSession(output, "default", false, false, true, 12, List.of("README.md"));
            writeSession(output, "work", true, true, true, 34, List.of());
            output.flush();
        });

        var sessions = SwimServerSessions.list(socket, "work");
        server.join(1000);

        assertEquals(List.of(SwimServerSessions.MAGIC, "list", "work"), request.get());
        assertEquals(2, sessions.size());
        assertEquals("default", sessions.get(0).name());
        assertEquals(List.of("README.md"), sessions.get(0).launchArgs());
        assertTrue(sessions.get(1).current());
        assertTrue(sessions.get(1).attached());
    }

    @Test
    void switchToUsesControlProtocol() throws Exception {
        Path socket = tempDir.resolve("switch.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.flush();
        });

        SwimServerSessions.switchTo(socket, "default", "review");
        server.join(1000);

        assertEquals(List.of(SwimServerSessions.MAGIC, "switch", "default", "review"), request.get());
    }

    @Test
    void killUsesControlProtocolAndReturnsMessage() throws Exception {
        Path socket = tempDir.resolve("kill.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.writeUTF("Killed SWIM session review.");
            output.flush();
        });

        String message = SwimServerSessions.kill(socket, "review");
        server.join(1000);

        assertEquals(List.of(SwimServerSessions.MAGIC, "kill", "review"), request.get());
        assertEquals("Killed SWIM session review.", message);
    }

    @Test
    void detachUsesControlProtocol() throws Exception {
        Path socket = tempDir.resolve("detach.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.flush();
        });

        SwimServerSessions.detach(socket, "review");
        server.join(1000);

        assertEquals(List.of(SwimServerSessions.MAGIC, "detach", "review"), request.get());
    }

    @Test
    void resizeUsesControlProtocol() throws Exception {
        Path socket = tempDir.resolve("resize.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF(),
                    String.valueOf(input.input().readInt()), String.valueOf(input.input().readInt())));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.flush();
        });

        SwimServerSessions.resize(socket, "review", 31, 111);
        server.join(1000);

        assertEquals(List.of(SwimServerSessions.MAGIC, "resize", "review", "31", "111"), request.get());
    }

    @Test
    void terminalSizeUsesControlProtocol() throws Exception {
        Path socket = tempDir.resolve("size.sock");
        var request = new AtomicReference<List<String>>();
        Thread server = fakeServer(socket, input -> {
            request.set(List.of(input.readUTF(), input.readUTF(), input.readUTF()));
            var output = new DataOutputStream(Channels.newOutputStream(input.channel()));
            output.writeUTF("OK");
            output.writeBoolean(true);
            output.writeInt(42);
            output.writeInt(132);
            output.flush();
        });

        var size = SwimServerSessions.terminalSize(socket, "review");
        server.join(1000);

        assertEquals(List.of(SwimServerSessions.MAGIC, "size", "review"), request.get());
        assertTrue(size.isPresent());
        assertEquals(42, size.get().rows());
        assertEquals(132, size.get().columns());
    }

    private static Thread fakeServer(Path socket, ServerHandler handler) throws Exception {
        var ready = new CountDownLatch(1);
        Thread thread = Thread.ofVirtual().start(() -> {
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socket));
                ready.countDown();
                try (var channel = server.accept()) {
                    handler.handle(new ChannelInput(channel, new DataInputStream(Channels.newInputStream(channel))));
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        return thread;
    }

    private static void writeSession(DataOutputStream output, String name, boolean current, boolean attached,
            boolean running, long pid, List<String> args) throws Exception {
        output.writeUTF(name);
        output.writeBoolean(current);
        output.writeBoolean(attached);
        output.writeBoolean(running);
        output.writeLong(pid);
        output.writeInt(args.size());
        for (String arg : args) {
            output.writeUTF(arg);
        }
    }

    @FunctionalInterface
    private interface ServerHandler {
        void handle(ChannelInput input) throws Exception;
    }

    private record ChannelInput(java.nio.channels.SocketChannel channel, DataInputStream input) {
        String readUTF() throws Exception {
            return input.readUTF();
        }
    }
}
