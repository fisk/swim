package org.fisk.swim.plugins.git;

import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.api.SwimPluginContext;

final class GitPluginSession implements AutoCloseable {
    private final GitStatusController _controller = new GitStatusController();

    GitPluginSession(SwimPluginContext context) {
        Path current = context.getCurrentPath() != null ? context.getCurrentPath() : context.getInitialPath();
        _controller.syncToPath(current);
    }

    String getTitle() {
        return _controller.title();
    }

    List<String> render(int width, int height) {
        return _controller.render(width, height);
    }

    SwimPanelResult handleInput(String input, int width, int height) {
        return _controller.handleInput(input, width, height);
    }

    void syncToPath(Path path) {
        _controller.syncToPath(path);
    }

    @Override
    public void close() {
    }
}
