package org.fisk.swim.lsp.java;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.mode.NormalMode;
import org.fisk.swim.ui.Window;
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
        SwimRuntime.loadPlugin(PLUGIN_ID, path);
    }

    public static void installNormalModeBindings(NormalMode mode, Window window, String leader) {
        Path path = window.getBufferContext().getBuffer().getPath();
        if (!isJavaPath(path)) {
            return;
        }

        var layer = mode.addKeybindingLayer();
        layer.addEventResponder("g d", () -> withLoadedClient(window, client -> client.goToDefinition(window.getBufferContext())));
        layer.addEventResponder(leader + " e i", () -> organizeImports(window));
        layer.addEventResponder(leader + " e f", () -> withLoadedClient(window, client -> client.makeFinal(window.getBufferContext())));
        layer.addEventResponder(leader + " e a", () -> withLoadedClient(window, client -> client.generateAccessors(window.getBufferContext())));
        layer.addEventResponder(leader + " e s", () -> withLoadedClient(window, client -> client.generateToString(window.getBufferContext())));
        layer.addEventResponder(leader + " e l", () -> withLoadedClient(window, client -> client.codeLens(window.getBufferContext())));
        layer.addEventResponder("o", () -> organizeImports(window));
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

    private static void organizeImports(Window window) {
        withLoadedClient(window, client -> client.organizeImports(window.getBufferContext()));
    }

    private static void withLoadedClient(Window window, Consumer<JavaLSPClient> action) {
        ensureLoaded(window.getBufferContext().getBuffer().getPath());
        action.accept(JavaLSPClient.getInstance());
    }

    private static boolean isJavaPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java");
    }
}
