package org.fisk.swim.fileindex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SwimProjectConfig {
    private final Path _root;
    private final Path _compileCommandsPath;
    private final List<Path> _nemoWorkspaceWriteRoots;

    private SwimProjectConfig(Path root, Path compileCommandsPath, List<Path> nemoWorkspaceWriteRoots) {
        _root = root;
        _compileCommandsPath = compileCommandsPath;
        _nemoWorkspaceWriteRoots = List.copyOf(nemoWorkspaceWriteRoots);
    }

    public static boolean hasMarker(Path directory) {
        if (directory == null) {
            return false;
        }
        Path marker = directory.resolve(".swim");
        return Files.isDirectory(marker) || Files.isRegularFile(marker);
    }

    public static SwimProjectConfig load(Path directory) {
        if (directory == null || !hasMarker(directory)) {
            return null;
        }
        Path marker = directory.resolve(".swim");
        if (!Files.isRegularFile(marker)) {
            return new SwimProjectConfig(directory.toAbsolutePath().normalize(), null, List.of());
        }
        Path root = directory.toAbsolutePath().normalize();
        return new SwimProjectConfig(root, parseCompileCommandsPath(root, marker), parseNemoWorkspaceWriteRoots(root, marker));
    }

    public Path root() {
        return _root;
    }

    public Path compileCommandsPath() {
        return _compileCommandsPath;
    }

    public Path compileCommandsRoot() {
        if (_compileCommandsPath == null) {
            return null;
        }
        return _compileCommandsPath.getParent();
    }

    public List<Path> nemoWorkspaceWriteRoots() { return _nemoWorkspaceWriteRoots; }

    private static List<Path> parseNemoWorkspaceWriteRoots(Path root, Path marker) {
        try {
            var roots = new java.util.ArrayList<Path>();
            for (String line : Files.readAllLines(marker)) {
                String[] pair = line.split("=", 2);
                if (pair.length != 2 || !"nemo.workspace_write_roots".equals(pair[0].trim())) continue;
                for (String value : pair[1].split(",")) {
                    Path path = Path.of(value.trim());
                    roots.add((path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize());
                }
            }
            return roots;
        } catch (IOException e) { return List.of(); }
    }

    private static Path parseCompileCommandsPath(Path root, Path marker) {
        try {
            List<String> lines = Files.readAllLines(marker);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 0) {
                    separator = trimmed.indexOf(':');
                }
                if (separator < 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (!"compile_commands".equals(key) || value.isEmpty()) {
                    continue;
                }
                Path configured = Path.of(value);
                if (!configured.isAbsolute()) {
                    configured = root.resolve(configured);
                }
                configured = configured.toAbsolutePath().normalize();
                return Files.isRegularFile(configured) ? configured : null;
            }
        } catch (IOException e) {
        }
        return null;
    }
}
