package org.fisk.swim.todo;

import java.util.List;

public record TodoSnapshot(List<TodoItem> items, List<TodoProject> projects, List<TodoTag> tags) {
    public TodoSnapshot {
        items = items == null ? List.of() : List.copyOf(items);
        projects = projects == null ? List.of() : List.copyOf(projects);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static TodoSnapshot empty() {
        return new TodoSnapshot(List.of(), List.of(), List.of());
    }
}
