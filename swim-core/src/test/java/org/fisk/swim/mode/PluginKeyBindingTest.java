package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginKeyBindingDescriptor;
import org.fisk.swim.api.SwimPluginKeyBindingRegistry;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.Response;
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

    @Test
    void exactPluginKeyBindingWinsOverLongerPluginPrefix() throws Exception {
        Path file = tempDir.resolve("plugin-keybinding-exact.txt");
        Files.writeString(file, "notes\n");
        AtomicInteger exactCalls = new AtomicInteger();
        AtomicInteger longerCalls = new AtomicInteger();
        SwimPluginKeyBindingRegistry.register("lazy-plugin",
                new SwimPluginKeyBinding("<SPACE> , o", "Plugin", "organize imports", "organize",
                        exactCalls::incrementAndGet));
        SwimPluginKeyBindingRegistry.register("lazy-plugin",
                new SwimPluginKeyBinding("<SPACE> , o x", "Plugin", "longer command", "longer",
                        longerCalls::incrementAndGet));
        SwimRuntime.setHost(new RecordingHost(tempDir));

        try (var harness = HeadlessWindowHarness.create(file, 60, 12)) {
            Window window = harness.getWindow();

            assertEquals(Response.MAYBE, HeadlessWindowHarness.dispatchIncrementally(window.getNormalMode(),
                    HeadlessWindowHarness.key(' ')));
            assertEquals(Response.MAYBE, HeadlessWindowHarness.dispatchIncrementally(window.getNormalMode(),
                    HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key(',')));
            assertEquals(Response.YES, HeadlessWindowHarness.dispatchIncrementally(window.getNormalMode(),
                    HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key(','),
                    HeadlessWindowHarness.key('o')));

            assertEquals(1, exactCalls.get());
            assertEquals(0, longerCalls.get());
        }
    }

    @Test
    void reportsPluginBindingThatExtendsExistingNormalModeBinding() {
        var conflicts = NormalMode.findPluginKeyBindingConflicts(
                List.of(binding("java-lsp", "<SPACE> l o")),
                List.of(KeyBindingHint.of("<SPACE> l", "Editing", "indent line")));

        assertEquals(List.of(new NormalMode.KeyBindingConflict(
                "java-lsp",
                "<SPACE> l o",
                "<SPACE> l",
                "indent line")), conflicts);
    }

    @Test
    void acceptsPluginBindingWithDistinctLeaderPrefix() {
        var conflicts = NormalMode.findPluginKeyBindingConflicts(
                List.of(binding("java-lsp", "<SPACE> , o")),
                List.of(KeyBindingHint.of("<SPACE> l", "Editing", "indent line")));

        assertEquals(List.of(), conflicts);
    }

    private static SwimPluginKeyBindingDescriptor binding(String pluginId, String key) {
        return new SwimPluginKeyBindingDescriptor(
                pluginId,
                key,
                "LSP",
                "organize imports",
                "lsp-organize-imports",
                "",
                () -> true,
                () -> {});
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
