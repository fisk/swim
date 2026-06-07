package org.fisk.swim.todo;

import java.util.List;

public record TodoItem(
        long id,
        String title,
        boolean completed,
        Long projectId,
        String projectName,
        List<String> tags,
        String createdAt,
        String updatedAt,
        String completedAt) {
    public TodoItem {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public boolean inInbox() {
        return projectId == null;
    }
}
