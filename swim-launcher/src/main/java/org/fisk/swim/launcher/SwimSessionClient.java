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

import org.fisk.swim.session.SwimServerSessions;

final class SwimSessionClient {
    private static final Duration SERVER_START_TIMEOUT = Duration.ofSeconds(5);

    private final Path _buildRoot;
    private final Path _socketPath;

    SwimSessionClient(Path buildRoot) {
        _buildRoot = buildRoot;
        _socketPath = defaultSocketPath();
    }

    int run(String[] args) {
        try {
            ensureServer();
            try (SocketChannel channel = connect()) {
                sendRequest(channel, args);
                try (SwimTerminalMode ignored = SwimTerminalMode.enterRawMode()) {
                    Thread inputRelay = Thread.ofVirtual().name("swim-client-input").start(() -> {
                        try {
                            copy(System.in, Channels.newOutputStream(channel));
                            channel.shutdownOutput();
                        } catch (IOException e) {
                        }
                    });
                    copy(Channels.newInputStream(channel), System.out);
                    try {
                        inputRelay.join(Duration.ofSeconds(1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("swim: unable to connect to session server: " + e.getMessage());
            return 1;
        }
    }

    private void ensureServer() throws IOException {
        if (canConnect()) {
            return;
        }
        Files.createDirectories(_socketPath.getParent());
        ProcessBuilder builder = new ProcessBuilder(SwimJavaCommand.serverCommand(_socketPath, _buildRoot));
        builder.redirectInput(ProcessBuilder.Redirect.DISCARD);
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

    private void sendRequest(SocketChannel channel, String[] args) throws IOException {
        OutputStream output = Channels.newOutputStream(channel);
        var terminalSize = SwimTerminalMode.currentSize();
        var dataOutput = new DataOutputStream(output);
        dataOutput.writeUTF(SwimServerSessions.MAGIC);
        dataOutput.writeUTF("attach");
        dataOutput.writeUTF(sessionName());
        dataOutput.writeInt(terminalSize.rows());
        dataOutput.writeInt(terminalSize.columns());
        List<String> launchArgs = Arrays.asList(args == null ? new String[0] : args);
        writeStringList(dataOutput, launchArgs);
        writeStringList(dataOutput, SwimJavaCommand.appCommand(launchArgs));
        dataOutput.flush();
        String response = new DataInputStream(Channels.newInputStream(channel)).readUTF();
        if (!"OK".equals(response)) {
            throw new IOException(response);
        }
    }

    private static void writeStringList(DataOutputStream output, List<String> values) throws IOException {
        output.writeInt(values.size());
        for (String value : values) {
            output.writeUTF(value);
        }
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
}
