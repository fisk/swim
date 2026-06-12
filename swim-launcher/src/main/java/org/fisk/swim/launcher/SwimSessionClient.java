package org.fisk.swim.launcher;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.fisk.swim.session.SwimServerSessions;

final class SwimSessionClient {
    private static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(5);

    private final Path _buildRoot;
    private final Path _socketPath;

    SwimSessionClient(Path buildRoot) {
        this(buildRoot, defaultSocketPath());
    }

    SwimSessionClient(Path buildRoot, Path socketPath) {
        _buildRoot = buildRoot;
        _socketPath = socketPath;
    }

    int run(String[] args) {
        if (isKillSessionCommand(args)) {
            return killSession(args);
        }
        LaunchRequest request;
        try {
            request = LaunchRequest.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("swim: " + e.getMessage());
            return 1;
        }
        try {
            ensureServer();
            try (SocketChannel channel = connect()) {
                var terminalSize = sendRequest(channel, request);
                var sessionGuard = new AttachedSessionGuard(_socketPath, request.sessionName());
                boolean gracefulExit = false;
                try {
                    try (SwimTerminalMode ignored = SwimTerminalMode.enterRawMode()) {
                        Thread resizeRelay = startResizeRelay(request, terminalSize);
                        Thread inputRelay = Thread.ofVirtual().name("swim-client-input").start(() -> {
                            try {
                                copy(System.in, Channels.newOutputStream(channel));
                                channel.shutdownOutput();
                            } catch (IOException e) {
                            }
                        });
                        try {
                            copy(Channels.newInputStream(channel), System.out);
                            gracefulExit = true;
                        } finally {
                            resizeRelay.interrupt();
                        }
                        try {
                            inputRelay.join(Duration.ofSeconds(1));
                            resizeRelay.join(Duration.ofSeconds(1));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } finally {
                    sessionGuard.close(gracefulExit);
                }
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("swim: unable to connect to session server: " + e.getMessage());
            return 1;
        }
    }

    private int killSession(String[] args) {
        if (args.length > 2) {
            System.err.println("swim: usage: swim --kill-session [name]");
            return 1;
        }
        String target = args.length == 2 ? args[1] : sessionName();
        try {
            System.out.println(SwimServerSessions.kill(_socketPath, target));
            return 0;
        } catch (IOException e) {
            System.err.println("swim: unable to kill session: " + e.getMessage());
            return 1;
        }
    }

    private static boolean isKillSessionCommand(String[] args) {
        return args != null && args.length > 0
                && ("--kill-session".equals(args[0]) || "--swim-kill-session".equals(args[0]));
    }

    private record LaunchRequest(String sessionName, List<String> launchArgs) {
        static LaunchRequest parse(String[] args) {
            if (args != null && args.length > 0 && "--attach".equals(args[0])) {
                if (args.length != 2 || args[1].isBlank()) {
                    throw new IllegalArgumentException("usage: swim --attach <session>");
                }
                return new LaunchRequest(SwimServerSessions.normalizeName(args[1]), List.of());
            }
            return new LaunchRequest(SwimSessionClient.sessionName(), Arrays.asList(args == null ? new String[0] : args));
        }
    }

    private void ensureServer() throws IOException {
        if (canConnect()) {
            return;
        }
        Files.createDirectories(_socketPath.getParent());
        ProcessBuilder builder = new ProcessBuilder(SwimJavaCommand.serverCommand(_socketPath, _buildRoot));
        builder.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        Path logFile = _socketPath.getParent().resolve("server.log");
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        builder.start();

        long deadline = System.nanoTime() + SERVER_START_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (canConnect()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for session server", e);
            }
        }
        throw new IOException("session server did not start; see " + logFile);
    }

    private boolean canConnect() {
        return SwimServerSessions.ping(_socketPath);
    }

    private SocketChannel connect() throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(_socketPath));
        return channel;
    }

    private SwimTerminalMode.TerminalSize sendRequest(SocketChannel channel, LaunchRequest request) throws IOException {
        OutputStream output = Channels.newOutputStream(channel);
        var terminalSize = SwimTerminalMode.currentSize();
        var dataOutput = new DataOutputStream(output);
        dataOutput.writeUTF(SwimServerSessions.MAGIC);
        dataOutput.writeUTF("attach");
        dataOutput.writeUTF(request.sessionName());
        dataOutput.writeUTF(clientWorkingDirectory().toString());
        writeStringMap(dataOutput, clientEnvironment());
        dataOutput.writeInt(terminalSize.rows());
        dataOutput.writeInt(terminalSize.columns());
        writeStringList(dataOutput, request.launchArgs());
        writeStringList(dataOutput, SwimJavaCommand.appCommand(request.launchArgs()));
        dataOutput.flush();
        String response = new DataInputStream(Channels.newInputStream(channel)).readUTF();
        if (!"OK".equals(response)) {
            throw new IOException(response);
        }
        return terminalSize;
    }

    private Thread startResizeRelay(LaunchRequest request, SwimTerminalMode.TerminalSize initialSize) {
        return Thread.ofVirtual().name("swim-client-resize").start(() -> {
            var previous = initialSize;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                var current = SwimTerminalMode.currentSize();
                if (current.equals(previous)) {
                    continue;
                }
                try {
                    SwimServerSessions.resize(_socketPath, request.sessionName(), current.rows(), current.columns());
                    previous = current;
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    private static void writeStringList(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            output.writeUTF(value);
        }
    }

    private static void writeStringMap(DataOutputStream output, Map<String, String> values) throws IOException {
        output.writeInt(values.size());
        for (var entry : values.entrySet()) {
            output.writeUTF(entry.getKey());
            output.writeUTF(entry.getValue());
        }
    }

    private static Path clientWorkingDirectory() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private static Map<String, String> clientEnvironment() {
        return new TreeMap<>(System.getenv());
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            output.flush();
        }
    }

    static Path defaultSocketPath() {
        String property = System.getProperty(SwimServerSessions.PROPERTY_SOCKET);
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String environment = System.getenv(SwimServerSessions.ENV_SOCKET);
        if (environment != null && !environment.isBlank()) {
            return Path.of(environment);
        }
        String user = System.getProperty("user.name", "unknown")
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        return Path.of("/tmp", "swim-" + user, "default.sock");
    }

    static String sessionName() {
        String session = System.getenv("SWIM_SESSION");
        if (session == null || session.isBlank()) {
            return "default";
        }
        return SwimServerSessions.normalizeName(session);
    }

    private static final class AttachedSessionGuard {
        private final Path _socketPath;
        private final String _sessionName;
        private final AtomicBoolean _armed = new AtomicBoolean(true);
        private final Thread _shutdownHook;

        private AttachedSessionGuard(Path socketPath, String sessionName) {
            _socketPath = socketPath;
            _sessionName = SwimServerSessions.normalizeName(sessionName);
            _shutdownHook = new Thread(this::shutdownAbruptly, "swim-client-session-shutdown");
            Runtime.getRuntime().addShutdownHook(_shutdownHook);
        }

        private void close(boolean gracefulExit) {
            if (gracefulExit) {
                disarm();
            } else {
                shutdownAbruptly();
            }
            removeShutdownHook();
            SwimTerminalMode.restoreTerminal();
        }

        private void disarm() {
            _armed.set(false);
        }

        private void shutdownAbruptly() {
            SwimTerminalMode.restoreTerminal();
            if (!_armed.getAndSet(false)) {
                return;
            }
            try {
                SwimServerSessions.kill(_socketPath, _sessionName);
            } catch (IOException | RuntimeException e) {
            }
        }

        private void removeShutdownHook() {
            try {
                Runtime.getRuntime().removeShutdownHook(_shutdownHook);
            } catch (IllegalStateException e) {
            }
        }
    }
}
