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
import org.fisk.swim.lsp.shared.LspHelp;
import org.fisk.swim.text.BufferContext;
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
    private static final List<String> CPP_FILE_SUFFIXES = List.of(
            ".inline.hpp", ".cpp", ".hpp", ".cxx", ".hxx", ".cc", ".hh", ".c", ".h");

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
        Path compilationDatabase = ClangdProjectRoots.findCompilationDatabaseRoot(path);
        if (!hasCompileCommand(compilationDatabase, path)) {
            return;
        }
        ensureLoaded(path);
        startClientIfNeeded(path, getClient());
    }

    public static LanguageMode createLanguageMode(Path path) {
        Path compilationDatabase = ClangdProjectRoots.findCompilationDatabaseRoot(path);
        if (compilationDatabase == null) {
            // Keep C++-specific local editing behavior (such as indentation)
            // even in projects that have no compilation database at all.
            return getClient();
        }
        if (!hasCompileCommand(compilationDatabase, path)) {
            // A C++ file outside compile_commands.json remains fully editable;
            // returning null makes the core use its plain, non-LSP mode.
            return null;
        }
        ensureLoaded(path);
        // Buffer construction also happens while restoring a saved workspace.
        // Do not hold startup (and therefore the freshly reloaded UI) behind
        // clangd's initialize request. didOpen records those buffers and the
        // client replays them after initialization completes.
        var client = getClient();
        if (client.isEnabled() && !client.isReady()) {
            client.startServer(path);
        }
        return client;
    }

    public static void install() {
        ClangdLspClient.installInstance(new ClangdLspClient());
    }

    public static void shutdown() {
        ClangdLspClient.shutdownInstalledInstance();
    }

    static boolean restart(BufferContext context) {
        if (context == null || !isCppPath(context.getBuffer().getPath())) {
            return false;
        }
        Path path = context.getBuffer().getPath();
        if (!hasCompileCommand(ClangdProjectRoots.findCompilationDatabaseRoot(path), path)) {
            return false;
        }
        ClangdLspClient.shutdownInstalledInstance();
        install();
        var client = startClientIfNeeded(path, getClient());
        if (!client.isReady()) {
            return false;
        }
        Window window = Window.getInstance();
        if (window != null) {
            Path workspaceRoot = ClangdProjectRoots.findWorkspaceRoot(path);
            for (BufferContext openContext : window.openBufferContextsSnapshot()) {
                Path openPath = openContext.getBuffer().getPath();
                if (isCppPath(openPath) && workspaceRoot.equals(ClangdProjectRoots.findWorkspaceRoot(openPath))) {
                    openContext.getBuffer().reloadLanguageMode();
                }
            }
            window.getCommandView().setMessage("clangd restarted; compile_commands.json reloaded");
        } else {
            context.getBuffer().reloadLanguageMode();
        }
        return true;
    }

    public static void preload(SwimPluginPreloadContext context) {
        LspHelp.registerSharedChapter(context);
        context.registerHelpChapter(ClangdLspHelp.chapter());
        for (String extension : new String[] {"c", "h", "cc", "cpp", "cxx", "hh", "hpp", "hxx"}) {
            context.registerPreloadResource(LanguagePluginRegistry.register(extension, PLUGIN_ID,
                    ClangdLspPluginSupport::createLanguageMode));
        }
        registerCppKey(context, "g d", "LSP", "go to definition", "lsp-definition",
                ClangdLspPluginSupport::goToDefinition);
        registerCppKey(context, "g r", "LSP", "find references", "lsp-references",
                ClangdLspPluginSupport::findReferences);
        registerCppKey(context, "<SPACE> , h", "LSP", "hover", "lsp-hover", ClangdLspPluginSupport::showHover);
        registerCppKey(context, "<SPACE> , p", "LSP", "signature help", "lsp-signature-help",
                ClangdLspPluginSupport::showSignatureHelp);
        registerCppKey(context, "<SPACE> , d", "LSP", "go to definition", "lsp-definition",
                ClangdLspPluginSupport::goToDefinition);
        registerCppKey(context, "<SPACE> , D", "LSP", "go to declaration", "lsp-declaration",
                ClangdLspPluginSupport::goToDeclaration);
        registerCppKey(context, "<SPACE> , y", "LSP", "go to type definition", "lsp-type-definition",
                ClangdLspPluginSupport::goToTypeDefinition);
        registerCppKey(context, "<SPACE> , i", "LSP", "go to implementation", "lsp-implementation",
                ClangdLspPluginSupport::goToImplementation);
        registerCppKey(context, "<SPACE> , u", "LSP", "find references", "lsp-references",
                ClangdLspPluginSupport::findReferences);
        registerCppKey(context, "<SPACE> , H", "LSP", "document highlights", "lsp-document-highlights",
                ClangdLspPluginSupport::showDocumentHighlights);
        registerCppKey(context, "<SPACE> , s", "LSP", "document symbols", "lsp-document-symbols",
                ClangdLspPluginSupport::showDocumentSymbols);
        registerCppKey(context, "<SPACE> , S", "LSP", "workspace symbols", "lsp-workspace-symbols",
                ClangdLspPluginSupport::promptWorkspaceSymbols);
        registerCppKey(context, "<SPACE> , a", "LSP", "code actions", "lsp-code-actions",
                ClangdLspPluginSupport::showCodeActions);
        registerCppKey(context, "<SPACE> , l", "LSP", "code lens", "lsp-code-lens",
                ClangdLspPluginSupport::showCodeLens);
        registerCppKey(context, "<SPACE> , f", "LSP", "format document", "lsp-format-document",
                ClangdLspPluginSupport::formatDocument);
        registerCppKey(context, "<SPACE> , F", "LSP", "format line", "lsp-format-line",
                ClangdLspPluginSupport::formatCurrentLine);
        registerCppKey(context, "<SPACE> , t", "LSP", "format on type", "lsp-format-on-type",
                ClangdLspPluginSupport::formatOnType);
        registerCppKey(context, "<SPACE> , R", "LSP", "rename symbol", "lsp-rename",
                ClangdLspPluginSupport::rename);
        registerCppKey(context, "<SPACE> , n", "LSP", "inlay hints", "lsp-inlay-hints",
                ClangdLspPluginSupport::showInlayHints);
        registerCppKey(context, "<SPACE> , z", "LSP", "folding ranges", "lsp-folding-ranges",
                ClangdLspPluginSupport::applyFoldingRanges);
        registerCppKey(context, "<SPACE> , v", "LSP", "selection ranges", "lsp-selection-ranges",
                ClangdLspPluginSupport::showSelectionRanges);
        registerCppKey(context, "<SPACE> , c", "LSP", "call hierarchy", "lsp-call-hierarchy",
                ClangdLspPluginSupport::showCallHierarchy);
        registerCppKey(context, "<SPACE> , T", "LSP", "type hierarchy", "lsp-type-hierarchy",
                ClangdLspPluginSupport::showTypeHierarchy);
        registerCppKey(context, "<SPACE> , m", "LSP", "document links", "lsp-document-links",
                ClangdLspPluginSupport::showDocumentLinks);
        registerCppKey(context, "<SPACE> , k", "LSP", "linked editing ranges", "lsp-linked-editing",
                ClangdLspPluginSupport::showLinkedEditingRanges);
        registerCppKey(context, "<SPACE> , C", "LSP", "color presentations", "lsp-color-presentations",
                ClangdLspPluginSupport::showColorPresentations);
    }

    private static ClangdLspClient startClientIfNeeded(Path path, ClangdLspClient client) {
        if (client.isEnabled() && !client.isReady()) {
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

    private static boolean hasCompileCommand(Path compilationDatabase, Path path) {
        return ClangdCompilationDatabase.containsCommandFor(compilationDatabase, path);
    }

    private static void withLoadedClient(Window window, java.util.function.Consumer<ClangdLspClient> action) {
        Path path = window.getBufferContext().getBuffer().getPath();
        ensureLoaded(path);
        action.accept(startClientIfNeeded(path, getClient()));
    }

    public static void goToDefinition() {
        withActiveCppWindow("lsp definition", client -> client.goToDefinition(Window.getInstance().getBufferContext()));
    }

    public static void findReferences() {
        withActiveCppWindow("lsp references", client -> client.findReferences(Window.getInstance().getBufferContext()));
    }

    public static void showHover() {
        withActiveCppWindow("lsp hover", client -> client.showHover(Window.getInstance().getBufferContext()));
    }

    public static void showSignatureHelp() {
        withActiveCppWindow("lsp signature help",
                client -> client.showSignatureHelp(Window.getInstance().getBufferContext()));
    }

    public static void goToDeclaration() {
        withActiveCppWindow("lsp declaration", client -> client.goToDeclaration(Window.getInstance().getBufferContext()));
    }

    public static void goToTypeDefinition() {
        withActiveCppWindow("lsp type definition",
                client -> client.goToTypeDefinition(Window.getInstance().getBufferContext()));
    }

    public static void goToImplementation() {
        withActiveCppWindow("lsp implementation",
                client -> client.goToImplementation(Window.getInstance().getBufferContext()));
    }

    public static void showDocumentHighlights() {
        withActiveCppWindow("lsp document highlights",
                client -> client.showDocumentHighlights(Window.getInstance().getBufferContext()));
    }

    public static void showDocumentSymbols() {
        withActiveCppWindow("lsp document symbols",
                client -> client.showDocumentSymbols(Window.getInstance().getBufferContext()));
    }

    public static void promptWorkspaceSymbols() {
        withActiveCppWindow("lsp workspace symbols",
                client -> client.promptWorkspaceSymbols(Window.getInstance().getBufferContext()));
    }

    public static void showCodeActions() {
        withActiveCppWindow("lsp code actions", client -> client.showCodeActions(Window.getInstance().getBufferContext()));
    }

    public static void showCodeLens() {
        withActiveCppWindow("lsp code lens", client -> client.showCodeLens(Window.getInstance().getBufferContext()));
    }

    public static void formatDocument() {
        withActiveCppWindow("lsp format document",
                client -> client.formatDocument(Window.getInstance().getBufferContext()));
    }

    public static void formatCurrentLine() {
        withActiveCppWindow("lsp format line",
                client -> client.formatCurrentLine(Window.getInstance().getBufferContext()));
    }

    public static void formatOnType() {
        withActiveCppWindow("lsp format on type",
                client -> client.formatOnType(Window.getInstance().getBufferContext()));
    }

    public static void rename() {
        withActiveCppWindow("lsp rename", client -> client.rename(Window.getInstance().getBufferContext()));
    }

    public static void showInlayHints() {
        withActiveCppWindow("lsp inlay hints", client -> client.showInlayHints(Window.getInstance().getBufferContext()));
    }

    public static void applyFoldingRanges() {
        withActiveCppWindow("lsp folding ranges",
                client -> client.applyFoldingRanges(Window.getInstance().getBufferContext()));
    }

    public static void showSelectionRanges() {
        withActiveCppWindow("lsp selection ranges",
                client -> client.showSelectionRanges(Window.getInstance().getBufferContext()));
    }

    public static void showCallHierarchy() {
        withActiveCppWindow("lsp call hierarchy",
                client -> client.showCallHierarchy(Window.getInstance().getBufferContext()));
    }

    public static void showTypeHierarchy() {
        withActiveCppWindow("lsp type hierarchy",
                client -> client.showTypeHierarchy(Window.getInstance().getBufferContext()));
    }

    public static void showDocumentLinks() {
        withActiveCppWindow("lsp document links",
                client -> client.showDocumentLinks(Window.getInstance().getBufferContext()));
    }

    public static void showLinkedEditingRanges() {
        withActiveCppWindow("lsp linked editing",
                client -> client.showLinkedEditingRanges(Window.getInstance().getBufferContext()));
    }

    public static void showColorPresentations() {
        withActiveCppWindow("lsp color presentations",
                client -> client.showColorPresentations(Window.getInstance().getBufferContext()));
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

    static Path findHeaderImplementationCounterpart(Path path) {
        if (path == null || path.getFileName() == null || path.getParent() == null) {
            return null;
        }
        String fileName = path.getFileName().toString();
        String suffix = cppSuffix(fileName);
        if (suffix.isBlank()) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - suffix.length());
        var candidates = findCounterparts(path, stem);
        if (candidates.size() < 2) return null;
        int current = candidates.indexOf(path);
        return candidates.get((current + 1) % candidates.size());
    }

    private static List<Path> findCounterparts(Path path, String stem) {
        var candidates = new java.util.ArrayList<Path>();
        for (String suffix : CPP_FILE_SUFFIXES) {
            Path candidate = path.getParent().resolve(stem + suffix);
            if (Files.isRegularFile(candidate)) candidates.add(candidate);
        }
        if (candidates.size() > 1) return candidates;
        Path root = ProjectPaths.getProjectRootPath(path);
        if (root == null || !Files.isDirectory(root)) return candidates;
        for (String suffix : CPP_FILE_SUFFIXES) {
            try (Stream<Path> files = Files.find(root, Integer.MAX_VALUE,
                    (candidate, attributes) -> attributes.isRegularFile()
                            && (stem + suffix).equals(candidate.getFileName().toString()))) {
                Path found = files
                        .sorted(Comparator.comparing(candidate -> root.relativize(candidate).toString()))
                        .findFirst()
                        .orElse(null);
                if (found != null && !candidates.contains(found)) candidates.add(found);
            } catch (IOException e) {
                return candidates;
            }
        }
        return candidates;
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
        return !cppSuffix(path.getFileName().toString()).isBlank();
    }

    private static String cppSuffix(String fileName) {
        String normalized = fileName.toLowerCase(Locale.ROOT);
        return CPP_FILE_SUFFIXES.stream().filter(normalized::endsWith).findFirst().orElse("");
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
