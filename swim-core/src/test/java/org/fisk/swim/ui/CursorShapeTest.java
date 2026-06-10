package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.mail.MailAccountSummary;
import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.terminal.TerminalCursorShape;
import org.fisk.swim.todo.TodoItem;
import org.fisk.swim.todo.TodoSnapshot;
import org.fisk.swim.todo.TodoStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CursorShapeTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
        EventThread.shutdownInstance();
        TerminalContext.shutdownInstance();
    }

    @Test
    void windowUsesBarCursorInInputModeAndBlockCursorInNormalMode() throws Exception {
        var installed = TerminalContextTestSupport.install(80, 16);
        Path path = tempDir.resolve("cursor-mode.txt");
        Files.writeString(path, "abc\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 16)) {
            Window window = harness.getWindow();

            window.update(true);
            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('i'));
            window.update(true);
            HeadlessWindowHarness.dispatch(window.getInputMode(), HeadlessWindowHarness.escape());
            window.update(true);

            assertTrue(installed.terminalWrites().contains(TerminalCursorShape.BAR.escapeSequence()));
            assertEquals(TerminalCursorShape.BLOCK.escapeSequence(),
                    installed.terminalWrites().get(installed.terminalWrites().size() - 1));
        }
    }

    @Test
    void windowUsesUnderlineCursorInReplaceMode() throws Exception {
        var installed = TerminalContextTestSupport.install(80, 16);
        Path path = tempDir.resolve("cursor-replace.txt");
        Files.writeString(path, "abc\n");

        try (var harness = HeadlessWindowHarness.create(path, 80, 16)) {
            Window window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getNormalMode(), HeadlessWindowHarness.key('R'));
            window.update(true);

            assertEquals(TerminalCursorShape.UNDERLINE.escapeSequence(),
                    installed.terminalWrites().get(installed.terminalWrites().size() - 1));
        }
    }

    @Test
    void typedPromptViewsExposeBarCursors() throws Exception {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root.resolve(".git"));
        Path file = root.resolve("current.txt");
        Files.writeString(file, "abc\n");

        try (var harness = HeadlessWindowHarness.create(file, 80, 16)) {
            Window window = harness.getWindow();

            window.getCommandView().activate(":");
            assertEquals(TerminalCursorShape.BAR, window.getCommandView().getCursor().getShape());

            var chat = new ChatPanelView(Rect.create(0, 0, 40, 8), "Nemo", ignored -> {
            });
            assertEquals(TerminalCursorShape.BAR, chat.getCursor().getShape());

            var capture = new TodoQuickCaptureView(Rect.create(0, 0, 40, 5));
            assertEquals(TerminalCursorShape.BAR, capture.getCursor().getShape());

            var search = ProjectSearchPanelView.create(Rect.create(0, 0, 40, 8), file);
            assertEquals(TerminalCursorShape.BAR, search.getCursor().getShape());

            var todo = new TodoWorkspaceView(Rect.create(0, 0, 80, 12), new InMemoryTodoStore());
            assertEquals(null, todo.getCursor());
            HeadlessWindowHarness.dispatch(todo, HeadlessWindowHarness.key('n'));
            assertEquals(TerminalCursorShape.BAR, todo.getCursor().getShape());

            var mail = new MailPanelView(Rect.create(0, 0, 80, 12), new CursorMailClient());
            assertEquals(null, mail.getCursor());
            HeadlessWindowHarness.dispatch(mail, HeadlessWindowHarness.key('/'));
            assertEquals(TerminalCursorShape.BAR, mail.getCursor().getShape());
            HeadlessWindowHarness.dispatch(mail, HeadlessWindowHarness.escape());
            HeadlessWindowHarness.dispatch(mail, HeadlessWindowHarness.key('c'));
            assertEquals(TerminalCursorShape.BAR, mail.getCursor().getShape());
        }
    }

    private static final class CursorMailClient implements MailClient {
        @Override
        public MailSnapshot snapshot() {
            return new MailSnapshot(
                    List.of(new MailAccountSummary("work", "Work", "IMAP", 1, 0,
                            "2026-05-15T08:00:00Z", "")),
                    List.of(new MailThreadSummary(1L, "work", "Thread", "sender@example.com",
                            "snippet", "2026-05-15T08:00:00Z", false, 1, List.of())),
                    "");
        }

        @Override
        public MailMessageDetail loadMessage(long threadId) {
            return new MailMessageDetail(threadId, threadId, "Thread", "sender@example.com",
                    "me@example.com", "2026-05-15T08:00:00Z", "Body", List.of());
        }

        @Override
        public void refresh() {
        }

        @Override
        public Path getDataPath() {
            return Path.of("/tmp/mail-cursor-test");
        }
    }

    private static final class InMemoryTodoStore implements TodoStore {
        private final LinkedHashMap<Long, MutableItem> _items = new LinkedHashMap<>();
        private long _nextId = 1L;

        @Override
        public TodoSnapshot snapshot() {
            List<TodoItem> items = _items.values().stream()
                    .map(MutableItem::toTodoItem)
                    .toList();
            return new TodoSnapshot(items, List.of(), List.of());
        }

        @Override
        public TodoItem createInboxItem(String title) {
            long id = _nextId++;
            MutableItem item = new MutableItem(id, title == null ? "" : title.trim());
            _items.put(id, item);
            return item.toTodoItem();
        }

        @Override
        public void assignProject(long itemId, String projectName) {
        }

        @Override
        public void replaceTags(long itemId, List<String> tagNames) {
        }

        @Override
        public void toggleCompleted(long itemId) {
        }

        @Override
        public void deleteItem(long itemId) {
            _items.remove(itemId);
        }

        @Override
        public Path getDataPath() {
            return Path.of("/tmp/todo-cursor-test");
        }

        @Override
        public void close() {
        }
    }

    private static final class MutableItem {
        private final long _id;
        private final String _title;

        private MutableItem(long id, String title) {
            _id = id;
            _title = title;
        }

        private TodoItem toTodoItem() {
            String now = Instant.EPOCH.toString();
            return new TodoItem(_id, _title, false, null, null, new ArrayList<>(), now, now, null);
        }
    }
}
