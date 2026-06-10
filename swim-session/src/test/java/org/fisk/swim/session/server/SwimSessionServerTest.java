package org.fisk.swim.session.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SwimSessionServerTest {
    @TempDir
    Path tempDir;

    @Test
    void appWorkingDirectoryUsesExistingClientWorkingDirectory() throws Exception {
        Path serverDirectory = tempDir.resolve("server");
        Path clientDirectory = tempDir.resolve("client");
        Files.createDirectories(serverDirectory);
        Files.createDirectories(clientDirectory);

        var request = request(clientDirectory);

        assertEquals(clientDirectory.toAbsolutePath().normalize(),
                SwimSessionServer.appWorkingDirectory(serverDirectory, request));
    }

    @Test
    void appWorkingDirectoryFallsBackToServerDirectoryWhenClientDirectoryIsMissing() throws Exception {
        Path serverDirectory = tempDir.resolve("server");
        Path missingClientDirectory = tempDir.resolve("missing-client");
        Files.createDirectories(serverDirectory);

        var request = request(missingClientDirectory);

        assertEquals(serverDirectory, SwimSessionServer.appWorkingDirectory(serverDirectory, request));
    }

    @Test
    void appEnvironmentUsesClientEnvironmentAndOverlaysSessionVariables() throws Exception {
        Path socket = tempDir.resolve("server.sock");
        var request = new SwimSessionServer.ClientRequest("work", tempDir, Map.of(
                "CLIENT_ONLY", "present",
                "SWIM_SERVER_SESSION", "client-value",
                "SWIM_SERVER_SOCKET", "client-socket"), List.of(),
                List.of("java", "-version"), 24, 80);

        Map<String, String> environment = SwimSessionServer.appEnvironment(socket, "review", request);

        assertEquals("present", environment.get("CLIENT_ONLY"));
        assertEquals("review", environment.get("SWIM_SERVER_SESSION"));
        assertEquals(socket.toString(), environment.get("SWIM_SERVER_SOCKET"));
        assertEquals("24", environment.get("SWIM_TTY_ROWS"));
        assertEquals("80", environment.get("SWIM_TTY_COLS"));
    }

    @Test
    void appCommandUsesRequestedCommandDirectly() {
        var request = new SwimSessionServer.ClientRequest("work", tempDir, Map.of(), List.of(),
                List.of("/path with spaces/java", "--module", "swim"), 24, 80);

        assertEquals(List.of("/path with spaces/java", "--module", "swim"), SwimSessionServer.appCommand(request));
    }

    private static SwimSessionServer.ClientRequest request(Path workingDirectory) {
        return new SwimSessionServer.ClientRequest("work", workingDirectory, Map.of("SWIM_TEST", "yes"), List.of(),
                List.of("java", "-version"), 24, 80);
    }
}
