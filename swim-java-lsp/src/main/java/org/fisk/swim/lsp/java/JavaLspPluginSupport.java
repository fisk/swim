package org.fisk.swim.lsp.java;

import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimPluginKeyBinding;
import org.fisk.swim.api.SwimPluginPreloadContext;
import org.fisk.swim.fileindex.ProjectPaths;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LanguagePluginRegistry;
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
        LogFactory.createLog().debug("Ensuring Java LSP plugin loaded for {}", path);
        SwimRuntime.loadPlugin(PLUGIN_ID, path);
    }

    public static void preload(SwimPluginPreloadContext context) {
        context.registerPreloadResource(LanguagePluginRegistry.register("java", PLUGIN_ID,
                JavaLspPluginSupport::createLanguageMode));
        registerJavaKey(context, "g d", "go to definition", "lsp-definition", JavaLspPluginSupport::goToDefinition);
        registerJavaKey(context, "g r", "find references", "lsp-references", JavaLspPluginSupport::findReferences);
        registerJavaKey(context, "<SPACE> , o", "organize imports", "lsp-organize-imports",
                JavaLspPluginSupport::organizeImports);
        registerJavaKey(context, "<SPACE> e f", "make field final", "lsp-make-final",
                JavaLspPluginSupport::makeFinal);
        registerJavaKey(context, "<SPACE> e a", "generate accessors", "lsp-generate-accessors",
                JavaLspPluginSupport::generateAccessors);
        registerJavaKey(context, "<SPACE> e s", "generate toString", "lsp-generate-tostring",
                JavaLspPluginSupport::generateToString);
        registerJavaKey(context, "<SPACE> e l", "code lens", "lsp-code-lens", JavaLspPluginSupport::codeLens);
    }

    public static LanguageMode createLanguageMode(Path path) {
        ensureLoaded(path);
        var lsp = getClient();
        LogFactory.createLog().debug("Creating Java language mode for {} using client {}", path, lsp.getClass().getName());
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

    public static void goToDefinition() {
        withActiveJavaWindow("lsp definition", client -> client.goToDefinition(Window.getInstance().getBufferContext()));
    }

    public static void findReferences() {
        withActiveJavaWindow("lsp references", client -> client.findReferences(Window.getInstance().getBufferContext()));
    }

    public static void organizeImports() {
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.allowEditorDriveAction("lsp code action");
        organizeImports(window);
    }

    public static void makeFinal() {
        withActiveJavaWindow("lsp code action", client -> client.makeFinal(Window.getInstance().getBufferContext()));
    }

    public static void generateAccessors() {
        withActiveJavaWindow("lsp code action", client -> client.generateAccessors(Window.getInstance().getBufferContext()));
    }

    public static void generateToString() {
        withActiveJavaWindow("lsp code action", client -> client.generateToString(Window.getInstance().getBufferContext()));
    }

    public static void codeLens() {
        withActiveJavaWindow("lsp code lens", client -> client.codeLens(Window.getInstance().getBufferContext()));
    }

    private static void registerJavaKey(SwimPluginPreloadContext context, String key, String summary, String commandName,
            Runnable action) {
        context.registerKeyBinding(new SwimPluginKeyBinding(key, "LSP", summary, commandName,
                JavaLspPluginSupport::isJavaBuffer, action));
    }

    private static void organizeImports(Window window) {
        withLoadedClient(window, client -> client.organizeImports(window.getBufferContext()));
    }

    private static void withLoadedClient(Window window, Consumer<JavaLSPClient> action) {
        ensureLoaded(window.getBufferContext().getBuffer().getPath());
        action.accept(JavaLSPClient.getInstance());
    }

    private static void withActiveJavaWindow(String editorDriveAction, Consumer<JavaLSPClient> action) {
        Window window = Window.getInstance();
        if (window == null || !isJavaPath(currentPath(window))) {
            return;
        }
        window.allowEditorDriveAction(editorDriveAction);
        withLoadedClient(window, action);
    }

    private static boolean isJavaBuffer() {
        return isJavaPath(currentPath(Window.getInstance()));
    }

    private static Path currentPath(Window window) {
        if (window == null || window.getBufferContext() == null || window.getBufferContext().getBuffer() == null) {
            return null;
        }
        return window.getBufferContext().getBuffer().getPath();
    }

    public static boolean isJavaPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".java");
    }
}
