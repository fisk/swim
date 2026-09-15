package org.fisk.swim.api;

import java.nio.file.Path;
import java.util.List;

public interface SwimPanel {
    String getId();
    String getTitle();
    List<String> render(int width, int height);
    SwimPanelResult handleInput(String input, int width, int height);

    /** Receives a local, zero-based primary-button click when a plugin panel supports it. */
    default SwimPanelResult handleMouseClick(int x, int y, int width, int height) {
        return SwimPanelResult.ignored();
    }

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
