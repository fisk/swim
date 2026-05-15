package org.fisk.swim.mail;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.ui.MailPanelView;
import org.fisk.swim.ui.Rect;
import org.fisk.swim.ui.Window;

public final class MailUiSupport {
    public static final String PLUGIN_ID = "swim-email";

    private MailUiSupport() {
    }

    public static void toggle(Window window) {
        if (window == null) {
            return;
        }
        if (window.getPanelView() instanceof MailPanelView) {
            window.hidePanel();
            return;
        }
        if (window.isShowingPanel()) {
            window.hidePanel();
        }

        SwimRuntime.loadPlugin(PLUGIN_ID);
        MailClient client = MailPluginRegistry.getClient();
        if (client == null) {
            window.getCommandView().setMessage("Mail plugin unavailable");
            return;
        }

        var panelView = new MailPanelView(Rect.create(0, 0, 0, 0), client);
        if (!window.showSidePanel(panelView, false, 0.20)) {
            window.getCommandView().setMessage("Unable to open mail");
        }
    }
}
