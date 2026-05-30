package org.fisk.swim.fileindex;

import java.nio.file.Path;
import java.nio.file.Paths;

public class ProjectPaths {
    private static Path normalizeStart(Path path) {
        if (path == null) {
            return null;
        }
        path = path.toAbsolutePath();
        if (path.toFile().isFile()) {
            return path.getParent();
        }
        return path;
    }

    private static Path findRoot(Path start, String marker1, String marker2) {
        var path = normalizeStart(start);
        if (path == null) {
            return null;
        }
        var root = path.getRoot();
        while (path != null) {
            if (path.resolve(marker1).toFile().exists() || path.resolve(marker2).toFile().exists()) {
                return path;
            }
            if (root != null && root.equals(path)) {
                break;
            }
            path = path.getParent();
        }
        return null;
    }

    private static Path findRoot(Path start, java.util.function.Predicate<Path> predicate) {
        var path = normalizeStart(start);
        if (path == null) {
            return null;
        }
        var root = path.getRoot();
        while (path != null) {
            if (predicate.test(path)) {
                return path;
            }
            if (root != null && root.equals(path)) {
                break;
            }
            path = path.getParent();
        }
        return null;
    }

    public static Path getSourceRootPath(Path start) {
        return findRoot(start, path -> path.resolve(".git").toFile().exists() || SwimProjectConfig.hasMarker(path));
    }

    public static Path getSourceRootPath() {
        return getSourceRootPath(Paths.get(System.getProperty("user.dir")));
    }

    public static Path getProjectRootPath(Path start) {
        return findRoot(start,
                path -> path.resolve(".git").toFile().exists()
                        || path.resolve("pom.xml").toFile().exists()
                        || SwimProjectConfig.hasMarker(path));
    }

    public static Path getProjectRootPath() {
        return getProjectRootPath(Paths.get(System.getProperty("user.dir")));
    }

    public static boolean hasRepository() {
        var projectPath = getProjectRootPath();
        if (projectPath == null) {
            return false;
        }
        var gitPath = projectPath.resolve(".git");
        return gitPath.toFile().exists();
    }
}
