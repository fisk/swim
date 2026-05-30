package org.fisk.swim.lsp.cpp;

import java.nio.file.Path;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.mode.NormalMode;
import org.fisk.swim.ui.Window;
import org.fisk.swim.utils.LogFactory;

public final class ClangdLspPluginSupport {
    public static final String PLUGIN_ID = "clangd-lsp";

    private static final ClangdLspClient UNAVAILABLE = new ClangdLspClient(new ClangdLspProvider((Path) null));

    private ClangdLspPluginSupport() {
    }

    public static ClangdLspClient getClient() {
        return ClangdLspClient.getInstalledInstanceOr(UNAVAILABLE);
    }

    public static void ensureLoaded(Path path) {
        LogFactory.createLog().info("Ensuring clangd LSP plugin loaded for {}", path);
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

    public static void installNormalModeBindings(NormalMode mode, Window window) {
        Path path = window.getBufferContext().getBuffer().getPath();
        if (!isCppPath(path)) {
            return;
        }
        var layer = mode.addKeybindingLayer();
        layer.addEventResponder("g d", () -> withLoadedClient(window, client -> client.goToDefinition(window.getBufferContext())));
        layer.addEventResponder("g r", () -> withLoadedClient(window, client -> client.findReferences(window.getBufferContext())));
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

    private static boolean isCppPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".cpp") || name.endsWith(".hpp");
    }
}
