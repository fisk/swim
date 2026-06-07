package org.fisk.swim.todo;

import java.nio.file.Path;
import java.util.List;

public interface TodoStore extends AutoCloseable {
    TodoSnapshot snapshot();

    TodoItem createInboxItem(String title);

    void assignProject(long itemId, String projectName);

    void replaceTags(long itemId, List<String> tagNames);

    void toggleCompleted(long itemId);

    void deleteItem(long itemId);

    Path getDataPath();

    @Override
    void close();
}
