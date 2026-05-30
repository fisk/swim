package org.fisk.swim.debug;

import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.Window;

public final class DebuggerUiSupport {
    private DebuggerUiSupport() {
    }

    public static void toggle(Window window) {
        if (window == null) {
            return;
        }
        if (window.getPanelView() instanceof DebuggerPanelView) {
            window.hidePanel();
            return;
        }
        open(window);
    }

    public static void open(Window window) {
        if (window == null) {
            return;
        }
        if (window.getPanelView() instanceof DebuggerPanelView) {
            window.activateView(window.getPanelView());
            return;
        }
        if (window.isShowingPanel()) {
            window.hidePanel();
        }
        window.showSidePanel(new DebuggerPanelView(Rect.create(0, 0, 0, 0)), false, 0.40);
    }
}
