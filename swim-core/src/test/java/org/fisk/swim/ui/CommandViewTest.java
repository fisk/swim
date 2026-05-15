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
import java.util.List;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailPluginRegistry;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.ui.MailPanelView;
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

            assertEquals("{15, 0, 15, 4}", absoluteBounds(splitView).toString());

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
    void mailCommandOpensMailPanel() throws Exception {
        Path path = tempDir.resolve("mail-command.txt");
        Files.writeString(path, "abc");

        SwimRuntime.setHost(new RecordingHost());
        MailPluginRegistry.register(new FakeMailClient(tempDir.resolve(".swim/email")));
        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "mail");

            assertTrue(window.getPanelView() instanceof MailPanelView);
        } finally {
            MailPluginRegistry.clear();
            SwimRuntime.clear();
        }
    }

    @Test
    void grepCommandOpensProjectSearchPanelWithQuery() throws Exception {
        Path root = tempDir.resolve("search-command-workspace");
        Files.createDirectories(root.resolve(".git"));
        Path path = root.resolve("Main.java");
        Files.writeString(path, "needle\n");

        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "grep needle");

            assertTrue(window.getPanelView() instanceof ProjectSearchPanelView);
            assertEquals("needle", ((ProjectSearchPanelView) window.getPanelView()).getQuery());
        }
    }

    private static void invokeRunCommand(CommandView commandView, String command) throws Exception {
        Method method = CommandView.class.getDeclaredMethod("runCommand", String.class);
        method.setAccessible(true);
        method.invoke(commandView, command);
    }

    private static Rect absoluteBounds(View view) {
        return view.getBounds();
    }

    private static final class RecordingHost implements SwimHost {
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
            return Path.of("/tmp");
        }
    }

    private static final class FakeMailClient implements MailClient {
        private final Path _dataPath;

        private FakeMailClient(Path dataPath) {
            _dataPath = dataPath;
        }

        @Override
        public MailSnapshot snapshot() {
            return new MailSnapshot(
                    List.of(),
                    List.of(new MailThreadSummary(1L, "account", "Subject", "sender@example.com",
                            "Snippet", "2026-05-13T00:00:00Z", true, 1, List.of())),
                    "status");
        }

        @Override
        public MailMessageDetail loadMessage(long threadId) {
            return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com", "dest@example.com",
                    "2026-05-13T00:00:00Z", "Body", List.of());
        }

        @Override
        public void refresh() {
        }

        @Override
        public Path getDataPath() {
            return _dataPath;
        }
    }
}
