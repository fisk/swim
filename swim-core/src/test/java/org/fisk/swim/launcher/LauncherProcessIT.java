package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.fisk.swim.launcher.Main;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LauncherProcessIT {
    @TempDir
    Path tempDir;

    private static final String EXPECTED_JAVA_PROVIDER = "oracle-embedded";
    private Boolean _interactiveScriptSupported;

    @Test
    @Timeout(30)
    void actualEditorCanSwitchFilesDeleteSaveAndQuit() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");
        Assumptions.assumeTrue(scriptCanDriveInteractiveCommand(script),
                "script utility must support driving interactive child stdin");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path file = tempDir.resolve("switch-source.txt");
        Path other = tempDir.resolve("switch-target.txt");
        Files.writeString(file, "source");
        Files.writeString(other, "xyz");
        String javaAgentArg = jacocoAgentArg(buildRoot);
        Assumptions.assumeTrue(scriptCanKeepCommandStdinOpen(
                buildRoot,
                script,
                "java", javaAgentArg, "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString()),
                "script utility must keep the launcher session stdin open long enough for interaction");

        var process = startProcess(buildRoot, launcherJar, script.toString(), "-q", "/dev/null",
                "java", javaAgentArg, "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString());
        try {
            waitForStartup(process);
            runCommand(process, "e " + other);
            type(process, "x");
            runCommand(process, "w");
            runCommand(process, "q");

            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Launcher did not exit after file-switch workflow.\n" + process.output());
            assertEquals("source", Files.readString(file));
            assertEquals("yz", Files.readString(other));
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(30)
    void actualEditorCanDeleteSaveAndQuit() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");
        Assumptions.assumeTrue(scriptCanDriveInteractiveCommand(script),
                "script utility must support driving interactive child stdin");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path file = tempDir.resolve("undo-redo.txt");
        Files.writeString(file, "abc");
        String javaAgentArg = jacocoAgentArg(buildRoot);
        Assumptions.assumeTrue(scriptCanKeepCommandStdinOpen(
                buildRoot,
                script,
                "java", javaAgentArg, "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString()),
                "script utility must keep the launcher session stdin open long enough for interaction");

        var process = startProcess(buildRoot, launcherJar, script.toString(), "-q", "/dev/null",
                "java", javaAgentArg, "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString());
        try {
            waitForStartup(process);
            type(process, "x");
            runCommand(process, "w");
            runCommand(process, "q");

            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Launcher did not exit after delete workflow.\n" + process.output());
            assertEquals("bc", Files.readString(file));
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(20)
    void actualEditorProcessStartsInPseudoTerminal() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path file = buildRoot.resolve("README.md");
        String javaAgentArg = jacocoAgentArg(buildRoot);
        Set<Path> existingLogs = listLogFiles();

        var process = startProcess(buildRoot, launcherJar, script.toString(), "-q", "/dev/null",
                "java", javaAgentArg, "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString());

        Thread.sleep(2500);
        assertTrue(process.process().isAlive(), "Editor process should still be alive after startup");
        Path logFile = waitForNewLogFile(existingLogs, java.time.Duration.ofSeconds(5));
        assertTrue(logFile != null,
                "Expected launcher to create a new /tmp/swim-*.log file. Output:\n" + process.output());

        process.process().destroyForcibly();
        boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(5));
        assertTrue(exited, "Editor process did not terminate after forced destroy");
        Files.deleteIfExists(logFile);
    }

    @Test
    @Timeout(20)
    void installedLauncherBinaryStartsWithoutStaleJdepsWarning() throws Exception {
        Path scriptUtility = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(scriptUtility), "script utility is required for launcher process test");
        Assumptions.assumeTrue(scriptCanDriveInteractiveCommand(scriptUtility),
                "script utility must support driving interactive child stdin");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherBinary = buildRoot.resolve("image").resolve("bin").resolve("swim");
        Assumptions.assumeTrue(Files.isExecutable(launcherBinary), "Installed launcher binary missing");
        Path file = tempDir.resolve("script-launch.txt");
        Files.writeString(file, "abc");
        Assumptions.assumeTrue(scriptCanKeepCommandStdinOpen(
                buildRoot,
                scriptUtility,
                launcherBinary.toString(), file.toString()),
                "script utility must keep the installed launcher session stdin open long enough for interaction");

        var process = startProcess(buildRoot, launcherBinary, scriptUtility.toString(), "-q", "/dev/null",
                launcherBinary.toString(), file.toString());

        try {
            waitForStartup(process);
            assertTrue(process.process().isAlive(), "Installed launcher binary should still be alive after startup");
            assertTrue(!process.output().toString().contains("package com.sun.tools.classfile not in jdk.jdeps"),
                    "Installed launcher binary emitted stale jdk.jdeps warning.\n" + process.output());

            type(process, "x");
            runCommand(process, "w");
            runCommand(process, "q");

            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Installed launcher binary did not exit after edit workflow.\n" + process.output());
            assertEquals("bc", Files.readString(file));
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(30)
    void installedLauncherBinaryRendersInitialScreenWithoutInput() throws Exception {
        Path scriptUtility = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(scriptUtility), "script utility is required for launcher process test");
        Assumptions.assumeTrue(scriptCanCaptureTranscript(scriptUtility),
                "script transcript capture is required for startup render verification");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherBinary = buildRoot.resolve("image").resolve("bin").resolve("swim");
        Assumptions.assumeTrue(Files.isExecutable(launcherBinary), "Installed launcher binary missing");

        Path file = buildRoot.resolve("README.md");
        Assumptions.assumeTrue(Files.isRegularFile(file), "README.md missing");
        Path transcript = tempDir.resolve("startup.typescript");

        var process = startProcess(buildRoot, launcherBinary, transcript, scriptUtility.toString(), "-q", "-F", transcript.toString(),
                launcherBinary.toString(), file.toString());

        try {
            waitForStartup(process);
            assertTrue(process.process().isAlive(),
                    "Installed launcher binary died during initial render.\n" + process.output());
            assertTrue(waitForFileText(transcript, "Loaded SWIM core", java.time.Duration.ofSeconds(15)),
                    "Installed launcher binary did not render the startup notice without input.\nTranscript:\n"
                            + Files.readString(transcript) + "\nOutput:\n" + process.output());
            assertTrue(waitForFileText(transcript, "README.md", java.time.Duration.ofSeconds(5)),
                    "Installed launcher binary did not render the opened file name without input.\nTranscript:\n"
                            + Files.readString(transcript) + "\nOutput:\n" + process.output());
            assertTrue(waitForFileText(transcript, "SWIM stands for", java.time.Duration.ofSeconds(5)),
                    "Installed launcher binary did not render buffer contents without input.\nTranscript:\n"
                            + Files.readString(transcript) + "\nOutput:\n" + process.output());
            runCommand(process, "q");
            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Installed launcher binary did not exit after startup render check.\n" + process.output());
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryUsesEmbeddedProvider() throws Exception {
        Path scriptUtility = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(scriptUtility), "script utility is required for launcher process test");
        Assumptions.assumeTrue(scriptCanDriveInteractiveCommand(scriptUtility),
                "script utility must support driving interactive child stdin");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherBinary = buildRoot.resolve("image").resolve("bin").resolve("swim");
        Assumptions.assumeTrue(Files.isExecutable(launcherBinary), "Installed launcher binary missing");
        Set<Path> existingLogs = listLogFiles();

        Path project = tempDir.resolve("embedded-demo");
        Path javaFile = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>embedded-demo</artifactId>
                  <version>1.0</version>
                </project>
                """);
        Files.writeString(javaFile, """
                package demo;
                class Main {}
                """);
        Assumptions.assumeTrue(scriptCanKeepCommandStdinOpen(
                buildRoot,
                scriptUtility,
                launcherBinary.toString(), javaFile.toString()),
                "script utility must keep the installed launcher session stdin open long enough for interaction");

        var process = startProcess(
                buildRoot,
                launcherBinary,
                Map.of(),
                scriptUtility.toString(), "-q", "/dev/null",
                launcherBinary.toString(), javaFile.toString());
        try {
            waitForStartup(process);
            assertTrue(process.process().isAlive(), "Installed launcher binary should still be alive after startup");
            Path logFile = waitForNewLogFile(existingLogs, java.time.Duration.ofSeconds(10));
            assertTrue(logFile != null,
                    "Expected launcher binary to create a new /tmp/swim-*.log file. Output:\n" + process.output());
            Path providerLog = waitForNewLogContainingText(
                    existingLogs,
                    "Java LSP provider: " + EXPECTED_JAVA_PROVIDER,
                    java.time.Duration.ofSeconds(40));
            assertTrue(providerLog != null,
                    "Expected Java provider was not activated.\nLog:\n" + Files.readString(logFile) + "\nOutput:\n" + process.output());
            logFile = providerLog;
            if ("oracle-embedded".equals(EXPECTED_JAVA_PROVIDER)) {
                assertTrue(!Files.readString(logFile).contains("Java LSP provider: oracle-process (fallback)"),
                        "Embedded provider fell back to oracle-process.\nLog:\n" + Files.readString(logFile));
                assertTrue(!waitForFileText(logFile, "netbeans.user is not set.", java.time.Duration.ofSeconds(5)),
                        "Embedded provider crashed after activation.\nLog:\n" + Files.readString(logFile));
            }

            runCommand(process, "q");
            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Installed launcher binary did not exit after quit command.\n" + process.output());
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryKeepsRunningWhenOpeningRepositoryJavaFile() throws Exception {
        Path scriptUtility = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(scriptUtility), "script utility is required for launcher process test");
        Assumptions.assumeTrue(scriptCanDriveInteractiveCommand(scriptUtility),
                "script utility must support driving interactive child stdin");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherBinary = buildRoot.resolve("image").resolve("bin").resolve("swim");
        Assumptions.assumeTrue(Files.isExecutable(launcherBinary), "Installed launcher binary missing");
        Path javaFile = buildRoot.resolve("swim-core").resolve("src").resolve("main").resolve("java")
                .resolve("org").resolve("fisk").resolve("swim").resolve("lsp").resolve("java").resolve("JavaLSPClient.java");
        Assumptions.assumeTrue(Files.isRegularFile(javaFile), "Repository Java file missing");
        Assumptions.assumeTrue(scriptCanKeepCommandStdinOpen(
                buildRoot,
                scriptUtility,
                launcherBinary.toString(), javaFile.toString()),
                "script utility must keep the installed launcher session stdin open long enough for interaction");

        var process = startProcess(buildRoot, launcherBinary, scriptUtility.toString(), "-q", "/dev/null",
                launcherBinary.toString(), javaFile.toString());
        try {
            waitForStartup(process);
            assertTrue(process.process().isAlive(), "Installed launcher binary died while opening repository Java file.\n" + process.output());

            type(process, "jjjjjjjjjj");
            Thread.sleep(5000);

            assertTrue(process.process().isAlive(), "Installed launcher binary crashed after opening and navigating a repository Java file.\n" + process.output());

            runCommand(process, "q");
            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
            assertTrue(exited, "Installed launcher binary did not exit after repository Java workflow.\n" + process.output());
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(120)
    void installedLauncherBinaryCanRebuildAndStillQuit() throws Exception {
        Path scriptUtility = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(scriptUtility), "script utility is required for launcher process test");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherBinary = buildRoot.resolve("image").resolve("bin").resolve("swim");
        Assumptions.assumeTrue(Files.isExecutable(launcherBinary), "Installed launcher binary missing");
        Set<Path> existingLogs = listLogFiles();

        Path file = tempDir.resolve("rebuild-check.txt");
        Files.writeString(file, "rebuild check\n");
        Assumptions.assumeTrue(scriptCanKeepCommandStdinOpen(
                buildRoot,
                scriptUtility,
                launcherBinary.toString(), file.toString()),
                "script utility must keep the installed launcher session stdin open long enough for interaction");

        var process = startProcess(buildRoot, launcherBinary, scriptUtility.toString(), "-q", "/dev/null",
                launcherBinary.toString(), file.toString());
        try {
            waitForStartup(process);
            assertTrue(process.process().isAlive(), "Installed launcher binary should still be alive after startup");

            Path logFile = waitForNewLogFile(existingLogs, java.time.Duration.ofSeconds(10));
            assertTrue(logFile != null,
                    "Expected launcher binary to create a new /tmp/swim-*.log file. Output:\n" + process.output());

            runCommand(process, "rebuild");

            boolean restarted = waitForOccurrences(
                    logFile,
                    "org.fisk.swim.SwimAppImpl - swim started",
                    2,
                    java.time.Duration.ofSeconds(90));
            assertTrue(restarted,
                    "Installed launcher binary did not restart cleanly after :rebuild.\nLog:\n"
                            + Files.readString(logFile) + "\nOutput:\n" + process.output());
            assertTrue(process.process().isAlive(),
                    "Installed launcher binary died during :rebuild.\nLog:\n" + Files.readString(logFile)
                            + "\nOutput:\n" + process.output());

            runCommand(process, "q");
            boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(15));
            assertTrue(exited,
                    "Installed launcher binary did not exit after :rebuild followed by :q.\nLog:\n"
                            + Files.readString(logFile) + "\nOutput:\n" + process.output());
        } finally {
            destroyIfAlive(process.process());
        }
    }

    @Test
    @Timeout(20)
    void launcherProcessExitsWhenCoreRequestsExit() throws Exception {
        Path buildRoot = createSyntheticBuildRoot();
        Path launcherJar = Main.getLauncherLocation();
        Path file = buildRoot.resolve("README.txt");
        Files.writeString(file, "hello");

        var process = startProcess(buildRoot, launcherJar,
                "java", "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString());

        boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(10));
        assertTrue(exited, "Launcher did not exit after synthetic core requested exit.\n" + process.output());
        assertTrue(!process.output().toString().contains("Exception"),
                "Launcher process logged an exception while exiting.\n" + process.output());
    }

    @Test
    @Timeout(30)
    void launcherRebuildAndReloadLoadsReplacementCoreAfterPreviousCloses() throws Exception {
        Path buildRoot = createSyntheticRebuildRoot();
        Path launcherJar = copyLauncherJarInto(buildRoot);
        Path file = buildRoot.resolve("README.txt");
        Files.writeString(file, "hello");

        var process = startProcess(buildRoot, launcherJar,
                "java", "--module-path", launcherJar.toString(),
                "-m", "org.fisk.swim.launcher/org.fisk.swim.launcher.Main", file.toString());

        boolean exited = process.process().waitFor(java.time.Duration.ofSeconds(15));
        assertTrue(exited, "Launcher did not exit after synthetic rebuild workflow.\n" + process.output());
        assertEquals("v2-after-close", Files.readString(buildRoot.resolve("marker.txt")));
        assertEquals(List.of("plugin-load", "plugin-close", "plugin-load", "plugin-close"),
                Files.readAllLines(buildRoot.resolve("plugin-events.txt")));
        assertTrue(!process.output().toString().contains("Exception"),
                "Launcher process logged an exception during rebuild workflow.\n" + process.output());
    }

    private static void waitForStartup(StartedProcess process) throws Exception {
        Path transcript = process.transcript();
        if (transcript != null) {
            if (waitForFileText(transcript, "Loaded SWIM core", java.time.Duration.ofSeconds(6))) {
                return;
            }
            if (waitForFileText(transcript, "SWIM stands for", java.time.Duration.ofSeconds(1))) {
                return;
            }
        }
        Thread.sleep(3500);
    }

    private static void runCommand(StartedProcess process, String command) throws Exception {
        type(process, ":");
        type(process, command);
        pressEnter(process);
        Thread.sleep(250);
    }

    private static void pressEnter(StartedProcess process) throws Exception {
        write(process.process(), new byte[] { '\r' });
        Thread.sleep(250);
    }

    private static void type(StartedProcess process, String text) throws Exception {
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            write(process.process(), new byte[] { value });
            Thread.sleep(60);
        }
    }

    private static void write(Process process, byte[] bytes) throws IOException {
        process.getOutputStream().write(bytes);
        process.getOutputStream().flush();
    }

    private static void destroyIfAlive(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(java.time.Duration.ofSeconds(5));
        }
    }

    private static Path waitForNewLogFile(Set<Path> existingLogs, java.time.Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (Path candidate : listLogFiles()) {
                if (!existingLogs.contains(candidate)) {
                    return candidate;
                }
            }
            Thread.sleep(100);
        }
        for (Path candidate : listLogFiles()) {
            if (!existingLogs.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path waitForNewLogContainingText(Set<Path> existingLogs, String text, java.time.Duration timeout) throws Exception {
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

    private static boolean waitForOccurrences(Path file, String text, int expectedCount, java.time.Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && countOccurrences(Files.readString(file), text) >= expectedCount) {
                return true;
            }
            Thread.sleep(250);
        }
        return Files.isRegularFile(file) && countOccurrences(Files.readString(file), text) >= expectedCount;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static boolean waitForFileText(Path file, String text, java.time.Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file) && Files.readString(file).contains(text)) {
                return true;
            }
            Thread.sleep(100);
        }
        return Files.isRegularFile(file) && Files.readString(file).contains(text);
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

    private StartedProcess startProcess(Path workdir, Path launcherJar, String... command) throws IOException {
        return startProcess(workdir, launcherJar, null, Map.of(), command);
    }

    private StartedProcess startProcess(Path workdir, Path launcherJar, Path transcript, String... command) throws IOException {
        return startProcess(workdir, launcherJar, transcript, Map.of(), command);
    }

    private StartedProcess startProcess(Path workdir, Path launcherJar, Map<String, String> environment, String... command) throws IOException {
        return startProcess(workdir, launcherJar, null, environment, command);
    }

    private StartedProcess startProcess(Path workdir, Path launcherJar, Path transcript, Map<String, String> environment, String... command) throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(launcherJar), "Launcher jar missing");

        var processBuilder = new ProcessBuilder(command);
        processBuilder.directory(workdir.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.environment().putAll(environment);
        var process = processBuilder.start();
        var output = new StringBuilder();
        var gobbler = new Thread(() -> readOutput(process, output), "launcher-process-it-output");
        gobbler.setDaemon(true);
        gobbler.start();
        return new StartedProcess(process, output, transcript);
    }

    private boolean scriptCanCaptureTranscript(Path scriptUtility) throws Exception {
        Path transcript = tempDir.resolve("script-probe.typescript");
        Files.deleteIfExists(transcript);
        var process = new ProcessBuilder(scriptUtility.toString(), "-q", transcript.toString(),
                "/bin/sh", "-c", "printf READY; sleep 2")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
            boolean observedReady = false;
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(transcript) && Files.readString(transcript).contains("READY")) {
                    observedReady = true;
                    break;
                }
                Thread.sleep(100);
            }
            if (!process.waitFor(java.time.Duration.ofSeconds(5))) {
                process.destroyForcibly();
                process.waitFor(java.time.Duration.ofSeconds(5));
            }
            return observedReady;
        } finally {
            destroyIfAlive(process);
        }
    }

    private boolean scriptCanDriveInteractiveCommand(Path scriptUtility) throws Exception {
        if (_interactiveScriptSupported != null) {
            return _interactiveScriptSupported;
        }
        var process = new ProcessBuilder(scriptUtility.toString(), "-q", "/dev/null",
                "/bin/sh", "-c", "sleep 10")
                .directory(tempDir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            Thread.sleep(4000);
            if (!process.isAlive()) {
                _interactiveScriptSupported = false;
                return false;
            }
            try {
                write(process, "PING\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                _interactiveScriptSupported = false;
                return false;
            }
            _interactiveScriptSupported = process.isAlive();
            return _interactiveScriptSupported;
        } finally {
            destroyIfAlive(process);
        }
    }

    private boolean scriptCanKeepCommandStdinOpen(Path workdir, Path scriptUtility, String... command) throws Exception {
        var fullCommand = new String[command.length + 3];
        fullCommand[0] = scriptUtility.toString();
        fullCommand[1] = "-q";
        fullCommand[2] = "/dev/null";
        System.arraycopy(command, 0, fullCommand, 3, command.length);
        var process = new ProcessBuilder(fullCommand)
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            Thread.sleep(4000);
            if (!process.isAlive()) {
                return false;
            }
            try {
                write(process, "PING\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return false;
            }
            return process.isAlive();
        } finally {
            destroyIfAlive(process);
        }
    }

    private static String jacocoAgentArg(Path buildRoot) {
        Path agent = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "jacoco",
                "org.jacoco.agent", "0.8.13", "org.jacoco.agent-0.8.13-runtime.jar");
        Assumptions.assumeTrue(Files.isRegularFile(agent), "JaCoCo agent jar missing");
        Path execFile = buildRoot.resolve("swim-core").resolve("target").resolve("jacoco-it.exec");
        return "-javaagent:" + agent + "=destfile=" + execFile + ",append=true";
    }

    private record StartedProcess(Process process, StringBuilder output, Path transcript) {
    }

    private Path createSyntheticBuildRoot() throws Exception {
        Path root = tempDir.resolve("swim");
        Path target = root.resolve("swim-core").resolve("target");
        Path runtimeLibs = target.resolve("runtime-libs");
        Files.createDirectories(runtimeLibs);
        Files.writeString(root.resolve("pom.xml"), "<project />");
        Files.createDirectories(root.resolve("swim-core"));

        Path compileDir = tempDir.resolve("compile-it");
        Path coreClasses = compileDir.resolve("core-classes");
        Files.createDirectories(coreClasses);

        compileJava(Map.of(
                "fake/core/ExitApp.java", """
                        package fake.core;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class ExitApp implements SwimApp {
                            public void start(Path path, SwimHost host) {
                                Thread thread = new Thread(() -> {
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    host.requestExit();
                                });
                                thread.setDaemon(true);
                                thread.start();
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return Path.of(".");
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                            }
                        }
                        """
        ), coreClasses, List.of(System.getProperty("java.class.path")));

        Path coreJar = target.resolve("swim-core-0.0.1-SNAPSHOT.jar");
        jarDirectory(coreClasses, coreJar, "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.ExitApp\n"));
        return root;
    }

    private Path createSyntheticRebuildRoot() throws Exception {
        Path root = tempDir.resolve("rebuild-swim");
        Path plugins = root.resolve("plugins");
        Files.createDirectories(plugins);
        Files.createDirectories(root.resolve("swim-core"));
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>demo</groupId>
                  <artifactId>rebuild-swim</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>maven-resources-plugin</artifactId>
                        <version>3.3.1</version>
                        <executions>
                          <execution>
                            <id>copy-replacement-core</id>
                            <phase>package</phase>
                            <goals><goal>copy-resources</goal></goals>
                            <configuration>
                              <outputDirectory>${project.basedir}/plugins</outputDirectory>
                              <resources>
                                <resource>
                                  <directory>${project.basedir}/artifacts</directory>
                                  <includes>
                                    <include>swim-core-0.0.2-SNAPSHOT.jar</include>
                                  </includes>
                                </resource>
                              </resources>
                              <overwrite>true</overwrite>
                            </configuration>
                          </execution>
                        </executions>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        Path compileDir = tempDir.resolve("rebuild-compile");
        Path v1Classes = compileDir.resolve("v1-classes");
        Path v2Classes = compileDir.resolve("v2-classes");
        Path pluginClasses = compileDir.resolve("plugin-classes");
        Files.createDirectories(v1Classes);
        Files.createDirectories(v2Classes);
        Files.createDirectories(pluginClasses);
        String classpath = System.getProperty("java.class.path");

        compileJava(Map.of(
                "fake/core/RebuildApp.java", """
                        package fake.core;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class RebuildApp implements SwimApp {
                            private Path path;
                            private SwimHost host;
                            public void start(Path path, SwimHost host) {
                                this.path = path;
                                this.host = host;
                                Thread thread = new Thread(() -> {
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    host.requestRebuildAndReload(path);
                                }, "rebuild-v1-trigger");
                                thread.setDaemon(true);
                                thread.start();
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return path;
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                                try {
                                    Files.writeString(host.getBuildRoot().resolve("closed.txt"), "closed");
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), v1Classes, List.of(classpath));

        compileJava(Map.of(
                "fake/core/RebuildApp.java", """
                        package fake.core;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import org.fisk.swim.api.SwimApp;
                        import org.fisk.swim.api.SwimHost;
                        public final class RebuildApp implements SwimApp {
                            private Path path;
                            public void start(Path path, SwimHost host) {
                                this.path = path;
                                try {
                                    String marker = Files.exists(host.getBuildRoot().resolve("closed.txt")) ? "v2-after-close" : "v2-before-close";
                                    Files.writeString(host.getBuildRoot().resolve("marker.txt"), marker);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                Thread thread = new Thread(() -> {
                                    try {
                                        Thread.sleep(200);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    host.requestExit();
                                }, "rebuild-v2-exit");
                                thread.setDaemon(true);
                                thread.start();
                            }
                            public void refresh(boolean forced) {
                            }
                            public Path getCurrentPath() {
                                return path;
                            }
                            public void showMessage(String message) {
                            }
                            public void close() {
                            }
                        }
                        """
        ), v2Classes, List.of(classpath));

        compileJava(Map.of(
                "fake/plugin/ReloadMarkerPlugin.java", """
                        package fake.plugin;
                        import java.nio.file.Files;
                        import java.nio.file.Path;
                        import java.nio.file.StandardOpenOption;
                        import org.fisk.swim.api.SwimPlugin;
                        import org.fisk.swim.api.SwimPluginContext;
                        public final class ReloadMarkerPlugin implements SwimPlugin {
                            private Path events;
                            public String getId() {
                                return "reload-marker-plugin";
                            }
                            public void load(SwimPluginContext context) {
                                events = context.getHost().getBuildRoot().resolve("plugin-events.txt");
                                append("plugin-load");
                            }
                            public void close() {
                                append("plugin-close");
                            }
                            private void append(String event) {
                                try {
                                    Files.writeString(events,
                                            event + System.lineSeparator(),
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.APPEND);
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        """
        ), pluginClasses, List.of(classpath));

        jarDirectory(v1Classes, plugins.resolve("swim-core-0.0.1-SNAPSHOT.jar"), "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.RebuildApp\n"));
        jarDirectory(pluginClasses, plugins.resolve("reload-marker-plugin-0.0.1-SNAPSHOT.jar"), "demo.reload.marker",
                Map.of("META-INF/services/org.fisk.swim.api.SwimPlugin", "fake.plugin.ReloadMarkerPlugin\n"));
        Path artifacts = root.resolve("artifacts");
        Files.createDirectories(artifacts);
        jarDirectory(v2Classes, artifacts.resolve("swim-core-0.0.2-SNAPSHOT.jar"), "org.fisk.swim.core",
                Map.of("META-INF/services/org.fisk.swim.api.SwimApp", "fake.core.RebuildApp\n"));
        return root;
    }

    private Path copyLauncherJarInto(Path buildRoot) throws IOException {
        Path source = Main.getLauncherLocation();
        Path target = buildRoot.resolve("target").resolve(source.getFileName().toString());
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        return target;
    }

    private static void compileJava(Map<String, String> sources, Path classesDir, List<String> classpathEntries) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available");
        }
        Path sourcesDir = classesDir.getParent().resolve(classesDir.getFileName().toString() + "-src");
        Files.createDirectories(sourcesDir);
        for (var entry : sources.entrySet()) {
            Path file = sourcesDir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }

        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            var sourceFiles = Files.walk(sourcesDir)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .map(Path::toFile)
                    .toList();
            var units = fileManager.getJavaFileObjectsFromFiles(sourceFiles);
            String classpath = String.join(System.getProperty("path.separator"), classpathEntries);
            var task = compiler.getTask(null, fileManager, null,
                    List.of("-d", classesDir.toString(), "-classpath", classpath),
                    null, units);
            if (!task.call()) {
                throw new IllegalStateException("Compilation failed for synthetic launcher integration sources");
            }
        }
    }

    private static void jarDirectory(Path classesDir, Path jarFile, String automaticModuleName, Map<String, String> extraFiles) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (automaticModuleName != null) {
            manifest.getMainAttributes().putValue("Automatic-Module-Name", automaticModuleName);
        }
        Files.createDirectories(jarFile.getParent());
        try (OutputStream output = Files.newOutputStream(jarFile);
             JarOutputStream jar = new JarOutputStream(output, manifest)) {
            Files.walk(classesDir)
                    .filter(Files::isRegularFile)
                    .forEach(path -> addJarEntry(jar, classesDir, path));
            for (var entry : extraFiles.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                try {
                    jar.putNextEntry(jarEntry);
                    jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    jar.closeEntry();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void addJarEntry(JarOutputStream jar, Path root, Path file) {
        try {
            String name = root.relativize(file).toString().replace('\\', '/');
            jar.putNextEntry(new JarEntry(name));
            jar.write(Files.readAllBytes(file));
            jar.closeEntry();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void readOutput(Process process, StringBuilder output) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                output.append(buffer, 0, read);
            }
        } catch (IOException e) {
        }
    }
}
