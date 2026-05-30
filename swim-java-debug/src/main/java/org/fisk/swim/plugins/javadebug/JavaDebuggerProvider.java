package org.fisk.swim.plugins.javadebug;

import java.nio.file.Path;
import java.util.ArrayList;

import org.fisk.swim.debug.DebugLaunchRequest;
import org.fisk.swim.debug.DebuggerProvider;
import org.fisk.swim.debug.DebuggerSession;

final class JavaDebuggerProvider implements DebuggerProvider {
    @Override
    public String id() {
        return "java";
    }

    @Override
    public String displayName() {
        return "Java";
    }

    @Override
    public String usage() {
        return ":debug java launch <main-class> <class-path> [source-root] [program-args...]";
    }

    @Override
    public DebuggerSession launch(DebugLaunchRequest request) throws Exception {
        if (request.args().isEmpty()) {
            throw new IllegalArgumentException(usage());
        }
        var args = new ArrayList<>(request.args());
        if ("launch".equals(args.getFirst())) {
            args.removeFirst();
        }
        if (args.size() < 2) {
            throw new IllegalArgumentException(usage());
        }
        String mainClass = args.removeFirst();
        Path classPath = Path.of(args.removeFirst()).toAbsolutePath().normalize();
        Path sourceRoot = args.isEmpty() ? defaultSourceRoot(request.currentPath(), classPath)
                : Path.of(args.removeFirst()).toAbsolutePath().normalize();
        return JavaDebuggerSession.launch(mainClass, classPath, sourceRoot, args);
    }

    private static Path defaultSourceRoot(Path currentPath, Path classPath) {
        if (currentPath != null && currentPath.getParent() != null) {
            return currentPath.getParent();
        }
        return classPath;
    }
}
