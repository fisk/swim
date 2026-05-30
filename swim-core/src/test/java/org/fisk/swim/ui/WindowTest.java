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
import java.util.List;

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
            HeadlessWindowHarness.dispatch(holder[0], HeadlessWindowHarness.key(':'));
            HeadlessWindowHarness.dispatch(holder[0], HeadlessWindowHarness.key('q'));

            var state = window.getCommandMenuView().getState();
            assertTrue(state.visible());
            assertEquals("q", state.prefix());
            assertEquals("q", state.matches().get(0).primaryName());
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

            HeadlessWindowHarness.dispatch(shell, HeadlessWindowHarness.escape());

            var processField = ShellPanelView.class.getDeclaredField("_process");
            processField.setAccessible(true);
            Process process = (Process) processField.get(shell);
            process.waitFor();
            assertFalse(process.isAlive());
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
    void openingDirectoryShowsDirectoryBrowserPanel() throws IOException {
        Path directory = tempDir.resolve("browse");
        Files.createDirectories(directory);

        try (var harness = HeadlessWindowHarness.create(writeFile("window.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();

            assertTrue(window.setBufferPath(directory));

            var browser = assertInstanceOf(DirectoryBrowserView.class, window.getPanelView());
            assertEquals(directory.toAbsolutePath().normalize(), browser.getDirectory());
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
}
