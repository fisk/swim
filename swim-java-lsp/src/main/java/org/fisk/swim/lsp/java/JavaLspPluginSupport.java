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
import org.fisk.swim.lsp.shared.LspHelp;
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
        LspHelp.registerSharedChapter(context);
        context.registerHelpChapter(JavaLspHelp.chapter());
        context.registerPreloadResource(LanguagePluginRegistry.register("java", PLUGIN_ID,
                JavaLspPluginSupport::createLanguageMode));
        registerJavaKey(context, "g d", "go to definition", "lsp-definition", JavaLspPluginSupport::goToDefinition);
        registerJavaKey(context, "g r", "find references", "lsp-references", JavaLspPluginSupport::findReferences);
        registerJavaKey(context, "<SPACE> , h", "hover", "lsp-hover", JavaLspPluginSupport::showHover);
        registerJavaKey(context, "<SPACE> , p", "signature help", "lsp-signature-help",
                JavaLspPluginSupport::showSignatureHelp);
        registerJavaKey(context, "<SPACE> , d", "go to definition", "lsp-definition",
                JavaLspPluginSupport::goToDefinition);
        registerJavaKey(context, "<SPACE> , D", "go to declaration", "lsp-declaration",
                JavaLspPluginSupport::goToDeclaration);
        registerJavaKey(context, "<SPACE> , y", "go to type definition", "lsp-type-definition",
                JavaLspPluginSupport::goToTypeDefinition);
        registerJavaKey(context, "<SPACE> , i", "go to implementation", "lsp-implementation",
                JavaLspPluginSupport::goToImplementation);
        registerJavaKey(context, "<SPACE> , u", "find references", "lsp-references",
                JavaLspPluginSupport::findReferences);
        registerJavaKey(context, "<SPACE> , H", "document highlights", "lsp-document-highlights",
                JavaLspPluginSupport::showDocumentHighlights);
        registerJavaKey(context, "<SPACE> , s", "document symbols", "lsp-document-symbols",
                JavaLspPluginSupport::showDocumentSymbols);
        registerJavaKey(context, "<SPACE> , S", "workspace symbols", "lsp-workspace-symbols",
                JavaLspPluginSupport::promptWorkspaceSymbols);
        registerJavaKey(context, "<SPACE> , a", "code actions", "lsp-code-actions",
                JavaLspPluginSupport::showCodeActions);
        registerJavaKey(context, "<SPACE> , l", "code lens", "lsp-code-lens", JavaLspPluginSupport::codeLens);
        registerJavaKey(context, "<SPACE> , f", "format document", "lsp-format-document",
                JavaLspPluginSupport::formatDocument);
        registerJavaKey(context, "<SPACE> , F", "format line", "lsp-format-line",
                JavaLspPluginSupport::formatCurrentLine);
        registerJavaKey(context, "<SPACE> , t", "format on type", "lsp-format-on-type",
                JavaLspPluginSupport::formatOnType);
        registerJavaKey(context, "<SPACE> , R", "rename symbol", "lsp-rename", JavaLspPluginSupport::rename);
        registerJavaKey(context, "<SPACE> , n", "inlay hints", "lsp-inlay-hints",
                JavaLspPluginSupport::showInlayHints);
        registerJavaKey(context, "<SPACE> , z", "folding ranges", "lsp-folding-ranges",
                JavaLspPluginSupport::applyFoldingRanges);
        registerJavaKey(context, "<SPACE> , v", "selection ranges", "lsp-selection-ranges",
                JavaLspPluginSupport::showSelectionRanges);
        registerJavaKey(context, "<SPACE> , c", "call hierarchy", "lsp-call-hierarchy",
                JavaLspPluginSupport::showCallHierarchy);
        registerJavaKey(context, "<SPACE> , T", "type hierarchy", "lsp-type-hierarchy",
                JavaLspPluginSupport::showTypeHierarchy);
        registerJavaKey(context, "<SPACE> , m", "document links", "lsp-document-links",
                JavaLspPluginSupport::showDocumentLinks);
        registerJavaKey(context, "<SPACE> , k", "linked editing ranges", "lsp-linked-editing",
                JavaLspPluginSupport::showLinkedEditingRanges);
        registerJavaKey(context, "<SPACE> , C", "color presentations", "lsp-color-presentations",
                JavaLspPluginSupport::showColorPresentations);
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

    public static void showHover() {
        withActiveJavaWindow("lsp hover", client -> client.showHover(Window.getInstance().getBufferContext()));
    }

    public static void showSignatureHelp() {
        withActiveJavaWindow("lsp signature help",
                client -> client.showSignatureHelp(Window.getInstance().getBufferContext()));
    }

    public static void goToDeclaration() {
        withActiveJavaWindow("lsp declaration", client -> client.goToDeclaration(Window.getInstance().getBufferContext()));
    }

    public static void goToTypeDefinition() {
        withActiveJavaWindow("lsp type definition",
                client -> client.goToTypeDefinition(Window.getInstance().getBufferContext()));
    }

    public static void goToImplementation() {
        withActiveJavaWindow("lsp implementation",
                client -> client.goToImplementation(Window.getInstance().getBufferContext()));
    }

    public static void showDocumentHighlights() {
        withActiveJavaWindow("lsp document highlights",
                client -> client.showDocumentHighlights(Window.getInstance().getBufferContext()));
    }

    public static void showDocumentSymbols() {
        withActiveJavaWindow("lsp document symbols",
                client -> client.showDocumentSymbols(Window.getInstance().getBufferContext()));
    }

    public static void promptWorkspaceSymbols() {
        withActiveJavaWindow("lsp workspace symbols",
                client -> client.promptWorkspaceSymbols(Window.getInstance().getBufferContext()));
    }

    public static void showCodeActions() {
        withActiveJavaWindow("lsp code actions", client -> client.showCodeActions(Window.getInstance().getBufferContext()));
    }

    public static void formatDocument() {
        withActiveJavaWindow("lsp format document",
                client -> client.formatDocument(Window.getInstance().getBufferContext()));
    }

    public static void formatCurrentLine() {
        withActiveJavaWindow("lsp format line",
                client -> client.formatCurrentLine(Window.getInstance().getBufferContext()));
    }

    public static void formatOnType() {
        withActiveJavaWindow("lsp format on type",
                client -> client.formatOnType(Window.getInstance().getBufferContext()));
    }

    public static void rename() {
        withActiveJavaWindow("lsp rename", client -> client.rename(Window.getInstance().getBufferContext()));
    }

    public static void showInlayHints() {
        withActiveJavaWindow("lsp inlay hints", client -> client.showInlayHints(Window.getInstance().getBufferContext()));
    }

    public static void applyFoldingRanges() {
        withActiveJavaWindow("lsp folding ranges",
                client -> client.applyFoldingRanges(Window.getInstance().getBufferContext()));
    }

    public static void showSelectionRanges() {
        withActiveJavaWindow("lsp selection ranges",
                client -> client.showSelectionRanges(Window.getInstance().getBufferContext()));
    }

    public static void showCallHierarchy() {
        withActiveJavaWindow("lsp call hierarchy",
                client -> client.showCallHierarchy(Window.getInstance().getBufferContext()));
    }

    public static void showTypeHierarchy() {
        withActiveJavaWindow("lsp type hierarchy",
                client -> client.showTypeHierarchy(Window.getInstance().getBufferContext()));
    }

    public static void showDocumentLinks() {
        withActiveJavaWindow("lsp document links",
                client -> client.showDocumentLinks(Window.getInstance().getBufferContext()));
    }

    public static void showLinkedEditingRanges() {
        withActiveJavaWindow("lsp linked editing",
                client -> client.showLinkedEditingRanges(Window.getInstance().getBufferContext()));
    }

    public static void showColorPresentations() {
        withActiveJavaWindow("lsp color presentations",
                client -> client.showColorPresentations(Window.getInstance().getBufferContext()));
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
