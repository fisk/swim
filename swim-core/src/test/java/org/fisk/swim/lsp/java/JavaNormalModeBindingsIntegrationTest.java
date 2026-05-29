package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;
import java.util.concurrent.CompletableFuture;

class JavaNormalModeBindingsIntegrationTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        JavaLSPClient.shutdownInstalledInstance();
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
    }

    @Test
    void normalModeOBindingOrganizesImportsForJavaBuffer() throws Exception {
        Path project = tempDir.resolve("java-project");
        Path file = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(file, """
                package demo;
                import java.util.List;
                class Main {}
                """);

        var client = new TestJavaLspClient();
        setField(client, "_enabled", true);
        setField(client, "_server", new OrganizeImportsLanguageServer());
        setField(client, "_capabilities", new ServerCapabilities());
        JavaLSPClient.installInstance(client);

        try (var harness = HeadlessWindowHarness.create(file, 48, 10)) {
            var window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('o'));

            assertEquals("""
                    package demo;
                    class Main {}
                    """, window.getBufferContext().getBuffer().getString());
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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

    private static final class OrganizeImportsLanguageServer implements LanguageServer {
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
            public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
                var edit = new org.eclipse.lsp4j.WorkspaceEdit();
                edit.setChanges(java.util.Map.of(
                        params.getTextDocument().getUri(),
                        List.of(new org.eclipse.lsp4j.TextEdit(
                                new org.eclipse.lsp4j.Range(
                                        new org.eclipse.lsp4j.Position(1, 0),
                                        new org.eclipse.lsp4j.Position(2, 0)),
                                ""))));
                var action = new CodeAction("Organize imports");
                action.setEdit(edit);
                return CompletableFuture.completedFuture(List.of(Either.forRight(action)));
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
