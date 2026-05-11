package org.fisk.swim.lsp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensServerFull;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;

class JavaLSPClientTest {
    @TempDir
    Path tempDir;

    @Test
    void choosesPlatformSpecificNbcodeExecutable() {
        assertEquals("nbcode.sh", JavaLSPClient.getNbcodeExecutableName("Darwin", "aarch64"));
        assertEquals("nbcode.sh", JavaLSPClient.getNbcodeExecutableName("Linux", "x86_64"));
        assertEquals("nbcode64.exe", JavaLSPClient.getNbcodeExecutableName("Windows 11", "x86_64"));
    }

    @Test
    void findsOracleExtensionAndNbcodeExecutable() throws IOException {
        Path extensions = tempDir.resolve("extensions");
        Path oldVersion = extensions.resolve("oracle.oracle-java-23.0.0");
        Path newVersion = extensions.resolve("oracle.oracle-java-24.0.0");
        Files.createDirectories(oldVersion.resolve("nbcode/bin"));
        Files.createDirectories(newVersion.resolve("nbcode/bin"));
        Path executable = newVersion.resolve("nbcode/bin/nbcode.sh");
        Files.writeString(executable, "#!/bin/sh\n");

        assertEquals(newVersion, JavaLSPClient.findOracleExtensionPath(extensions));
        assertEquals(executable, JavaLSPClient.findNbcode(newVersion, "Linux", "x86_64"));
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
        assertEquals(TextColor.ANSI.RED, highlights.get(0).foregroundColor());
        assertEquals(TextColor.ANSI.GREEN, highlights.get(1).foregroundColor());
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
        var second = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, second);

        assertEquals(TextColor.ANSI.GREEN, foregroundColour(second.getCharacter(6)));
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
        var second = org.fisk.swim.text.AttributedString.create(context.getBuffer().getString(), TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT);
        client.applyColouring(context, second);

        assertEquals(TextColor.ANSI.GREEN, foregroundColour(second.getCharacter(6)));
        assertEquals(2, requests.get());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
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
}
