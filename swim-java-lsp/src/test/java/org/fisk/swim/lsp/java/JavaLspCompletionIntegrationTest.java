package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaLspCompletionIntegrationTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        JavaLSPClient.shutdownInstalledInstance();
        SwimPluginPreloadRegistry.clearForTests();
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
    }

    @Test
    void inputModeNavigatesAndAppliesCompletionPopup() throws Exception {
        var client = new TestJavaLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new CompletionLanguageServer());
        var capabilities = new ServerCapabilities();
        capabilities.setCompletionProvider(new CompletionOptions(Boolean.FALSE, List.of(".")));
        setField(client, "_capabilities", capabilities);
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(writeFile("completion-input.java", ""), 32, 8)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('a'));
            waitForCompletionSession(client);

            assertTrue(client.hasCompletionSession());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());

            assertEquals("beta", window.getBufferContext().getBuffer().getString());
            assertFalse(client.hasCompletionSession());
        }
    }

    @Test
    void typingCommitCharacterAcceptsCompletionAndPreservesDelimiter() throws Exception {
        var client = new TestJavaLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new CompletionLanguageServer());
        var options = new CompletionOptions(Boolean.FALSE, List.of("."));
        options.setAllCommitCharacters(List.of("(", "."));
        var capabilities = new ServerCapabilities();
        capabilities.setCompletionProvider(options);
        setField(client, "_capabilities", capabilities);
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(writeFile("completion-commit.java", ""), 32, 8)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('a'));
            waitForCompletionSession(client);
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('('));

            assertEquals("alpha(", window.getBufferContext().getBuffer().getString());
            assertFalse(client.hasCompletionSession());
        }
    }

    @Test
    void pageDownAdvancesSelectionByPopupPage() throws Exception {
        var client = new TestJavaLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new PagingCompletionLanguageServer());
        var capabilities = new ServerCapabilities();
        capabilities.setCompletionProvider(new CompletionOptions(Boolean.FALSE, List.of(".")));
        setField(client, "_capabilities", capabilities);
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(writeFile("completion-page.java", ""), 48, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('a'));
            waitForCompletionSession(client);
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.pageDown());
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());

            assertEquals("item08", window.getBufferContext().getBuffer().getString());
            assertFalse(client.hasCompletionSession());
        }
    }

    @Test
    void snippetCompletionReplacesPlaceholderAndTabsForward() throws Exception {
        var client = new TestJavaLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new SnippetCompletionLanguageServer());
        var capabilities = new ServerCapabilities();
        capabilities.setCompletionProvider(new CompletionOptions(Boolean.FALSE, List.of(".")));
        setField(client, "_capabilities", capabilities);
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(writeFile("completion-snippet.java", ""), 48, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('a'));
            waitForCompletionSession(client);
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());

            assertTrue(client.hasActiveSnippet());
            assertEquals("alpha(value);", window.getBufferContext().getBuffer().getString());
            assertEquals(6, window.getBufferContext().getBuffer().getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('x'));

            assertEquals("alpha(x);", window.getBufferContext().getBuffer().getString());
            assertTrue(client.hasActiveSnippet());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.tab());

            assertFalse(client.hasActiveSnippet());
            assertEquals("alpha(x);".length(), window.getBufferContext().getBuffer().getCursor().getPosition());
        }
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private static void waitForCompletionSession(JavaLSPClient client) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (client.hasCompletionSession()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for completion session");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class TestJavaLspClient extends JavaLSPClient {
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

    private static final class PagingCompletionLanguageServer implements LanguageServer {
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
                var items = new ArrayList<CompletionItem>();
                for (int i = 0; i < 12; ++i) {
                    var item = new CompletionItem(String.format("item%02d", i));
                    item.setSortText(String.format("%03d", i));
                    items.add(item);
                }
                return CompletableFuture.completedFuture(Either.forLeft(items));
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

    private static final class SnippetCompletionLanguageServer implements LanguageServer {
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
                var snippet = new CompletionItem("alpha");
                snippet.setSortText("001");
                snippet.setInsertText("alpha(${1:value});$0");
                snippet.setInsertTextFormat(InsertTextFormat.Snippet);
                return CompletableFuture.completedFuture(Either.forLeft(List.of(snippet)));
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
