package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.services.LanguageClient;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class ClangdDiagnosticUiIntegrationTest {
    private static final TextColor ERROR_COLOR = TextColor.ANSI.RED_BRIGHT;
    private static final TextColor WARNING_COLOR = TextColor.ANSI.YELLOW_BRIGHT;

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
        var terminal = TerminalContextTestSupport.install(20, 10);

        try (var harness = HeadlessWindowHarness.create(file, 20, 10)) {
            var window = harness.getWindow();
            LanguageClient languageClient = createLanguageClient(client);
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
}
