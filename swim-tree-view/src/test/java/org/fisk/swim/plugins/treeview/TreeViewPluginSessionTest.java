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

class TreeViewPluginSessionTest {
    @TempDir
    Path _tempDir;

    @Test
    void usesInitialPathAsProjectRootAndSelectsCurrentFile() throws IOException {
        Path srcDir = Files.createDirectories(_tempDir.resolve("src"));
        Path mainFile = Files.writeString(srcDir.resolve("Main.java"), "class Main {}\n");
        TreeViewPluginSession session = new TreeViewPluginSession(new TestPluginContext(_tempDir, mainFile));

        assertEquals(_tempDir, session.getProjectRoot());
        assertEquals(mainFile, session.snapshot(30, 5).selectedPath());

        AtomicReference<Path> openedPath = new AtomicReference<>();
        session.setFileOpener(openedPath::set);
        assertTrue(session.handleInput("enter").handled());
        assertEquals(mainFile, openedPath.get());
        assertEquals(mainFile, session.snapshot(30, 5).selectedPath());
    }

    @Test
    void ignoresPathsOutsideProjectRoot() throws IOException {
        Path mainFile = Files.writeString(_tempDir.resolve("Main.java"), "class Main {}\n");
        TreeViewPluginSession session = new TreeViewPluginSession(new TestPluginContext(_tempDir, mainFile));

        assertFalse(session.syncToPath(_tempDir.getParent().resolve("elsewhere.txt")));
        assertEquals(mainFile, session.snapshot(30, 5).selectedPath());
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
