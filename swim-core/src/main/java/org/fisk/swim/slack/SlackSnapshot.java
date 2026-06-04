package org.fisk.swim.slack;

import java.util.List;

public record SlackSnapshot(
        List<SlackWorkspaceSummary> workspaces,
        String statusMessage) {
    public static SlackSnapshot empty() {
        return new SlackSnapshot(List.of(), "");
    }
}
