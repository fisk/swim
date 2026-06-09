package org.fisk.swim.plugins.git;

import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.api.SwimKeyBindingHint;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;

final class GitSwimPanel implements SwimPanel {
    private final GitPluginSession _session;

    GitSwimPanel(GitPluginSession session) {
        _session = session;
    }

    @Override
    public String getId() {
        return GitPluginSupport.PLUGIN_ID;
    }

    @Override
    public String getTitle() {
        return _session.getTitle();
    }

    @Override
    public List<String> render(int width, int height) {
        return _session.render(width, height);
    }

    @Override
    public SwimPanelResult handleInput(String input, int width, int height) {
        return _session.handleInput(input, width, height);
    }

    @Override
    public List<SwimKeyBindingHint> keyBindingHints() {
        return _session.keyBindingHints();
    }

    @Override
    public void syncToCurrentPath(Path path) {
        _session.syncToPath(path);
    }
}
