package org.fisk.swim.lsp.cpp;

import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.fileindex.SwimProjectConfig;

public final class ClangdProjectRoots {
    private ClangdProjectRoots() {
    }

    public static Path findCompilationDatabaseRoot(Path start) {
        Path current = normalizeStart(start);
        while (current != null) {
            SwimProjectConfig config = SwimProjectConfig.load(current);
            if (config != null && config.compileCommandsRoot() != null) {
                return config.compileCommandsRoot();
            }
            if (Files.isRegularFile(current.resolve("compile_commands.json"))) {
                return current;
            }
            Path buildDir = current.resolve("build");
            if (Files.isRegularFile(buildDir.resolve("compile_commands.json"))) {
                return buildDir;
            }
            if (config != null) {
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    public static Path findWorkspaceRoot(Path start) {
        Path current = normalizeStart(start);
        Path fallback = current;
        while (current != null) {
            if (hasWorkspaceMarker(current)) {
                return current;
            }
            current = current.getParent();
        }
        return fallback;
    }

    private static boolean hasWorkspaceMarker(Path directory) {
        return SwimProjectConfig.hasMarker(directory)
                || Files.isDirectory(directory.resolve(".git"))
                || Files.isRegularFile(directory.resolve("compile_commands.json"))
                || Files.isRegularFile(directory.resolve("build").resolve("compile_commands.json"))
                || Files.isRegularFile(directory.resolve("compile_flags.txt"))
                || Files.isRegularFile(directory.resolve(".clangd"))
                || Files.isRegularFile(directory.resolve("CMakeLists.txt"))
                || Files.isRegularFile(directory.resolve("meson.build"))
                || Files.isRegularFile(directory.resolve("Makefile"));
    }

    private static Path normalizeStart(Path path) {
        if (path == null) {
            return null;
        }
        Path normalized = path.toAbsolutePath().normalize();
        return Files.isDirectory(normalized) ? normalized : normalized.getParent();
    }
}
