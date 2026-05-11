package org.fisk.swim.api;

import java.nio.file.Path;
import java.util.List;

public interface SwimPanel {
    String getId();
    String getTitle();
    List<String> render(int width, int height);
    SwimPanelResult handleInput(String input, int width, int height);

    default void syncToCurrentPath(Path path) {
    }
}
