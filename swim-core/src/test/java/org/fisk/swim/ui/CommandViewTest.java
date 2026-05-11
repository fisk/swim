package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandViewTest {
    @TempDir
    Path tempDir;

    @Test
    void deactivateIsSafeWhenWindowHasBeenDisposed() {
        var view = new CommandView(Rect.create(0, 0, 10, 1));

        assertDoesNotThrow(view::deactivate);
    }

    @Test
    void searchNextAndPreviousTreatSearchStringLiterally() throws IOException {
        Path path = tempDir.resolve("search.txt");
        Files.writeString(path, "x [ y [ z");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var commandView = window.getCommandView();
            var cursor = window.getBufferContext().getBuffer().getCursor();

            commandView.activate("/");
            commandView.runSearch("[");
            commandView.deactivate();
            assertEquals(2, cursor.getPosition());

            assertDoesNotThrow(commandView::searchNext);
            assertEquals(6, cursor.getPosition());

            assertDoesNotThrow(commandView::searchPrevious);
            assertEquals(2, cursor.getPosition());
        }
    }

    @Test
    void deactivateKeepsFocusOnActivePanel() throws IOException {
        Path path = tempDir.resolve("panel-focus.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var panel = new TextPanelView(Rect.create(0, 0, 0, 0), "Nemo", "alpha");
            window.showPanel(panel);

            window.getCommandView().deactivate();

            assertSame(panel, HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void deactivateRestoresFocusToActiveSplitLeaf() throws IOException {
        Path path = tempDir.resolve("split-focus.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var activeSplit = window.splitActiveBufferHorizontally();
            window.getCommandView().activate(":");

            window.getCommandView().deactivate();

            assertSame(activeSplit, HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void commandMenuShowsMatchingCommandsForTypedPrefix() throws IOException {
        Path path = tempDir.resolve("command-prefix.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var commandView = harness.getWindow().getCommandView();
            commandView.activate(":");

            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('v'));

            var state = commandView.getMenuState();
            assertTrue(state.visible());
            assertEquals("v", state.prefix());
            assertEquals("vsplit", state.selectedMatch().primaryName());
            assertEquals(1, state.matches().size());
        }
    }

    @Test
    void commandMenuArrowsMoveSelectionAcrossMatches() throws IOException {
        Path path = tempDir.resolve("command-selection.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var commandView = harness.getWindow().getCommandView();
            commandView.activate(":");

            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('r'));
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.down());

            var state = commandView.getMenuState();
            assertEquals("reload", state.matches().get(0).primaryName());
            assertEquals("rebuild", state.selectedMatch().primaryName());
        }
    }

    @Test
    void tabCompletesSelectedCommandAndPreservesArguments() throws IOException {
        Path path = tempDir.resolve("command-complete.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var commandView = harness.getWindow().getCommandView();
            commandView.activate(":");

            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('f'));
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('o'));
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.tab());
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('l'));
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('e'));
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('f'));
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('t'));

            assertEquals("focus left", commandView.getCommandText());
        }
    }

    @Test
    void splitAndFocusCommandsManipulateActivePane() throws Exception {
        Path path = tempDir.resolve("split-commands.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var originalView = window.getBufferContext().getBufferView();

            invokeRunCommand(window.getCommandView(), "vsplit");
            var splitView = (View) window.getActiveView();

            assertEquals("{15, 2, 15, 4}", absoluteBounds(splitView).toString());

            invokeRunCommand(window.getCommandView(), "focus left");
            assertSame(originalView, window.getActiveView());

            invokeRunCommand(window.getCommandView(), "focus right");
            assertSame(splitView, window.getActiveView());
        }
    }

    @Test
    void closeCommandRefusesToCloseLastBufferView() throws Exception {
        Path path = tempDir.resolve("close-command.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var commandView = window.getCommandView();

            invokeRunCommand(commandView, "close");

            assertEquals("Cannot close the last buffer view", HeadlessWindowHarness.getField(commandView, "_message"));
            assertSame(window.getBufferContext().getBufferView(), window.getActiveView());
        }
    }

    @Test
    void onlyCommandKeepsSingleBufferWhenPanelIsActive() throws Exception {
        Path path = tempDir.resolve("only-command.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "vsplit");
            window.showTextPanel("Nemo", "alpha");

            invokeRunCommand(window.getCommandView(), "only");

            assertTrue(window.getActiveView() instanceof BufferView);
            assertEquals("{0, 2, 30, 4}", absoluteBounds((View) window.getActiveView()).toString());
            assertFalse(window.isShowingPanel());
        }
    }

    @Test
    void rebuildCommandRequestsHostRebuildAndReload() throws Exception {
        var host = new RecordingHost();
        var view = new CommandView(Rect.create(0, 0, 10, 1));
        SwimRuntime.setHost(host);
        invokeSwimRuntimeStatic("setCurrentPathSupplier", new Class<?>[] { java.util.function.Supplier.class }, (java.util.function.Supplier<Path>) () -> tempDir.resolve("build-target.txt"));
        try {
            invokeRunCommand(view, "rebuild");
        } finally {
            SwimRuntime.clear();
            invokeSwimRuntimeStatic("resetCurrentPathSupplier", new Class<?>[0]);
        }

        assertEquals(tempDir.resolve("build-target.txt"), host.rebuildPath);
    }

    private static void invokeRunCommand(CommandView view, String command) throws Exception {
        Method method = CommandView.class.getDeclaredMethod("runCommand", String.class);
        method.setAccessible(true);
        method.invoke(view, command);
    }

    private static void invokeSwimRuntimeStatic(String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = SwimRuntime.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private static Rect absoluteBounds(View view) {
        int x = view.getBounds().getPoint().getX();
        int y = view.getBounds().getPoint().getY();
        View parent = view.getParent();
        while (parent != null) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
            parent = parent.getParent();
        }
        return Rect.create(x, y, view.getBounds().getSize().getWidth(), view.getBounds().getSize().getHeight());
    }

    private static final class RecordingHost implements SwimHost {
        private Path rebuildPath;

        @Override
        public void requestReload(Path path) {
        }

        @Override
        public void requestRebuildAndReload(Path path) {
            rebuildPath = path;
        }

        @Override
        public void requestLoadPlugin(String pluginId, Path path) {
        }

        @Override
        public void requestExit() {
        }

        @Override
        public Path getBuildRoot() {
            return null;
        }
    }
}
