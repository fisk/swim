package org.fisk.swim.config;

public record SessionWorkspace(
        String kind,
        String path,
        String activePath,
        SessionLayoutNode layout,
        String label) {
    public SessionWorkspace {
        label = label == null || label.isBlank() ? null : label.trim();
    }

    public SessionWorkspace(String kind, String path, String activePath, SessionLayoutNode layout) {
        this(kind, path, activePath, layout, null);
    }
}
