package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.fisk.swim.testutil.SwimHomeFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxJavaLspIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(90)
    void installedLauncherBinaryUsesJavaSpecificOKeybindingWhenJavaSupportIsAvailable() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-java-lsp-0.0.1-SNAPSHOT.jar");
        Path bundledExtension = InstalledSwimDriver.buildRoot().resolve("deps").resolve("oracle.oracle-java");
        Assumptions.assumeTrue(Files.isDirectory(bundledExtension), "Bundled Oracle Java extension payload not available");

        Path project = tempDir.resolve("java-project");
        Path javaFile = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Files.writeString(javaFile, """
                package demo;
                import java.util.List;
                class Main {
                    List<String> values;
                }
                """);
        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        Path copiedExtension = home.copyOracleJavaExtensionFrom(bundledExtension);
        Set<Path> existingLogs = listLogFiles();
        Assumptions.assumeTrue(installedRuntimeActivatesEmbeddedProvider(home.home(), project, existingLogs, copiedExtension),
                "Installed runtime does not currently activate embedded Java provider in this environment");

        try (var session = InstalledSwimDriver.startWithHome(
                home.home(),
                project,
                Map.of("JAVA_TOOL_OPTIONS",
                        "-Duser.home=" + home.home() + " -Dswim.oracle.java.extension.path=" + copiedExtension),
                "src/main/java/demo/Main.java")) {
            session.waitForText("class Main", STARTUP_TIMEOUT);
            Path logFile = waitForNewLogContaining(existingLogs, "Java LSP provider: oracle-embedded",
                    Duration.ofSeconds(40));
            assertTrue(logFile != null, "Expected embedded Java provider to activate");
            session.sendLiteral("o");
            Thread.sleep(3000);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(15));
        }

        assertEquals("""
                package demo;
                import java.util.List;
                class Main {
                    List<String> values;
                }
                """, Files.readString(javaFile));
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

    private boolean installedRuntimeActivatesEmbeddedProvider(
            Path home,
            Path project,
            Set<Path> existingLogs,
            Path copiedExtension) throws Exception {
        try (var session = InstalledSwimDriver.startWithHome(
                home,
                project,
                Map.of("JAVA_TOOL_OPTIONS",
                        "-Duser.home=" + home + " -Dswim.oracle.java.extension.path=" + copiedExtension),
                "src/main/java/demo/Main.java")) {
            session.waitForText("class Main", STARTUP_TIMEOUT);
            return waitForNewLogContaining(existingLogs, "Java LSP provider: oracle-embedded", Duration.ofSeconds(20)) != null;
        }
    }
}
