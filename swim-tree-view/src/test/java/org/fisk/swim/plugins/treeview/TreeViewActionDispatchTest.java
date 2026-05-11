package org.fisk.swim.plugins.treeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeViewActionDispatchTest {
    @TempDir
    Path _tempDir;

    @Test
    void dispatchesOpenFileActionsThroughHandler() throws IOException {
        Path file = Files.writeString(_tempDir.resolve("Main.java"), "class Main {}\n");
        TreeViewPluginSession session = new TreeViewPluginSession(new TestPluginContext(_tempDir, file));

        AtomicReference<TreeViewAction> dispatched = new AtomicReference<>();
        TreeViewHandledInteractionResult result = session.interact("enter", 30, 5, action -> {
            dispatched.set(action);
            return TreeViewActionHandlerResult.success("opened");
        });

        assertEquals(TreeViewActionType.OPEN_FILE, result.interactionResult().commandResult().action().type());
        assertEquals(file, dispatched.get().path());
        assertTrue(result.actionHandlerResult().handled());
        assertEquals("opened", result.actionHandlerResult().message());
    }

    @Test
    void ignoresNoopActionsDuringDispatch() throws IOException {
        Files.writeString(_tempDir.resolve("a.txt"), "a\n");
        TreeViewPluginSession session = new TreeViewPluginSession(new TestPluginContext(_tempDir, _tempDir));

        TreeViewHandledInteractionResult result = session.interact("down", 30, 5, action -> {
            throw new AssertionError("handler should not run for no-op action");
        });

        assertTrue(result.interactionResult().commandResult().handled());
        assertFalse(result.actionHandlerResult().handled());
    }

    private record TestPluginContext(Path initialPath, Path currentPath) implements SwimPluginContext {
        @Override
        public Path getInitialPath() {
            return initialPath;
        }

        @Override
        public Path getCurrentPath() {
            return currentPath;
        }

        @Override
        public SwimHost getHost() {
            return new SwimHost() {
                @Override
                public void requestReload(Path path) {
                }

                @Override
                public void requestRebuildAndReload(Path path) {
                }

                @Override
                public void requestLoadPlugin(String pluginId, Path path) {
                }

                @Override
                public void requestExit() {
                }

                @Override
                public Path getBuildRoot() {
                    return initialPath;
                }
            };
        }
    }
}
