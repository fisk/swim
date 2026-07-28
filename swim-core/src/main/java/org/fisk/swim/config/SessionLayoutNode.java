package org.fisk.swim.config;

public record SessionLayoutNode(
        String orientation,
        double ratio,
        SessionLayoutNode first,
        SessionLayoutNode second,
        String path,
        int cursorPosition) {
    public SessionLayoutNode(String orientation, double ratio, SessionLayoutNode first, SessionLayoutNode second, String path) {
        this(orientation, ratio, first, second, path, 0);
    }
}
