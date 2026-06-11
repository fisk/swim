package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensServerFull;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.JavaDefinitionPopupView;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class JavaLSPClientTest {
    @TempDir
    Path tempDir;

    @Test
    void findsOracleExtension() throws IOException {
        Path extensions = tempDir.resolve("extensions");
        Path oldVersion = extensions.resolve("oracle.oracle-java-23.0.0");
        Path newVersion = extensions.resolve("oracle.oracle-java-24.0.0");
        Files.createDirectories(oldVersion.resolve("nbcode/bin"));
        Files.createDirectories(newVersion.resolve("nbcode/bin"));

        assertEquals(newVersion, JavaLSPClient.findOracleExtensionPath(extensions));
    }

    @Test
    void workspacePathIsStableForProjectRoot() {
        Path swimHome = tempDir.resolve(".swim");
        Path project = tempDir.resolve("demo-project");

        Path workspacePath = JavaLSPClient.getWorkspacePath(swimHome, project);

        assertTrue(workspacePath.startsWith(swimHome.resolve("workspace")));
        assertTrue(workspacePath.getFileName().toString().startsWith("demo-project-"));
    }

    @Test
    void appliesWorkspaceEditWithoutShiftingLaterRanges() throws IOException {
        Path file = tempDir.resolve("Example.txt");
        Files.writeString(file, "alpha beta gamma");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);

        var edit = new WorkspaceEdit();
        edit.setChanges(java.util.Map.of(
                file.toUri().toString(),
                List.of(
                        new TextEdit(new Range(new Position(0, 0), new Position(0, 5)), "A"),
                        new TextEdit(new Range(new Position(0, 11), new Position(0, 16)), "G"))));

        JavaLSPClient.applyWorkspaceEdit(context, edit);

        assertEquals("A beta G", context.getBuffer().getString());
    }

    @Test
    void openedTextDocumentUsesCurrentBufferVersion() throws IOException {
        Path file = tempDir.resolve("Versioned.txt");
        Files.writeString(file, "class Versioned {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();

        context.getBuffer().insert("x");

        assertEquals(context.getBuffer().getVersionedTextDocumentID().getVersion(),
                client.getTextDocument(context).getVersion());
    }

    @Test
    void decodesSemanticTokensIntoHighlights() throws IOException {
        Path file = tempDir.resolve("Semantic.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var legend = new SemanticTokensLegend(
                List.of("keyword", "class"),
                List.of());
        var tokens = new SemanticTokens(List.of(
                0, 0, 5, 0, 0,
                0, 6, 4, 1, 0));

        var highlights = JavaLSPClient.decodeSemanticHighlights(context, tokens, legend);

        assertEquals(2, highlights.size());
        assertEquals(JavaLSPClient.SEMANTIC_KEYWORD, highlights.get(0).foregroundColor());
        assertEquals(JavaLSPClient.SEMANTIC_TYPE, highlights.get(1).foregroundColor());
    }

    @Test
    void applyColouringUsesSemanticTokensAndCachesByVersion() throws Exception {
        Path file = tempDir.resolve("SemanticApply.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
        setField(client, "_enabled", true);

        var requests = new AtomicInteger();
        TextDocumentService textDocumentService = new SemanticTokensTextDocumentService(requests);
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);

        var first = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, first);
        waitForSemanticCache(client, context.getBuffer().getURI().toString());
        var second = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, second);

        assertEquals(JavaLSPClient.SEMANTIC_TYPE, foregroundColour(second.getCharacter(6)));
        assertEquals(1, requests.get());
    }

    @Test
    void applyColouringRetriesWhenSemanticTokensAreInitiallyUnavailable() throws Exception {
        Path file = tempDir.resolve("SemanticRetry.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
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
                if (requests.getAndIncrement() == 0) {
                    return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
                }
                return CompletableFuture.completedFuture(new SemanticTokens(List.of(
                        0, 0, 5, 0, 0,
                        0, 6, 4, 1, 0)));
            }
        };
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);

        var first = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, first);
        waitForSemanticCache(client, context.getBuffer().getURI().toString());
        var second = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, second);

        assertEquals(JavaLSPClient.SEMANTIC_TYPE, foregroundColour(second.getCharacter(6)));
        assertEquals(2, requests.get());
    }

    @Test
    void applyColouringDoesNotWaitForSemanticTokens() throws Exception {
        Path file = tempDir.resolve("SemanticBlocked.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
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
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);

        var attributed = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> client.applyColouring(context, attributed));
    }

    @Test
    void handleInsertedCharacterDoesNotWaitForCompletionRequest() throws Exception {
        Path file = tempDir.resolve("CompletionBlocked.java");
        Files.writeString(file, "a");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        context.getBuffer().getCursor().setPosition(1);
        var client = new JavaLSPClient();
        setField(client, "_enabled", true);

        var completionRequests = new AtomicInteger();
        var completionGate = new CompletableFuture<Void>();
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
            public CompletableFuture<Either<List<CompletionItem>, org.eclipse.lsp4j.CompletionList>> completion(
                    CompletionParams params) {
                completionRequests.incrementAndGet();
                completionGate.join();
                return CompletableFuture.completedFuture(Either.forLeft(List.of(new CompletionItem("alpha"))));
            }
        };
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        capabilities.setCompletionProvider(new CompletionOptions(Boolean.FALSE, List.of(".")));
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> client.handleInsertedCharacter(context, 'a'));

        waitForRequestCount(completionRequests, 1);
        completionGate.complete(null);
        client.shutdown();
    }

    @Test
    void insertDoesNotWaitForSemanticRefreshInProgress() throws Exception {
        Path file = tempDir.resolve("SemanticTypingBlocked.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
        setField(client, "_enabled", true);

        var requests = new AtomicInteger();
        var semanticResponse = new CompletableFuture<SemanticTokens>();
        var didChangeRequests = new AtomicInteger();
        var didChangeBatchSize = new AtomicInteger();
        var didChangeVersion = new AtomicInteger();
        TextDocumentService textDocumentService = new TextDocumentService() {
            @Override
            public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
            }

            @Override
            public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
                didChangeRequests.incrementAndGet();
                didChangeBatchSize.set(params.getContentChanges().size());
                didChangeVersion.set(params.getTextDocument().getVersion());
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
                return semanticResponse;
            }
        };
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);
        setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);

        var first = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, first);
        waitForRequestCount(requests, 1);

        int insertPosition = context.getBuffer().getString().indexOf("Demo") + 2;
        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            context.getBuffer().insert(insertPosition, "X");
            context.getBuffer().insert(insertPosition + 1, "Y");
        });

        semanticResponse.complete(new SemanticTokens(List.of(
                0, 0, 5, 0, 0,
                0, 6, 4, 1, 0)));
        waitForRequestCount(didChangeRequests, 1);
        assertEquals(1, didChangeRequests.get());
        assertEquals(2, didChangeBatchSize.get());
        assertEquals(context.getBuffer().getVersionedTextDocumentID().getVersion(), didChangeVersion.get());
        client.shutdown();
    }

    @Test
    void semanticRefreshAppliesMutationsRecordedAfterSnapshot() throws Exception {
        Path file = tempDir.resolve("SemanticSnapshotMutation.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
        setField(client, "_enabled", true);

        var requests = new AtomicInteger();
        var firstResponse = new CompletableFuture<SemanticTokens>();
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
                if (requests.getAndIncrement() == 0) {
                    return firstResponse;
                }
                return new CompletableFuture<>();
            }
        };
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);
        setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);

        var first = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, first);
        waitForRequestCount(requests, 1);

        int insertPosition = context.getBuffer().getString().indexOf("Demo") + 2;
        context.getBuffer().insert(insertPosition, "X");
        firstResponse.complete(new SemanticTokens(List.of(
                0, 0, 5, 0, 0,
                0, 6, 4, 1, 0)));
        waitForSemanticCache(client, context.getBuffer().getURI().toString());

        var coloured = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, coloured);

        assertEquals(JavaLSPClient.SEMANTIC_TYPE, foregroundColour(coloured.getCharacter(insertPosition)));
    }

    @Test
    void insertedTextUsesAdjacentCachedSemanticColourWhileRefreshIsPending() throws Exception {
        Path file = tempDir.resolve("SemanticMutation.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
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
                if (requests.getAndIncrement() == 0) {
                    return CompletableFuture.completedFuture(new SemanticTokens(List.of(
                            0, 0, 5, 0, 0,
                            0, 6, 4, 1, 0)));
                }
                return new CompletableFuture<>();
            }
        };
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);
        setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);

        var first = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, first);
        waitForSemanticCache(client, context.getBuffer().getURI().toString());

        int insertPosition = context.getBuffer().getString().indexOf("Demo") + 2;
        context.getBuffer().insert(insertPosition, "X");
        var coloured = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, coloured);

        assertEquals(JavaLSPClient.SEMANTIC_TYPE, foregroundColour(coloured.getCharacter(insertPosition)));
    }

    @Test
    void didOpenPrefetchesSemanticTokensAsynchronously() throws Exception {
        Path file = tempDir.resolve("SemanticAsync.txt");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var client = new JavaLSPClient();
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
                if (requests.getAndIncrement() < 2) {
                    return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
                }
                return CompletableFuture.completedFuture(new SemanticTokens(List.of(
                        0, 0, 5, 0, 0,
                        0, 6, 4, 1, 0)));
            }
        };
        LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

        var capabilities = new ServerCapabilities();
        var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
        var semanticOptions = new SemanticTokensWithRegistrationOptions();
        semanticOptions.setLegend(legend);
        semanticOptions.setFull(Either.forLeft(true));
        capabilities.setSemanticTokensProvider(semanticOptions);
        setField(client, "_server", server);
        setField(client, "_capabilities", capabilities);
        setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);

        client.didOpen(context);

        @SuppressWarnings("unchecked")
        Map<String, ?> cache = waitForSemanticCache(client, context.getBuffer().getURI().toString());
        assertTrue(cache.containsKey(context.getBuffer().getURI().toString()));

        var coloured = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, coloured);

        assertEquals(JavaLSPClient.SEMANTIC_TYPE, foregroundColour(coloured.getCharacter(6)));
        assertTrue(requests.get() >= 3);
    }

    @Test
    void goToDefinitionJumpsDirectlyWhenThereIsOneTarget() throws Exception {
        Path current = tempDir.resolve("Current.txt");
        Path target = tempDir.resolve("Target.txt");
        Files.writeString(current, "alpha\n");
        Files.writeString(target, "zero\nbeta\n");

        var client = new JavaLSPClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        setField(client, "_server", new DefinitionLanguageServer(Either.forLeft(List.of(
                new Location(target.toUri().toString(), new Range(new Position(1, 2), new Position(1, 6)))))));

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();

            client.goToDefinition(window.getBufferContext());

            assertEquals(target.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertEquals(window.getBufferContext().getTextLayout().getIndexForPhysicalLineCharacter(1, 2),
                    window.getBufferContext().getBuffer().getCursor().getPosition());
            assertSame(window.getBufferContext().getBufferView(), window.getRootView().getFirstResponder());
        }
    }

    @Test
    void goToDefinitionShowsPopupWhenThereAreMultipleTargets() throws Exception {
        Path current = tempDir.resolve("CurrentMulti.txt");
        Path targetOne = tempDir.resolve("TargetOne.txt");
        Path targetTwo = tempDir.resolve("TargetTwo.txt");
        Files.writeString(current, "alpha\n");
        Files.writeString(targetOne, "first\n");
        Files.writeString(targetTwo, "second\nline\n");

        var client = new JavaLSPClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        setField(client, "_server", new DefinitionLanguageServer(Either.forRight(List.of(
                new LocationLink(targetOne.toUri().toString(),
                        new Range(new Position(0, 0), new Position(0, 5)),
                        new Range(new Position(0, 0), new Position(0, 5))),
                new LocationLink(targetTwo.toUri().toString(),
                        new Range(new Position(1, 1), new Position(1, 4)),
                        new Range(new Position(1, 1), new Position(1, 4)))))));

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();

            client.goToDefinition(window.getBufferContext());

            var popup = assertInstanceOf(JavaDefinitionPopupView.class, window.getRootView().getFirstResponder());
            assertEquals(2, popup.getSession().size());
            assertTrue(popup.getSession().getEntries().get(0).label().contains("TargetOne.txt:1:1"));
        }
    }

    @Test
    void definitionPopupEnterJumpsToSelectedTarget() throws Exception {
        Path current = tempDir.resolve("CurrentPopup.txt");
        Path targetOne = tempDir.resolve("TargetPopupOne.txt");
        Path targetTwo = tempDir.resolve("TargetPopupTwo.txt");
        Files.writeString(current, "alpha\n");
        Files.writeString(targetOne, "first\n");
        Files.writeString(targetTwo, "zero\nchosen\n");

        var client = new JavaLSPClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        setField(client, "_server", new DefinitionLanguageServer(Either.forLeft(List.of(
                new Location(targetOne.toUri().toString(), new Range(new Position(0, 0), new Position(0, 3))),
                new Location(targetTwo.toUri().toString(), new Range(new Position(1, 2), new Position(1, 6)))))));

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();
            client.goToDefinition(window.getBufferContext());

            var popup = assertInstanceOf(JavaDefinitionPopupView.class, window.getRootView().getFirstResponder());
            HeadlessWindowHarness.dispatch(popup, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(popup, HeadlessWindowHarness.enter());

            assertEquals(targetTwo.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertEquals(window.getBufferContext().getTextLayout().getIndexForPhysicalLineCharacter(1, 2),
                    window.getBufferContext().getBuffer().getCursor().getPosition());
            assertSame(window.getBufferContext().getBufferView(), window.getRootView().getFirstResponder());
        }
    }

    @Test
    void findReferencesShowsPopupWhenThereAreMultipleTargets() throws Exception {
        Path current = tempDir.resolve("CurrentRefs.txt");
        Path targetOne = tempDir.resolve("RefOne.txt");
        Path targetTwo = tempDir.resolve("RefTwo.txt");
        Files.writeString(current, "alpha\n");
        Files.writeString(targetOne, "first ref\n");
        Files.writeString(targetTwo, "second\nref line\n");

        var client = new JavaLSPClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        setField(client, "_server", new ReferencesLanguageServer(List.of(
                new Location(targetOne.toUri().toString(), new Range(new Position(0, 0), new Position(0, 5))),
                new Location(targetTwo.toUri().toString(), new Range(new Position(1, 0), new Position(1, 3))))));

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();

            client.findReferences(window.getBufferContext());

            var popup = assertInstanceOf(JavaDefinitionPopupView.class, window.getRootView().getFirstResponder());
            assertEquals("References", popup.getTitle());
            assertEquals(2, popup.getSession().size());
            assertTrue(popup.getSession().getEntries().get(0).label().contains("RefOne.txt:1:1"));
        }
    }

    @Test
    void referencesPopupEnterJumpsToSelectedTarget() throws Exception {
        Path current = tempDir.resolve("CurrentRefsPopup.txt");
        Path targetOne = tempDir.resolve("RefPopupOne.txt");
        Path targetTwo = tempDir.resolve("RefPopupTwo.txt");
        Files.writeString(current, "alpha\n");
        Files.writeString(targetOne, "first\n");
        Files.writeString(targetTwo, "zero\nchosen\n");

        var client = new JavaLSPClient();
        setField(client, "_enabled", true);
        setField(client, "_projectPath", tempDir);
        setField(client, "_server", new ReferencesLanguageServer(List.of(
                new Location(targetOne.toUri().toString(), new Range(new Position(0, 0), new Position(0, 3))),
                new Location(targetTwo.toUri().toString(), new Range(new Position(1, 2), new Position(1, 6))))));

        try (var harness = HeadlessWindowHarness.create(current, 80, 16)) {
            var window = harness.getWindow();
            client.findReferences(window.getBufferContext());

            var popup = assertInstanceOf(JavaDefinitionPopupView.class, window.getRootView().getFirstResponder());
            HeadlessWindowHarness.dispatch(popup, HeadlessWindowHarness.down());
            HeadlessWindowHarness.dispatch(popup, HeadlessWindowHarness.enter());

            assertEquals(targetTwo.toAbsolutePath().normalize(),
                    window.getBufferContext().getBuffer().getPath().toAbsolutePath().normalize());
            assertEquals(window.getBufferContext().getTextLayout().getIndexForPhysicalLineCharacter(1, 2),
                    window.getBufferContext().getBuffer().getCursor().getPosition());
            assertSame(window.getBufferContext().getBufferView(), window.getRootView().getFirstResponder());
        }
    }

    @Test
    void bufferAttributedStringBecomesSemanticAfterDidOpenWithoutManualEdits() throws Exception {
        Path file = tempDir.resolve("SemanticWindow.java");
        Files.writeString(file, "class Demo {}\n");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        try (var harness = HeadlessWindowHarness.installForBufferContext(context)) {
            var client = new JavaLSPClient();
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
                    if (requests.getAndIncrement() == 0) {
                        return CompletableFuture.completedFuture(new SemanticTokens(List.of()));
                    }
                    return CompletableFuture.completedFuture(new SemanticTokens(List.of(
                            0, 0, 5, 0, 0,
                            0, 6, 4, 1, 0)));
                }
            };
            LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

            var capabilities = new ServerCapabilities();
            var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
            var semanticOptions = new SemanticTokensWithRegistrationOptions();
            semanticOptions.setLegend(legend);
            semanticOptions.setFull(Either.forLeft(true));
            capabilities.setSemanticTokensProvider(semanticOptions);
            setField(client, "_server", server);
            setField(client, "_capabilities", capabilities);
            setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);

            client.didOpen(context);
            waitForSemanticCache(client, context.getBuffer().getURI().toString());

            var attributed = context.getBuffer().getAttributedString();
            assertEquals(JavaLSPClient.SEMANTIC_TYPE, foregroundColour(attributed.getCharacter(6)));
            assertTrue(requests.get() >= 2);
        }
    }

    @Test
    void realEmbeddedJavaClientColorsRealSwimRuntimeBuffer() throws Exception {
        Path extensionPath = EmbeddedOracleModuleLayerLspProvider.resolveOracleExtensionPath();
        var provider = new EmbeddedOracleModuleLayerLspProvider(extensionPath);
        org.junit.jupiter.api.Assumptions.assumeTrue(provider.isAvailable(), "Oracle Java extension payload not available");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                EmbeddedOracleModuleLayerLspProvider.hasRequiredJvmAccess(),
                "Embedded provider requires the NetBeans-compatible JVM launcher flags");

        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path javaFile = project.resolve("src/main/java/org/fisk/swim/SwimRuntime.java");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(project.resolve("pom.xml")));
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(javaFile));

        var context = new BufferContext(Rect.create(0, 0, 120, 40), javaFile);
        try (var harness = HeadlessWindowHarness.installForBufferContext(context)) {
            var client = new JavaLSPClient(provider);
            setField(client, "_swimHomePath", tempDir.resolve(".swim-home"));
            client.startServer(javaFile);
            client.ensureInit();
            assertNotNull(client.getCapabilities());
            assertNotNull(client.getCapabilities().getSemanticTokensProvider());

            setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);
            client.didOpen(context);

            int classIndex = context.getBuffer().getString().indexOf("SwimRuntime");
            if (classIndex < 0) {
                throw new AssertionError("Expected SwimRuntime class name in buffer");
            }

            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            TextColor color = TextColor.ANSI.DEFAULT;
            while (System.nanoTime() < deadline) {
                var attributed = context.getBuffer().getAttributedString();
                color = foregroundColour(attributed.getCharacter(classIndex));
                if (!TextColor.ANSI.DEFAULT.equals(color) && !JavaLSPClient.SEMANTIC_KEYWORD.equals(color)) {
                    break;
                }
                Thread.sleep(250);
            }

            assertEquals(JavaLSPClient.SEMANTIC_TYPE, color);
            client.shutdown();
        }
    }

    @Test
    void bufferViewDrawUsesSemanticForegroundColours() throws Exception {
        Path file = tempDir.resolve("SemanticRender.java");
        Files.writeString(file, "class Demo {}\n");
        var installedTerminal = TerminalContextTestSupport.install(120, 40);
        try (var harness = HeadlessWindowHarness.create(file, 120, 20)) {
            var context = harness.getWindow().getBufferContext();
            var client = new JavaLSPClient();
            setField(client, "_enabled", true);

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
                    return CompletableFuture.completedFuture(new SemanticTokens(List.of(
                            0, 0, 5, 0, 0,
                            0, 6, 4, 1, 0)));
                }
            };
            LanguageServer server = new SemanticTokensLanguageServer(textDocumentService);

            var capabilities = new ServerCapabilities();
            var legend = new SemanticTokensLegend(List.of("keyword", "class"), List.of());
            var semanticOptions = new SemanticTokensWithRegistrationOptions();
            semanticOptions.setLegend(legend);
            semanticOptions.setFull(Either.forLeft(true));
            capabilities.setSemanticTokensProvider(semanticOptions);
            setField(client, "_server", server);
            setField(client, "_capabilities", capabilities);
            setNamedField(context.getBuffer(), org.fisk.swim.text.Buffer.class, "_languageMode", client);

            client.didOpen(context);
            waitForSemanticCache(client, context.getBuffer().getURI().toString());

            installedTerminal.drawCalls().clear();
            context.getBufferView().draw(context.getBufferView().getBounds());

            int classIndex = context.getBuffer().getString().indexOf("Demo");
            var line = context.getTextLayout().getPhysicalLineAt(classIndex);
            int charIndex = classIndex - line.getStartPosition();
            int expectedX = context.getBufferView().getBounds().getPoint().getX()
                    + context.getBufferView().getTextColumnStart()
                    + charIndex;
            int expectedY = context.getBufferView().getBounds().getPoint().getY() + line.getY() - context.getBufferView().getStartLine();

            var classDraw = installedTerminal.drawCalls().stream()
                    .filter(call -> call.x() == expectedX && call.y() == expectedY && call.text().equals("D"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected draw call for class identifier"));

            assertEquals(JavaLSPClient.SEMANTIC_TYPE, classDraw.foreground());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> waitForSemanticCache(JavaLSPClient client, String uri) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Map<String, ?> cache = (Map<String, ?>) getField(client, "_semanticTokensCache");
            if (cache.containsKey(uri)) {
                return cache;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for semantic token cache for " + uri);
    }

    private static void waitForRequestCount(AtomicInteger requests, int expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (requests.get() >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for semantic token requests");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setNamedField(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static TextColor foregroundColour(org.fisk.swim.text.AttributedString string) throws Exception {
        Object fragment = string.getFragments().get(0);
        Field attributesField = fragment.getClass().getDeclaredField("_attributes");
        attributesField.setAccessible(true);
        Object attributes = attributesField.get(fragment);
        Field foregroundField = attributes.getClass().getDeclaredField("_foregroundColour");
        foregroundField.setAccessible(true);
        return (TextColor) foregroundField.get(attributes);
    }

    private static final class SemanticTokensTextDocumentService implements TextDocumentService {
        private final AtomicInteger _requests;

        private SemanticTokensTextDocumentService(AtomicInteger requests) {
            _requests = requests;
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
        public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
            _requests.incrementAndGet();
            return CompletableFuture.completedFuture(new SemanticTokens(List.of(
                    0, 0, 5, 0, 0,
                    0, 6, 4, 1, 0)));
        }
    }

    private static final class SemanticTokensLanguageServer implements LanguageServer {
        private final TextDocumentService _textDocumentService;

        private SemanticTokensLanguageServer(TextDocumentService textDocumentService) {
            _textDocumentService = textDocumentService;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            throw new UnsupportedOperationException();
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
            throw new UnsupportedOperationException();
        }
    }

    private static final class DefinitionLanguageServer implements LanguageServer {
        private final Either<List<? extends Location>, List<? extends LocationLink>> _response;

        private DefinitionLanguageServer(Either<List<? extends Location>, List<? extends LocationLink>> response) {
            _response = response;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            throw new UnsupportedOperationException();
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
            return new TextDocumentService() {
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
                public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
                        org.eclipse.lsp4j.DefinitionParams params) {
                    return CompletableFuture.completedFuture(_response);
                }
            };
        }

        @Override
        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException();
        }
    }

    private static final class ReferencesLanguageServer implements LanguageServer {
        private final List<Location> _locations;

        private ReferencesLanguageServer(List<Location> locations) {
            _locations = locations;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            throw new UnsupportedOperationException();
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
            return new TextDocumentService() {
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
                public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
                    return CompletableFuture.completedFuture(_locations);
                }
            };
        }

        @Override
        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
            throw new UnsupportedOperationException();
        }
    }

}
