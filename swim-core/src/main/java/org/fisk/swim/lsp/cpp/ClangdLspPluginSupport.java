package org.fisk.swim.lsp.cpp;

import java.nio.file.Path;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.lsp.LanguageMode;
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
        SwimRuntime.loadPlugin(PLUGIN_ID);
    }

    public static void ensureStartedForProject(Path path) {
        if (ClangdProjectRoots.findCompilationDatabaseRoot(path) == null) {
            return;
        }
        ensureLoaded(path);
        startClientIfNeeded(path, getClient());
    }

    public static LanguageMode createLanguageMode(Path path) {
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
}
