package org.fisk.swim.lsp.java;

import java.nio.file.Path;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.utils.LogFactory;

public final class JavaLspPluginSupport {
    public static final String PLUGIN_ID = "java-lsp";

    private static final JavaLSPClient UNAVAILABLE = new JavaLSPClient(new JavaLspProvider() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Session start(
                Path projectPath,
                Path workspacePath,
                org.eclipse.lsp4j.services.LanguageClient client,
                org.eclipse.lsp4j.ClientCapabilities clientCapabilities,
                Object initializationOptions,
                long timeoutSeconds) {
            throw new UnsupportedOperationException();
        }
    });

    private JavaLspPluginSupport() {
    }

    public static JavaLSPClient getClient() {
        return JavaLSPClient.getInstalledInstanceOr(UNAVAILABLE);
    }

    public static void ensureLoaded(Path path) {
        LogFactory.createLog().info("Ensuring Java LSP plugin loaded for {}", path);
        SwimRuntime.loadPlugin(PLUGIN_ID);
    }

    public static LanguageMode createLanguageMode(Path path) {
        ensureLoaded(path);
        var lsp = getClient();
        LogFactory.createLog().info("Creating Java language mode for {} using client {}", path, lsp.getClass().getName());
        if (lsp.isEnabled() && !lsp.hasStarted()) {
            try {
                lsp.startServer(path);
                lsp.ensureInit();
            } catch (RuntimeException e) {
                LogFactory.createLog().error("Failed to initialize Java LSP for project " + ProjectPaths.getProjectRootPath(path), e);
                lsp.disable();
            }
        }
        return lsp;
    }

    public static void install() {
        JavaLSPClient.installInstance(new JavaLSPClient());
    }

    public static void shutdown() {
        JavaLSPClient.shutdownInstalledInstance();
    }
}
