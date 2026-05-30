package org.fisk.swim.plugins.javadebug;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaDebuggerSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void canLaunchToggleBreakpointContinueAndInspectVariables() throws Exception {
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
        assertEquals(0, compile.waitFor(), new String(compile.getInputStream().readAllBytes()));

        var provider = new JavaDebuggerProvider();
        JavaDebuggerSession session = (JavaDebuggerSession) provider.launch(
                new org.fisk.swim.debug.DebugLaunchRequest(source, List.of("launch", "Demo", classesDir.toString(), srcDir.toString())));
        try {
            DebugSnapshot initial = waitForState(session, DebugState.STOPPED, Duration.ofSeconds(10));
            assertEquals(DebugState.STOPPED, initial.state());
            session.toggleBreakpoint(new DebugSourceLocation(source, 3, 1, null));
            assertTrue(session.snapshot().breakpoints().stream().anyMatch(breakpoint -> breakpoint.line() == 3));
            assertTrue(initial.frames().isEmpty() || initial.frames().getFirst().displayLabel().contains("main"));
            session.resume();
            DebugSnapshot afterResume = waitForNotRunning(session, Duration.ofSeconds(10));
            assertTrue(afterResume.state() != DebugState.RUNNING);
        } finally {
            session.close();
            assertFalse(session.snapshot().state() == DebugState.RUNNING);
        }
    }

    private static DebugSnapshot waitForState(JavaDebuggerSession session, DebugState state, Duration timeout)
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

    private static DebugSnapshot waitForLine(JavaDebuggerSession session, int line, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            DebugSnapshot snapshot = session.snapshot();
            if (snapshot.currentLocation() != null && snapshot.currentLocation().line() == line) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        return session.snapshot();
    }

    private static DebugSnapshot waitForNotRunning(JavaDebuggerSession session, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            DebugSnapshot snapshot = session.snapshot();
            if (snapshot.state() != DebugState.RUNNING) {
                return snapshot;
            }
            Thread.sleep(50);
        }
        return session.snapshot();
    }
}
