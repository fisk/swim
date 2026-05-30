package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorDebugIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanLaunchAndStopJavaDebugger() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-java-debug-0.0.1-SNAPSHOT.jar");

        Path srcDir = tempDir.resolve("src");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classesDir);
        Path source = srcDir.resolve("Demo.java");
        Files.writeString(source, """
                public class Demo {
                    public static void main(String[] args) {
                        int value = 1;
                        value = value + 1;
                        System.out.println(value);
                    }
                }
                """);
        var compile = new ProcessBuilder("javac", "-g", "-d", classesDir.toString(), source.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(compile.waitFor() == 0, new String(compile.getInputStream().readAllBytes()));

        try (var session = InstalledSwimDriver.start(tempDir, srcDir, "Demo.java")) {
            session.waitForText("Demo", STARTUP_TIMEOUT);
            session.runCommand("debug java launch Demo " + classesDir + " " + srcDir);
            session.waitForText("Java Debugger", UI_TIMEOUT);
            session.waitForText("Debugger started", UI_TIMEOUT);
            session.runCommand("debug stop");
            session.waitForText("Debugger terminated", UI_TIMEOUT);
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanLaunchAndStopCppDebugger() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-cpp-debug-0.0.1-SNAPSHOT.jar");
        Assumptions.assumeTrue(Boolean.getBoolean("swim.native.debug.tests"),
                "Enable native debugger tmux tests with -Dswim.native.debug.tests=true");

        Path srcDir = tempDir.resolve("cpp-src");
        Files.createDirectories(srcDir);
        Path source = srcDir.resolve("main.cpp");
        Path executable = srcDir.resolve("main");
        Files.writeString(source, """
                #include <iostream>
                int main() {
                  int value = 1;
                  value = value + 1;
                  std::cout << value << std::endl;
                  return 0;
                }
                """);
        var compile = new ProcessBuilder("/usr/bin/clang++", "-g", source.toString(), "-o", executable.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(compile.waitFor() == 0, new String(compile.getInputStream().readAllBytes()));

        String debuggerCommand = "debug cpp lldb " + executable + " " + srcDir;
        if (Files.isExecutable(Path.of("/opt/homebrew/bin/g++-15"))
                && Files.isExecutable(Path.of("/opt/homebrew/bin/gdb"))) {
            Path gdbExecutable = srcDir.resolve("main-gdb");
            var gdbCompile = new ProcessBuilder("/opt/homebrew/bin/g++-15", "-g", source.toString(), "-o",
                    gdbExecutable.toString())
                    .redirectErrorStream(true)
                    .start();
            assertTrue(gdbCompile.waitFor() == 0, new String(gdbCompile.getInputStream().readAllBytes()));
            if (gdbCanDebugExecutable(source, gdbExecutable)) {
                debuggerCommand = "debug cpp gdb " + gdbExecutable + " " + srcDir;
            }
        }

        try (var session = InstalledSwimDriver.start(tempDir, srcDir, "main.cpp")) {
            session.waitForText("main.cpp", STARTUP_TIMEOUT);
            session.runCommand(debuggerCommand);
            session.waitForText("Debugger loaded", UI_TIMEOUT);
            session.sendLiteral("B");
            session.waitForText("Breakpoints updated", UI_TIMEOUT);
            session.runCommand("debug continue");
            session.waitForText("Stopped", UI_TIMEOUT);
            session.runCommand("debug stop");
            session.waitForText("Debugger terminated", UI_TIMEOUT);
        }
    }

    private static boolean gdbCanDebugExecutable(Path source, Path executable) throws Exception {
        var process = new ProcessBuilder("/opt/homebrew/bin/gdb", "--batch",
                "-ex", "file " + executable,
                "-ex", "break " + source + ":4",
                "-ex", "run",
                executable.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        return exit == 0
                && !output.contains("not in executable format")
                && !output.contains("Don't know how to run")
                && !output.contains("No symbol table")
                && !output.contains("DWARF Error");
    }
}
