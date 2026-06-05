package org.fisk.swim.config;

public record SessionWorkspace(
        String kind,
        String path,
        String activePath,
        SessionLayoutNode layout) {
}
