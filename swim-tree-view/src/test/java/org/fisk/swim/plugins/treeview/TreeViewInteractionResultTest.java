package org.fisk.swim.plugins.treeview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPluginContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreeViewInteractionResultTest {
    @TempDir
    Path _tempDir;

    @Test
    void combinesCommandHandlingWithUpdatedSnapshot() throws IOException {
        Files.writeString(_tempDir.resolve("a.txt"), "a\n");
        Files.writeString(_tempDir.resolve("b.txt"), "b\n");
        TreeViewPluginSession session = new TreeViewPluginSession(new TestPluginContext(_tempDir, _tempDir));

        TreeViewInteractionResult result = session.interact("down", 24, 4);

        assertTrue(result.commandResult().handled());
        assertEquals(TreeViewActionType.NONE, result.commandResult().action().type());
        assertTrue(result.snapshot().lines().stream().anyMatch(line -> line.contains("a.txt") || line.contains("b.txt")));
        assertTrue(result.snapshot().selectedPath().startsWith(_tempDir));
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
