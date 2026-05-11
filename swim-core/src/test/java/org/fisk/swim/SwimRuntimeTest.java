package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;
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
    void loadPluginUsesCurrentPathSupplier() {
        RecordingHost host = new RecordingHost();
        Path path = Path.of("/tmp/java.txt");
        SwimRuntime.setHost(host);
        SwimRuntime.setCurrentPathSupplier(() -> path);

        SwimRuntime.loadPlugin("java-lsp");

        assertEquals("java-lsp", host.pluginId);
        assertEquals(path, host.pluginPath);
    }

    @Test
    void getPanelDelegatesToHost() {
        RecordingHost host = new RecordingHost();
        host.panel = new SwimPanel() {
            @Override
            public String getId() {
                return "tree";
            }

            @Override
            public String getTitle() {
                return "Tree";
            }

            @Override
            public java.util.List<String> render(int width, int height) {
                return java.util.List.of("Tree");
            }

            @Override
            public SwimPanelResult handleInput(String input, int width, int height) {
                return SwimPanelResult.ignored();
            }
        };
        SwimRuntime.setHost(host);

        assertSame(host.panel, SwimRuntime.getPanel("tree"));
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
        private String pluginId;
        private Path pluginPath;
        private boolean exitRequested;
        private SwimPanel panel;

        @Override
        public void requestReload(Path path) {
            reloadPath = path;
        }

        @Override
        public void requestRebuildAndReload(Path path) {
            rebuildPath = path;
        }

        @Override
        public void requestLoadPlugin(String pluginId, Path path) {
            this.pluginId = pluginId;
            this.pluginPath = path;
        }

        @Override
        public void requestExit() {
            exitRequested = true;
        }

        @Override
        public SwimPanel getPanel(String pluginId) {
            return panel;
        }

        @Override
        public Path getBuildRoot() {
            return null;
        }
    }
}
