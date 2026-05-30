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

class CppDebuggerSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void canLaunchBreakpointContinueAndStopWithLldb() throws Exception {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/lldb")), "lldb is required");
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
        var compile = new ProcessBuilder("/usr/bin/clang++", "-g", source.toString(), "-o", executable.toString())
                .redirectErrorStream(true)
                .start();
        assertEquals(0, compile.waitFor(), new String(compile.getInputStream().readAllBytes()));

        var provider = new CppDebuggerProvider();
        var session = (CppDebuggerSession) provider.launch(
                new org.fisk.swim.debug.DebugLaunchRequest(source, List.of("launch", executable.toString(), tempDir.toString())));
        try {
            session.toggleBreakpoint(new DebugSourceLocation(source, 4, 1, null));
            assertTrue(session.snapshot().breakpoints().stream().anyMatch(breakpoint -> breakpoint.line() == 4));
            session.resume();
            DebugSnapshot stopped = waitForState(session, DebugState.STOPPED, Duration.ofSeconds(10));
            assertEquals(DebugState.STOPPED, stopped.state());
            assertTrue(stopped.variables().stream().anyMatch(variable -> "value".equals(variable.name())));
            session.stop();
            assertFalse(session.snapshot().state() == DebugState.RUNNING);
        } finally {
            session.close();
        }
    }

    private static DebugSnapshot waitForState(CppDebuggerSession session, DebugState state, Duration timeout)
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
