package org.fisk.swim.config;

import java.util.List;

public record EditorSession(
        List<String> openBuffers,
        String activeBuffer,
        List<SessionWorkspace> workspaces,
        int activeWorkspaceIndex,
        String nemoOverlayConversationId) {
    public EditorSession {
        openBuffers = openBuffers == null ? List.of() : List.copyOf(openBuffers);
        workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
        activeWorkspaceIndex = workspaces.isEmpty() ? 0 : Math.max(0, Math.min(activeWorkspaceIndex, workspaces.size() - 1));
        nemoOverlayConversationId = nemoOverlayConversationId == null || nemoOverlayConversationId.isBlank()
                ? null : nemoOverlayConversationId.trim();
    }

    public EditorSession(List<String> openBuffers, String activeBuffer, List<SessionWorkspace> workspaces,
            int activeWorkspaceIndex) {
        this(openBuffers, activeBuffer, workspaces, activeWorkspaceIndex, null);
    }

    public static EditorSession empty() {
        return new EditorSession(List.of(), null, List.of(), 0, null);
    }
}
