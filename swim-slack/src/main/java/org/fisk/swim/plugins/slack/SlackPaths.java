package org.fisk.swim.plugins.slack;

import java.nio.file.Path;

record SlackPaths(
        Path swimHome,
        Path slackHome,
        Path workspacesPath) {
    static SlackPaths fromUserHome() {
        Path swimHome = Path.of(System.getProperty("user.home"), ".swim");
        Path slackHome = swimHome.resolve("slack");
        return new SlackPaths(
                swimHome,
                slackHome,
                slackHome.resolve("workspaces.json"));
    }
}
