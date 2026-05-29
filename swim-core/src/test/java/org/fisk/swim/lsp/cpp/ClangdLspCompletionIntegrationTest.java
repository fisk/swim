package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClangdLspCompletionIntegrationTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ClangdLspClient.shutdownInstalledInstance();
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
    }

    @Test
    void inputModeNavigatesAndAppliesCompletionPopupForCppBuffer() throws Exception {
        Path project = tempDir.resolve("cpp-project");
        Path file = project.resolve("src/main.cpp");
        Files.createDirectories(file.getParent());
        Files.writeString(project.resolve("compile_commands.json"), "[]\n");
        Files.writeString(file, "");

        var client = new TestClangdLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new CompletionLanguageServer());
        var capabilities = new ServerCapabilities();
        capabilities.setCompletionProvider(new CompletionOptions(Boolean.FALSE, List.of(".")));
        setField(client, "_capabilities", capabilities);
        ClangdLspClient.installInstance(client);

        try (var harness = HeadlessWindowHarness.create(file, 32, 8)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('a'));

            assertTrue(client.hasCompletionSession());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());

            assertEquals("beta", window.getBufferContext().getBuffer().getString());
            assertFalse(client.hasCompletionSession());
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class TestClangdLspClient extends ClangdLspClient {
        private TestClangdLspClient() {
            super(new ClangdLspProvider(Path.of("/usr/bin/clangd")));
        }

        @Override
        public boolean hasStarted() {
            return true;
        }

        @Override
        public synchronized void startServer(Path filePath) {
        }

        @Override
        public void ensureInit() {
        }
    }

    private static final class CompletionLanguageServer implements LanguageServer {
        private final TextDocumentService _textDocumentService = new TextDocumentService() {
            @Override
            public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
            }

            @Override
            public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
            }

            @Override
            public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {
            }

            @Override
            public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
            }

            @Override
            public CompletableFuture<Either<List<CompletionItem>, org.eclipse.lsp4j.CompletionList>> completion(
                    CompletionParams params) {
                var alpha = new CompletionItem("alpha");
                alpha.setSortText("001");
                alpha.setPreselect(true);
                var beta = new CompletionItem("beta");
                beta.setSortText("002");
                return CompletableFuture.completedFuture(Either.forLeft(List.of(alpha, beta)));
            }
        };

        @Override
        public CompletableFuture<org.eclipse.lsp4j.InitializeResult> initialize(org.eclipse.lsp4j.InitializeParams params) {
            return CompletableFuture.completedFuture(new org.eclipse.lsp4j.InitializeResult());
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
        }

        @Override
        public TextDocumentService getTextDocumentService() {
            return _textDocumentService;
        }

        @Override
        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
            return new org.eclipse.lsp4j.services.WorkspaceService() {
                @Override
                public void didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams params) {
                }

                @Override
                public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
                }
            };
        }
    }
}
