package org.fisk.swim.config;

import java.nio.file.Path;

public record EditorPaths(
        Path swimHome,
        Path configPath,
        Path sessionPath) {
    public static EditorPaths fromUserHome() {
        Path swimHome = Path.of(System.getProperty("user.home"), ".swim");
        return new EditorPaths(
                swimHome,
                swimHome.resolve("config.json"),
                swimHome.resolve("session.json"));
    }
}
