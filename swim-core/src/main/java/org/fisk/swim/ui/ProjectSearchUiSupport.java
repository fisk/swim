package org.fisk.swim.ui;

public final class ProjectSearchUiSupport {
    private ProjectSearchUiSupport() {
    }

    public static void toggle(Window window) {
        if (window == null) {
            return;
        }
        if (window.getPanelView() instanceof ProjectSearchPanelView) {
            window.hidePanel();
            return;
        }
        open(window, "");
    }

    public static void open(Window window, String initialQuery) {
        openPanel(window, initialQuery);
    }

    static ProjectSearchPanelView openPreview(Window window, String initialQuery) {
        return openPanel(window, initialQuery);
    }

    private static ProjectSearchPanelView openPanel(Window window, String initialQuery) {
        if (window == null) {
            return null;
        }
        if (window.getPanelView() instanceof ProjectSearchPanelView panelView) {
            panelView.setQuery(initialQuery);
            window.activateView(panelView);
            return panelView;
        }
        if (window.isShowingPanel()) {
            window.hidePanel();
        }

        var panelView = ProjectSearchPanelView.create(Rect.create(0, 0, 0, 0),
                window.getBufferContext().getBuffer().getPath());
        if (panelView == null) {
            window.getCommandView().setMessage("Project search unavailable");
            return null;
        }
        panelView.setQuery(initialQuery);
        window.showPanel(panelView);
        if (window.getPanelView() != panelView) {
            window.getCommandView().setMessage("Unable to open project search");
            return null;
        }
        return panelView;
    }
}
