package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.terminal.TextColor;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(10)
class JavaLspConnectionRecoveryTest {
    @TempDir
    Path tempDir;

    @Test
    void semanticTimeoutRetriesWithoutClosingHealthyConnection() throws Exception {
        var slowRequest = new CompletableFuture<SemanticTokens>();
        var provider = new RecordingProvider(slowRequest);
        var client = startClient(provider);
        try {
            requestHighlights(client);
            await(() -> hasHighlights(client));

            assertTrue(slowRequest.isCancelled());
            assertEquals(1, provider.starts.get());
            assertEquals(0, provider.closes.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void serverRequestErrorRetriesWithoutClosingHealthyConnection() throws Exception {
        var failure = new ResponseErrorException(new ResponseError(
                ResponseErrorCode.InternalError, "Semantic analysis failed", null));
        var provider = new RecordingProvider(CompletableFuture.failedFuture(failure));
        var client = startClient(provider);
        try {
            requestHighlights(client);
            await(() -> hasHighlights(client));

            assertEquals(1, provider.starts.get());
            assertEquals(0, provider.closes.get());
        } finally {
            client.shutdown();
        }
    }

    @Test
    void brokenSocketStillRestartsProvider() throws Exception {
        var failure = new CompletionException(new SocketException("Connection reset"));
        var provider = new RecordingProvider(CompletableFuture.failedFuture(failure));
        var client = startClient(provider);
        try {
            requestHighlights(client);
            await(() -> provider.starts.get() == 2);
            client.ensureInit();

            assertEquals(1, provider.closes.get());
        } finally {
            client.shutdown();
        }
    }

    private JavaLSPClient startClient(RecordingProvider provider) throws Exception {
        var client = new JavaLSPClient(provider);
        Field swimHome = JavaLSPClient.class.getDeclaredField("_swimHomePath");
        swimHome.setAccessible(true);
        swimHome.set(client, tempDir.resolve("swim-home"));
        client.startServer(tempDir);
        client.ensureInit();
        return client;
    }

    private void requestHighlights(JavaLSPClient client) throws Exception {
        Path file = tempDir.resolve("Example.txt");
        Files.writeString(file, "class Example {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        client.applyColouring(context, org.fisk.swim.text.AttributedString.create(
                context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT));
    }

    private static boolean hasHighlights(JavaLSPClient client) {
        try {
            Field cache = JavaLSPClient.class.getDeclaredField("_semanticTokensCache");
            cache.setAccessible(true);
            return !((Map<?, ?>) cache.get(client)).isEmpty();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for Java LSP request handling");
    }

    private static final class RecordingProvider implements JavaLspProvider, LanguageServer, TextDocumentService {
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger requests = new AtomicInteger();
        private final CompletableFuture<SemanticTokens> firstResponse;

        private RecordingProvider(CompletableFuture<SemanticTokens> firstResponse) {
            this.firstResponse = firstResponse;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public Session start(Path project, Path workspace, LanguageClient client, ClientCapabilities capabilities,
                Object options, long timeoutSeconds) {
            var semanticOptions = new SemanticTokensWithRegistrationOptions();
            semanticOptions.setLegend(new SemanticTokensLegend(List.of("class"), List.of()));
            var serverCapabilities = new ServerCapabilities();
            serverCapabilities.setSemanticTokensProvider(semanticOptions);
            starts.incrementAndGet();
            return new Session(this, serverCapabilities, closes::incrementAndGet, "test");
        }

        @Override
        public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
            return requests.getAndIncrement() == 0 ? firstResponse
                    : CompletableFuture.completedFuture(new SemanticTokens(List.of(0, 6, 7, 0, 0)));
        }

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
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult());
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
            return this;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException();
        }
    }
}
