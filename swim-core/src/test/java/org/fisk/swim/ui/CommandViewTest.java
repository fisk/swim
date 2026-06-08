package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.debug.DebugLaunchRequest;
import org.fisk.swim.debug.DebugSnapshot;
import org.fisk.swim.debug.DebugSourceLocation;
import org.fisk.swim.debug.DebugState;
import org.fisk.swim.debug.DebuggerManager;
import org.fisk.swim.debug.DebuggerPanelView;
import org.fisk.swim.debug.DebuggerProvider;
import org.fisk.swim.debug.DebuggerProviderRegistry;
import org.fisk.swim.debug.DebuggerSession;
import org.fisk.swim.debug.DebugSessionListener;
import org.fisk.swim.copy.Copy;
import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailPluginRegistry;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.slack.FakeSlackClient;
import org.fisk.swim.slack.SlackPluginRegistry;
import org.fisk.swim.todo.TodoUiSupport;
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
    void searchNextAndPreviousUseRegexPatterns() throws IOException {
        Path path = tempDir.resolve("search.txt");
        Files.writeString(path, "x [ y [ z");

        try (var harness = HeadlessWindowHarness.create(path, 30, 8)) {
            var window = harness.getWindow();
            var commandView = window.getCommandView();
            var cursor = window.getBufferContext().getBuffer().getCursor();

            commandView.activate("/");
            commandView.runSearch("\\[");
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
            assertEquals("v", state.selectedMatch().primaryName());
            assertEquals(3, state.matches().size());
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
            assertTrue(state.matches().size() >= 2);
            assertEquals(state.matches().get(1).primaryName(), state.selectedMatch().primaryName());
            assertTrue(!state.matches().get(0).primaryName().equals(state.selectedMatch().primaryName()));
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
    void blankCommandMenuPrefersLastExecutedCommand() throws Exception {
        Path path = tempDir.resolve("command-last.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var commandView = harness.getWindow().getCommandView();

            invokeRunCommand(commandView, "focus left");
            commandView.activate(":");

            var state = commandView.getMenuState();
            assertTrue(state.visible());
            assertEquals("", state.prefix());
            assertEquals("focus left", state.selectedMatch().primaryName());
            assertEquals("last command", state.selectedMatch().description());

            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.tab());

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

            assertEquals("{15, 0, 15, 3}", absoluteBounds(splitView).toString());

            invokeRunCommand(window.getCommandView(), "focus left");
            assertSame(originalView, window.getActiveView());

            invokeRunCommand(window.getCommandView(), "focus right");
            assertSame(splitView, window.getActiveView());
        }
    }

    @Test
    void vsplitOnShellWorkspaceCreatesAnotherShellFrame() throws Exception {
        Path path = tempDir.resolve("shell-vsplit-command.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());

            invokeRunCommand(window.getCommandView(), "vsplit");

            assertTrue(window.getActiveView() instanceof ShellPanelView);
            assertEquals("{16, 0, 16, 2}", ((View) window.getActiveView()).getBounds().toString());
            assertEquals(2, leafViews(window).size());
            assertTrue(leafViews(window).stream().allMatch(view -> view instanceof ShellPanelView));
        }
    }

    @Test
    void vshellAndHshellCommandsOpenSplitShells() throws Exception {
        Path path = tempDir.resolve("shell-split-commands.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 32, 11)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "vshell");
            assertTrue(window.getActiveView() instanceof ShellPanelView);
            assertEquals("{16, 0, 16, 6}", ((View) window.getActiveView()).getBounds().toString());

            invokeRunCommand(window.getCommandView(), "close");
            invokeRunCommand(window.getCommandView(), "hshell");
            assertTrue(window.getActiveView() instanceof ShellPanelView);
            assertEquals("{0, 4, 32, 2}", ((View) window.getActiveView()).getBounds().toString());
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

            assertTrue(window.isShowingMailWorkspace());
            assertTrue(window.getActiveView() instanceof MailPanelView);
        } finally {
            MailPluginRegistry.clear();
            SwimRuntime.clear();
        }
    }

    @Test
    void slackCommandOpensSlackWorkspace() throws Exception {
        Path path = tempDir.resolve("slack-command.txt");
        Files.writeString(path, "abc");

        SwimRuntime.setHost(new RecordingHost());
        SlackPluginRegistry.register(new FakeSlackClient(tempDir.resolve(".swim/slack/workspaces.json")));
        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "slack");

            assertTrue(window.isShowingSlackWorkspace());
            var split = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            assertTrue(split.getFirstView() instanceof SlackPanelView);
        } finally {
            SlackPluginRegistry.clear();
            SwimRuntime.clear();
        }
    }

    @Test
    void todoCommandOpensTodoWorkspace() throws Exception {
        Path path = tempDir.resolve("todo-command.txt");
        Files.writeString(path, "abc");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        TodoUiSupport.shutdownInstance();
        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "todo");

            assertTrue(window.isShowingTodoWorkspace());
            assertTrue(window.getActiveView() instanceof TodoWorkspaceView);
        } finally {
            TodoUiSupport.shutdownInstance();
            System.setProperty("user.home", previousHome);
        }
    }

    @Test
    void treeCommandOpensTreePanel() throws Exception {
        Path path = tempDir.resolve("tree-command-left.txt");
        Path target = tempDir.resolve("tree-command-right.txt");
        Files.writeString(path, "left\npane");
        Files.writeString(target, "right\npane");
        RecordingHost host = new RecordingHost();
        host.panel = new FakeTreePanel(target);
        SwimRuntime.setHost(host);
        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "tree");

            assertEquals("swim-tree-view", host.pluginId);
            assertTrue(window.getPanelView() instanceof PluginPanelView);

            HeadlessWindowHarness.dispatch((PluginPanelView) window.getPanelView(), HeadlessWindowHarness.enter());

            assertEquals("right\npane", window.getBufferContext().getBuffer().getString());
            assertSame(window.getBufferContext().getBufferView(), window.getActiveView());
        } finally {
            SwimRuntime.clear();
        }
    }

    @Test
    void gitCommandOpensPluginWorkspace() throws Exception {
        Path path = tempDir.resolve("git-command.txt");
        Files.writeString(path, "abc");

        RecordingHost host = new RecordingHost();
        host.panel = new FakeGitPanel();
        SwimRuntime.setHost(host);
        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "git");

            assertEquals("swim-git", host.pluginId);
            assertTrue(window.getActiveView() instanceof PluginPanelView);
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("1:Git"));
        } finally {
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

    @Test
    void registersCommandShowsRegistersAndMacros() throws Exception {
        Path path = tempDir.resolve("registers.txt");
        Files.writeString(path, "abc");
        Copy.getInstance().clear();
        Copy.getInstance().setText("hello", false, 'a');
        Copy.getInstance().setMacro('q', List.of(RecordedKey.parseToken("x")));

        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            invokeRunCommand(harness.getWindow().getCommandView(), "registers");

            assertTrue(harness.getWindow().getPanelView() instanceof TextPanelView);
            String text = HeadlessWindowHarness.getField(harness.getWindow().getPanelView(), "_text", String.class);
            assertTrue(text.contains("\"a char hello"));
            assertTrue(text.contains("@q x"));
        } finally {
            Copy.getInstance().clear();
        }
    }

    @Test
    void marksCommandShowsConfiguredMarks() throws Exception {
        Path path = tempDir.resolve("marks.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            harness.getWindow().setMark('a');
            invokeRunCommand(harness.getWindow().getCommandView(), "marks");

            assertTrue(harness.getWindow().getPanelView() instanceof TextPanelView);
            String text = HeadlessWindowHarness.getField(harness.getWindow().getPanelView(), "_text", String.class);
            assertTrue(text.contains("a "));
            assertTrue(text.contains("marks.txt"));
        }
    }

    @Test
    void jumpsCommandShowsJumpList() throws Exception {
        Path path = tempDir.resolve("jumps.txt");
        Files.writeString(path, """
                one
                two
                """);

        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();
            window.performJump(() -> window.getBufferContext().getBuffer().getCursor().goEndOfBuffer());
            invokeRunCommand(window.getCommandView(), "jumps");

            assertTrue(window.getPanelView() instanceof TextPanelView);
            String text = HeadlessWindowHarness.getField(window.getPanelView(), "_text", String.class);
            assertTrue(text.contains("jumps.txt"));
        }
    }

    @Test
    void substituteCommandsRewriteCurrentLineAndWholeBuffer() throws Exception {
        Path path = tempDir.resolve("substitute.txt");
        Files.writeString(path, """
                alpha beta
                alpha gamma
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var window = harness.getWindow();
            invokeRunCommand(window.getCommandView(), "s/alpha/omega/");
            assertEquals("""
                    omega beta
                    alpha gamma
                    """, window.getBufferContext().getBuffer().getString());

            invokeRunCommand(window.getCommandView(), "%s/alpha/delta/g");
            assertEquals("""
                    omega beta
                    delta gamma
                    """, window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void rangedSubstituteOnlyRewritesSelectedLines() throws Exception {
        Path path = tempDir.resolve("ranged-substitute.txt");
        Files.writeString(path, """
                alpha
                alpha
                alpha
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "2,3s/alpha/beta/g");

            assertEquals("""
                    alpha
                    beta
                    beta
                    """, window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void globalDeleteRemovesMatchingLines() throws Exception {
        Path path = tempDir.resolve("global-delete.txt");
        Files.writeString(path, """
                keep
                remove this
                keep too
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "g/remove/d");

            assertEquals("""
                    keep
                    keep too
                    """, window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void writeReadAndNormalCommandsWork() throws Exception {
        Path path = tempDir.resolve("ex-commands.txt");
        Path output = tempDir.resolve("written.txt");
        Path read = tempDir.resolve("read.txt");
        Files.writeString(path, "one\ntwo\n");
        Files.writeString(read, "inserted\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "w " + output);
            assertEquals("one\ntwo\n", Files.readString(output));

            invokeRunCommand(window.getCommandView(), "read " + read);
            assertEquals("one\ninserted\ntwo\n", window.getBufferContext().getBuffer().getString());

            window.getBufferContext().getBuffer().getCursor().setPosition(0);
            invokeRunCommand(window.getCommandView(), "normal dd");
            assertEquals("inserted\ntwo\n", window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void lgrepBuildsLocationListAndNavigatesMatches() throws Exception {
        Path path = tempDir.resolve("lgrep.txt");
        Files.writeString(path, """
                alpha needle
                beta
                gamma needle
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "lgrep needle");
            assertTrue(window.getPanelView() instanceof ListView);

            invokeRunCommand(window.getCommandView(), "lnext");
            assertTrue(window.getBufferContext().getBuffer().getCurrentLineText().contains("gamma needle"));

            invokeRunCommand(window.getCommandView(), "lprev");
            assertTrue(window.getBufferContext().getBuffer().getCurrentLineText().contains("alpha needle"));
        }
    }

    @Test
    void multicursorCommandPlacesCursorsOnAllMatches() throws Exception {
        Path path = tempDir.resolve("multicursor.txt");
        Files.writeString(path, """
                alpha
                beta alpha
                alpha gamma
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var window = harness.getWindow();

            invokeRunCommand(window.getCommandView(), "multicursor alpha");

            assertEquals(3, window.getBufferContext().getBuffer().getCursors().size());
        }
    }

    @Test
    void debugProvidersCommandShowsRegisteredProviders() throws Exception {
        DebuggerProviderRegistry.register("fake", "test-debugger", new FakeDebuggerProvider());
        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("debug-providers.txt"), 50, 12)) {
            invokeRunCommand(harness.getWindow().getCommandView(), "debug providers");
            assertTrue(HeadlessWindowHarness.getField(harness.getWindow().getCommandView(), "_message", String.class)
                    .contains("fake"));
        } finally {
            DebuggerProviderRegistry.unregisterPlugin("test-debugger");
            DebuggerManager.closeCurrentSession();
        }
    }

    @Test
    void debugLaunchCommandOpensDebuggerPanel() throws Exception {
        Path path = tempDir.resolve("debug-command.txt");
        Files.writeString(path, "class Main {}\n");
        DebuggerProviderRegistry.register("fake", "test-debugger", new FakeDebuggerProvider());
        try (var harness = HeadlessWindowHarness.create(path, 50, 12)) {
            var window = harness.getWindow();
            invokeRunCommand(window.getCommandView(), "debug fake launch");
            assertTrue(window.getPanelView() instanceof DebuggerPanelView);
        } finally {
            DebuggerProviderRegistry.unregisterPlugin("test-debugger");
            DebuggerManager.closeCurrentSession();
        }
    }

    private static void invokeRunCommand(CommandView commandView, String command) throws Exception {
        Method method = CommandView.class.getDeclaredMethod("runCommand", String.class);
        method.setAccessible(true);
        method.invoke(commandView, command);
    }

    @SuppressWarnings("unchecked")
    private static List<View> leafViews(Window window) throws Exception {
        Method method = Window.class.getDeclaredMethod("getLeafViews");
        method.setAccessible(true);
        return (List<View>) method.invoke(window);
    }

    private static Rect absoluteBounds(View view) {
        return view.getBounds();
    }

    private static final class RecordingHost implements SwimHost {
        private String pluginId;
        private SwimPanel panel;

        @Override
        public void requestReload(Path path) {
        }

        @Override
        public void requestRebuildAndReload(Path path) {
        }

        @Override
        public void requestLoadPlugin(String pluginId, Path path) {
            this.pluginId = pluginId;
        }

        @Override
        public void requestExit() {
        }

        @Override
        public SwimPanel getPanel(String pluginId) {
            return panel;
        }

        @Override
        public Path getBuildRoot() {
            return Path.of("/tmp");
        }
    }

    private static final class FakeGitPanel implements SwimPanel {
        @Override
        public String getId() {
            return "swim-git";
        }

        @Override
        public String getTitle() {
            return "Git";
        }

        @Override
        public List<String> render(int width, int height) {
            return List.of("Git", "> v Staged (0)", "  v Unstaged (0)");
        }

        @Override
        public SwimPanelResult handleInput(String input, int width, int height) {
            return SwimPanelResult.success();
        }
    }

    private static final class FakeTreePanel implements SwimPanel {
        private final Path _openPath;

        private FakeTreePanel(Path openPath) {
            _openPath = openPath;
        }

        @Override
        public String getId() {
            return "swim-tree-view";
        }

        @Override
        public String getTitle() {
            return "Tree";
        }

        @Override
        public List<String> render(int width, int height) {
            return List.of("Tree", "> - " + _openPath.getFileName());
        }

        @Override
        public SwimPanelResult handleInput(String input, int width, int height) {
            return "enter".equals(input) ? SwimPanelResult.success(_openPath) : SwimPanelResult.success();
        }
    }

    private static final class FakeDebuggerProvider implements DebuggerProvider {
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
            return new DebuggerSession() {
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
                            List.of(), List.of(), -1, List.of(), -1, List.of());
                }

                @Override
                public void setListener(DebugSessionListener listener) {
                    listener.onSnapshotChanged(snapshot());
                }

                @Override
                public void resume() {
                }

                @Override
                public void stepOver() {
                }

                @Override
                public void stepInto() {
                }

                @Override
                public void stepOut() {
                }

                @Override
                public void stop() {
                }

                @Override
                public void toggleBreakpoint(DebugSourceLocation location) {
                }

                @Override
                public void selectThread(int threadIndex) {
                }

                @Override
                public void selectFrame(int frameIndex) {
                }

                @Override
                public void close() {
                }
            };
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
