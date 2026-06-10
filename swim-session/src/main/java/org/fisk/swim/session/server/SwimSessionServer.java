package org.fisk.swim.session.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.session.SwimServerSession;
import org.fisk.swim.session.SwimServerSessions;

final class SwimSessionServer {
    private static final byte CTRL_L = 12;

    private final Path _socketPath;
    private final Path _workingDirectory;
    private final ConcurrentHashMap<String, ManagedSession> _sessions = new ConcurrentHashMap<>();

    private SwimSessionServer(Path socketPath, Path workingDirectory) {
        _socketPath = socketPath;
        _workingDirectory = workingDirectory;
    }

    static void run(Path socketPath, Path workingDirectory) {
        try {
            SwimNative.setsid();
        } catch (RuntimeException e) {
        }
        new SwimSessionServer(socketPath, workingDirectory).acceptLoop();
    }

    private void acceptLoop() {
        try {
            Files.createDirectories(_socketPath.getParent());
            try {
                Files.setPosixFilePermissions(_socketPath.getParent(), EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException e) {
            }
            Files.deleteIfExists(_socketPath);
            ProtocolFamily family = StandardProtocolFamily.UNIX;
            try (ServerSocketChannel server = ServerSocketChannel.open(family)) {
                server.bind(UnixDomainSocketAddress.of(_socketPath));
                while (true) {
                    SocketChannel channel = server.accept();
                    Thread.startVirtualThread(() -> handleConnection(channel));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to run SWIM session server at " + _socketPath, e);
        }
    }

    private void handleConnection(SocketChannel channel) {
        boolean keepOpen = false;
        try {
            InputStream input = Channels.newInputStream(channel);
            OutputStream output = Channels.newOutputStream(channel);
            var dataInput = new DataInputStream(input);
            var dataOutput = new DataOutputStream(output);
            String magic = dataInput.readUTF();
            if (!SwimServerSessions.MAGIC.equals(magic)) {
                throw new IOException("Invalid SWIM session client");
            }
            String requestType = dataInput.readUTF();
            switch (requestType) {
            case "ping" -> {
                dataOutput.writeUTF("OK");
                dataOutput.flush();
            }
            case "attach" -> {
                handleAttach(channel, input, dataInput, dataOutput);
                keepOpen = true;
            }
            case "list" -> handleList(dataInput, dataOutput);
            case "switch" -> handleSwitch(dataInput, dataOutput);
            case "kill" -> handleKill(dataInput, dataOutput);
            default -> writeError(dataOutput, "Unknown SWIM session request: " + requestType);
            }
        } catch (IOException | RuntimeException e) {
        } finally {
            if (!keepOpen) {
                closeQuietly(channel);
            }
        }
    }

    private void handleAttach(SocketChannel channel, InputStream input, DataInputStream dataInput,
            DataOutputStream dataOutput) throws IOException {
        ClientRequest request = readAttachRequest(dataInput);
        dataOutput.writeUTF("OK");
        dataOutput.flush();
        var client = new ClientConnection(channel, input, Channels.newOutputStream(channel), request);
        session(request.session()).attach(client, request);
        client.start();
    }

    private void handleList(DataInputStream input, DataOutputStream output) throws IOException {
        String currentSession = SwimServerSessions.normalizeName(input.readUTF());
        var sessions = _sessions.values().stream()
                .map(session -> session.snapshot(currentSession))
                .sorted(Comparator.comparing(SwimServerSession::name))
                .toList();
        output.writeUTF("OK");
        output.writeInt(sessions.size());
        for (var session : sessions) {
            output.writeUTF(session.name());
            output.writeBoolean(session.current());
            output.writeBoolean(session.attached());
            output.writeBoolean(session.running());
            output.writeLong(session.pid());
            output.writeInt(session.launchArgs().size());
            for (String arg : session.launchArgs()) {
                output.writeUTF(arg);
            }
        }
        output.flush();
    }

    private void handleSwitch(DataInputStream input, DataOutputStream output) throws IOException {
        String sourceName = SwimServerSessions.normalizeName(input.readUTF());
        String targetName = SwimServerSessions.normalizeName(input.readUTF());
        if (targetName.isBlank()) {
            writeError(output, "Session name is required");
            return;
        }
        if (sourceName.equals(targetName)) {
            output.writeUTF("OK");
            output.flush();
            return;
        }
        ManagedSession source = _sessions.get(sourceName);
        if (source == null) {
            writeError(output, "No such source session: " + sourceName);
            return;
        }
        ClientConnection client = source.currentClient();
        if (client == null) {
            writeError(output, "No client is attached to " + sourceName);
            return;
        }
        ManagedSession target = session(targetName);
        ClientRequest sourceRequest = client.request();
        ClientRequest targetRequest = sourceRequest.withSession(targetName).withoutLaunchArgs();
        source.detach(client);
        try {
            target.attach(client, targetRequest);
        } catch (IOException | RuntimeException e) {
            target.detach(client);
            try {
                source.attach(client, sourceRequest);
            } catch (IOException | RuntimeException ignored) {
            }
            writeError(output, "Unable to switch to " + targetName + ": " + e.getMessage());
            return;
        }
        output.writeUTF("OK");
        output.flush();
    }

    private void handleKill(DataInputStream input, DataOutputStream output) throws IOException {
        String targetName = SwimServerSessions.normalizeName(input.readUTF());
        ManagedSession target = _sessions.get(targetName);
        if (target == null) {
            writeError(output, "No such SWIM session: " + targetName);
            return;
        }
        KillResult result = target.kill();
        if (result.removable()) {
            _sessions.remove(targetName, target);
        }
        output.writeUTF("OK");
        output.writeUTF(result.message());
        output.flush();
    }

    private ManagedSession session(String name) {
        String normalized = SwimServerSessions.normalizeName(name);
        return _sessions.computeIfAbsent(normalized,
                ignored -> new ManagedSession(normalized, _workingDirectory, _socketPath));
    }

    private static ClientRequest readAttachRequest(DataInputStream input) throws IOException {
        String session = input.readUTF();
        int rows = input.readInt();
        int columns = input.readInt();
        List<String> launchArgs = readStringList(input);
        List<String> appCommand = readStringList(input);
        return new ClientRequest(SwimServerSessions.normalizeName(session), launchArgs, appCommand, rows, columns);
    }

    private static List<String> readStringList(DataInputStream input) throws IOException {
        int count = input.readInt();
        var values = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            values.add(input.readUTF());
        }
        return List.copyOf(values);
    }

    private static void writeError(DataOutputStream output, String message) throws IOException {
        output.writeUTF("ERR");
        output.writeUTF(message == null || message.isBlank() ? "SWIM session request failed" : message);
        output.flush();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
        }
    }

    record ClientRequest(String session, List<String> args, List<String> appCommand, int rows, int columns) {
        ClientRequest {
            session = SwimServerSessions.normalizeName(session);
            args = List.copyOf(args == null ? List.of() : args);
            appCommand = List.copyOf(appCommand == null ? List.of() : appCommand);
            rows = Math.max(1, rows);
            columns = Math.max(1, columns);
            if (appCommand.isEmpty()) {
                throw new IllegalArgumentException("App command is required");
            }
        }

        ClientRequest withSession(String session) {
            return new ClientRequest(session, args, appCommand, rows, columns);
        }

        ClientRequest withoutLaunchArgs() {
            return new ClientRequest(session, List.of(), appCommand, rows, columns);
        }
    }

    private static final class ClientConnection implements AutoCloseable {
        private final SocketChannel _channel;
        private final InputStream _input;
        private final OutputStream _output;
        private final AtomicReference<ManagedSession> _session = new AtomicReference<>();
        private volatile ClientRequest _request;

        private ClientConnection(SocketChannel channel, InputStream input, OutputStream output, ClientRequest request) {
            _channel = channel;
            _input = input;
            _output = output;
            _request = request;
        }

        ClientRequest request() {
            return _request;
        }

        void attachTo(ManagedSession session, ClientRequest request) {
            _request = request;
            _session.set(session);
        }

        void start() {
            Thread.startVirtualThread(this::copyInputToCurrentSession);
        }

        boolean writeOutput(byte[] buffer, int offset, int length) {
            try {
                _output.write(buffer, offset, length);
                _output.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        private void copyInputToCurrentSession() {
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = _input.read(buffer)) != -1) {
                    ManagedSession session = _session.get();
                    if (session != null) {
                        session.writeInput(buffer, 0, read);
                    }
                }
            } catch (IOException e) {
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            ManagedSession session = _session.getAndSet(null);
            if (session != null) {
                session.detach(this);
            }
            closeQuietly(_channel);
        }
    }

    private record KillResult(String message, boolean removable) {
    }

    private static final class ManagedSession {
        private static final Duration GRACEFUL_KILL_TIMEOUT = Duration.ofSeconds(2);
        private static final Duration FORCED_KILL_TIMEOUT = Duration.ofSeconds(2);

        private final String _name;
        private final Path _workingDirectory;
        private final Path _socketPath;
        private final Object _lock = new Object();
        private final AtomicReference<ClientConnection> _client = new AtomicReference<>();
        private volatile Process _process;
        private volatile OutputStream _processInput;
        private volatile List<String> _launchArgs = List.of();

        private ManagedSession(String name, Path workingDirectory, Path socketPath) {
            _name = name;
            _workingDirectory = workingDirectory;
            _socketPath = socketPath;
        }

        void attach(ClientConnection client, ClientRequest request) throws IOException {
            synchronized (_lock) {
                ClientConnection previousClient = _client.getAndSet(client);
                if (previousClient != null && previousClient != client) {
                    previousClient.close();
                }
                client.attachTo(this, request);
                ensureStarted(request);
                requestRedraw();
            }
        }

        void detach(ClientConnection client) {
            _client.compareAndSet(client, null);
        }

        ClientConnection currentClient() {
            return _client.get();
        }

        SwimServerSession snapshot(String currentSession) {
            Process process = _process;
            boolean running = process != null && process.isAlive();
            long pid = running ? process.pid() : -1L;
            return new SwimServerSession(_name, _name.equals(currentSession), _client.get() != null, running, pid,
                    _launchArgs);
        }

        void writeInput(byte[] buffer, int offset, int length) {
            OutputStream processInput = _processInput;
            if (processInput == null) {
                return;
            }
            try {
                processInput.write(buffer, offset, length);
                processInput.flush();
            } catch (IOException e) {
            }
        }

        KillResult kill() {
            ClientConnection client;
            Process process;
            synchronized (_lock) {
                client = _client.getAndSet(null);
                process = _process;
                _process = null;
                closeQuietly(_processInput);
                _processInput = null;
                _launchArgs = List.of();
            }
            if (client != null) {
                client.close();
            }
            if (process == null) {
                return new KillResult("SWIM session " + _name + " was already stopped.", true);
            }
            boolean forced = terminateProcessTree(process);
            if (process.isAlive()) {
                return new KillResult("Unable to kill SWIM session " + _name + " completely; pid "
                        + process.pid() + " is still alive.", false);
            }
            String suffix = forced ? " after forcing remaining processes." : ".";
            return new KillResult("Killed SWIM session " + _name + " (pid " + process.pid() + ")" + suffix, true);
        }

        private void ensureStarted(ClientRequest request) throws IOException {
            if (_process != null && _process.isAlive()) {
                return;
            }
            _launchArgs = request.args();
            List<String> command = ptyCommand(request);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(_workingDirectory.toFile())
                    .redirectErrorStream(true);
            builder.environment().put(SwimServerSessions.ENV_SOCKET, _socketPath.toString());
            builder.environment().put(SwimServerSessions.ENV_SESSION, _name);
            Process process = builder.start();
            _process = process;
            _processInput = process.getOutputStream();
            Thread.startVirtualThread(() -> copyProcessToCurrentClient(process));
        }

        private List<String> ptyCommand(ClientRequest request) {
            Path script = Path.of("/usr/bin/script");
            if (!Files.isExecutable(script)) {
                throw new IllegalStateException("/usr/bin/script is required for SWIM server sessions");
            }
            String resize = "stty rows " + request.rows()
                    + " cols " + request.columns() + "; ";
            String command = resize + "exec " + shellCommand(request.appCommand());
            return List.of(script.toString(), "-q", "/dev/null", "/bin/sh", "-lc", command);
        }

        private void requestRedraw() {
            OutputStream processInput = _processInput;
            if (processInput == null) {
                return;
            }
            try {
                processInput.write(CTRL_L);
                processInput.flush();
            } catch (IOException e) {
            }
        }

        private void copyProcessToCurrentClient(Process process) {
            try (InputStream processOutput = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = processOutput.read(buffer)) != -1) {
                    ClientConnection client = _client.get();
                    if (client == null) {
                        continue;
                    }
                    if (!client.writeOutput(buffer, 0, read)) {
                        _client.compareAndSet(client, null);
                    }
                }
            } catch (IOException e) {
            } finally {
                ClientConnection client = null;
                synchronized (_lock) {
                    if (_process == process) {
                        _process = null;
                        closeQuietly(_processInput);
                        _processInput = null;
                        _launchArgs = List.of();
                        client = _client.getAndSet(null);
                    }
                }
                if (client != null) {
                    client.close();
                }
            }
        }

        private static boolean terminateProcessTree(Process process) {
            ProcessHandle root = process.toHandle();
            List<ProcessHandle> descendants = root.descendants().toList();
            var processTree = new ArrayList<>(descendants);
            processTree.add(root);
            descendants.forEach(ProcessHandle::destroy);
            root.destroy();
            if (waitForExit(processTree, GRACEFUL_KILL_TIMEOUT)) {
                return false;
            }
            processTree.forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            waitForExit(processTree, FORCED_KILL_TIMEOUT);
            return true;
        }

        private static boolean waitForExit(List<ProcessHandle> handles, Duration timeout) {
            long deadline = System.nanoTime() + Math.max(0L, timeout.toNanos());
            boolean allExited = true;
            for (ProcessHandle handle : handles) {
                if (!waitForExit(handle, deadline, timeout.isZero())) {
                    allExited = false;
                }
            }
            return allExited;
        }

        private static boolean waitForExit(ProcessHandle handle, long deadline, boolean zeroTimeout) {
            while (handle.isAlive()) {
                if (zeroTimeout || System.nanoTime() >= deadline) {
                    return false;
                }
                try {
                    Thread.sleep(Math.min(25L, Math.max(1L, Duration.ofNanos(deadline - System.nanoTime()).toMillis())));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        private static String shellCommand(List<String> command) {
            return command.stream()
                    .map(ManagedSession::shellQuote)
                    .collect(java.util.stream.Collectors.joining(" "));
        }

        private static String shellQuote(String value) {
            return "'" + value.replace("'", "'\"'\"'") + "'";
        }
    }
}
