package org.fisk.swim.plugins.slack;

import java.util.List;

public record SlackWorkspacesConfig(List<SlackWorkspaceConfig> workspaces) {
    public SlackWorkspacesConfig {
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
    }

    static SlackWorkspacesConfig empty() {
        return new SlackWorkspacesConfig(List.of());
    }
}
