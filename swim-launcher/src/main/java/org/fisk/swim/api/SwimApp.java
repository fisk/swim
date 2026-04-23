package org.fisk.swim.api;

import java.nio.file.Path;

public interface SwimApp extends AutoCloseable {
    void start(Path path, SwimHost host);
    void refresh(boolean forced);
    Path getCurrentPath();
    void showMessage(String message);

    @Override
    void close();
}
