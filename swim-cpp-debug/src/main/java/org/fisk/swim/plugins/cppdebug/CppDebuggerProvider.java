package org.fisk.swim.plugins.cppdebug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.nio.file.Files;

import org.fisk.swim.debug.DebugLaunchRequest;
import org.fisk.swim.debug.DebuggerProvider;
import org.fisk.swim.debug.DebuggerSession;

final class CppDebuggerProvider implements DebuggerProvider {
    @Override
    public String id() {
        return "cpp";
    }

    @Override
    public String displayName() {
        return "C/C++";
    }

    @Override
    public String usage() {
        return ":debug cpp [launch|gdb|lldb] <executable> [source-root] [--cwd <directory>] [program-args...]";
    }

    @Override
    public DebuggerSession launch(DebugLaunchRequest request) throws Exception {
        if (request.args().isEmpty()) {
            throw new IllegalArgumentException(usage());
        }
        var args = new ArrayList<>(request.args());
        String backend = "auto";
        if ("launch".equals(args.getFirst())) {
            args.removeFirst();
        } else if ("gdb".equals(args.getFirst()) || "lldb".equals(args.getFirst())) {
            backend = args.removeFirst();
        }
        if (args.isEmpty()) {
            throw new IllegalArgumentException(usage());
        }
        Path executable = pathFromCommand(args.removeFirst());
        Path sourceRoot = request.currentPath() == null || request.currentPath().getParent() == null
                ? executable.getParent() : request.currentPath().getParent();
        if (!args.isEmpty() && !"--cwd".equals(args.getFirst())) {
            sourceRoot = pathFromCommand(args.removeFirst());
        }
        Path workingDirectory = null;
        if (!args.isEmpty() && "--cwd".equals(args.getFirst())) {
            args.removeFirst();
            if (args.isEmpty()) {
                throw new IllegalArgumentException("Missing directory after --cwd");
            }
            workingDirectory = pathFromCommand(args.removeFirst());
            if (!Files.isDirectory(workingDirectory)) {
                throw new IllegalArgumentException("Debug working directory does not exist: " + workingDirectory);
            }
        }
        return switch (resolveBackend(backend)) {
        case "gdb" -> GdbDebuggerSession.launch(executable, sourceRoot, workingDirectory, args);
        case "lldb" -> CppDebuggerSession.launch(executable, sourceRoot, workingDirectory, args);
        default -> throw new IllegalStateException("No C/C++ debugger backend available");
        };
    }

    private static String resolveBackend(String requested) {
        if ("gdb".equals(requested) || "lldb".equals(requested)) {
            return requested;
        }
        if (gdbAvailable()) {
            return "gdb";
        }
        if (Files.isExecutable(Path.of("/usr/bin/lldb")) || onPath("lldb")) {
            return "lldb";
        }
        return "";
    }

    private static Path pathFromCommand(String value) {
        if ("~".equals(value)) {
            return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
        if (value.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), value.substring(2)).toAbsolutePath().normalize();
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static boolean gdbAvailable() {
        return Files.isExecutable(Path.of("/opt/homebrew/bin/gdb"))
                || Files.isExecutable(Path.of("/usr/bin/gdb"))
                || onPath("gdb");
    }

    private static boolean onPath(String executable) {
        try {
            var process = new ProcessBuilder("which", executable)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
