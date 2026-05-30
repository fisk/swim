package org.fisk.swim.fileindex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SwimProjectConfig {
    private final Path _root;
    private final Path _compileCommandsPath;

    private SwimProjectConfig(Path root, Path compileCommandsPath) {
        _root = root;
        _compileCommandsPath = compileCommandsPath;
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
            return new SwimProjectConfig(directory.toAbsolutePath().normalize(), null);
        }
        return new SwimProjectConfig(directory.toAbsolutePath().normalize(), parseCompileCommandsPath(directory, marker));
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
