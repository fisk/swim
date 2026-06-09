package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.HelpWorkspaceView;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginKeyBindingTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        SwimPluginKeyBindingRegistry.clearForTests();
        SwimRuntime.clear();
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
    }

    @Test
    void preloadedPluginKeyBindingLazyLoadsPluginBeforeExecutingCommand() throws Exception {
        Path file = tempDir.resolve("plugin-keybinding.txt");
        Files.writeString(file, "notes\n");
        SwimPluginKeyBindingRegistry.register("lazy-plugin",
                new SwimPluginKeyBinding("<SPACE> x", "Plugin", "lazy help", "help", "help"));
        RecordingHost host = new RecordingHost(tempDir);
        SwimRuntime.setHost(host);

        try (var harness = HeadlessWindowHarness.create(file, 60, 12)) {
            Window window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key('x'));

            assertEquals(List.of("lazy-plugin"), host.loadedPluginIds);
            assertEquals(file.toAbsolutePath(), host.loadedPaths.getFirst().toAbsolutePath());
            assertInstanceOf(HelpWorkspaceView.class, window.getActiveView());
        }
    }

    private static final class RecordingHost implements SwimHost {
        private final Path buildRoot;
        private final List<String> loadedPluginIds = new ArrayList<>();
        private final List<Path> loadedPaths = new ArrayList<>();

        private RecordingHost(Path buildRoot) {
            this.buildRoot = buildRoot;
        }

        @Override
        public void requestReload(Path path) {
        }

        @Override
        public void requestRebuildAndReload(Path path) {
        }

        @Override
        public void requestLoadPlugin(String pluginId, Path path) {
            loadedPluginIds.add(pluginId);
            loadedPaths.add(path);
        }

        @Override
        public void requestExit() {
        }

        @Override
        public Path getBuildRoot() {
            return buildRoot;
        }
    }
}
