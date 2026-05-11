package org.fisk.swim.plugins.treeview;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class TreeViewProjectRoot {
    private TreeViewProjectRoot() {
    }

    public static Path resolve(Path initialPath, Path currentPath) {
        Path candidate = initialPath != null ? initialPath : currentPath;
        if (candidate == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return normalized;
        }
        Path parent = normalized.getParent();
        return parent != null ? parent : normalized;
    }
}
