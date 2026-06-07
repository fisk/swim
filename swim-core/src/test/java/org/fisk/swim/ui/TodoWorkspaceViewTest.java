package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import org.fisk.swim.todo.TodoItem;
import org.fisk.swim.todo.TodoProject;
import org.fisk.swim.todo.TodoSnapshot;
import org.fisk.swim.todo.TodoStore;
import org.fisk.swim.todo.TodoTag;
import org.junit.jupiter.api.Test;

class TodoWorkspaceViewTest {
    @Test
    void newTodoStartsInInboxAndProjectAssignmentMovesItOut() {
        var store = new InMemoryTodoStore();
        var view = new TodoWorkspaceView(Rect.create(0, 0, 80, 16), store);

        dispatch(view, 'n');
        type(view, "Review design");
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        assertEquals("Review design", store.items().getFirst().title());
        assertEquals(null, store.items().getFirst().projectName());
        assertEquals(List.of("Review design"), visibleTitles(view));

        dispatch(view, 'p');
        type(view, "Swim");
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        assertEquals("Swim", store.items().getFirst().projectName());
        assertEquals(List.of(), visibleTitles(view));

        dispatch(view, 'a');
        assertEquals(List.of("Review design"), visibleTitles(view));
    }

    @Test
    void sidebarFiltersByProjectAndTag() {
        var store = new InMemoryTodoStore();
        store.createInboxItem("Inbox task");
        TodoItem project = store.createInboxItem("Project task");
        store.assignProject(project.id(), "Swim");
        TodoItem tagged = store.createInboxItem("Tagged task");
        store.replaceTags(tagged.id(), List.of("urgent"));
        var view = new TodoWorkspaceView(Rect.create(0, 0, 80, 16), store);

        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.tab());
        dispatch(view, 'j');
        dispatch(view, 'j');
        dispatch(view, 'j');
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        assertEquals(List.of("Project task"), visibleTitles(view));

        dispatch(view, 'j');
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        assertEquals(List.of("Tagged task"), visibleTitles(view));
    }

    @Test
    void completionMovesTodoToCompletedFilterAndCanReopen() {
        var store = new InMemoryTodoStore();
        store.createInboxItem("Finish feature");
        var view = new TodoWorkspaceView(Rect.create(0, 0, 80, 16), store);

        dispatch(view, 'c');

        assertEquals(List.of(), visibleTitles(view));

        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.tab());
        dispatch(view, 'j');
        dispatch(view, 'j');
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        assertEquals(List.of("Finish feature"), visibleTitles(view));

        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.tab());
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.enter());

        dispatch(view, 'i');
        assertEquals(List.of("Finish feature"), visibleTitles(view));
    }

    @SuppressWarnings("unchecked")
    private static List<String> visibleTitles(TodoWorkspaceView view) {
        List<TodoItem> visibleItems = (List<TodoItem>) HeadlessWindowHarness.getField(view, "_visibleItems");
        return visibleItems.stream().map(TodoItem::title).toList();
    }

    private static void type(TodoWorkspaceView view, String text) {
        for (char character : text.toCharArray()) {
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key(character));
        }
    }

    private static void dispatch(TodoWorkspaceView view, char character) {
        HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key(character));
    }

    private static final class InMemoryTodoStore implements TodoStore {
        private final LinkedHashMap<Long, MutableItem> _items = new LinkedHashMap<>();
        private long _nextId = 1L;

        @Override
        public TodoSnapshot snapshot() {
            List<TodoItem> items = items();
            List<TodoProject> projects = items.stream()
                    .filter(item -> item.projectId() != null)
                    .map(item -> new TodoProject(item.projectId(), item.projectName()))
                    .distinct()
                    .sorted(Comparator.comparing(TodoProject::name))
                    .toList();
            List<TodoTag> tags = items.stream()
                    .flatMap(item -> item.tags().stream())
                    .distinct()
                    .sorted()
                    .map(TodoTag::new)
                    .toList();
            return new TodoSnapshot(items, projects, tags);
        }

        @Override
        public TodoItem createInboxItem(String title) {
            long id = _nextId++;
            MutableItem item = new MutableItem(id, title.trim(), false, null, null, new ArrayList<>());
            _items.put(id, item);
            return item.toTodoItem();
        }

        @Override
        public void assignProject(long itemId, String projectName) {
            MutableItem item = _items.get(itemId);
            if (item == null) {
                return;
            }
            if (projectName == null || projectName.isBlank()) {
                item.projectId = null;
                item.projectName = null;
            } else {
                item.projectId = Math.abs(projectName.toLowerCase().hashCode()) + 1L;
                item.projectName = projectName.trim();
            }
        }

        @Override
        public void replaceTags(long itemId, List<String> tagNames) {
            MutableItem item = _items.get(itemId);
            if (item != null) {
                item.tags = new ArrayList<>(tagNames);
            }
        }

        @Override
        public void toggleCompleted(long itemId) {
            MutableItem item = _items.get(itemId);
            if (item != null) {
                item.completed = !item.completed;
            }
        }

        @Override
        public void deleteItem(long itemId) {
            _items.remove(itemId);
        }

        @Override
        public Path getDataPath() {
            return Path.of("/tmp/todo-test");
        }

        @Override
        public void close() {
        }

        private List<TodoItem> items() {
            return _items.values().stream()
                    .map(MutableItem::toTodoItem)
                    .toList();
        }
    }

    private static final class MutableItem {
        private final long id;
        private final String title;
        private boolean completed;
        private Long projectId;
        private String projectName;
        private List<String> tags;

        private MutableItem(long id, String title, boolean completed, Long projectId, String projectName,
                List<String> tags) {
            this.id = id;
            this.title = title;
            this.completed = completed;
            this.projectId = projectId;
            this.projectName = projectName;
            this.tags = tags;
        }

        private TodoItem toTodoItem() {
            String now = Instant.EPOCH.toString();
            return new TodoItem(id, title, completed, projectId, projectName, tags, now, now, completed ? now : null);
        }
    }
}
