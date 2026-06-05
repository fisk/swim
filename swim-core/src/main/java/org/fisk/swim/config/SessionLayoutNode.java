package org.fisk.swim.config;

public record SessionLayoutNode(
        String orientation,
        double ratio,
        SessionLayoutNode first,
        SessionLayoutNode second,
        String path) {
}
