package org.fisk.swim.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DebuggerManagerTest {
    @AfterEach
    void tearDown() throws Exception {
        DebuggerProviderRegistry.unregisterPlugin("test-debugger");
        DebuggerManager.closeCurrentSession();
    }

    @Test
    void launchAndControlDelegatesToRegisteredSession() throws Exception {
        var session = new RecordingSession();
        DebuggerProviderRegistry.register("fake", "test-debugger", new DebuggerProvider() {
            @Override
            public String id() {
                return "fake";
            }

            @Override
            public String displayName() {
                return "Fake";
            }

            @Override
            public String usage() {
                return "fake launch";
            }

            @Override
            public DebuggerSession launch(DebugLaunchRequest request) {
                return session;
            }
        });

        DebuggerManager.launch("fake", Path.of("/tmp/Main.java"), List.of("launch"));
        DebuggerManager.resume();
        DebuggerManager.stepOver();
        DebuggerManager.stepInto();
        DebuggerManager.stepOut();
        DebuggerManager.selectThread(0);
        DebuggerManager.selectFrame(0);
        DebuggerManager.stop();

        assertTrue(session.resumed);
        assertTrue(session.steppedOver);
        assertTrue(session.steppedInto);
        assertTrue(session.steppedOut);
        assertTrue(session.stopped);
    }

    private static final class RecordingSession implements DebuggerSession {
        private boolean resumed;
        private boolean steppedOver;
        private boolean steppedInto;
        private boolean steppedOut;
        private boolean stopped;

        @Override
        public String providerId() {
            return "fake";
        }

        @Override
        public String displayName() {
            return "Fake";
        }

        @Override
        public DebugSnapshot snapshot() {
            return new DebugSnapshot("Fake", DebugState.STOPPED, "ready",
                    new DebugSourceLocation(Path.of("/tmp/Main.java"), 3, 1, "main"),
                    List.of(), List.of(new DebugThreadInfo("1", "main", true)), 0,
                    List.of(new DebugFrameInfo("0", "main()", new DebugSourceLocation(Path.of("/tmp/Main.java"), 3, 1, "main"))),
                    0, List.of());
        }

        @Override
        public void setListener(DebugSessionListener listener) {
            listener.onSnapshotChanged(snapshot());
        }

        @Override
        public void resume() {
            resumed = true;
        }

        @Override
        public void stepOver() {
            steppedOver = true;
        }

        @Override
        public void stepInto() {
            steppedInto = true;
        }

        @Override
        public void stepOut() {
            steppedOut = true;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void toggleBreakpoint(DebugSourceLocation location) {
            assertEquals(3, location.line());
        }

        @Override
        public void selectThread(int threadIndex) {
            assertEquals(0, threadIndex);
        }

        @Override
        public void selectFrame(int frameIndex) {
            assertEquals(0, frameIndex);
        }

        @Override
        public void close() {
            stopped = true;
        }
    }
}
