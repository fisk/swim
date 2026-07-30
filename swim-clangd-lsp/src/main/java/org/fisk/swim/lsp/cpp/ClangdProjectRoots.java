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
                return findCachedCompilationDatabaseRoot(start);
            }
            current = current.getParent();
        }
        // The configured database can temporarily disappear while a build
        // directory is being regenerated. Keep clangd usable from SWIM's
        // last filtered copy until the source database returns. This is also
        // the database clangd was using immediately before that regeneration.
        return findCachedCompilationDatabaseRoot(start);
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

    static Path findCachedCompilationDatabaseRoot(Path start, Path swimHome) {
        Path workspaceRoot = findWorkspaceRoot(start);
        if (workspaceRoot == null) {
            return null;
        }
        Path cached = ClangdLspClient.getWorkspacePath(swimHome, workspaceRoot)
                .resolve("compile_commands.json");
        return Files.isRegularFile(cached) ? cached.getParent() : null;
    }

    private static Path findCachedCompilationDatabaseRoot(Path start) {
        return findCachedCompilationDatabaseRoot(start, Path.of(System.getProperty("user.home"), ".swim"));
    }
}
