package org.fisk.swim.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class LauncherProcessIT {
    @Test
    @Timeout(20)
    void launcherStartsAndExitsViaCommandMode() throws Exception {
        Path script = Path.of("/usr/bin/script");
        Assumptions.assumeTrue(Files.isExecutable(script), "script utility is required for launcher process test");

        Path buildRoot = Main.findBuildRoot(Path.of(System.getProperty("user.dir")));
        Assumptions.assumeTrue(buildRoot != null, "Unable to locate build root for launcher process test");

        Path launcherJar = buildRoot.resolve("swim-launcher").resolve("target").resolve("swim-launcher-0.0.1-SNAPSHOT.jar");
        Path runtimeLibs = buildRoot.resolve("swim-launcher").resolve("target").resolve("runtime-libs");
        Path file = buildRoot.resolve("README.md");

        Assumptions.assumeTrue(Files.isRegularFile(launcherJar), "Launcher jar missing");
        Assumptions.assumeTrue(Files.isDirectory(runtimeLibs), "Launcher runtime libs missing");
        Assumptions.assumeTrue(Files.isRegularFile(file), "README missing");

        String classpath = launcherJar + ":" + runtimeLibs.resolve("*");
        var processBuilder = new ProcessBuilder(
                script.toString(),
                "-q",
                "/dev/null",
                "java",
                "-cp",
                classpath,
                "org.fisk.swim.launcher.Main",
                file.toString());
        processBuilder.directory(buildRoot.toFile());
        processBuilder.redirectErrorStream(true);

        var process = processBuilder.start();
        var output = new StringBuilder();
        var gobbler = new Thread(() -> readOutput(process, output), "launcher-process-it-output");
        gobbler.setDaemon(true);
        gobbler.start();

        Thread.sleep(1500);
        process.getOutputStream().write(":q\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        process.getOutputStream().close();

        boolean exited = process.waitFor(Duration.ofSeconds(10));
        if (!exited) {
            process.destroyForcibly();
        }
        assertTrue(exited, "Launcher did not exit after :q. Output:\n" + output);
        assertEquals(0, process.exitValue(), "Launcher exited non-zero. Output:\n" + output);
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
