package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.EventThread;
import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.session.SwimServerSessions;
import org.fisk.swim.slack.FakeSlackClient;
import org.fisk.swim.terminal.TerminalEmulator;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.todo.TodoSnapshot;
import org.fisk.swim.todo.TodoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.Screen.RefreshType;

class WindowTest {
    @TempDir
    Path tempDir;

    @Test
    void noArgumentLaunchShowsReadOnlyWelcomeBuffer() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        try {
            Window.createInstance(List.of());
            var window = Window.getInstance();

            assertTrue(window.getBufferContext().getBuffer().getString().contains(":help"));
            assertTrue(window.getBufferContext().getBuffer().getString().contains("swim file.txt other.txt"));
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());
            String before = window.getBufferContext().getBuffer().getString();

            window.getBufferContext().getBuffer().insert("x");

            assertEquals(before, window.getBufferContext().getBuffer().getString());
        } finally {
            if (Window.getInstance() != null) {
                Window.getInstance().dispose();
            }
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void noArgumentLaunchRendersWelcomePageArt() throws Exception {
        var terminal = TerminalContextTestSupport.install(80, 24);
        try {
            Window.createInstance(List.of());

            Window.getInstance().update(true);

            String rendered = String.join("\n", terminal.drawCalls().stream()
                    .map(org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall::text)
                    .toList());
            assertTrue(rendered.contains("\\_/\\_/"));
            assertTrue(rendered.contains(WelcomePage.HELP_TEXT));
            assertTrue(rendered.contains("o     o     o"));
            assertTrue(rendered.contains("<=<"));
            assertEquals(List.of(WelcomePage.DISPLAY_NAME), tabLabels(Window.getInstance()));
        } finally {
            if (Window.getInstance() != null) {
                Window.getInstance().dispose();
            }
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void multiFileLaunchOpensFirstFileAndQueuesRemainingBuffers() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        Path first = writeFile("launch-first.txt", "first");
        Path second = writeFile("launch-second.txt", "second");
        try {
            Window.createInstance(List.of(first, second));
            var window = Window.getInstance();

            assertEquals(first.toAbsolutePath(), window.getBufferContext().getBuffer().getPath());
            assertEquals(List.of(first.toAbsolutePath(), second.toAbsolutePath()),
                    window.openBuffers().stream().map(Window.OpenBufferEntry::path).toList());

            assertTrue(window.switchNextBuffer());

            assertEquals(second.toAbsolutePath(), window.getBufferContext().getBuffer().getPath());
        } finally {
            if (Window.getInstance() != null) {
                Window.getInstance().dispose();
            }
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void helpWorkspaceShowsChaptersAndReturnsToPreviousWorkspace() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("help-workspace.txt", "abc"), 70, 16)) {
            var window = harness.getWindow();
            var originalView = window.getActiveView();

            assertTrue(window.showHelpWorkspace());
            var help = assertInstanceOf(HelpWorkspaceView.class, window.getActiveView());

            assertEquals("start", help.selectedChapterId());
            assertTrue(help.articleText().contains("Normal mode and Insert mode"));

            Rect helpBounds = absoluteScreenBounds(help);
            HeadlessWindowHarness.dispatch(help,
                    new MouseAction(MouseActionType.CLICK_DOWN, 1,
                            new TerminalPosition(helpBounds.getPoint().getX() + 2, helpBounds.getPoint().getY() + 4)));
            assertEquals("start", help.selectedChapterId());
            assertTrue(help.articleStartLine() > 0);

            int sectionStart = help.articleStartLine();
            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.key('k'));
            assertEquals(sectionStart - 1, help.articleStartLine());

            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.key('j'));
            assertEquals(sectionStart, help.articleStartLine());

            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.key('G'));
            assertTrue(help.articleStartLine() > sectionStart);

            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('g'));
            assertEquals(0, help.articleStartLine());

            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.key(']'));
            assertEquals("movement", help.selectedChapterId());
            assertEquals(0, help.articleStartLine());

            HeadlessWindowHarness.dispatch(help, new com.googlecode.lanterna.input.KeyStroke(com.googlecode.lanterna.input.KeyType.PageDown));
            assertTrue(help.articleStartLine() > 0);

            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.key('q'));
            assertSame(originalView, window.getActiveView());
            assertFalse(tabLabels(window).contains("help"));
        }
    }

    @Test
    void escapeClosesHelpWorkspaceTab() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("help-escape.txt", "abc"), 70, 16)) {
            var window = harness.getWindow();
            var originalView = window.getActiveView();

            assertTrue(window.showHelpWorkspace());
            assertTrue(tabLabels(window).contains("help"));
            var help = assertInstanceOf(HelpWorkspaceView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(help, HeadlessWindowHarness.escape());

            assertSame(originalView, window.getActiveView());
            assertFalse(tabLabels(window).contains("help"));
        }
    }

    @Test
    void closeActiveViewRefusesToCloseLastBuffer() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var originalView = window.getBufferContext().getBufferView();

            assertFalse(window.closeActiveView());
            assertSame(originalView, window.getActiveView());
            assertEquals("{0, 2, 24, 7}", absoluteBounds(originalView).toString());
        }
    }

    @Test
    void modeLineRendersAboveTabBar() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        try {
            Window.createInstance(writeFile("layout.txt", "abc"));
            var window = Window.getInstance();

            assertEquals(13, window.getModeLineView().getBounds().getPoint().getY());
            assertEquals(14, window.getTabBarView().getBounds().getPoint().getY());
            assertEquals(15, window.getCommandView().getBounds().getPoint().getY());

            invoke(window, "applyLayout", new Class<?>[] { Size.class }, Size.create(60, 12));

            assertEquals(9, window.getModeLineView().getBounds().getPoint().getY());
            assertEquals(10, window.getTabBarView().getBounds().getPoint().getY());
            assertEquals(11, window.getCommandView().getBounds().getPoint().getY());
        } finally {
            if (Window.getInstance() != null) {
                Window.getInstance().dispose();
            }
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void commandActivationUpdatesTopMenuContext() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            window.getCommandView().activate(":");

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("command line active"));
            assertTrue(window.getKeyMenuView().bodyText().contains("Tab complete"));
            assertTrue(window.getCommandMenuView().getState().visible());
            assertEquals("q", window.getCommandMenuView().getState().selectedMatch().primaryName());
        }
    }

    @Test
    void narrowWindowKeepsTopMenuToSingleBodyRow() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("narrow-menu.txt", "abc"), 18, 11)) {
            var window = harness.getWindow();
            window.getKeyMenuView().setModeName("NORMAL");
            window.getKeyMenuView().setBufferFocused(true);
            window.getKeyMenuView().setFocusContext(KeyMenuView.FocusContext.BUFFER);
            window.getKeyMenuView().observe(HeadlessWindowHarness.key('g'));

            invoke(window, "applyLayout", new Class<?>[] { Size.class }, Size.create(18, 11));

            assertEquals(2, window.getKeyMenuView().getBounds().getSize().getHeight());
            assertEquals(window.getKeyMenuView().getBounds().getSize().getHeight(),
                    window.getBufferContext().getBufferView().getBounds().getPoint().getY());
        }
    }

    @Test
    void topMenuHeightMismatchRequestsRelayout() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("prefix-dropdown.txt", "abc"), 80, 12)) {
            var window = harness.getWindow();

            window.getKeyMenuView().setBounds(Rect.create(0, 0, 80, 1));

            assertTrue((Boolean) invoke(window, "keyMenuNeedsRelayout", new Class<?>[] { Size.class }, Size.create(80, 12)));
        }
    }

    @Test
    void terminalResizeRelayoutsAndRedrawsEvenWhenViewTreeIsClean() throws Exception {
        var terminalSize = new AtomicReference<>(new TerminalSize(40, 12));
        var installed = TerminalContextTestSupport.install(40, 12, null, terminalSize::get);
        try (var harness = HeadlessWindowHarness.create(writeFile("resize-redraw.txt", "abc"), 40, 12)) {
            var window = harness.getWindow();
            window.update(true);
            int clearCalls = installed.clearCalls().get();
            installed.drawCalls().clear();
            installed.refreshCalls().clear();

            terminalSize.set(new TerminalSize(66, 18));
            window.update(false);

            assertEquals(66, window.getRootView().getBounds().getSize().getWidth());
            assertEquals(18, window.getRootView().getBounds().getSize().getHeight());
            assertEquals(2, window.getBufferContext().getBufferView().getBounds().getPoint().getY());
            assertFalse(installed.drawCalls().isEmpty());
            assertEquals(List.of(RefreshType.COMPLETE), installed.refreshCalls());
            assertEquals(clearCalls + 1, installed.clearCalls().get());
        } finally {
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void mailComposeTabCanMoveFocusIntoEditableBodyBuffer() throws Exception {
        Path path = tempDir.resolve("mail-compose-focus.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            invoke(window, "initializeWorkspaceHistory", new Class<?>[0]);
            assertTrue(window.showMailWorkspace(new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            }));

            var mailView = assertInstanceOf(MailPanelView.class, ((SplitView) HeadlessWindowHarness.getField(window, "_workspaceView")).getFirstView());
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('c'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.tab());
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.tab());
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.tab());
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.tab());

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertFalse(window.getBufferContext().getBuffer().isReadOnly());
            assertSame(window.getInputMode(), window.getCurrentMode());
        }
    }

    @Test
    void mailWorkspacePlacesMessageViewerBelowMailPanel() throws Exception {
        Path path = tempDir.resolve("mail-layout.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            assertTrue(window.showMailWorkspace(new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            }));

            var split = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            assertEquals(SplitView.Orientation.VERTICAL, split.getOrientation());
            assertInstanceOf(MailPanelView.class, split.getFirstView());
            assertInstanceOf(BufferView.class, split.getSecondView());
        }
    }

    @Test
    void mailWorkspaceOpensNewTabInsteadOfReusingExistingMailWorkspace() throws Exception {
        Path path = tempDir.resolve("mail-new-tab.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            invoke(window, "initializeWorkspaceHistory", new Class<?>[0]);
            MailClient client = new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(new org.fisk.swim.mail.MailAccountSummary("work", "Work", "IMAP", 2, 1, "", "")),
                            java.util.List.of(
                                    new MailThreadSummary(1L, "work", "Subject 1", "sender@example.com",
                                            "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of()),
                                    new MailThreadSummary(2L, "work", "Subject 2", "sender@example.com",
                                            "snippet", "2026-05-15T09:00:00Z", false, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(threadId, threadId, "Subject " + threadId, "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            };

            assertTrue(window.showMailWorkspace(client));
            var firstSplit = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            var firstMailView = assertInstanceOf(MailPanelView.class, firstSplit.getFirstView());

            HeadlessWindowHarness.dispatch(firstMailView, HeadlessWindowHarness.key('j'));
            assertEquals(1, HeadlessWindowHarness.getField(firstMailView, "_selectedIndex", Integer.class));

            assertTrue(window.showMailWorkspace(client));
            var secondSplit = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            var secondMailView = assertInstanceOf(MailPanelView.class, secondSplit.getFirstView());

            assertNotSame(firstMailView, secondMailView);
            assertEquals(0, HeadlessWindowHarness.getField(secondMailView, "_selectedIndex", Integer.class));
            assertEquals(2L, tabLabels(window).stream().filter("mail"::equals).count());
        }
    }

    @Test
    void mailWorkspaceEscapeClosesInsteadOfHidingForReuse() throws Exception {
        Path path = tempDir.resolve("mail-close.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            invoke(window, "initializeWorkspaceHistory", new Class<?>[0]);
            MailClient client = new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(new org.fisk.swim.mail.MailAccountSummary("work", "Work", "IMAP", 1, 1, "", "")),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            };

            assertTrue(window.showMailWorkspace(client));
            var firstSplit = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            var firstMailView = assertInstanceOf(MailPanelView.class, firstSplit.getFirstView());

            HeadlessWindowHarness.dispatch(firstMailView, HeadlessWindowHarness.escape());

            assertFalse(window.isShowingMailWorkspace());
            assertFalse(tabLabels(window).contains("mail"));
            assertTrue(window.showMailWorkspace(client));
            var secondSplit = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            var secondMailView = assertInstanceOf(MailPanelView.class, secondSplit.getFirstView());

            assertNotSame(firstMailView, secondMailView);
        }
    }

    @Test
    void mailWorkspaceEscapeDoesNotExitSwim() throws Exception {
        Path path = tempDir.resolve("mail-close-no-exit.txt");
        Files.writeString(path, "abc");
        RecordingHost host = new RecordingHost();

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            SwimRuntime.setHost(host);
            var window = harness.getWindow();
            assertTrue(window.showMailWorkspace(new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            }));

            var mailView = assertInstanceOf(MailPanelView.class, ((SplitView) HeadlessWindowHarness.getField(window,
                    "_workspaceView")).getFirstView());

            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.escape());

            assertEquals(0, host.exitRequests);
            assertFalse(window.isShowingMailWorkspace());
            assertFalse(tabLabels(window).contains("mail"));
        } finally {
            SwimRuntime.clear();
        }
    }

    @Test
    void mailMessageBufferEscapeRemovesMailTab() throws Exception {
        Path path = tempDir.resolve("mail-message-close.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            invoke(window, "initializeWorkspaceHistory", new Class<?>[0]);
            assertTrue(window.showMailWorkspace(new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            }));

            var mailView = assertInstanceOf(MailPanelView.class, ((SplitView) HeadlessWindowHarness.getField(window,
                    "_workspaceView")).getFirstView());
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.enter());
            assertInstanceOf(BufferView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(window.getRootView(), HeadlessWindowHarness.escape());

            assertFalse(window.isShowingMailWorkspace());
            assertFalse(tabLabels(window).contains("mail"));
            assertEquals(path.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void todoWorkspaceQRemovesTodoTabWithoutExitingSwim() throws Exception {
        Path path = tempDir.resolve("todo-q-close.txt");
        Files.writeString(path, "abc");
        RecordingHost host = new RecordingHost();

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            SwimRuntime.setHost(host);
            var window = harness.getWindow();
            assertTrue(window.showTodoWorkspace(emptyTodoStore(tempDir.resolve(".swim/todo"))));
            var todoView = assertInstanceOf(TodoWorkspaceView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(todoView, HeadlessWindowHarness.key('q'));

            assertEquals(0, host.exitRequests);
            assertFalse(window.isShowingTodoWorkspace());
            assertFalse(tabLabels(window).contains("todo"));
            assertEquals(path.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        } finally {
            SwimRuntime.clear();
        }
    }

    @Test
    void todoWorkspaceEscapeRemovesTodoTabWithoutExitingSwim() throws Exception {
        Path path = tempDir.resolve("todo-escape-close.txt");
        Files.writeString(path, "abc");
        RecordingHost host = new RecordingHost();

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            SwimRuntime.setHost(host);
            var window = harness.getWindow();
            assertTrue(window.showTodoWorkspace(emptyTodoStore(tempDir.resolve(".swim/todo"))));
            var todoView = assertInstanceOf(TodoWorkspaceView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(todoView, HeadlessWindowHarness.escape());

            assertEquals(0, host.exitRequests);
            assertFalse(window.isShowingTodoWorkspace());
            assertFalse(tabLabels(window).contains("todo"));
            assertEquals(path.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        } finally {
            SwimRuntime.clear();
        }
    }

    @Test
    void slackWorkspacePlacesMessageViewerBelowSlackPanelAndComposeSendsFromBuffer() throws Exception {
        Path path = tempDir.resolve("slack-layout.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            var client = new FakeSlackClient(tempDir.resolve(".swim/slack/workspaces.json"));

            assertTrue(window.showSlackWorkspace(client));

            var split = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            assertEquals(SplitView.Orientation.VERTICAL, split.getOrientation());
            var slackView = assertInstanceOf(SlackPanelView.class, split.getFirstView());
            assertInstanceOf(BufferView.class, split.getSecondView());

            HeadlessWindowHarness.dispatch(slackView, HeadlessWindowHarness.key('c'));

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertFalse(window.getBufferContext().getBuffer().isReadOnly());
            assertSame(window.getInputMode(), window.getCurrentMode());

            window.getBufferContext().getBuffer().insert("Hello Slack");
            HeadlessWindowHarness.dispatch(window.getInputMode(), HeadlessWindowHarness.ctrl('s'));

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (client.sentDrafts().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertEquals(1, client.sentDrafts().size());
            assertEquals("Hello Slack", client.sentDrafts().getFirst().text());
        }
    }

    @Test
    void slackWorkspaceIsReusedAcrossHideAndReopen() throws Exception {
        Path path = tempDir.resolve("slack-reuse.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            var client = new FakeSlackClient(tempDir.resolve(".swim/slack/workspaces.json"));

            assertTrue(window.showSlackWorkspace(client));
            var firstSplit = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            var firstSlackView = assertInstanceOf(SlackPanelView.class, firstSplit.getFirstView());

            HeadlessWindowHarness.dispatch(firstSlackView, HeadlessWindowHarness.key('h'));
            HeadlessWindowHarness.dispatch(firstSlackView, HeadlessWindowHarness.key('j'));
            HeadlessWindowHarness.dispatch(firstSlackView, HeadlessWindowHarness.key('j'));
            assertEquals("D1", HeadlessWindowHarness.getField(firstSlackView, "_selectedConversationId", String.class));

            assertTrue(window.hideCurrentWorkspaceWindow());
            assertFalse(window.isShowingSlackWorkspace());

            assertTrue(window.showSlackWorkspace(client));
            var secondSplit = assertInstanceOf(SplitView.class, HeadlessWindowHarness.getField(window, "_workspaceView"));
            var secondSlackView = assertInstanceOf(SlackPanelView.class, secondSplit.getFirstView());

            assertSame(firstSlackView, secondSlackView);
            assertEquals("D1", HeadlessWindowHarness.getField(secondSlackView, "_selectedConversationId", String.class));
        }
    }

    @Test
    void mailEnterFocusesReadOnlyMessageBuffer() throws Exception {
        Path path = tempDir.resolve("mail-enter-focus.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            assertTrue(window.showMailWorkspace(new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            }));

            var mailView = assertInstanceOf(MailPanelView.class, ((SplitView) HeadlessWindowHarness.getField(window, "_workspaceView"))
                    .getFirstView());

            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.enter());

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());
            assertSame(window.getNormalMode(), window.getCurrentMode());
        }
    }

    @Test
    void successfulMailSendReturnsFocusToMailList() throws Exception {
        Path path = tempDir.resolve("mail-send-focus.txt");
        Files.writeString(path, "abc");

        try (var harness = HeadlessWindowHarness.create(path, 60, 16)) {
            var window = harness.getWindow();
            assertTrue(window.showMailWorkspace(new MailClient() {
                @Override
                public MailSnapshot snapshot() {
                    return new MailSnapshot(
                            java.util.List.of(new org.fisk.swim.mail.MailAccountSummary("work", "Work", "IMAP", 1, 0, "", "")),
                            java.util.List.of(new MailThreadSummary(1L, "work", "Subject", "sender@example.com",
                                    "snippet", "2026-05-15T10:00:00Z", true, 1, java.util.List.of())),
                            "");
                }

                @Override
                public MailMessageDetail loadMessage(long threadId) {
                    return new MailMessageDetail(1L, threadId, "Subject", "sender@example.com",
                            "dest@example.com", "2026-05-15T10:00:00Z", "body", java.util.List.of());
                }

                @Override
                public org.fisk.swim.mail.MailSendResult sendDraft(org.fisk.swim.mail.MailDraft draft) {
                    return org.fisk.swim.mail.MailSendResult.success("sent");
                }

                @Override
                public void refresh() {
                }

                @Override
                public Path getDataPath() {
                    return tempDir.resolve(".swim/email");
                }
            }));

            var mailView = assertInstanceOf(MailPanelView.class, ((SplitView) HeadlessWindowHarness.getField(window, "_workspaceView"))
                    .getFirstView());
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('c'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('a'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('@'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('b'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('.'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('c'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('o'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.key('m'));
            HeadlessWindowHarness.dispatch(mailView, HeadlessWindowHarness.ctrl('s'));

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (!(window.getActiveView() instanceof MailPanelView) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }

            assertInstanceOf(MailPanelView.class, window.getActiveView());
            assertSame(window.getNormalMode(), window.getCurrentMode());
        }
    }

    @Test
    void typingCommandPrefixUpdatesPopupMatches() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var commandView = window.getCommandView();

            commandView.activate(":");
            HeadlessWindowHarness.dispatch(commandView, HeadlessWindowHarness.key('r'));

            var state = window.getCommandMenuView().getState();
            assertTrue(state.visible());
            assertEquals("r", state.prefix());
            assertTrue(state.matches().size() >= 2);
            assertEquals("r", state.matches().get(0).primaryName().substring(0, 1));
        }
    }

    @Test
    void commandPopupUsesAvailableVerticalSpaceForMoreMatches() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            window.getCommandView().activate(":");

            assertEquals(9, window.getCommandMenuView().getBounds().getSize().getHeight());
        }
    }

    @Test
    void chatColonInputUsesCommandCompletionPopup() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var chat = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {}, ignored -> {}, ignored -> {},
                    text -> CommandView.CommandMenuState.forCommandText(text, 0,
                            List.of(new CommandView.CommandSpec("sessions", List.of(), "", "list sessions"),
                                    new CommandView.CommandSpec("switch", List.of(), "<session-id>", "switch session"))));

            window.showPanel(chat);
            HeadlessWindowHarness.dispatch(chat, HeadlessWindowHarness.key(':'));
            HeadlessWindowHarness.dispatch(chat, HeadlessWindowHarness.key('s'));

            var state = window.getCommandMenuView().getState();
            assertTrue(state.visible());
            assertEquals("s", state.prefix());
            assertEquals("sessions", state.matches().get(0).primaryName());
        }
    }

    @Test
    void chatPanelOverlaysWorkspaceInsteadOfJoiningSplitTree() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            window.splitActiveBufferVertically();
            int leavesBefore = leafViews(window).size();
            var chat = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {});

            assertTrue(window.showPanel(chat));

            assertEquals(leavesBefore, leafViews(window).size());
            assertSame(chat, window.getPanelView());
            assertSame(chat, window.getActiveView());
            assertEquals("{0, 4, 32, 5}", chat.getBounds().toString());
            assertTrue(rootSubviews(window).indexOf(chat) > rootSubviews(window).indexOf(HeadlessWindowHarness.getField(window, "_workspaceView", View.class)));
            assertTrue(rootSubviews(window).indexOf(chat) < rootSubviews(window).indexOf(window.getModeLineView()));
        }
    }

    @Test
    void clickingNemoPanelRestoresFocusAfterBufferClick() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("nemo-mouse.txt", "abc"), 40, 12)) {
            var window = harness.getWindow();
            var chat = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {});
            assertTrue(window.showPanel(chat));

            Rect bufferBounds = absoluteScreenBounds(window.getBufferContext().getBufferView());
            click(window, bufferBounds.getPoint().getX() + 2, bufferBounds.getPoint().getY() + 1);
            assertInstanceOf(BufferView.class, window.getActiveView());

            Rect chatBounds = absoluteScreenBounds(chat);
            click(window, chatBounds.getPoint().getX() + 2, chatBounds.getPoint().getY() + 1);

            assertSame(chat, window.getActiveView(), "chat=" + absoluteScreenBounds(chat)
                    + " active=" + window.getActiveView().getBounds()
                    + " subviews=" + rootSubviews(window).stream()
                            .map(view -> view.getClass().getSimpleName() + ":" + absoluteScreenBounds(view))
                            .toList());
            assertSame(chat, HeadlessWindowHarness.getField(window.getRootView(), "_firstResponder"));
        }
    }

    @Test
    void nemoWorkspaceEscapeEntersBrowseModeAndIReturnsToInput() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("nemo-workspace.txt", "abc"), 40, 12)) {
            var window = harness.getWindow();
            var chat = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {});
            chat.appendMessage("me", "hello");
            chat.appendMessage("nemo", "world");

            assertTrue(window.showNemoWorkspace(chat));
            assertSame(chat, window.getActiveView());

            HeadlessWindowHarness.dispatch(chat, HeadlessWindowHarness.escape());

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals("NORMAL", window.modeNameForDisplay());
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());
            assertTrue(window.getBufferContext().getBuffer().getString().contains("nemo> world"));

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('i'));

            assertSame(chat, window.getActiveView());
            assertEquals("INPUT", window.modeNameForDisplay());
        }
    }

    @Test
    void shellPanelOverlaysWorkspaceInsteadOfJoiningSplitTree() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            window.splitActiveBufferVertically();
            int leavesBefore = leafViews(window).size();
            var shell = new ShellPanelView(Rect.create(0, 0, 0, 0), "Shell", command -> {
            }, "/bin/sh");

            assertTrue(window.showPanel(shell));

            assertEquals(leavesBefore, leafViews(window).size());
            assertSame(shell, window.getPanelView());
            assertSame(shell, window.getActiveView());
            assertEquals("{0, 6, 32, 3}", shell.getBounds().toString());
            assertTrue(rootSubviews(window).indexOf(shell) > rootSubviews(window).indexOf(HeadlessWindowHarness.getField(window, "_workspaceView", View.class)));
            assertTrue(rootSubviews(window).indexOf(shell) < rootSubviews(window).indexOf(window.getModeLineView()));
            window.closePanelShellSession();
        }
    }

    @Test
    void shellPanelUsesShellSpecificCommandCompletionPopup() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var holder = new ShellPanelView[1];
            holder[0] = new ShellPanelView(Rect.create(0, 0, 0, 0), "Shell",
                    command -> {
            }, "/bin/sh");

            window.showPanel(holder[0]);
            HeadlessWindowHarness.dispatch(holder[0], HeadlessWindowHarness.ctrl('g'));

            var state = window.getCommandMenuView().getState();
            assertTrue(state.visible());
            assertEquals("", state.prefix());
            assertEquals("q", state.matches().get(0).primaryName());
            assertEquals("c", state.matches().get(1).primaryName());
            assertEquals("e", state.matches().get(2).primaryName());
            assertEquals("v", state.matches().get(3).primaryName());
            assertEquals("w", state.matches().get(4).primaryName());
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("shell input active"));
        }
    }

    @Test
    void shellPanelEscapeClosesPanel() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var shell = new ShellPanelView(Rect.create(0, 0, 0, 0), "Shell", command -> {
            }, "/bin/sh");

            window.showPanel(shell);
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.escape());

            assertFalse(window.isShowingPanel());
        }
    }

    @Test
    void closingShellPanelStopsShellProcess() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var shell = new ShellPanelView(Rect.create(0, 0, 0, 0), "Shell", command -> {
            }, "/bin/sh");
            window.showPanel(shell);

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('q'));

            var processField = ShellPanelView.class.getDeclaredField("_process");
            processField.setAccessible(true);
            Process process = (Process) processField.get(shell);
            process.waitFor();
            assertFalse(process.isAlive());
        }
    }

    @Test
    void shellPanelCtrlGQClosesImmediately() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var shell = new ShellPanelView(Rect.create(0, 0, 0, 0), "Shell", command -> {
            }, "/bin/sh");
            window.showPanel(shell);

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('q'));

            assertFalse(window.isShowingPanel());
        }
    }

    @Test
    void normalModeCtrlGCWOpensShellWorkspace() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('g'),
                    HeadlessWindowHarness.key('c'), HeadlessWindowHarness.key('w'));

            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertTrue(tabLabels(window).contains("Shell"));
            assertEquals(32, shell.getBounds().getSize().getWidth());
            assertEquals(7, shell.getBounds().getSize().getHeight());
        }
    }

    @Test
    void normalModeCtrlGCVOpensShellInVerticalSplit() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('g'),
                    HeadlessWindowHarness.key('c'), HeadlessWindowHarness.key('v'));

            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertEquals("{16, 0, 16, 6}", shell.getBounds().toString());
            assertEquals(2, leafViews(window).size());
        }
    }

    @Test
    void normalModeCtrlGCHOpensShellInHorizontalSplit() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('g'),
                    HeadlessWindowHarness.key('c'), HeadlessWindowHarness.key('h'));

            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertEquals("{0, 4, 32, 2}", shell.getBounds().toString());
            assertEquals(2, leafViews(window).size());
        }
    }

    @Test
    void shellWorkspaceCtrlGWSelectsRecentWorkspace() throws Exception {
        Path directory = tempDir.resolve("shell-workspace-switch");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue((Boolean) invoke(window, "openDirectoryWorkspace", new Class<?>[] { Path.class }, directory));
            assertTrue(window.switchToRecentWindow(2));
            assertTrue(window.showShellWorkspace());

            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('w'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('2'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.enter());

            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void shellWorkspaceCtrlGVEntersBrowseModeAndIReturnsToPrompt() throws Exception {
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('v'));

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals("NORMAL", window.modeNameForDisplay());
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('i'));

            assertSame(shell, window.getActiveView());
            assertEquals("INPUT", window.modeNameForDisplay());
        }
    }

    @Test
    void shellWorkspaceCtrlGEscapeEntersBrowseModeWithoutClosingShell() throws Exception {
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.escape());

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals("NORMAL", window.modeNameForDisplay());
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());
        }
    }

    @Test
    void shellWorkspaceCtrlGCVCreatesAnotherShellFrame() throws Exception {
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('c'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('v'));

            assertTrue(window.getActiveView() instanceof ShellPanelView);
            assertEquals(2, leafViews(window).size());
            assertTrue(leafViews(window).stream().allMatch(view -> view instanceof ShellPanelView));
        }
    }

    @Test
    void shellBrowseModeSupportsNormalModeNavigation() throws Exception {
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            emulatorOf(shell).feed("alpha\nbeta\ngamma\n");

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('v'));

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals(window.getBufferContext().getBuffer().getLength(),
                    window.getBufferContext().getBuffer().getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getActiveView(), HeadlessWindowHarness.key('g'),
                    HeadlessWindowHarness.key('g'));

            HeadlessWindowHarness.dispatch(window.getActiveView(), HeadlessWindowHarness.key('j'));

            assertEquals(6, window.getBufferContext().getBuffer().getCursor().getPosition());
        }
    }

    @Test
    void switchingAwayFromShellWorkspaceKeepsShellProcessAlive() throws Exception {
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('w'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('2'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.enter());

            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());

            assertTrue(window.switchToRecentWindow(2));
            assertSame(shell, window.getActiveView());
            assertEquals("INPUT", window.modeNameForDisplay());
        }
    }

    @Test
    void exitedShellSplitRemovesOnlyThatFrame() throws Exception {
        Path file = writeFile("shell-exit-frame.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellSplitHorizontally());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());

            assertTrue(window.closeExitedShellView(shell));

            assertEquals(1, leafViews(window).size());
            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void exitedShellWorkspaceRemovesShellTabAndActivatesFallback() throws Exception {
        Path file = writeFile("shell-exit-tab.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertTrue(tabLabels(window).contains("Shell"));

            assertTrue(window.closeExitedShellView(shell));

            assertFalse(tabLabels(window).contains("Shell"));
            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void exitedShellSplitInInactiveTabDoesNotStealFocus() throws Exception {
        Path first = writeFile("shell-exit-inactive-first.txt", "one");
        Path second = writeFile("shell-exit-inactive-second.txt", "two");

        try (var harness = HeadlessWindowHarness.create(first, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellSplitHorizontally());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, second));

            assertTrue(window.closeExitedShellView(shell));

            assertEquals(second.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertTrue(window.switchToWorkspaceIndex(0));
            assertEquals(1, leafViews(window).size());
            assertEquals(first.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void exitedLastShellWorkspaceRequestsSwimExit() throws Exception {
        Path file = writeFile("shell-exit-last-tab.txt", "abc");
        RecordingHost host = new RecordingHost();

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertTrue(window.switchToWorkspaceIndex(0));
            assertTrue(window.closeAnyCurrentWorkspace());
            SwimRuntime.setHost(host);

            assertTrue(window.closeExitedShellView(shell));

            assertEquals(1, host.exitRequests);
            assertTrue(tabLabels(window).isEmpty());
        } finally {
            SwimRuntime.clear();
        }
    }

    @Test
    void shellPanelIsReusedAfterHide() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            assertTrue(window.showShellPanel());
            assertInstanceOf(ShellPanelView.class, window.getPanelView());

            window.hidePanel();
            assertFalse(window.isShowingPanel());

            assertTrue(window.showShellPanel());
            assertInstanceOf(ShellPanelView.class, window.getPanelView());
        }
    }

    @Test
    void searchActivationUpdatesTopMenuContext() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            window.getCommandView().activate("/");

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("forward search"));
            assertTrue(window.getKeyMenuView().bodyText().contains("Enter search forward"));
            assertFalse(window.getCommandMenuView().getState().visible());
        }
    }

    @Test
    void showingListUpdatesTopMenuContext() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            window.showList(List.of(item("alpha")), "Files");

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("list navigation"));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("Files"));
            assertTrue(window.getKeyMenuView().bodyText().contains("type to filter"));

            window.hideList();

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("discover"));
        }
    }

    @Test
    void openingDirectoryShowsDirectoryBrowserWorkspaceAndCanSwitchBack() throws Exception {
        Path directory = tempDir.resolve("browse");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 24, 11)) {
            var window = harness.getWindow();

            assertTrue(window.setBufferPath(directory));

            var browser = assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
            assertEquals(directory.toAbsolutePath().normalize(), browser.getDirectory());
            assertTrue(tabLabels(window).contains("Browse: browse"));

            assertTrue(window.switchToRecentWindow(2));
            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void topBarShowsProjectLocalBufferMruWhileTabOrderRemainsStable() throws Exception {
        Path directory = tempDir.resolve("browse-order");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();

            assertTrue(window.showDirectoryBrowser(directory));
            assertTrue(tabLabels(window).contains("Browse: browse-order"));
            assertTrue(tabLabels(window).contains("window.txt"));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("1:window.txt"));

            assertTrue(window.switchToRecentWindow(2));
            String header = window.getKeyMenuView().buildHeaderLine().toString();
            assertTrue(header.contains("1:window.txt"));
            assertTrue(!header.contains("Browse: browse-order"));
            assertTrue(tabLabels(window).contains("Browse: browse-order"));
        }
    }

    @Test
    void switchingAwayAndBackPreservesBufferWorkspaceSplits() throws Exception {
        Path directory = tempDir.resolve("browse-split");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();

            window.splitActiveBufferHorizontally();
            assertEquals(2, leafViews(window).size());

            assertTrue((Boolean) invoke(window, "openDirectoryWorkspace", new Class<?>[] { Path.class }, directory));
            assertTrue(window.switchToRecentWindow(2));

            assertEquals(2, leafViews(window).size());
        }
    }

    @Test
    void closingBufferTabActivatesMostRecentlyUsedRemainingTab() throws Exception {
        Path first = writeFile("close-mru-first.txt", "one");
        Path second = writeFile("close-mru-second.txt", "two");
        Path third = writeFile("close-mru-third.txt", "three");

        try (var harness = HeadlessWindowHarness.create(first, 40, 12)) {
            var window = harness.getWindow();
            invoke(window, "initializeWorkspaceHistory", new Class<?>[0]);
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, second));
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, third));

            window.closeCurrentTabOrExit();

            assertEquals(second.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertFalse(tabLabels(window).contains("close-mru-third.txt"));
        }
    }

    @Test
    void closingWorkspaceTabActivatesMostRecentlyUsedRemainingTab() throws Exception {
        Path first = writeFile("close-window-mru-first.txt", "one");
        Path second = writeFile("close-window-mru-second.txt", "two");
        Path directory = tempDir.resolve("close-window-mru-browse");
        Files.createDirectories(directory);

        try (var harness = HeadlessWindowHarness.create(first, 40, 12)) {
            var window = harness.getWindow();
            invoke(window, "initializeWorkspaceHistory", new Class<?>[0]);
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, second));
            assertTrue((Boolean) invoke(window, "openDirectoryWorkspace", new Class<?>[] { Path.class }, directory));
            assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());

            assertTrue(window.closeCurrentWorkspaceWindow());

            assertEquals(second.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertFalse(tabLabels(window).contains("Browse: close-window-mru-browse"));
        }
    }

    @Test
    void splittingActivePanelSplitsThatFrameAndAddsABufferView() throws Exception {
        Path file = writeFile("panel-split.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();
            var originalBuffer = window.getBufferContext().getBufferView();
            var panel = new TextPanelView(Rect.create(0, 0, 0, 0), "Info", "details");
            assertTrue(window.showPanel(panel));
            assertSame(panel, window.getActiveView());

            var splitBuffer = window.splitActiveBufferVertically();

            assertInstanceOf(BufferView.class, splitBuffer);
            assertSame(splitBuffer, window.getActiveView());
            assertEquals(3, leafViews(window).size());
            assertTrue(leafViews(window).contains(originalBuffer));
            assertTrue(leafViews(window).contains(panel));
            assertTrue(leafViews(window).contains(splitBuffer));
        }
    }

    @Test
    void splittingBufferCreatesIndependentBufferState() throws Exception {
        Path file = writeFile("independent-split.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();
            var originalView = window.getBufferContext().getBufferView();
            var originalContext = window.getBufferContext();
            originalContext.getBuffer().getCursor().setPosition(1);

            var splitView = window.splitActiveBufferHorizontally();

            assertInstanceOf(BufferView.class, splitView);
            var splitContext = window.getBufferContext();
            assertNotSame(originalContext, splitContext);
            assertEquals(originalContext.getBuffer().getString(), splitContext.getBuffer().getString());

            splitContext.getBuffer().getCursor().setPosition(5);

            assertEquals(1, originalContext.getBuffer().getCursor().getPosition());
            assertEquals(5, splitContext.getBuffer().getCursor().getPosition());
            assertNotSame(originalView, splitView);
        }
    }

    @Test
    void refreshingOpenBuffersForPathUpdatesEverySplitBufferView() throws Exception {
        Path file = writeFile("nemo-refresh-split.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();
            var originalContext = window.getBufferContext();
            window.splitActiveBufferHorizontally();
            var splitContext = window.getBufferContext();

            assertTrue(window.refreshOpenBuffersForPath(file, "updated\ntext\n"));

            assertEquals("updated\ntext\n", originalContext.getBuffer().getString());
            assertEquals("updated\ntext\n", splitContext.getBuffer().getString());
            assertFalse(originalContext.getBuffer().isModified());
            assertFalse(splitContext.getBuffer().isModified());
        }
    }

    @Test
    void clickingSplitFrameBarActivatesThatBufferFrame() throws Exception {
        Path file = writeFile("click-split-frame.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();
            var topView = window.getBufferContext().getBufferView();
            var bottomView = window.splitActiveBufferVertically();
            assertSame(bottomView, window.getActiveView());
            window.activateView(topView);

            Rect bottomBounds = absoluteScreenBounds(bottomView);
            click(window, bottomBounds.getPoint().getX() + 2,
                    bottomBounds.getPoint().getY() + bottomBounds.getSize().getHeight());

            assertSame(bottomView, window.getActiveView());
            assertSame(bottomView, window.getRootView().getFirstResponder());
        }
    }

    @Test
    void clickingSplitBufferGutterActivatesThatBufferFrame() throws Exception {
        Path file = writeFile("click-split-gutter.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();
            var topView = window.getBufferContext().getBufferView();
            var bottomView = window.splitActiveBufferVertically();
            assertSame(bottomView, window.getActiveView());
            window.activateView(topView);

            Rect bottomBounds = absoluteScreenBounds(bottomView);
            click(window, bottomBounds.getPoint().getX(),
                    bottomBounds.getPoint().getY());

            assertSame(bottomView, window.getActiveView());
            assertSame(bottomView, window.getRootView().getFirstResponder());
        }
    }

    @Test
    void splitThenVsplitDoesNotLoseAnExtraRowToHorizontalSeparator() throws Exception {
        Path file = writeFile("nested-split.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 24, 11)) {
            var window = harness.getWindow();

            var bottomView = window.splitActiveBufferVertically();
            assertEquals("{0, 4, 24, 2}", absoluteBounds(bottomView).toString());

            var rightView = window.splitActiveBufferHorizontally();
            assertEquals("{12, 4, 12, 2}", rightView.getBounds().toString());
        }
    }

    @Test
    void ctrlWGreaterAndLessResizeVerticalSplit() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("resize-width.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var left = window.getBufferContext().getBufferView();
            window.splitActiveBufferHorizontally();
            var right = assertInstanceOf(BufferView.class, window.getActiveView());

            String initialLeft = left.getBounds().toString();
            String initialRight = right.getBounds().toString();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('w'), HeadlessWindowHarness.key('>'));

            assertTrue(!initialRight.equals(right.getBounds().toString()));
            assertTrue(right.getBounds().getSize().getWidth() > 16);

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('w'), HeadlessWindowHarness.key('<'));

            assertEquals(initialLeft, left.getBounds().toString());
            assertEquals(initialRight, right.getBounds().toString());
        }
    }

    @Test
    void ctrlWCountedResizeAppliesMultipleSteps() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("resize-count.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            window.splitActiveBufferHorizontally();
            var right = assertInstanceOf(BufferView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(window.getNormalMode(),
                    HeadlessWindowHarness.ctrl('w'),
                    HeadlessWindowHarness.key('3'),
                    HeadlessWindowHarness.key('>'));

            assertTrue(right.getBounds().getSize().getWidth() > 20);
        }
    }

    @Test
    void ctrlWPlusAndMinusResizeHorizontalSplit() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("resize-height.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var bottom = window.splitActiveBufferVertically();

            String initialBottom = bottom.getBounds().toString();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('w'), HeadlessWindowHarness.key('+'));

            assertTrue(bottom.getBounds().getSize().getHeight() > 2);

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('w'), HeadlessWindowHarness.key('-'));

            assertEquals(initialBottom, bottom.getBounds().toString());
        }
    }

    @Test
    void ctrlWEqualsEqualizesSplitSizes() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("resize-equalize.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();
            var left = window.getBufferContext().getBufferView();
            window.splitActiveBufferHorizontally();
            var right = assertInstanceOf(BufferView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('w'), HeadlessWindowHarness.key('>'));
            assertTrue(right.getBounds().getSize().getWidth() > left.getBounds().getSize().getWidth());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('w'), HeadlessWindowHarness.key('='));

            assertTrue(Math.abs(left.getBounds().getSize().getWidth() - right.getBounds().getSize().getWidth()) <= 1);
        }
    }

    @Test
    void splitWorkspaceStaysBelowCommandPopupLayer() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("popup-layer.txt", "abc"), 40, 12)) {
            var window = harness.getWindow();
            window.splitActiveBufferVertically();
            window.splitActiveBufferHorizontally();

            var subviews = rootSubviews(window);
            int workspaceIndex = subviews.indexOf(HeadlessWindowHarness.getField(window, "_workspaceView", View.class));
            int commandMenuIndex = subviews.indexOf(window.getCommandMenuView());
            int commandViewIndex = subviews.indexOf(window.getCommandView());

            assertTrue(workspaceIndex >= 0);
            assertTrue(commandViewIndex > workspaceIndex);
            assertTrue(commandMenuIndex > workspaceIndex);
        }
    }

    @Test
    void chatPanelStaysBelowCommandPopupLayer() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("chat-popup-layer.txt", "abc"), 40, 12)) {
            var window = harness.getWindow();
            var chat = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {});

            window.showPanel(chat);

            var subviews = rootSubviews(window);
            int chatIndex = subviews.indexOf(chat);
            int commandMenuIndex = subviews.indexOf(window.getCommandMenuView());

            assertTrue(chatIndex >= 0);
            assertTrue(commandMenuIndex > chatIndex);
        }
    }

    @Test
    void nestedSplitCursorStaysOffSeparatorRow() throws Exception {
        var installedTerminal = TerminalContextTestSupport.install(80, 16);
        Path file = writeFile("nested-cursor.txt", "alpha\nbeta\n");

        try (var harness = HeadlessWindowHarness.create(file, 24, 11)) {
            var window = harness.getWindow();
            window.splitActiveBufferVertically();
            var rightView = window.splitActiveBufferHorizontally();
            var buffer = window.getBufferContext().getBuffer();
            int index = buffer.getString().indexOf("beta") + 1;
            buffer.getCursor().setPosition(index);

            window.update(true);

            var line = window.getBufferContext().getTextLayout().getPhysicalLineAt(index);
            var absolute = absoluteScreenBounds(rightView);
            int expectedColumn = absolute.getPoint().getX()
                    + rightView.getTextColumnStart()
                    + index - line.getStartPosition();
            int expectedRow = absolute.getPoint().getY() + line.getY() - rightView.getStartLine();
            var cursorPosition = installedTerminal.cursorPosition().get();
            assertEquals(expectedColumn, cursorPosition.getColumn());
            assertEquals(expectedRow, cursorPosition.getRow());
            assertTrue(cursorPosition.getRow() < absolute.getPoint().getY() + absolute.getSize().getHeight() - 1);
        } finally {
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void windowSelectionSequenceSwitchesToRequestedRecentWorkspace() throws Exception {
        Path directory = tempDir.resolve("browse-sequence");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        TerminalContextTestSupport.install(40, 12);
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            assertTrue(window.showDirectoryBrowser(directory));
            assertTrue(window.switchToRecentWindow(2));
            var responder = EventThread.getInstance().getResponder();

            Response response = responder.processEvent(new KeyStrokes(List.of(
                    HeadlessWindowHarness.key('w'),
                    HeadlessWindowHarness.key('2'),
                    HeadlessWindowHarness.enter())));

            assertEquals(Response.YES, response);
            responder.respond();
            assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
        } finally {
            if (Window.getInstance() != null) {
                Window.getInstance().dispose();
            }
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void incrementalWindowSelectionSequenceWinsOverNormalModeWBinding() throws Exception {
        Path directory = tempDir.resolve("browse-sequence-incremental");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        TerminalContextTestSupport.install(40, 12);
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            assertTrue(window.showDirectoryBrowser(directory));
            assertTrue(window.switchToRecentWindow(2));
            var responder = EventThread.getInstance().getResponder();
            var pending = new ArrayList<com.googlecode.lanterna.input.KeyStroke>();

            assertEquals(Response.MAYBE, processSequenceStep(responder, pending, HeadlessWindowHarness.key('w')));
            assertEquals(Response.MAYBE, processSequenceStep(responder, pending, HeadlessWindowHarness.key('2')));
            assertEquals(Response.YES, processSequenceStep(responder, pending, HeadlessWindowHarness.enter()));

            assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
        } finally {
            if (Window.getInstance() != null) {
                Window.getInstance().dispose();
            }
            EventThread.shutdownInstance();
            org.fisk.swim.terminal.TerminalContext.shutdownInstance();
        }
    }

    @Test
    void openingFileFromDirectoryWorkspaceRecalculatesBufferLayoutWithActualWidth() throws IOException {
        Path directory = tempDir.resolve("browse-layout");
        Files.createDirectories(directory);
        Path file = Files.writeString(directory.resolve("UndoLog.java"), "alpha\nbeta\ngamma\n");

        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("scratch.txt"), 60, 14)) {
            var window = harness.getWindow();
            assertTrue(window.showDirectoryBrowser(directory));
            var view = assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());

            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertEquals(4, window.getBufferContext().getTextLayout().getLogicalLineCount());
        }
    }

    @Test
    void openingFileFromDirectoryWorkspaceRoutesNavigationToTheOpenedBuffer() throws IOException {
        Path directory = tempDir.resolve("browse-navigation");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("UndoLog.java"), "alpha\nbeta\ngamma\n");

        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("scratch.txt"), 60, 14)) {
            var window = harness.getWindow();
            var originalBuffer = window.getBufferContext().getBuffer();

            assertTrue(window.showDirectoryBrowser(directory));
            var view = assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

            var openedBuffer = window.getBufferContext().getBuffer();
            assertTrue(openedBuffer != originalBuffer);
            assertEquals(0, openedBuffer.getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getActiveView(), HeadlessWindowHarness.key('j'));

            assertEquals(6, openedBuffer.getCursor().getPosition());
            assertEquals(0, originalBuffer.getCursor().getPosition());
        }
    }

    @Test
    void openingDirectoryInSplitReplacesOnlyActiveFrame() throws Exception {
        Path first = writeFile("split-browser-left.txt", "left\n");
        Path second = writeFile("split-browser-right.txt", "right\n");
        Path directory = tempDir.resolve("frame-browser");
        Files.createDirectories(directory);
        Path opened = Files.writeString(directory.resolve("OpenMe.txt"), "opened\n");

        try (var harness = HeadlessWindowHarness.create(first, 60, 14)) {
            var window = harness.getWindow();
            window.splitActiveBufferHorizontally();
            assertTrue(window.setBufferPath(second));
            assertEquals(second.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());

            assertTrue(window.focusView(Window.Direction.LEFT));
            assertEquals(first.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());

            assertTrue(window.setBufferPath(directory));
            assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
            assertEquals(2, leafViews(window).size());

            var browser = assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
            HeadlessWindowHarness.dispatch(browser, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(browser, HeadlessWindowHarness.enter());

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals(opened.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertEquals(2, leafViews(window).size());

            assertTrue(window.focusView(Window.Direction.RIGHT));
            assertEquals(second.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void tmuxPrefixCreatesShellTabAndSwitchesStableWorkspaces() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        Path file = writeFile("tmux-workspace.txt", "abc");
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();
            Path originalPath = window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize();

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('c')));

            assertInstanceOf(ShellPanelView.class, window.getActiveView());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('0')));

            assertEquals(originalPath, window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('n')));

            assertInstanceOf(ShellPanelView.class, window.getActiveView());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('l')));

            assertEquals(originalPath, window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void tmuxPrefixQuoteSelectsTabsBeyondNine() throws Exception {
        TerminalContextTestSupport.install(80, 16);
        Path first = writeFile("tmux-tab-0.txt", "zero");
        Path tenth = null;
        try {
            Window.createInstance(first);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();
            for (int i = 1; i <= 10; i++) {
                Path path = writeFile("tmux-tab-" + i + ".txt", "tab " + i);
                if (i == 10) {
                    tenth = path;
                }
                assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, path));
            }

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('\''), HeadlessWindowHarness.key('0'),
                    HeadlessWindowHarness.enter()));

            assertEquals(first.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('\''), HeadlessWindowHarness.key('1'),
                    HeadlessWindowHarness.key('0'), HeadlessWindowHarness.enter()));

            assertEquals(tenth.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void tmuxPrefixRenamesMovesAndSwapsTabs() throws Exception {
        TerminalContextTestSupport.install(80, 16);
        Path first = writeFile("tmux-manage-0.txt", "zero");
        Path second = writeFile("tmux-manage-1.txt", "one");
        Path third = writeFile("tmux-manage-2.txt", "two");
        try {
            Window.createInstance(first);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, second));
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, third));
            assertTrue(window.switchToWorkspaceIndex(0));

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key(',')));
            assertSame(window.getCommandView(), window.getRootView().getFirstResponder());
            assertEquals("tab-rename tmux-manage-0.txt", window.getCommandView().getCommandText());

            window.getCommandView().execute("tab-rename review");
            window.getCommandView().deactivate();
            assertEquals(List.of("review", "tmux-manage-1.txt", "tmux-manage-2.txt"), tabLabels(window));

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('>')));
            assertEquals(List.of("tmux-manage-1.txt", "review", "tmux-manage-2.txt"), tabLabels(window));

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('<')));
            assertEquals(List.of("review", "tmux-manage-1.txt", "tmux-manage-2.txt"), tabLabels(window));

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('.')));
            assertEquals("tab-move ", window.getCommandView().getCommandText());
            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(window.getCommandView(),
                    HeadlessWindowHarness.key('2')));
            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(window.getCommandView(),
                    HeadlessWindowHarness.enter()));

            assertEquals(List.of("tmux-manage-1.txt", "tmux-manage-2.txt", "review"), tabLabels(window));
            assertEquals(first.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void sessionSnapshotUsesStableTabOrderAndTabLabels() throws Exception {
        TerminalContextTestSupport.install(80, 16);
        Path first = writeFile("tmux-session-0.txt", "zero");
        Path second = writeFile("tmux-session-1.txt", "one");
        Path third = writeFile("tmux-session-2.txt", "two");
        try {
            Window.createInstance(first);
            var window = Window.getInstance();
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, second));
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, third));
            assertTrue(window.switchToWorkspaceIndex(0));
            assertTrue(window.renameCurrentTab("review"));
            assertTrue(window.moveCurrentTabToIndex(2));

            var session = (org.fisk.swim.config.EditorSession) invoke(window, "createSession", new Class<?>[0]);

            assertEquals(2, session.activeWorkspaceIndex());
            assertEquals(second.toAbsolutePath().normalize().toString(), session.workspaces().get(0).path());
            assertEquals(third.toAbsolutePath().normalize().toString(), session.workspaces().get(1).path());
            assertEquals(first.toAbsolutePath().normalize().toString(), session.workspaces().get(2).path());
            assertEquals("review", session.workspaces().get(2).label());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void tmuxPrefixSplitsFocusesAndClosesFrames() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        Path file = writeFile("tmux-frame.txt", "abc");
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();
            var originalView = window.getActiveView();

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('%')));

            assertEquals(2, leafViews(window).size());
            var splitView = window.getActiveView();
            assertNotSame(originalView, splitView);

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key(';')));

            assertSame(originalView, window.getActiveView());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('o')));

            assertSame(splitView, window.getActiveView());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('x')));

            assertEquals(1, leafViews(window).size());
            assertSame(originalView, window.getActiveView());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void tmuxPrefixSplitsShellTabIntoShellFrames() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        Path file = writeFile("tmux-shell-frame.txt", "abc");
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();
            assertTrue(window.showShellWorkspace());

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('%')));

            assertEquals(2, leafViews(window).size());
            assertTrue(leafViews(window).stream().allMatch(ShellPanelView.class::isInstance));
            assertInstanceOf(ShellPanelView.class, window.getActiveView());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void tmuxPrefixColonOpensCommandPrompt() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        Path file = writeFile("tmux-command.txt", "abc");
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key(':')));

            assertSame(window.getCommandView(), window.getRootView().getFirstResponder());
        } finally {
            shutdownRealWindow();
        }
    }

    @Test
    void tmuxPrefixSOpensServerSessionChooser() throws Exception {
        TerminalContextTestSupport.install(60, 16);
        Path file = writeFile("tmux-sessions.txt", "abc");
        String previousSocket = System.getProperty(SwimServerSessions.PROPERTY_SOCKET);
        System.clearProperty(SwimServerSessions.PROPERTY_SOCKET);
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();

            assertEquals(Response.YES, HeadlessWindowHarness.dispatch(responder,
                    HeadlessWindowHarness.ctrl('b'), HeadlessWindowHarness.key('s')));

            var panel = assertInstanceOf(TextPanelView.class, window.getPanelView());
            String text = HeadlessWindowHarness.getField(panel, "_text", String.class);
            assertTrue(text.contains("No SWIM session server is attached"));
        } finally {
            if (previousSocket == null) {
                System.clearProperty(SwimServerSessions.PROPERTY_SOCKET);
            } else {
                System.setProperty(SwimServerSessions.PROPERTY_SOCKET, previousSocket);
            }
            shutdownRealWindow();
        }
    }

    @Test
    void bottomTabsShowStableProjectNamesAndTopBarShowsActiveProjectBufferMru() throws Exception {
        TerminalContextTestSupport.install(90, 18);
        Path alpha = tempDir.resolve("alpha");
        Path beta = tempDir.resolve("beta");
        Files.createDirectories(alpha.resolve("src"));
        Files.createDirectories(beta.resolve("src"));
        Files.writeString(alpha.resolve("pom.xml"), "<project />");
        Files.writeString(beta.resolve("pom.xml"), "<project />");
        Path alphaOne = Files.writeString(alpha.resolve("src/One.txt"), "one");
        Path alphaTwo = Files.writeString(alpha.resolve("src/Two.txt"), "two");
        Path betaMain = Files.writeString(beta.resolve("src/Main.txt"), "beta");
        try {
            Window.createInstance(alphaOne);
            var window = Window.getInstance();
            window.splitActiveBufferHorizontally();
            assertTrue(window.setBufferPath(alphaTwo));
            assertTrue((Boolean) invoke(window, "openBufferWorkspace", new Class<?>[] { Path.class }, betaMain));

            String betaTabs = window.getTabBarView().buildLine(90).toString();
            assertTrue(betaTabs.contains("0:alpha"));
            assertTrue(betaTabs.contains("1:beta"));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("1:src/Main.txt"));
            assertTrue(!window.getKeyMenuView().buildHeaderLine().toString().contains("src/Two.txt"));

            assertTrue(window.switchToWorkspaceIndex(0));

            String alphaHeader = window.getKeyMenuView().buildHeaderLine().toString();
            assertTrue(alphaHeader.contains("1:src/Two.txt"));
            assertTrue(alphaHeader.contains("2:src/One.txt"));
            assertTrue(!alphaHeader.contains("src/Main.txt"));

            window.getBufferContext().getBuffer().insert("!");
            window.refreshChromeState();

            String dirtyAlphaHeader = window.getKeyMenuView().buildHeaderLine().toString();
            assertTrue(dirtyAlphaHeader.contains("1:*src/Two.txt"));
            assertTrue(dirtyAlphaHeader.contains("2:src/One.txt"));
        } finally {
            shutdownRealWindow();
        }
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private static void shutdownRealWindow() {
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
        EventThread.shutdownInstance();
        org.fisk.swim.terminal.TerminalContext.shutdownInstance();
    }

    private static Rect absoluteBounds(View view) {
        return view.getBounds();
    }

    private static Rect absoluteScreenBounds(View view) {
        int x = view.getBounds().getPoint().getX();
        int y = view.getBounds().getPoint().getY();
        for (var parent = view.getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Rect.create(x, y, view.getBounds().getSize().getWidth(), view.getBounds().getSize().getHeight());
    }

    private static void click(Window window, int x, int y) {
        HeadlessWindowHarness.dispatch(window.getRootView(),
                new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(x, y)));
    }

    private static ListView.ListItem item(String label) {
        return new ListView.ListItem() {
            @Override
            public void onClick() {
            }

            @Override
            public String displayString() {
                return label;
            }
        };
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Process processOf(ShellPanelView shell) throws Exception {
        var field = ShellPanelView.class.getDeclaredField("_process");
        field.setAccessible(true);
        return (Process) field.get(shell);
    }

    @SuppressWarnings("unchecked")
    private static List<View> rootSubviews(Window window) {
        return (List<View>) HeadlessWindowHarness.getField(window.getRootView(), "_subviews");
    }

    private static TerminalEmulator emulatorOf(ShellPanelView shell) throws Exception {
        var field = ShellPanelView.class.getDeclaredField("_emulator");
        field.setAccessible(true);
        return (TerminalEmulator) field.get(shell);
    }

    @SuppressWarnings("unchecked")
    private static List<View> leafViews(Window window) throws Exception {
        return (List<View>) invoke(window, "getLeafViews", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static List<String> tabLabels(Window window) throws Exception {
        return ((List<TabBarView.Tab>) invoke(window, "tabEntries", new Class<?>[0])).stream()
                .map(TabBarView.Tab::label)
                .toList();
    }

    private static TodoStore emptyTodoStore(Path dataPath) {
        return new TodoStore() {
            @Override
            public TodoSnapshot snapshot() {
                return TodoSnapshot.empty();
            }

            @Override
            public org.fisk.swim.todo.TodoItem createInboxItem(String title) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void assignProject(long itemId, String projectName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void replaceTags(long itemId, List<String> tagNames) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void toggleCompleted(long itemId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void deleteItem(long itemId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Path getDataPath() {
                return dataPath;
            }

            @Override
            public void close() {
            }
        };
    }

    private static Response processSequenceStep(org.fisk.swim.event.EventResponder responder,
            ArrayList<com.googlecode.lanterna.input.KeyStroke> pending,
            com.googlecode.lanterna.input.KeyStroke next) {
        pending.add(next);
        var response = responder.processEvent(new KeyStrokes(List.copyOf(pending)));
        if (response == Response.YES) {
            responder.respond();
            pending.clear();
        } else if (response == Response.NO) {
            pending.clear();
        }
        return response;
    }

    private static final class RecordingHost implements SwimHost {
        private int exitRequests;

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
            exitRequests++;
        }

        @Override
        public Path getBuildRoot() {
            return null;
        }
    }
}
