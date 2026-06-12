package org.fisk.swim.lsp.cpp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class ClangdLspClientTest {
    @TempDir
    Path tempDir;

    @Test
    void fallbackColouringScansLargeBlockCommentsWithoutRegexOverflow() {
        String source = "int before = 0;\n/*" + "*".repeat(50_000) + "\nint after = 1;\n";
        var attributed = AttributedString.create(source, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        var client = new ClangdLspClient(new ClangdLspProvider(Path.of("/missing/clangd")));

        assertTimeout(Duration.ofSeconds(2), () -> client.applyColouring(null, attributed));

        assertEquals(ClangdLspClient.SEMANTIC_KEYWORD, foreground(attributed, 0));
        assertEquals(ClangdLspClient.SEMANTIC_COMMENT, foreground(attributed, source.indexOf("/*")));
    }

    @Test
    void fallbackColouringHighlightsCppLexicalTokens() {
        String source = "#define TEXT \"value\"\nint main() { return '// not comment'; } // real comment\n";
        var attributed = AttributedString.create(source, TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        var client = new ClangdLspClient(new ClangdLspProvider(Path.of("/missing/clangd")));

        client.applyColouring(null, attributed);

        assertEquals(ClangdLspClient.SEMANTIC_MACRO, foreground(attributed, 0));
        assertEquals(ClangdLspClient.SEMANTIC_KEYWORD, foreground(attributed, source.indexOf("int")));
        assertEquals(ClangdLspClient.SEMANTIC_STRING, foreground(attributed, source.indexOf("'// not comment'")));
        assertEquals(ClangdLspClient.SEMANTIC_COMMENT, foreground(attributed, source.lastIndexOf("// real comment")));
    }

    @Test
    void applyColouringDoesNotWaitForSemanticTokens() throws Exception {
        Path file = tempDir.resolve("semantic.cpp");
        Files.writeString(file, "class Demo {};\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new ClangdLspClient(new ClangdLspProvider(Path.of("/missing/clangd")));
        setField(client, "_enabled", true);

        var requests = new AtomicInteger();
        TextDocumentService textDocumentService = new TextDocumentService() {
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
            public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
                requests.incrementAndGet();
                return new CompletableFuture<>();
            }
        };

        var capabilities = new ServerCapabilities();
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(new SemanticTokensLegend(List.of("class"), List.of()));
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", new SemanticTokensLanguageServer(textDocumentService));
        setField(client, "_capabilities", capabilities);

        try {
            var attributed = AttributedString.create(context.getBuffer().getString(),
                    TextColor.ANSI.DEFAULT,
                    TextColor.ANSI.DEFAULT);

            assertTimeout(Duration.ofMillis(100), () -> client.applyColouring(context, attributed));
            waitForRequestCount(requests, 1);

            assertTrue(requests.get() >= 1);
        } finally {
            client.shutdown();
        }
    }

    private static TextColor foreground(AttributedString string, int position) {
        return string.getCharacter(position).getFragments().get(0).getAttributes().foregroundColour();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void waitForRequestCount(AtomicInteger requests, int expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (requests.get() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for semantic token request");
    }

    private static final class SemanticTokensLanguageServer implements LanguageServer {
        private final TextDocumentService _textDocumentService;

        private SemanticTokensLanguageServer(TextDocumentService textDocumentService) {
            _textDocumentService = textDocumentService;
        }

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
            return null;
        }
    }
}
