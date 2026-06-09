package org.fisk.swim.api;

import java.nio.file.Path;
import java.util.List;

public interface SwimPanel {
    String getId();
    String getTitle();
    List<String> render(int width, int height);
    SwimPanelResult handleInput(String input, int width, int height);

    default List<SwimPanelLine> renderRich(int width, int height) {
        return render(width, height).stream()
                .map(SwimPanelLine::plain)
                .toList();
    }

    default List<SwimKeyBindingHint> keyBindingHints() {
        return List.of();
    }

    default void syncToCurrentPath(Path path) {
    }
}
