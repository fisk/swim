package org.fisk.swim.slack;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.ui.Window;

public final class SlackUiSupport {
    public static final String PLUGIN_ID = "swim-slack";

    private SlackUiSupport() {
    }

    public static void toggle(Window window) {
        if (window == null) {
            return;
        }
        if (window.isShowingSlackWorkspace()) {
            window.hideCurrentWorkspaceWindow();
            return;
        }

        SlackClient client = SlackPluginRegistry.getClient();
        if (client == null) {
            SwimRuntime.loadPlugin(PLUGIN_ID);
            client = SlackPluginRegistry.getClient();
        }
        if (client == null) {
            window.getCommandView().setMessage("Slack plugin unavailable");
            return;
        }

        if (!window.showSlackWorkspace(client)) {
            window.getCommandView().setMessage("Unable to open Slack");
        }
    }
}
