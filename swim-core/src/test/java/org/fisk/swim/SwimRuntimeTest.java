package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.fisk.swim.api.SwimHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SwimRuntimeTest {
    @AfterEach
    void tearDown() {
        SwimRuntime.clear();
        SwimRuntime.resetCurrentPathSupplier();
    }

    @Test
    void exitRequestsHostExitWhenHostIsConfigured() {
        RecordingHost host = new RecordingHost();
        SwimRuntime.setHost(host);

        SwimRuntime.exit();

        assertTrue(host.exitRequested);
    }

    @Test
    void reloadUsesCurrentPathSupplier() {
        RecordingHost host = new RecordingHost();
        Path path = Path.of("/tmp/reload.txt");
        SwimRuntime.setHost(host);
        SwimRuntime.setCurrentPathSupplier(() -> path);

        SwimRuntime.reload();

        assertEquals(path, host.reloadPath);
    }

    @Test
    void rebuildAndReloadUsesCurrentPathSupplier() {
        RecordingHost host = new RecordingHost();
        Path path = Path.of("/tmp/rebuild.txt");
        SwimRuntime.setHost(host);
        SwimRuntime.setCurrentPathSupplier(() -> path);

        SwimRuntime.rebuildAndReload();

        assertEquals(path, host.rebuildPath);
    }

    @Test
    void reloadAndRebuildAreNoOpsWithoutHost() {
        SwimRuntime.clear();
        SwimRuntime.setCurrentPathSupplier(() -> {
            throw new AssertionError("path lookup should not run without a host");
        });

        assertDoesNotThrow(SwimRuntime::reload);
        assertDoesNotThrow(SwimRuntime::rebuildAndReload);
    }

    private static final class RecordingHost implements SwimHost {
        private Path reloadPath;
        private Path rebuildPath;
        private boolean exitRequested;

        @Override
        public void requestReload(Path path) {
            reloadPath = path;
        }

        @Override
        public void requestRebuildAndReload(Path path) {
            rebuildPath = path;
        }

        @Override
        public void requestExit() {
            exitRequested = true;
        }

        @Override
        public Path getBuildRoot() {
            return null;
        }
    }
}
