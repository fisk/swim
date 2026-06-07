package org.fisk.swim.ui;

import org.fisk.swim.SwimRuntime;

public final class TreeUiSupport {
    private static final String TREE_VIEW_PLUGIN_ID = "swim-tree-view";

    private TreeUiSupport() {
    }

    public static void toggle(Window window) {
        if (window == null) {
            return;
        }
        if (window.getPanelView() instanceof PluginPanelView panelView
                && TREE_VIEW_PLUGIN_ID.equals(panelView.getPluginId())) {
            window.hidePanel();
            return;
        }
        if (window.isShowingPanel()) {
            window.hidePanel();
        }
        SwimRuntime.loadPlugin(TREE_VIEW_PLUGIN_ID);
        var panel = SwimRuntime.getPanel(TREE_VIEW_PLUGIN_ID);
        if (panel == null) {
            window.getCommandView().setMessage("Tree view plugin unavailable");
            return;
        }
        panel.syncToCurrentPath(window.getBufferContext().getBuffer().getPath());
        if (!window.showSidePanel(new PluginPanelView(Rect.create(0, 0, 0, 0), TREE_VIEW_PLUGIN_ID, panel),
                true, 0.28)) {
            window.getCommandView().setMessage("Unable to open tree view");
        }
    }
}
