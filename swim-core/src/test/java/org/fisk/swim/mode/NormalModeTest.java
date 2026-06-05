package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.copy.Copy;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.MailPanelView;
import org.fisk.swim.ui.ProjectSearchPanelView;
import org.fisk.swim.ui.ShellPanelView;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailPluginRegistry;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.slack.FakeSlackClient;
import org.fisk.swim.slack.SlackPluginRegistry;
import org.fisk.swim.ui.SlackPanelView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NormalModeTest {
    @TempDir
    Path tempDir;

    @Test
    void quotePrefixSelectsRegisterWithoutEnteringInsertMode() throws Exception {
        Path path = tempDir.resolve("register-prefix.txt");
        Files.writeString(path, "abc\n");

        Copy.getInstance().clear();
        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            Response response = window.getNormalMode()
                    .processEvent(new KeyStrokes(List.of(HeadlessWindowHarness.key('"'), HeadlessWindowHarness.key('a'))));
            assertEquals(Response.YES, response);
            window.getNormalMode().respond();
            assertEquals("Using register a", HeadlessWindowHarness.getField(window.getCommandView(), "_message", String.class));
        }
    }

    @Test
    void qaStartsMacroRecordingWithoutEnteringInsertMode() throws Exception {
        Path path = tempDir.resolve("macro-prefix.txt");
        Files.writeString(path, "abc\n");

        Copy.getInstance().clear();
        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            Response response = window.getNormalMode()
                    .processEvent(new KeyStrokes(List.of(HeadlessWindowHarness.key('q'), HeadlessWindowHarness.key('a'))));
            assertEquals(Response.YES, response);
            window.getNormalMode().respond();
            assertTrue(window.isRecordingMacro());
        }
    }

    @Test
    void gnAddsAnotherCursorForCurrentWord() throws Exception {
        Path path = tempDir.resolve("multicursor-prefix.txt");
        Files.writeString(path, """
                alpha
                beta alpha
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('n'));

            assertEquals(2, window.getBufferContext().getBuffer().getCursors().size());
        }
    }

    @Test
    void gCClearsAdditionalCursors() throws Exception {
        Path path = tempDir.resolve("multicursor-clear-prefix.txt");
        Files.writeString(path, """
                alpha
                beta alpha
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('n'));

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('C'));

            assertEquals(1, window.getBufferContext().getBuffer().getCursors().size());
        }
    }

    @Test
    void textObjectDeleteInsideParensWorks() throws Exception {
        Path path = tempDir.resolve("text-object.txt");
        Files.writeString(path, "call(alpha, beta)\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            window.getBufferContext().getBuffer().getCursor().setPosition("call(".length());

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('d'),
                    HeadlessWindowHarness.key('i'),
                    HeadlessWindowHarness.key('('));

            assertEquals("call()\n", window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void bangStartsNemoChat() throws Exception {
        Path path = tempDir.resolve("bang-opens-nemo.txt");
        Files.writeString(path, "abc");

        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        Files.createDirectories(tempDir.resolve(".swim/nemo"));
        Files.writeString(tempDir.resolve(".swim/nemo/nemo.conf"), "");
        resetNemoClientForTests();
        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('!'));

            assertTrue(window.getPanelView() instanceof ChatPanelView);
        } finally {
            resetNemoClientForTests();
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void greaterThanStartsShellPanel() throws Exception {
        Path path = tempDir.resolve("greater-than-opens-shell.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('>'));

            assertTrue(window.getPanelView() instanceof ShellPanelView);
        }
    }

    @Test
    void eStartsMailPanel() throws Exception {
        Path path = tempDir.resolve("mail-opens-panel.txt");
        Files.writeString(path, "abc");

        RecordingHost host = new RecordingHost();
        SwimRuntime.setHost(host);
        MailPluginRegistry.register(new FakeMailClient(tempDir.resolve(".swim/email")));
        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('e'));

            assertTrue(window.isShowingMailWorkspace());
            assertTrue(window.getActiveView() instanceof MailPanelView);
            assertTrue(host.pluginId == null || "swim-email".equals(host.pluginId));
        } finally {
            MailPluginRegistry.clear();
            SwimRuntime.clear();
        }
    }

    @Test
    void sStartsSlackWorkspace() throws Exception {
        Path path = tempDir.resolve("slack-opens-workspace.txt");
        Files.writeString(path, "abc");

        RecordingHost host = new RecordingHost();
        SwimRuntime.setHost(host);
        SlackPluginRegistry.register(new FakeSlackClient(tempDir.resolve(".swim/slack/workspaces.json")));
        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('s'));

            assertTrue(window.isShowingSlackWorkspace());
            assertTrue(((org.fisk.swim.ui.SplitView) HeadlessWindowHarness.getField(window, "_workspaceView")).getFirstView()
                    instanceof SlackPanelView);
            assertTrue(host.pluginId == null || "swim-slack".equals(host.pluginId));
        } finally {
            SlackPluginRegistry.clear();
            SwimRuntime.clear();
        }
    }

    @Test
    void uppercaseMStartsProjectSearchPanel() throws Exception {
        Path root = tempDir.resolve("search-workspace");
        Files.createDirectories(root.resolve(".git"));
        Path path = root.resolve("Main.java");
        Files.writeString(path, "class Main {}\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('M'));

            assertTrue(window.getPanelView() instanceof ProjectSearchPanelView);
        }
    }

    @Test
    void gwStartsFancyJumpPrefixHandling() throws Exception {
        Path path = tempDir.resolve("fancy-jump.txt");
        Files.writeString(path, "alpha beta gamma\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            Response response = window.getNormalMode()
                    .processEvent(new KeyStrokes(List.of(HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('w'))));

            assertEquals(Response.MAYBE, response);
        }
    }

    @Test
    void gcStartsFancyCharacterJumpPrefixHandling() throws Exception {
        Path path = tempDir.resolve("fancy-char-jump.txt");
        Files.writeString(path, "alpha beta gamma\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            Response response = window.getNormalMode()
                    .processEvent(new KeyStrokes(List.of(HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('c'))));

            assertEquals(Response.MAYBE, response);
        }
    }

    private static void resetNemoClientForTests() throws Exception {
        Class<?> nemoClientClass = Class.forName("org.fisk.swim.nemo.NemoClient");
        Object instance = nemoClientClass.getMethod("getInstance").invoke(null);
        Method reset = nemoClientClass.getDeclaredMethod("resetForTests");
        reset.setAccessible(true);
        reset.invoke(instance);
    }

    private static final class RecordingHost implements SwimHost {
        private String pluginId;

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
                            "Snippet", "2026-05-13T00:00:00Z", true, 1, List.of("tag"))),
                    "status");
        }

        @Override
        public MailMessageDetail loadMessage(long threadId) {
            return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com", "dest@example.com",
                    "2026-05-13T00:00:00Z", "Body", List.of("tag"));
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
