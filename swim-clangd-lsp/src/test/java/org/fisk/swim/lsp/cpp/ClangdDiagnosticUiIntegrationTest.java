package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.fisk.swim.api.SwimPluginPreloadRegistry;
import org.fisk.swim.event.EventResponder;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.CodeActionPopupView;
import org.fisk.swim.ui.DiagnosticPopupView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.UiTheme;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class ClangdDiagnosticUiIntegrationTest {
    private static final TextColor ERROR_COLOR = UiTheme.DIAGNOSTIC_ERROR_FOREGROUND;
    private static final TextColor WARNING_COLOR = UiTheme.DIAGNOSTIC_WARNING_FOREGROUND;
    private static final TextColor ERROR_BACKGROUND = UiTheme.DIAGNOSTIC_ERROR_BACKGROUND;
    private static final TextColor WARNING_BACKGROUND = UiTheme.DIAGNOSTIC_WARNING_BACKGROUND;

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ClangdLspClient.shutdownInstalledInstance();
        SwimPluginPreloadRegistry.clearForTests();
        if (Window.getInstance() != null) {
            Window.getInstance().dispose();
        }
    }

    @Test
    void clangdDiagnosticsColorLineNumbers() throws Exception {
        Path file = tempDir.resolve("main.cpp");
        Files.writeString(file, """
                int main() {
                  return 0;
                }
                """);

        var client = new TestClangdLspClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);
        var terminal = TerminalContextTestSupport.install(20, 10);

        try (var harness = HeadlessWindowHarness.create(file, 20, 10)) {
            var window = harness.getWindow();
            LanguageClient languageClient = createLanguageClient(client);
            languageClient.publishDiagnostics(new PublishDiagnosticsParams(
                    file.toUri().toString(),
                    List.of(
                            diagnostic(0, 0, DiagnosticSeverity.Error, "Error line"),
                            diagnostic(1, 0, DiagnosticSeverity.Warning, "Warning line"))));

            window.update(true);
            assertEquals(ERROR_COLOR, foregroundAt(terminal.drawCalls(), 0, 2));
            assertEquals(WARNING_COLOR, foregroundAt(terminal.drawCalls(), 0, 3));
            assertEquals(ERROR_BACKGROUND, backgroundAt(terminal.drawCalls(), 2, 2));
            assertEquals(WARNING_BACKGROUND, backgroundAt(terminal.drawCalls(), 2, 3));
        }
    }

    @Test
    void clangdDiagnosticsPopupCanOpenCodeActionsAndApplySelectedFix() throws Exception {
        Path file = tempDir.resolve("fix.cpp");
        Files.writeString(file, """
                int main() {
                  return missing;
                }
                """);

        var client = new TestClangdLspClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        setField(client, "_server", new CodeActionLanguageServer());
        setField(client, "_capabilities", new ServerCapabilities());
        ClangdLspClient.installInstance(client);
        ClangdLspPluginSupport.preload(() -> ClangdLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(file, 60, 12)) {
            var window = harness.getWindow();
            LanguageClient languageClient = createLanguageClient(client);
            languageClient.publishDiagnostics(new PublishDiagnosticsParams(
                    file.toUri().toString(),
                    List.of(diagnostic(0, 0, DiagnosticSeverity.Error, "Missing fix"))));

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('x'));

            var diagnosticPopup = HeadlessWindowHarness.getField(window, "_diagnosticPopupView", DiagnosticPopupView.class);
            assertNotNull(diagnosticPopup);
            assertTrue(window.getRootView().getFirstResponder() instanceof DiagnosticPopupView);

            HeadlessWindowHarness.dispatch((EventResponder) window.getRootView().getFirstResponder(), HeadlessWindowHarness.key('a'));

            var actionPopup = HeadlessWindowHarness.getField(window, "_codeActionPopupView", CodeActionPopupView.class);
            assertNotNull(actionPopup);
            assertTrue(window.getRootView().getFirstResponder() instanceof CodeActionPopupView);

            HeadlessWindowHarness.dispatch((EventResponder) window.getRootView().getFirstResponder(), HeadlessWindowHarness.enter());

            assertTrue(window.getBufferContext().getBuffer().getString().startsWith("// clangd fixed"));
        }
    }

    private static Diagnostic diagnostic(int line, int character, DiagnosticSeverity severity, String message) {
        var diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(line, character), new Position(line, character + 1)));
        diagnostic.setSeverity(severity);
        diagnostic.setSource("clangd-test");
        diagnostic.setMessage(message);
        return diagnostic;
    }

    private static LanguageClient createLanguageClient(ClangdLspClient client) throws Exception {
        Method method = ClangdLspClient.class.getDeclaredMethod("createLanguageClient");
        method.setAccessible(true);
        return (LanguageClient) method.invoke(client);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static TextColor foregroundAt(List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls, int x, int y) {
        for (var call : drawCalls) {
            if (call.y() != y) {
                continue;
            }
            if (x < call.x() || x >= call.x() + call.text().length()) {
                continue;
            }
            return call.foreground();
        }
        throw new AssertionError("No draw call at " + x + "," + y + " in " + Arrays.toString(drawCalls.toArray()));
    }

    private static TextColor backgroundAt(List<org.fisk.swim.terminal.TerminalContextTestSupport.DrawCall> drawCalls, int x, int y) {
        for (var call : drawCalls) {
            if (call.y() != y) {
                continue;
            }
            if (x < call.x() || x >= call.x() + call.text().length()) {
                continue;
            }
            return call.background();
        }
        throw new AssertionError("No draw call at " + x + "," + y + " in " + Arrays.toString(drawCalls.toArray()));
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
        public boolean isReady() {
            return true;
        }

        @Override
        public synchronized void startServer(Path filePath) {
        }

        @Override
        public void ensureInit() {
        }
    }

    private static final class CodeActionLanguageServer implements LanguageServer {
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
                if (params.getContext() == null || params.getContext().getDiagnostics() == null
                        || params.getContext().getDiagnostics().isEmpty()) {
                    return CompletableFuture.completedFuture(List.of());
                }
                var edit = new WorkspaceEdit();
                edit.setChanges(java.util.Map.of(
                        params.getTextDocument().getUri(),
                        List.of(new TextEdit(
                                new Range(new Position(0, 0), new Position(0, 0)),
                                "// clangd fixed\n"))));
                var action = new CodeAction("Apply clangd test fix");
                action.setKind("quickfix");
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
