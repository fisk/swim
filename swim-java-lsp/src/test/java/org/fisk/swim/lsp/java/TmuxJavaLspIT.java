package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void installedLauncherBinaryCompletesLeaderCommaOKeybinding() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-java-lsp-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("java-keybinding-project");
        Path javaFile = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>demo</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Files.writeString(javaFile, """
                package demo;
                class Main {
                }
                """);

        String sessionName = "java-keybinding-" + System.nanoTime();
        try (var session = InstalledSwimDriver.start(
                tempDir,
                project,
                Map.of("SWIM_SESSION", sessionName),
                "src/main/java/demo/Main.java")) {
            session.waitForText("class Main", STARTUP_TIMEOUT);

            session.sendLiteralKeyStrokes(" ,o");
            Thread.sleep(500);
            String paneAfterBinding = session.capturePane();
            assertFalse(paneAfterBinding.contains("chain") && paneAfterBinding.contains("SPC , o"),
                    "Expected leader comma o to leave key discovery chain.\nPane:\n" + paneAfterBinding);
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(UI_TIMEOUT);
        }

        assertEquals("""
                ackage demo;
                class Main {
                }
                """, Files.readString(javaFile));
    }

    @Test
    @Timeout(90)
    void installedLauncherBinaryOrganizesImportsWithLeaderCommaOWhenJavaSupportIsAvailable() throws Exception {
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
                import java.util.ArrayList;
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
        existingLogs = listLogFiles();

        String sessionName = "java-organize-" + System.nanoTime();
        try (var session = InstalledSwimDriver.startWithHome(
                home.home(),
                project,
                Map.of("JAVA_TOOL_OPTIONS",
                        "-Duser.home=" + home.home() + " -Dswim.oracle.java.extension.path=" + copiedExtension,
                        "SWIM_SESSION", sessionName),
                "src/main/java/demo/Main.java")) {
            session.waitForText("class Main", STARTUP_TIMEOUT);
            Path logFile = waitForNewLogContaining(existingLogs, "Java LSP provider: oracle-embedded",
                    Duration.ofSeconds(40));
            assertTrue(logFile != null, "Expected embedded Java provider to activate");
            assertTrue(waitForLogContaining(logFile, "publishDiagnostics called", Duration.ofSeconds(30)),
                    "Expected Java provider to publish diagnostics before organize imports");
            assertTrue(waitForLogContaining(logFile, "showMessage: Indexing completed.", Duration.ofSeconds(30)),
                    "Expected Java provider to finish indexing before organize imports");
            session.sendLiteralKeyStrokes(" ,o");
            Thread.sleep(5000);
            String paneAfterBinding = session.capturePane();
            assertFalse(paneAfterBinding.contains("chain") && paneAfterBinding.contains("SPC , o"),
                    "Expected leader comma o to leave key discovery chain.\nPane:\n" + paneAfterBinding);
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(15));
        } finally {
            killSession(sessionName);
        }

        assertEquals("""
                package demo;
                import java.util.List;
                class Main {
                    List<String> values;
                }
                """, Files.readString(javaFile));
    }

    @Test
    @Timeout(90)
    void installedLauncherBinaryCompletesLeaderCommaOInSwimWindowJava() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-java-lsp-0.0.1-SNAPSHOT.jar");
        Path buildRoot = InstalledSwimDriver.buildRoot();
        Path bundledExtension = buildRoot.resolve("deps").resolve("oracle.oracle-java");
        Assumptions.assumeTrue(Files.isDirectory(bundledExtension), "Bundled Oracle Java extension payload not available");
        Path javaFile = buildRoot.resolve("swim-core/src/main/java/org/fisk/swim/ui/Window.java");
        Assumptions.assumeTrue(Files.isRegularFile(javaFile), "Window.java not available in build root");

        SwimHomeFixture home = SwimHomeFixture.create(tempDir);
        Path copiedExtension = home.copyOracleJavaExtensionFrom(bundledExtension);
        String sessionName = "java-window-organize-" + System.nanoTime();
        Set<Path> existingLogs = listLogFiles();
        try (var session = InstalledSwimDriver.startWithHome(
                home.home(),
                buildRoot,
                Map.of("JAVA_TOOL_OPTIONS",
                        "-Duser.home=" + home.home() + " -Dswim.oracle.java.extension.path=" + copiedExtension,
                        "SWIM_SESSION", sessionName),
                "swim-core/src/main/java/org/fisk/swim/ui/Window.java")) {
            session.waitForText("package org.fisk.swim.ui;", STARTUP_TIMEOUT);
            Path logFile = waitForNewLogContaining(existingLogs, "Java LSP provider: oracle-embedded",
                    Duration.ofSeconds(40));
            assertTrue(logFile != null, "Expected embedded Java provider to activate");

            session.sendLiteralKeyStrokes(" ,o");
            Thread.sleep(5000);
            String paneAfterBinding = session.capturePane();
            assertFalse(paneAfterBinding.contains("chain") && paneAfterBinding.contains("SPC , o"),
                    "Expected leader comma o to leave key discovery chain.\nPane:\n" + paneAfterBinding);
        } finally {
            killSession(sessionName);
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

    private static boolean waitForLogContaining(Path logFile, String text, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(logFile) && Files.readString(logFile).contains(text)) {
                return true;
            }
            Thread.sleep(100);
        }
        return Files.isRegularFile(logFile) && Files.readString(logFile).contains(text);
    }

    private boolean installedRuntimeActivatesEmbeddedProvider(
            Path home,
            Path project,
            Set<Path> existingLogs,
            Path copiedExtension) throws Exception {
        String sessionName = "java-provider-check-" + System.nanoTime();
        try (var session = InstalledSwimDriver.startWithHome(
                home,
                project,
                Map.of("JAVA_TOOL_OPTIONS",
                        "-Duser.home=" + home + " -Dswim.oracle.java.extension.path=" + copiedExtension,
                        "SWIM_SESSION", sessionName),
                "src/main/java/demo/Main.java")) {
            session.waitForText("class Main", STARTUP_TIMEOUT);
            return waitForNewLogContaining(existingLogs, "Java LSP provider: oracle-embedded", Duration.ofSeconds(20)) != null;
        } finally {
            killSession(sessionName);
        }
    }

    private static void killSession(String sessionName) {
        if (sessionName == null || sessionName.isBlank()) {
            return;
        }
        try {
            new ProcessBuilder(InstalledSwimDriver.launcherBinary().toString(), "--kill-session", sessionName)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor();
        } catch (Exception e) {
        }
    }
}
