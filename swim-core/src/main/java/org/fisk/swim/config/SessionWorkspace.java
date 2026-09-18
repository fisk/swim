package org.fisk.swim.config;

public record SessionWorkspace(
        String kind,
        String path,
        String activePath,
        SessionLayoutNode layout,
        String label,
        String conversationId) {
    public SessionWorkspace {
        label = label == null || label.isBlank() ? null : label.trim();
        conversationId = conversationId == null || conversationId.isBlank() ? null : conversationId.trim();
    }

    public SessionWorkspace(String kind, String path, String activePath, SessionLayoutNode layout) {
        this(kind, path, activePath, layout, null, null);
    }

    public SessionWorkspace(String kind, String path, String activePath, SessionLayoutNode layout, String label) {
        this(kind, path, activePath, layout, label, null);
    }
}
