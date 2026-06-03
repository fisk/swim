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
        if (window.isShowingMailWorkspace()) {
            window.hideCurrentWorkspaceWindow();
            return;
        }

        MailClient client = MailPluginRegistry.getClient();
        if (client == null) {
            SwimRuntime.loadPlugin(PLUGIN_ID);
            client = MailPluginRegistry.getClient();
        }
        if (client == null) {
            window.getCommandView().setMessage("Mail plugin unavailable");
            return;
        }

        if (!window.showMailWorkspace(client)) {
            window.getCommandView().setMessage("Unable to open mail");
        }
    }
}
