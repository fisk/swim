package org.fisk.swim.plugins.cppdebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.fisk.swim.debug.DebugSnapshot;
import org.fisk.swim.debug.DebugSourceLocation;
import org.fisk.swim.debug.DebugState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GdbDebuggerSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void canLaunchBreakpointContinueAndStopWithGdbWhenSupported() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/opt/homebrew/bin/gdb"))
                        || Files.isExecutable(Path.of("/usr/bin/gdb")),
                "gdb is required");
        Assumptions.assumeTrue(Boolean.getBoolean("swim.native.debug.tests"),
                "Enable with -Dswim.native.debug.tests=true");

        Path source = tempDir.resolve("main.cpp");
        Path executable = tempDir.resolve("main");
        Files.writeString(source, """
                #include <iostream>
                int main() {
                  int value = 1;
                  value = value + 1;
                  std::cout << value << std::endl;
                  return 0;
                }
                """);
        var compile = new ProcessBuilder("/opt/homebrew/bin/g++-15", "-g", source.toString(), "-o", executable.toString())
                .redirectErrorStream(true)
                .start();
        assertEquals(0, compile.waitFor(), new String(compile.getInputStream().readAllBytes()));

        Assumptions.assumeTrue(gdbCanDebugExecutable(source, executable),
                "Installed gdb cannot run local executables on this machine");

        var provider = new CppDebuggerProvider();
        var session = (GdbDebuggerSession) provider.launch(
                new org.fisk.swim.debug.DebugLaunchRequest(source, List.of("gdb", executable.toString(), tempDir.toString())));
        try {
            session.toggleBreakpoint(new DebugSourceLocation(source, 4, 1, null));
            assertTrue(session.snapshot().breakpoints().stream().anyMatch(breakpoint -> breakpoint.line() == 4));
            session.resume();
            DebugSnapshot stopped = waitForState(session, DebugState.STOPPED, Duration.ofSeconds(10));
            assertEquals(DebugState.STOPPED, stopped.state());
            session.stop();
            assertFalse(session.snapshot().state() == DebugState.RUNNING);
        } finally {
            session.close();
        }
    }

    private static boolean gdbCanDebugExecutable(Path source, Path executable) throws Exception {
        String gdb = Files.isExecutable(Path.of("/opt/homebrew/bin/gdb")) ? "/opt/homebrew/bin/gdb" : "/usr/bin/gdb";
        var process = new ProcessBuilder(gdb, "--batch",
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

    private static DebugSnapshot waitForState(GdbDebuggerSession session, DebugState state, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            DebugSnapshot snapshot = session.snapshot();
            if (snapshot.state() == state) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        return session.snapshot();
    }
}
