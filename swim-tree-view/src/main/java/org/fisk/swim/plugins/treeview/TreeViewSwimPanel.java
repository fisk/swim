package org.fisk.swim.plugins.treeview;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;

final class TreeViewSwimPanel implements SwimPanel {
    private final TreeViewPluginSession _session;

    TreeViewSwimPanel(TreeViewPluginSession session) {
        _session = session;
    }

    @Override
    public String getId() {
        return TreeViewPluginSupport.PLUGIN_ID;
    }

    @Override
    public String getTitle() {
        return _session.getPanel().snapshot(1, 1).lines().getFirst().strip();
    }

    @Override
    public List<String> render(int width, int height) {
        return _session.snapshot(width, height).lines();
    }

    @Override
    public SwimPanelResult handleInput(String input, int width, int height) {
        try {
            var interaction = _session.interact(input, width, height);
            var action = interaction.commandResult().action();
            if (action.type() == TreeViewActionType.OPEN_FILE) {
                return SwimPanelResult.success(action.path());
            }
            return interaction.commandResult().handled()
                    ? SwimPanelResult.success()
                    : SwimPanelResult.ignored();
        } catch (IOException e) {
            return SwimPanelResult.successMessage("Tree view refresh failed");
        }
    }

    @Override
    public void syncToCurrentPath(Path path) {
        _session.syncToPath(path);
    }
}
