package org.fisk.swim.session;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SwimServerSessions {
    public static final String MAGIC = "SWIM_SESSION_2";
    public static final String ENV_SOCKET = "SWIM_SERVER_SOCKET";
    public static final String ENV_SESSION = "SWIM_SERVER_SESSION";
    public static final String PROPERTY_SOCKET = "swim.server.socket";
    public static final String PROPERTY_SESSION = "swim.server.session";

    private SwimServerSessions() {
    }

    public static boolean isAvailable() {
        return socketPath().isPresent();
    }

    public static String currentSessionName() {
        String property = System.getProperty(PROPERTY_SESSION);
        if (property != null && !property.isBlank()) {
            return normalizeName(property);
        }
        String environment = System.getenv(ENV_SESSION);
        if (environment != null && !environment.isBlank()) {
            return normalizeName(environment);
        }
        return "default";
    }

    public static List<SwimServerSession> list() throws IOException {
        return list(requireSocketPath(), currentSessionName());
    }

    public static List<SwimServerSession> list(Path socketPath, String currentSession) throws IOException {
        try (SocketChannel channel = connect(socketPath)) {
            var output = new DataOutputStream(Channels.newOutputStream(channel));
            output.writeUTF(MAGIC);
            output.writeUTF("list");
            output.writeUTF(normalizeName(currentSession));
            output.flush();

            var input = new DataInputStream(Channels.newInputStream(channel));
            readOk(input);
            int count = input.readInt();
            var sessions = new ArrayList<SwimServerSession>();
            for (int i = 0; i < count; i++) {
                String name = input.readUTF();
                boolean current = input.readBoolean();
                boolean attached = input.readBoolean();
                boolean running = input.readBoolean();
                long pid = input.readLong();
                int argCount = input.readInt();
                var args = new ArrayList<String>();
                for (int arg = 0; arg < argCount; arg++) {
                    args.add(input.readUTF());
                }
                sessions.add(new SwimServerSession(name, current, attached, running, pid, args));
            }
            return List.copyOf(sessions);
        }
    }

    public static void switchTo(String targetSession) throws IOException {
        switchTo(requireSocketPath(), currentSessionName(), targetSession);
    }

    public static void switchTo(Path socketPath, String sourceSession, String targetSession) throws IOException {
        String target = normalizeName(targetSession);
        if (target.isBlank()) {
            throw new IOException("Session name is required");
        }
        try (SocketChannel channel = connect(socketPath)) {
            var output = new DataOutputStream(Channels.newOutputStream(channel));
            output.writeUTF(MAGIC);
            output.writeUTF("switch");
            output.writeUTF(normalizeName(sourceSession));
            output.writeUTF(target);
            output.flush();

            readOk(new DataInputStream(Channels.newInputStream(channel)));
        }
    }

    public static boolean ping(Path socketPath) {
        try (SocketChannel channel = connect(socketPath)) {
            var output = new DataOutputStream(Channels.newOutputStream(channel));
            output.writeUTF(MAGIC);
            output.writeUTF("ping");
            output.flush();
            readOk(new DataInputStream(Channels.newInputStream(channel)));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return "default";
        }
        return name.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Optional<Path> socketPath() {
        String property = System.getProperty(PROPERTY_SOCKET);
        if (property != null && !property.isBlank()) {
            return Optional.of(Path.of(property));
        }
        String environment = System.getenv(ENV_SOCKET);
        if (environment != null && !environment.isBlank()) {
            return Optional.of(Path.of(environment));
        }
        return Optional.empty();
    }

    private static Path requireSocketPath() throws IOException {
        return socketPath().orElseThrow(() -> new IOException("No SWIM session server is attached"));
    }

    private static SocketChannel connect(Path socketPath) throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(socketPath));
        return channel;
    }

    private static void readOk(DataInputStream input) throws IOException {
        String status = input.readUTF();
        if ("OK".equals(status)) {
            return;
        }
        if ("ERR".equals(status)) {
            throw new IOException(input.readUTF());
        }
        throw new IOException("Unexpected SWIM session server response: " + status);
    }
}
