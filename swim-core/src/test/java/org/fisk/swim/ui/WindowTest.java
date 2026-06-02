package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.terminal.TerminalEmulator;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowTest {
    @TempDir
    Path tempDir;

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
    void narrowWindowExpandsTopMenuHeight() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("narrow-menu.txt", "abc"), 18, 11)) {
            var window = harness.getWindow();

            invoke(window, "applyLayout", new Class<?>[] { Size.class }, Size.create(18, 11));

            assertTrue(window.getKeyMenuView().getBounds().getSize().getHeight() > 2);
            assertEquals(window.getKeyMenuView().getBounds().getSize().getHeight(),
                    window.getBufferContext().getBufferView().getBounds().getPoint().getY());
        }
    }

    @Test
    void mailComposeTabCanMoveFocusIntoEditableBodyBuffer() throws Exception {
        Path path = tempDir.resolve("mail-compose-focus.txt");
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
            assertEquals("reload", state.matches().get(0).primaryName());
            assertEquals("rebuild", state.matches().get(1).primaryName());
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
    void normalModeCtrlGCOpensShellWorkspace() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.ctrl('g'), HeadlessWindowHarness.key('c'));

            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("1:Shell"));
            assertEquals(32, shell.getBounds().getSize().getWidth());
            assertEquals(7, shell.getBounds().getSize().getHeight());
        }
    }

    @Test
    void shellWorkspaceCtrlGWSelectsRecentWorkspace() throws Exception {
        Path directory = tempDir.resolve("shell-workspace-switch");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showDirectoryBrowser(directory));
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
            var process = processOf(shell);

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('v'));

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals("NORMAL", window.modeNameForDisplay());
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());
            assertTrue(process.isAlive());

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('i'));

            assertSame(shell, window.getActiveView());
            assertEquals("INPUT", window.modeNameForDisplay());
            assertTrue(process.isAlive());
        }
    }

    @Test
    void shellWorkspaceCtrlGEscapeEntersBrowseModeWithoutClosingShell() throws Exception {
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 32, 11)) {
            var window = harness.getWindow();
            assertTrue(window.showShellWorkspace());
            var shell = assertInstanceOf(ShellPanelView.class, window.getActiveView());
            var process = processOf(shell);

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.escape());

            assertInstanceOf(BufferView.class, window.getActiveView());
            assertEquals("NORMAL", window.modeNameForDisplay());
            assertTrue(window.getBufferContext().getBuffer().isReadOnly());
            assertTrue(process.isAlive());
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
            var process = processOf(shell);

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.ctrl('g'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('w'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.key('2'));
            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.enter());

            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertTrue(process.isAlive());

            assertTrue(window.switchToRecentWindow(2));
            assertSame(shell, window.getActiveView());
            assertEquals("INPUT", window.modeNameForDisplay());
            assertTrue(process.isAlive());
        }
    }

    @Test
    void shellPanelIsReusedAfterHide() throws Exception {
        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 32, 11)) {
            var window = harness.getWindow();

            assertTrue(window.showShellPanel());
            var first = assertInstanceOf(ShellPanelView.class, window.getPanelView());

            window.hidePanel();
            assertFalse(window.isShowingPanel());

            assertTrue(window.showShellPanel());
            assertSame(first, window.getPanelView());
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

            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("explore key chains"));
        }
    }

    @Test
    void openingDirectoryShowsDirectoryBrowserWorkspaceAndCanSwitchBack() throws IOException {
        Path directory = tempDir.resolve("browse");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 24, 11)) {
            var window = harness.getWindow();

            assertTrue(window.setBufferPath(directory));

            var browser = assertInstanceOf(DirectoryBrowserView.class, window.getActiveView());
            assertEquals(directory.toAbsolutePath().normalize(), browser.getDirectory());
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("1:Browse: browse"));

            assertTrue(window.switchToRecentWindow(2));
            assertEquals(file.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
        }
    }

    @Test
    void switchingWorkspacesReordersRecentWindowLabels() throws IOException {
        Path directory = tempDir.resolve("browse-order");
        Files.createDirectories(directory);
        Path file = writeFile("window.txt", "abc");

        try (var harness = HeadlessWindowHarness.create(file, 40, 12)) {
            var window = harness.getWindow();

            assertTrue(window.showDirectoryBrowser(directory));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("1:Browse: browse-order"));
            assertTrue(window.getKeyMenuView().buildHeaderLine().toString().contains("2:window.txt"));

            assertTrue(window.switchToRecentWindow(2));
            String header = window.getKeyMenuView().buildHeaderLine().toString();
            assertTrue(header.contains("1:window.txt"));
            assertTrue(header.contains("2:Browse: browse-order"));
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

            assertTrue(window.showDirectoryBrowser(directory));
            assertTrue(window.switchToRecentWindow(2));

            assertEquals(2, leafViews(window).size());
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

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private static Rect absoluteBounds(View view) {
        return view.getBounds();
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

    private static TerminalEmulator emulatorOf(ShellPanelView shell) throws Exception {
        var field = ShellPanelView.class.getDeclaredField("_emulator");
        field.setAccessible(true);
        return (TerminalEmulator) field.get(shell);
    }

    @SuppressWarnings("unchecked")
    private static List<View> leafViews(Window window) throws Exception {
        return (List<View>) invoke(window, "getLeafViews", new Class<?>[0]);
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
}
