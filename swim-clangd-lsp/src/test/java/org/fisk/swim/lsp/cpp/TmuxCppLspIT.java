package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxCppLspIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void installedLauncherBinaryStartsClangdWhenProjectHasRootCompilationDatabase() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-clangd-lsp-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("cpp-root-db");
        Path file = project.resolve("src/main.cpp");
        Files.createDirectories(file.getParent());
        Files.writeString(project.resolve("compile_commands.json"), "[]\n");
        Files.writeString(file, "int main() { return 0; }\n");

        Set<Path> existingLogs = listLogFiles();
        try (var session = InstalledSwimDriver.start(tempDir, project, "src/main.cpp")) {
            session.waitForText("int main()", STARTUP_TIMEOUT);
            Path logFile = waitForNewLogContaining(existingLogs, "Starting clangd with command",
                    Duration.ofSeconds(20));
            assertTrue(logFile != null, "Expected clangd startup log for root compile_commands.json");
            String log = Files.readString(logFile);
            assertTrue(log.contains("clangd workspace root:"),
                    "Expected clangd workspace-root log entry.\nLog:\n" + log);
            assertTrue(log.contains("Loaded compilation database from")
                            && log.contains("cpp-root-db/compile_commands.json"),
                    "Expected clangd to load the root compilation database.\nLog:\n" + log);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryStartsClangdWhenProjectHasBuildCompilationDatabase() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-clangd-lsp-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("cpp-build-db");
        Path build = project.resolve("build");
        Path file = project.resolve("src/main.cpp");
        Files.createDirectories(file.getParent());
        Files.createDirectories(build);
        Files.writeString(build.resolve("compile_commands.json"), "[]\n");
        Files.writeString(file, "int add(int a, int b) { return a + b; }\n");

        Set<Path> existingLogs = listLogFiles();
        try (var session = InstalledSwimDriver.start(tempDir, project, "src/main.cpp")) {
            session.waitForText("int add", STARTUP_TIMEOUT);
            Path logFile = waitForNewLogContaining(existingLogs, "Starting clangd with command",
                    Duration.ofSeconds(20));
            assertTrue(logFile != null, "Expected clangd startup log for build/compile_commands.json");
            String log = Files.readString(logFile);
            assertTrue(log.contains("Loaded compilation database from")
                            && log.contains("cpp-build-db/build/compile_commands.json"),
                    "Expected clangd to load the build compilation database.\nLog:\n" + log);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryDoesNotStartClangdWithoutCompilationDatabase() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-clangd-lsp-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("cpp-no-db");
        Path file = project.resolve("src/main.cpp");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "int value = 42;\n");

        Set<Path> existingLogs = listLogFiles();
        try (var session = InstalledSwimDriver.start(tempDir, project, "src/main.cpp")) {
            session.waitForText("int value = 42;", STARTUP_TIMEOUT);
            Path logFile = waitForNewLogContaining(existingLogs, "Starting clangd with command",
                    Duration.ofSeconds(5));
            assertTrue(logFile == null, "Did not expect clangd startup without a compilation database");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    private static Set<Path> listLogFiles() throws Exception {
        Set<Path> logs = new HashSet<>();
        Path tmpDir = Path.of("/tmp");
        if (!Files.isDirectory(tmpDir)) {
            return logs;
        }
        try (var stream = Files.list(tmpDir)) {
            stream.filter(path -> path.getFileName().toString().startsWith("swim-"))
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .forEach(logs::add);
        }
        return logs;
    }

    private static Path waitForNewLogContaining(Set<Path> existingLogs, String text, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (Path candidate : listLogFiles()) {
                if (existingLogs.contains(candidate) || !Files.isRegularFile(candidate)) {
                    continue;
                }
                if (Files.readString(candidate).contains(text)) {
                    return candidate;
                }
            }
            Thread.sleep(100);
        }
        for (Path candidate : listLogFiles()) {
            if (existingLogs.contains(candidate) || !Files.isRegularFile(candidate)) {
                continue;
            }
            if (Files.readString(candidate).contains(text)) {
                return candidate;
            }
        }
        return null;
    }

}
