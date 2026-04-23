package org.fisk.swim.api;

import java.nio.file.Path;

import com.googlecode.lanterna.input.KeyStroke;

public interface SwimApp extends AutoCloseable {
    void start(Path path, SwimHost host);
    void submitKeyStroke(KeyStroke keyStroke);
    void refresh(boolean forced);
    Path getCurrentPath();
    void showMessage(String message);

    @Override
    void close();
}
