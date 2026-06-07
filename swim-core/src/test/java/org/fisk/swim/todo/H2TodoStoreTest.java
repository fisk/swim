package org.fisk.swim.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class H2TodoStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void createsDatabaseFileAndPersistsInboxProjectTagsAndCompletionAcrossReopen() throws Exception {
        TodoPaths paths = paths();
        long itemId;

        try (var store = new H2TodoStore(paths)) {
            TodoItem item = store.createInboxItem("  Write todo tests  ");
            itemId = item.id();

            TodoSnapshot inboxSnapshot = store.snapshot();
            assertEquals(1, inboxSnapshot.items().size());
            assertTrue(inboxSnapshot.items().getFirst().inInbox());

            store.assignProject(itemId, "Swim");
            store.replaceTags(itemId, List.of("#quality", "integration", "quality"));
            store.toggleCompleted(itemId);
        }

        assertTrue(Files.exists(paths.databaseFilePath()));

        try (var reopened = new H2TodoStore(paths)) {
            TodoSnapshot snapshot = reopened.snapshot();

            assertEquals(1, snapshot.items().size());
            TodoItem item = snapshot.items().getFirst();
            assertEquals("Write todo tests", item.title());
            assertEquals("Swim", item.projectName());
            assertEquals(List.of("integration", "quality"), item.tags());
            assertTrue(item.completed());
            assertFalse(item.inInbox());
            assertEquals(List.of(new TodoProject(item.projectId(), "Swim")), snapshot.projects());
            assertEquals(List.of(new TodoTag("integration"), new TodoTag("quality")), snapshot.tags());
        }

        try (var connection = DriverManager.getConnection(paths.databaseJdbcUrl());
                var statement = connection.createStatement();
                var result = statement.executeQuery("""
                        select count(*)
                          from todo_items i
                          join todo_projects p on p.id = i.project_id
                          join todo_item_tags it on it.todo_id = i.id
                         where i.id = %d and p.name = 'Swim'
                        """.formatted(itemId))) {
            assertTrue(result.next());
            assertEquals(2, result.getInt(1));
        }
    }

    @Test
    void blankProjectMovesItemBackToInbox() throws Exception {
        try (var store = new H2TodoStore(paths())) {
            TodoItem item = store.createInboxItem("Sort notes");
            store.assignProject(item.id(), "Admin");
            store.assignProject(item.id(), "");

            TodoItem moved = store.snapshot().items().getFirst();
            assertTrue(moved.inInbox());
            assertEquals(null, moved.projectName());
        }
    }

    @Test
    void normalizesTagsByTrimmingHashPrefixAndDeduplicatingCaseInsensitively() {
        assertEquals(List.of("urgent", "home"),
                H2TodoStore.normalizeTags(List.of("#urgent", " urgent ", "HOME", "#home")));
    }

    private TodoPaths paths() {
        Path swimHome = tempDir.resolve(".swim");
        Path todoHome = swimHome.resolve("todo");
        return new TodoPaths(swimHome, todoHome, todoHome.resolve("todos.mv.db"));
    }
}
