package org.fisk.swim.todo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public final class H2TodoStore implements TodoStore {
    private static final Logger LOG = LogFactory.createLog();

    private final TodoPaths _paths;
    private final Object _lock = new Object();
    private Connection _connection;

    public H2TodoStore() throws SQLException, IOException {
        this(TodoPaths.fromUserHome());
    }

    H2TodoStore(TodoPaths paths) throws SQLException, IOException {
        _paths = paths;
        ensureH2DriverLoaded();
        Files.createDirectories(paths.todoHome());
        try {
            _connection = DriverManager.getConnection(paths.databaseJdbcUrl());
            TodoDb.initialize(_connection);
        } catch (SQLException | RuntimeException e) {
            close();
            throw e;
        }
    }

    @Override
    public TodoSnapshot snapshot() {
        synchronized (_lock) {
            try {
                return new TodoSnapshot(loadItems(), loadProjects(), loadTags());
            } catch (SQLException e) {
                throw new RuntimeException("Failed to load todos", e);
            }
        }
    }

    @Override
    public TodoItem createInboxItem(String title) {
        String cleanedTitle = normalizeTitle(title);
        if (cleanedTitle.isBlank()) {
            throw new IllegalArgumentException("Todo title cannot be empty");
        }
        synchronized (_lock) {
            try {
                String now = now();
                try (var statement = _connection.prepareStatement("""
                        insert into todo_items(project_id, title, status, created_at, updated_at)
                        values(null, ?, 'open', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, cleanedTitle);
                    statement.setString(2, now);
                    statement.setString(3, now);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (keys.next()) {
                            return loadItem(keys.getLong(1));
                        }
                    }
                }
                throw new SQLException("Todo insert did not return an id");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to create todo", e);
            }
        }
    }

    @Override
    public void assignProject(long itemId, String projectName) {
        synchronized (_lock) {
            try {
                Long projectId = null;
                String cleaned = normalizeName(projectName);
                if (!cleaned.isBlank()) {
                    projectId = ensureProject(cleaned);
                }
                try (var statement = _connection.prepareStatement(
                        "update todo_items set project_id = ?, updated_at = ? where id = ?")) {
                    if (projectId == null) {
                        statement.setNull(1, java.sql.Types.BIGINT);
                    } else {
                        statement.setLong(1, projectId);
                    }
                    statement.setString(2, now());
                    statement.setLong(3, itemId);
                    statement.executeUpdate();
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to assign todo project", e);
            }
        }
    }

    @Override
    public void replaceTags(long itemId, List<String> tagNames) {
        synchronized (_lock) {
            boolean previousAutoCommit = true;
            try {
                previousAutoCommit = _connection.getAutoCommit();
                _connection.setAutoCommit(false);
                try (var delete = _connection.prepareStatement("delete from todo_item_tags where todo_id = ?")) {
                    delete.setLong(1, itemId);
                    delete.executeUpdate();
                }
                for (String tagName : normalizeTags(tagNames)) {
                    String canonical = ensureTag(tagName);
                    try (var insert = _connection.prepareStatement("""
                            merge into todo_item_tags(todo_id, tag_name)
                            key(todo_id, tag_name)
                            values(?, ?)
                            """)) {
                        insert.setLong(1, itemId);
                        insert.setString(2, canonical);
                        insert.executeUpdate();
                    }
                }
                try (var update = _connection.prepareStatement("update todo_items set updated_at = ? where id = ?")) {
                    update.setString(1, now());
                    update.setLong(2, itemId);
                    update.executeUpdate();
                }
                _connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly();
                throw new RuntimeException("Failed to update todo tags", e);
            } finally {
                restoreAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public void toggleCompleted(long itemId) {
        synchronized (_lock) {
            try {
                TodoItem item = loadItem(itemId);
                if (item == null) {
                    return;
                }
                boolean completed = !item.completed();
                try (var statement = _connection.prepareStatement("""
                        update todo_items
                           set status = ?, completed_at = ?, updated_at = ?
                         where id = ?
                        """)) {
                    statement.setString(1, completed ? "done" : "open");
                    statement.setString(2, completed ? now() : null);
                    statement.setString(3, now());
                    statement.setLong(4, itemId);
                    statement.executeUpdate();
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to toggle todo completion", e);
            }
        }
    }

    @Override
    public void deleteItem(long itemId) {
        synchronized (_lock) {
            boolean previousAutoCommit = true;
            try {
                previousAutoCommit = _connection.getAutoCommit();
                _connection.setAutoCommit(false);
                try (var deleteTags = _connection.prepareStatement("delete from todo_item_tags where todo_id = ?")) {
                    deleteTags.setLong(1, itemId);
                    deleteTags.executeUpdate();
                }
                try (var delete = _connection.prepareStatement("delete from todo_items where id = ?")) {
                    delete.setLong(1, itemId);
                    delete.executeUpdate();
                }
                _connection.commit();
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly();
                throw new RuntimeException("Failed to delete todo", e);
            } finally {
                restoreAutoCommit(previousAutoCommit);
            }
        }
    }

    @Override
    public Path getDataPath() {
        return _paths.todoHome();
    }

    @Override
    public void close() {
        synchronized (_lock) {
            if (_connection == null) {
                return;
            }
            try {
                _connection.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close todo database", e);
            } finally {
                _connection = null;
            }
        }
    }

    private List<TodoProject> loadProjects() throws SQLException {
        var projects = new ArrayList<TodoProject>();
        try (var statement = _connection.prepareStatement("""
                select id, name
                  from todo_projects
                 where archived = 0
                 order by lower(name), name
                """);
                var result = statement.executeQuery()) {
            while (result.next()) {
                projects.add(new TodoProject(result.getLong("id"), result.getString("name")));
            }
        }
        return projects;
    }

    private List<TodoTag> loadTags() throws SQLException {
        var tags = new ArrayList<TodoTag>();
        try (var statement = _connection.prepareStatement("select name from todo_tags order by lower(name), name");
                var result = statement.executeQuery()) {
            while (result.next()) {
                tags.add(new TodoTag(result.getString("name")));
            }
        }
        return tags;
    }

    private List<TodoItem> loadItems() throws SQLException {
        Map<Long, TodoItemBuilder> builders = new LinkedHashMap<>();
        try (var statement = _connection.prepareStatement("""
                select i.id, i.title, i.status, i.project_id, p.name as project_name,
                       i.created_at, i.updated_at, i.completed_at
                  from todo_items i
                  left join todo_projects p on p.id = i.project_id
                 order by case when i.status = 'done' then 1 else 0 end,
                          i.created_at desc,
                          i.id desc
                """);
                var result = statement.executeQuery()) {
            while (result.next()) {
                long id = result.getLong("id");
                Long projectId = result.getObject("project_id") == null ? null : result.getLong("project_id");
                builders.put(id, new TodoItemBuilder(
                        id,
                        result.getString("title"),
                        "done".equals(result.getString("status")),
                        projectId,
                        result.getString("project_name"),
                        result.getString("created_at"),
                        result.getString("updated_at"),
                        result.getString("completed_at")));
            }
        }
        if (builders.isEmpty()) {
            return List.of();
        }
        try (var statement = _connection.prepareStatement("""
                select todo_id, tag_name
                  from todo_item_tags
                 order by lower(tag_name), tag_name
                """);
                var result = statement.executeQuery()) {
            while (result.next()) {
                TodoItemBuilder builder = builders.get(result.getLong("todo_id"));
                if (builder != null) {
                    builder.tags().add(result.getString("tag_name"));
                }
            }
        }
        return builders.values().stream().map(TodoItemBuilder::build).toList();
    }

    private TodoItem loadItem(long id) throws SQLException {
        for (TodoItem item : loadItems()) {
            if (item.id() == id) {
                return item;
            }
        }
        return null;
    }

    private long ensureProject(String name) throws SQLException {
        String normalized = normalizeKey(name);
        try (var select = _connection.prepareStatement("select id from todo_projects where name_lc = ?")) {
            select.setString(1, normalized);
            try (var result = select.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }
        String now = now();
        try (var insert = _connection.prepareStatement("""
                insert into todo_projects(name, name_lc, created_at, updated_at)
                values(?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, name);
            insert.setString(2, normalized);
            insert.setString(3, now);
            insert.setString(4, now);
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Project insert did not return an id");
    }

    private String ensureTag(String name) throws SQLException {
        String normalized = normalizeKey(name);
        try (var select = _connection.prepareStatement("select name from todo_tags where name_lc = ?")) {
            select.setString(1, normalized);
            try (var result = select.executeQuery()) {
                if (result.next()) {
                    return result.getString(1);
                }
            }
        }
        try (var insert = _connection.prepareStatement("""
                insert into todo_tags(name, name_lc)
                values(?, ?)
                """)) {
            insert.setString(1, name);
            insert.setString(2, normalized);
            insert.executeUpdate();
        }
        return name;
    }

    private void rollbackQuietly() {
        try {
            _connection.rollback();
        } catch (SQLException e) {
            LOG.warn("Failed to roll back todo transaction", e);
        }
    }

    private void restoreAutoCommit(boolean value) {
        try {
            _connection.setAutoCommit(value);
        } catch (SQLException e) {
            LOG.warn("Failed to restore todo autocommit", e);
        }
    }

    private static String normalizeTitle(String title) {
        return title == null ? "" : title.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeKey(String value) {
        return normalizeName(value).toLowerCase(Locale.ROOT);
    }

    static List<String> normalizeTags(List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashMap<String, String>();
        for (String raw : tagNames) {
            String tag = normalizeName(raw);
            while (tag.startsWith("#")) {
                tag = tag.substring(1).trim();
            }
            if (!tag.isBlank()) {
                tag = tag.toLowerCase(Locale.ROOT);
                normalized.putIfAbsent(normalizeKey(tag), tag);
            }
        }
        return List.copyOf(normalized.values());
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static void ensureH2DriverLoaded() throws SQLException {
        try {
            Class.forName("org.h2.Driver", true, H2TodoStore.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new SQLException("H2 JDBC driver is unavailable", e);
        }
    }

    private record TodoItemBuilder(
            long id,
            String title,
            boolean completed,
            Long projectId,
            String projectName,
            String createdAt,
            String updatedAt,
            String completedAt,
            List<String> tags) {
        TodoItemBuilder(long id, String title, boolean completed, Long projectId, String projectName,
                String createdAt, String updatedAt, String completedAt) {
            this(id, title, completed, projectId, projectName, createdAt, updatedAt, completedAt, new ArrayList<>());
        }

        TodoItem build() {
            return new TodoItem(id, title, completed, projectId, projectName, tags, createdAt, updatedAt, completedAt);
        }
    }
}
