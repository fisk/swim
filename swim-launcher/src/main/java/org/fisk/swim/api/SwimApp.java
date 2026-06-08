package org.fisk.swim.api;

import java.nio.file.Path;
import java.util.List;

public interface SwimApp extends AutoCloseable {
    void start(Path path, SwimHost host);

    default void start(List<Path> paths, SwimHost host) {
        start(paths == null || paths.isEmpty() ? null : paths.getFirst(), host);
    }

    void refresh(boolean forced);
    Path getCurrentPath();
    void showMessage(String message);

    default void checkpointForReload() {
    }

    @Override
    void close();
}
