package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
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
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
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

class JavaDiagnosticUiIntegrationTest {
    private static final TextColor ERROR_COLOR = TextColor.ANSI.RED_BRIGHT;
    private static final TextColor WARNING_COLOR = UiTheme.DIAGNOSTIC_WARNING_FOREGROUND;

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
    void diagnosticsPopupCanOpenCodeActionsAndApplySelectedFix() throws Exception {
        Path project = tempDir.resolve("java-project");
        Path file = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(file, """
                package demo;
                class Main {}
                """);

        var client = new TestJavaLspClient(new CodeActionLanguageServer());
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(file, 60, 12)) {
            var window = harness.getWindow();
            client.createLanguageClient().publishDiagnostics(new PublishDiagnosticsParams(
                    window.getBufferContext().getBuffer().getURI().toString(),
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

            assertTrue(window.getBufferContext().getBuffer().getString().startsWith("// fixed"));
        }
    }

    @Test
    void diagnosticNavigationMovesToNextProjectDiagnostic() throws Exception {
        Path project = tempDir.resolve("java-project-nav");
        Path first = project.resolve("src/main/java/demo/First.java");
        Path second = project.resolve("src/main/java/demo/Second.java");
        Files.createDirectories(first.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(first, "package demo;\nclass First {}\n");
        Files.writeString(second, "package demo;\nclass Second {}\n");

        var client = new TestJavaLspClient(new CodeActionLanguageServer());
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);

        try (var harness = HeadlessWindowHarness.create(first, 60, 12)) {
            var window = harness.getWindow();
            client.createLanguageClient().publishDiagnostics(new PublishDiagnosticsParams(
                    second.toUri().toString(),
                    List.of(diagnostic(1, 2, DiagnosticSeverity.Warning, "Navigate here"))));

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key(']'));

            assertEquals(second.toAbsolutePath().normalize(), window.getBufferContext().getBuffer().getPath());
            assertEquals(1, window.getBufferContext().getBuffer().getCursor().getLogicalLine().getY());
        }
    }

    @Test
    void javaLspDiagnosticsColorLineNumbers() throws Exception {
        Path project = tempDir.resolve("java-project-gutter");
        Path file = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(file, """
                package demo;
                class Main {}
                """);

        var client = new TestJavaLspClient(new CodeActionLanguageServer());
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
        var terminal = TerminalContextTestSupport.install(20, 10);

        try (var harness = HeadlessWindowHarness.create(file, 20, 10)) {
            var window = harness.getWindow();
            LanguageClient languageClient = client.createLanguageClient();
            languageClient.publishDiagnostics(new PublishDiagnosticsParams(
                    window.getBufferContext().getBuffer().getURI().toString(),
                    List.of(
                            diagnostic(0, 0, DiagnosticSeverity.Error, "Error line"),
                            diagnostic(1, 0, DiagnosticSeverity.Warning, "Warning line"))));

            window.update(true);
            assertEquals(ERROR_COLOR, foregroundAt(terminal.drawCalls(), 0, 2));
            assertEquals(WARNING_COLOR, foregroundAt(terminal.drawCalls(), 0, 3));
        }
    }

    @Test
    void javaLspDiagnosticsShiftAfterLocalLineDelete() throws Exception {
        Path project = tempDir.resolve("java-project-shift-diagnostics");
        Path file = project.resolve("src/main/java/demo/Main.java");
        Files.createDirectories(file.getParent());
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(file, """
                package demo;
                class Main {}
                class Other {}
                """);

        var client = new TestJavaLspClient(new CodeActionLanguageServer());
        JavaLSPClient.installInstance(client);
        JavaLspPluginSupport.preload(() -> JavaLspPluginSupport.PLUGIN_ID);
        var terminal = TerminalContextTestSupport.install(24, 10);

        try (var harness = HeadlessWindowHarness.create(file, 24, 10)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            LanguageClient languageClient = client.createLanguageClient();
            languageClient.publishDiagnostics(new PublishDiagnosticsParams(
                    buffer.getURI().toString(),
                    List.of(diagnostic(2, 0, DiagnosticSeverity.Warning, "Warning line"))));

            window.update(true);
            assertEquals(WARNING_COLOR, foregroundAt(terminal.drawCalls(), 0, 4));

            terminal.drawCalls().clear();
            buffer.remove(0, buffer.getString().indexOf("class Main"));
            window.update(true);

            assertEquals(WARNING_COLOR, foregroundAt(terminal.drawCalls(), 0, 3));
        }
    }

    private static Diagnostic diagnostic(int line, int character, DiagnosticSeverity severity, String message) {
        var diagnostic = new Diagnostic();
        diagnostic.setRange(new Range(new Position(line, character), new Position(line, character + 1)));
        diagnostic.setSeverity(severity);
        diagnostic.setSource("java-test");
        diagnostic.setMessage(message);
        return diagnostic;
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

    private static final class TestJavaLspClient extends JavaLSPClient {
        private TestJavaLspClient(LanguageServer server) throws Exception {
            setField(this, "_enabled", true);
            setField(this, "_server", server);
            setField(this, "_capabilities", new ServerCapabilities());
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
                                "// fixed\n"))));
                var action = new CodeAction("Apply test fix");
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
