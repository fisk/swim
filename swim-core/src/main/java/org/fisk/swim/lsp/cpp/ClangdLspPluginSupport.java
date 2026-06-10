package org.fisk.swim.lsp.cpp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginPreloadContext;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LanguagePluginRegistry;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;

public final class ClangdLspPluginSupport {
    public static final String PLUGIN_ID = "clangd-lsp";
    private static final List<String> C_SOURCE_HEADER_EXTENSIONS = List.of(".h");
    private static final List<String> CPP_HEADER_EXTENSIONS = List.of(".hpp", ".h", ".hh", ".hxx");
    private static final List<String> CXX_HEADER_EXTENSIONS = List.of(".hxx", ".hpp", ".hh", ".h");
    private static final List<String> CC_HEADER_EXTENSIONS = List.of(".hh", ".hpp", ".h", ".hxx");
    private static final List<String> H_HEADER_SOURCE_EXTENSIONS = List.of(".c", ".cpp", ".cxx", ".cc");
    private static final List<String> CPP_SOURCE_EXTENSIONS = List.of(".cpp", ".cxx", ".cc", ".c");
    private static final List<String> ALL_CPP_EXTENSIONS = List.of(".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx");

    private static final ClangdLspClient UNAVAILABLE = new ClangdLspClient(new ClangdLspProvider((Path) null));

    private ClangdLspPluginSupport() {
    }

    public static ClangdLspClient getClient() {
        return ClangdLspClient.getInstalledInstanceOr(UNAVAILABLE);
    }

    public static void ensureLoaded(Path path) {
        LogFactory.createLog().debug("Ensuring clangd LSP plugin loaded for {}", path);
        SwimRuntime.loadPlugin(PLUGIN_ID, path);
    }

    public static void ensureStartedForProject(Path path) {
        if (ClangdProjectRoots.findCompilationDatabaseRoot(path) == null) {
            return;
        }
        ensureLoaded(path);
        startClientIfNeeded(path, getClient());
    }

    public static LanguageMode createLanguageMode(Path path) {
        if (ClangdProjectRoots.findCompilationDatabaseRoot(path) == null) {
            return getClient();
        }
        ensureLoaded(path);
        var client = startClientIfNeeded(path, getClient());
        return client;
    }

    public static void install() {
        ClangdLspClient.installInstance(new ClangdLspClient());
    }

    public static void shutdown() {
        ClangdLspClient.shutdownInstalledInstance();
    }

    public static void preload(SwimPluginPreloadContext context) {
        for (String extension : new String[] {"c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx"}) {
            context.registerPreloadResource(LanguagePluginRegistry.register(extension, PLUGIN_ID,
                    ClangdLspPluginSupport::createLanguageMode));
        }
        registerCppKey(context, "g d", "LSP", "go to definition", "lsp-definition",
                ClangdLspPluginSupport::goToDefinition);
        registerCppKey(context, "g r", "LSP", "find references", "lsp-references",
                ClangdLspPluginSupport::findReferences);
        registerCppKey(context, "g m", "C/C++", "switch header/implementation", "cpp-counterpart",
                ClangdLspPluginSupport::switchHeaderImplementation);
    }

    private static ClangdLspClient startClientIfNeeded(Path path, ClangdLspClient client) {
        if (client.isEnabled() && !client.hasStarted()) {
            try {
                client.startServer(path);
                client.ensureInit();
            } catch (RuntimeException e) {
                LogFactory.createLog().error("Failed to initialize clangd for {}", path, e);
                client.disable();
            }
        }
        return client;
    }

    private static void withLoadedClient(Window window, java.util.function.Consumer<ClangdLspClient> action) {
        ensureLoaded(window.getBufferContext().getBuffer().getPath());
        action.accept(getClient());
    }

    public static void goToDefinition() {
        withActiveCppWindow("lsp definition", client -> client.goToDefinition(Window.getInstance().getBufferContext()));
    }

    public static void findReferences() {
        withActiveCppWindow("lsp references", client -> client.findReferences(Window.getInstance().getBufferContext()));
    }

    public static void switchHeaderImplementation() {
        Window window = Window.getInstance();
        if (window == null || !isCppPath(currentPath(window))) {
            return;
        }
        window.allowEditorDriveAction("open C/C++ counterpart");
        switchHeaderImplementation(window);
    }

    private static void registerCppKey(SwimPluginPreloadContext context, String key, String group, String summary,
            String commandName, Runnable action) {
        context.registerKeyBinding(new SwimPluginKeyBinding(key, group, summary, commandName,
                ClangdLspPluginSupport::isCppBuffer, action));
    }

    private static void withActiveCppWindow(String editorDriveAction, java.util.function.Consumer<ClangdLspClient> action) {
        Window window = Window.getInstance();
        if (window == null || !isCppPath(currentPath(window))) {
            return;
        }
        window.allowEditorDriveAction(editorDriveAction);
        withLoadedClient(window, action);
    }

    private static void switchHeaderImplementation(Window window) {
        Path path = window.getBufferContext().getBuffer().getPath();
        Path counterpart = findHeaderImplementationCounterpart(path);
        if (counterpart == null) {
            window.getCommandView().setMessage("No C/C++ counterpart found");
            return;
        }
        if (!window.setBufferPath(counterpart)) {
            window.getCommandView().setMessage("Unable to open " + counterpart);
        }
    }

    static Path findHeaderImplementationCounterpart(Path path) {
        if (path == null || path.getFileName() == null || path.getParent() == null) {
            return null;
        }
        String fileName = path.getFileName().toString();
        String extension = extension(fileName);
        if (extension.isBlank()) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - extension.length());
        List<String> candidates = counterpartExtensions(extension);
        if (candidates.isEmpty()) {
            return null;
        }
        Path sameDirectory = findCounterpartInDirectory(path.getParent(), stem, candidates);
        if (sameDirectory != null) {
            return sameDirectory;
        }
        return findCounterpartInProject(path, stem, candidates);
    }

    private static Path findCounterpartInDirectory(Path directory, String stem, List<String> extensions) {
        for (String extension : extensions) {
            Path candidate = directory.resolve(stem + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path findCounterpartInProject(Path path, String stem, List<String> extensions) {
        Path root = ProjectPaths.getProjectRootPath(path);
        if (root == null || !Files.isDirectory(root)) {
            return null;
        }
        for (String extension : extensions) {
            try (Stream<Path> files = Files.find(root, Integer.MAX_VALUE,
                    (candidate, attributes) -> attributes.isRegularFile()
                            && (stem + extension).equals(candidate.getFileName().toString()))) {
                Path found = files
                        .filter(candidate -> !candidate.equals(path))
                        .sorted(Comparator.comparing(candidate -> root.relativize(candidate).toString()))
                        .findFirst()
                        .orElse(null);
                if (found != null) {
                    return found;
                }
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    private static List<String> counterpartExtensions(String extension) {
        return switch (extension) {
        case ".h" -> H_HEADER_SOURCE_EXTENSIONS;
        case ".hh", ".hpp", ".hxx" -> CPP_SOURCE_EXTENSIONS;
        case ".c" -> C_SOURCE_HEADER_EXTENSIONS;
        case ".cc" -> CC_HEADER_EXTENSIONS;
        case ".cpp" -> CPP_HEADER_EXTENSIONS;
        case ".cxx" -> CXX_HEADER_EXTENSIONS;
        default -> List.of();
        };
    }

    private static boolean isCppBuffer() {
        return isCppPath(currentPath(Window.getInstance()));
    }

    private static Path currentPath(Window window) {
        if (window == null || window.getBufferContext() == null || window.getBufferContext().getBuffer() == null) {
            return null;
        }
        return window.getBufferContext().getBuffer().getPath();
    }

    public static boolean isCppPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        return !counterpartExtensions(extension(path.getFileName().toString())).isEmpty();
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        String extension = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ALL_CPP_EXTENSIONS.contains(extension) ? extension : "";
    }
}
