package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.todo.TodoItem;
import org.fisk.swim.todo.TodoProject;
import org.fisk.swim.todo.TodoSnapshot;
import org.fisk.swim.todo.TodoStore;
import org.fisk.swim.todo.TodoTag;
import org.fisk.swim.todo.TodoUiSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TodoQuickCaptureWindowTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
        TodoUiSupport.shutdownInstance();
        EventThread.shutdownInstance();
        TerminalContext.shutdownInstance();
    }

    @Test
    void quickCaptureAttachesOneCenteredOverlayAndRestoresFocusOnEscape() throws Exception {
        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("window.txt"), 60, 14)) {
            var window = harness.getWindow();
            var root = window.getRootView();
            EventResponder previous = root.getFirstResponder();
            var store = new InMemoryTodoStore();

            assertTrue(window.showTodoQuickCapture(store));

            TodoQuickCaptureView capture = captureView(window);
            assertSame(capture, root.getFirstResponder());
            assertTrue(window.isShowingTodoQuickCapture());
            assertEquals(52, capture.getBounds().getSize().getWidth());
            assertEquals(4, capture.getBounds().getPoint().getX());
            assertEquals(4, capture.getBounds().getPoint().getY());

            assertFalse(window.showTodoQuickCapture(store));
            assertEquals(1, captureViews(window).size());

            HeadlessWindowHarness.dispatch(capture, HeadlessWindowHarness.escape());

            assertFalse(window.isShowingTodoQuickCapture());
            assertSame(previous, root.getFirstResponder());
            assertEquals(0, store.items().size());
        }
    }

    @Test
    void quickCaptureSubmitCreatesInboxItemRefreshesTodoWorkspaceAndRestoresFocus() throws Exception {
        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("todo-workspace.txt"), 70, 16)) {
            var window = harness.getWindow();
            var store = new InMemoryTodoStore();
            assertTrue(window.showTodoWorkspace(store));
            var todoView = assertInstanceOf(TodoWorkspaceView.class, window.getActiveView());

            assertTrue(window.showTodoQuickCapture(store));
            TodoQuickCaptureView capture = captureView(window);
            type(capture, "Capture from overlay");
            HeadlessWindowHarness.dispatch(capture, HeadlessWindowHarness.enter());

            assertFalse(window.isShowingTodoQuickCapture());
            assertSame(todoView, window.getRootView().getFirstResponder());
            assertEquals(List.of("Capture from overlay"), visibleTitles(todoView));
            assertEquals(1, store.items().size());
            assertTrue(store.items().getFirst().inInbox());
        }
    }

    @Test
    void globalCtrlTOpensQuickCaptureAndRepeatingItDoesNotDuplicateOverlay() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        Path file = tempDir.resolve("global-capture.txt");
        Files.writeString(file, "abc");
        TerminalContextTestSupport.install(40, 12);
        try {
            Window.createInstance(file);
            var window = Window.getInstance();
            var responder = EventThread.getInstance().getResponder();

            assertEquals(Response.YES, dispatch(responder, HeadlessWindowHarness.ctrl('t')));
            assertTrue(window.isShowingTodoQuickCapture());

            assertEquals(Response.YES, dispatch(responder, HeadlessWindowHarness.ctrl('t')));
            assertEquals(1, captureViews(window).size());
        } finally {
            System.setProperty("user.home", previousHome);
        }
    }

    private static Response dispatch(EventResponder responder, com.googlecode.lanterna.input.KeyStroke key) {
        var response = responder.processEvent(new KeyStrokes(List.of(key)));
        if (response == Response.YES) {
            responder.respond();
        }
        return response;
    }

    private static void type(TodoQuickCaptureView view, String text) {
        for (int i = 0; i < text.length(); i++) {
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key(text.charAt(i)));
        }
    }

    private static TodoQuickCaptureView captureView(Window window) {
        return captureViews(window).getFirst();
    }

    private static List<TodoQuickCaptureView> captureViews(Window window) {
        return rootSubviews(window).stream()
                .filter(TodoQuickCaptureView.class::isInstance)
                .map(TodoQuickCaptureView.class::cast)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static List<View> rootSubviews(Window window) {
        return (List<View>) HeadlessWindowHarness.getField(window.getRootView(), "_subviews");
    }

    @SuppressWarnings("unchecked")
    private static List<String> visibleTitles(TodoWorkspaceView view) {
        List<TodoItem> visibleItems = (List<TodoItem>) HeadlessWindowHarness.getField(view, "_visibleItems");
        return visibleItems.stream().map(TodoItem::title).toList();
    }

    private static final class InMemoryTodoStore implements TodoStore {
        private final List<TodoItem> _items = new ArrayList<>();
        private long _nextId = 1;

        @Override
        public TodoSnapshot snapshot() {
            List<TodoProject> projects = _items.stream()
                    .filter(item -> item.projectId() != null)
                    .map(item -> new TodoProject(item.projectId(), item.projectName()))
                    .distinct()
                    .sorted(Comparator.comparing(TodoProject::name))
                    .toList();
            List<TodoTag> tags = _items.stream()
                    .flatMap(item -> item.tags().stream())
                    .distinct()
                    .sorted()
                    .map(TodoTag::new)
                    .toList();
            return new TodoSnapshot(List.copyOf(_items), projects, tags);
        }

        @Override
        public TodoItem createInboxItem(String title) {
            var now = "2026-06-06T00:00:00Z";
            TodoItem item = new TodoItem(_nextId++, title.trim(), false, null, null, List.of(), now, now, null);
            _items.add(item);
            return item;
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
        }

        @Override
        public Path getDataPath() {
            return null;
        }

        @Override
        public void close() {
        }

        private List<TodoItem> items() {
            return _items;
        }
    }
}
