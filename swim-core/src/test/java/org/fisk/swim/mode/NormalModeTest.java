package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void mPrefixSetsClassicMark() throws Exception {
        Path path = tempDir.resolve("classic-mark.txt");
        Files.writeString(path, "abc\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            window.getBufferContext().getBuffer().getCursor().setPosition(2);

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('m'), HeadlessWindowHarness.key('a'));
            window.getBufferContext().getBuffer().getCursor().setPosition(0);
            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('`'), HeadlessWindowHarness.key('a'));

            assertEquals(2, window.getBufferContext().getBuffer().getCursor().getPosition());
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
    void pageDownAndPageUpScrollByNearlyAFullPage() throws Exception {
        Path path = tempDir.resolve("page-scroll.txt");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            text.append("line ").append(i).append('\n');
        }
        Files.writeString(path, text.toString());

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var window = harness.getWindow();
            var view = window.getBufferContext().getBufferView();
            int start = view.getStartLine();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.pageDown());
            int afterDown = view.getStartLine();
            assertTrue(afterDown >= start + 5);

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.pageUp());
            assertTrue(view.getStartLine() < afterDown);
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
    void escapeDoesNotStartNemoChat() throws Exception {
        Path path = tempDir.resolve("escape-does-not-open-nemo.txt");
        Files.writeString(path, "abc");

        String originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        Files.createDirectories(tempDir.resolve(".swim/nemo"));
        Files.writeString(tempDir.resolve(".swim/nemo/nemo.conf"), "");
        resetNemoClientForTests();
        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.escape());

            assertTrue(window.getPanelView() == null);
        } finally {
            resetNemoClientForTests();
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void greaterThanStartsIndentOperator() throws Exception {
        Path path = tempDir.resolve("greater-than-indents.txt");
        Files.writeString(path, "abc\n");

        try (var harness = HeadlessWindowHarness.create(path, 40, 10)) {
            var window = harness.getWindow();

            assertEquals(Response.MAYBE, window.getNormalMode()
                    .processEvent(new KeyStrokes(List.of(HeadlessWindowHarness.key('>')))));

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('>'), HeadlessWindowHarness.key('>'));

            assertEquals("    abc\n", window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void eMovesToEndOfWord() throws Exception {
        Path path = tempDir.resolve("e-word-end.txt");
        Files.writeString(path, "alpha beta\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('e'));

            assertEquals(4, window.getBufferContext().getBuffer().getCursor().getPosition());
        }
    }

    @Test
    void sDeletesCharacterAndEntersInputMode() throws Exception {
        Path path = tempDir.resolve("s-substitute-char.txt");
        Files.writeString(path, "abc\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            window.getBufferContext().getBuffer().getCursor().setPosition(1);

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('s'));

            assertEquals("ac\n", window.getBufferContext().getBuffer().getString());
            assertSame(window.getInputMode(), window.getCurrentMode());
        }
    }

    @Test
    void uppercaseMMovesToMiddleOfScreen() throws Exception {
        Path path = tempDir.resolve("middle-motion.txt");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            text.append("line ").append(i).append('\n');
        }
        Files.writeString(path, text.toString());

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            window.getBufferContext().getBufferView().scrollPageDown();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('M'));

            assertTrue(window.getBufferContext().getBuffer().getCurrentLineText().contains("line "));
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

    @Test
    void operatorDeleteToEndOfLineWorks() throws Exception {
        Path path = tempDir.resolve("delete-to-end.txt");
        Files.writeString(path, "alpha beta\ngamma\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            window.getBufferContext().getBuffer().getCursor().setPosition("alpha ".length());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('d'), HeadlessWindowHarness.key('$'));

            assertEquals("alpha \ngamma\n", window.getBufferContext().getBuffer().getString());
            assertEquals("beta", Copy.getInstance().getText());
        }
    }

    @Test
    void normalModeCreatesFoldsWithMotionAndLineCount() throws Exception {
        Path path = tempDir.resolve("normal-fold-create.txt");
        Files.writeString(path, """
                one
                two
                three
                four
                five
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            buffer.getCursor().setPosition(buffer.getString().indexOf("two"));

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('f'), HeadlessWindowHarness.key('j'));

            assertEquals(1, buffer.getFolds().size());
            assertEquals(buffer.getString().indexOf("two"), buffer.getFolds().getFirst().start());

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('E'));
            buffer.getCursor().setPosition(0);

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('F'));

            assertEquals(1, buffer.getFolds().size());
            assertEquals(0, buffer.getFolds().getFirst().start());
            assertEquals(buffer.getString().indexOf("two"), buffer.getFolds().getFirst().end());

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('E'));
            buffer.getCursor().setPosition(0);

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.key('3'), HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('F'));

            assertEquals(1, buffer.getFolds().size());
            assertEquals(0, buffer.getFolds().getFirst().start());
            assertEquals(buffer.getString().indexOf("four"), buffer.getFolds().getFirst().end());
        }
    }

    @Test
    void normalModeNavigatesAndDeletesFolds() throws Exception {
        Path path = tempDir.resolve("normal-fold-navigation.txt");
        Files.writeString(path, """
                one
                two
                three
                four
                five
                six
                """);

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            int two = buffer.getString().indexOf("two");
            int four = buffer.getString().indexOf("four");
            int six = buffer.getString().indexOf("six");
            assertTrue(buffer.createFold(two, four));
            assertTrue(buffer.createFold(four, six));

            buffer.getCursor().setPosition(0);
            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('j'));
            assertEquals(two, buffer.getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('j'));
            assertEquals(four, buffer.getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('k'));
            assertEquals(two, buffer.getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('d'));
            assertEquals(1, buffer.getFolds().size());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('z'), HeadlessWindowHarness.key('E'));
            assertTrue(buffer.getFolds().isEmpty());
        }
    }

    @Test
    void classicWordMotionsAndCountsWork() throws Exception {
        Path path = tempDir.resolve("word-motions.txt");
        Files.writeString(path, "alpha beta gamma\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('2'), HeadlessWindowHarness.key('w'));
            assertEquals("alpha beta ".length(), window.getBufferContext().getBuffer().getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('b'));
            assertEquals("alpha ".length(), window.getBufferContext().getBuffer().getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('e'));
            assertEquals("alpha beta".length() - 1, window.getBufferContext().getBuffer().getCursor().getPosition());
        }
    }

    @Test
    void indentOutdentAndCaseOperatorsWork() throws Exception {
        Path path = tempDir.resolve("operators.txt");
        Files.writeString(path, "alpha\n  beta\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('>'), HeadlessWindowHarness.key('>'));
            assertEquals("    alpha\n  beta\n", window.getBufferContext().getBuffer().getString());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('<'), HeadlessWindowHarness.key('<'));
            assertEquals("alpha\n  beta\n", window.getBufferContext().getBuffer().getString());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('U'),
                    HeadlessWindowHarness.key('i'), HeadlessWindowHarness.key('w'));
            assertEquals("ALPHA\n  beta\n", window.getBufferContext().getBuffer().getString());
        }
    }

    @Test
    void visualBlockYankPastesRectangularText() throws Exception {
        Path path = tempDir.resolve("visual-block-paste.txt");
        Files.writeString(path, "ab12\ncd34\n");
        Copy.getInstance().clear();

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            window.switchToMode(window.getVisualBlockMode());
            buffer.getCursor().setPosition(2);
            buffer.getCursors().get(1).setPosition(8);

            HeadlessWindowHarness.dispatch(window.getVisualBlockMode(), HeadlessWindowHarness.key('y'));

            assertTrue(Copy.getInstance().isBlock(null));
            buffer.getCursor().setPosition(0);
            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('P'));

            assertEquals("12ab12\n34cd34\n", buffer.getString());
        }
    }

    @Test
    void replaceModeOverwritesUntilEscape() throws Exception {
        Path path = tempDir.resolve("replace-mode.txt");
        Files.writeString(path, "abcdef\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            window.getBufferContext().getBuffer().getCursor().setPosition(2);

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('R'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('X'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('Y'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.escape());

            assertEquals("abXYef\n", window.getBufferContext().getBuffer().getString());
            assertSame(window.getNormalMode(), window.getCurrentMode());
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
