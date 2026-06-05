package org.fisk.swim.config;

import java.util.List;

public record EditorSession(
        List<String> openBuffers,
        String activeBuffer,
        List<SessionWorkspace> workspaces,
        int activeWorkspaceIndex) {
    public EditorSession {
        openBuffers = openBuffers == null ? List.of() : List.copyOf(openBuffers);
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
        activeWorkspaceIndex = workspaces.isEmpty() ? 0 : Math.max(0, Math.min(activeWorkspaceIndex, workspaces.size() - 1));
    }

    public static EditorSession empty() {
        return new EditorSession(List.of(), null, List.of(), 0);
    }
}
