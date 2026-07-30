package org.fisk.swim.config;

public record SessionLayoutNode(
        String orientation,
        double ratio,
        SessionLayoutNode first,
        SessionLayoutNode second,
        String path,
        int cursorPosition,
        boolean active) {
    public SessionLayoutNode(String orientation, double ratio, SessionLayoutNode first, SessionLayoutNode second,
            String path, int cursorPosition) {
        this(orientation, ratio, first, second, path, cursorPosition, false);
    }

    public SessionLayoutNode(String orientation, double ratio, SessionLayoutNode first, SessionLayoutNode second, String path) {
        this(orientation, ratio, first, second, path, 0, false);
    }
}
