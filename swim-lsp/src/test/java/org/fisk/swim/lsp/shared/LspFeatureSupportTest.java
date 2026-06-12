package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.fisk.swim.lsp.LspContextService;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Rect;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LspFeatureSupportTest {
    @TempDir
    Path tempDir;

    @Test
    void installsSharedClientCapabilitiesIncludingCompletion() {
        var workspace = new WorkspaceClientCapabilities();
        var textDocument = new TextDocumentClientCapabilities();

        LspFeatureSupport.installClientCapabilities(workspace, textDocument);

        assertEquals(true, workspace.getApplyEdit());
        assertEquals(true, textDocument.getHover().getDynamicRegistration());
        assertEquals(true, textDocument.getCompletion().getContextSupport());
        assertEquals(true, textDocument.getCompletion().getCompletionItem().getCommitCharactersSupport());
        assertEquals(true, textDocument.getRename().getPrepareSupport());
        assertEquals(true, textDocument.getInlayHint().getDynamicRegistration());
    }

    @Test
    void documentSymbolsProduceClassAndMethodContextScopes() {
        DocumentSymbol method = symbol("run(String[])", SymbolKind.Method, 2, 2, 5, 3);
        DocumentSymbol clazz = symbol("Main", SymbolKind.Class, 0, 0, 6, 1);
        clazz.setChildren(List.of(method));

        List<LspContextService.Scope> scopes = LspSymbolContextTracker.scopesFor(
                List.of(Either.<SymbolInformation, DocumentSymbol>forRight(clazz)));

        assertEquals(2, scopes.size());
        assertEquals("Main", scopes.get(0).label());
        assertEquals("Main.run", scopes.get(1).label());
        assertEquals(2, scopes.get(1).startLine());
    }

    @Test
    void appliesWorkspaceEditToOpenBufferWithoutShiftingLaterRanges() throws Exception {
        Path file = Files.writeString(tempDir.resolve("open.txt"), "alpha beta gamma");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), file);
        var edit = new WorkspaceEdit(Map.of(
                file.toUri().toString(),
                List.of(
                        new TextEdit(new Range(new Position(0, 0), new Position(0, 5)), "A"),
                        new TextEdit(new Range(new Position(0, 11), new Position(0, 16)), "G"))));

        int applied = LspFeatureSupport.applyWorkspaceEditToOpenBuffersAndFiles(context, edit);

        assertEquals(2, applied);
        assertEquals("A beta G", context.getBuffer().getString());
    }

    @Test
    void appliesWorkspaceEditToUnopenedFile() throws Exception {
        Path current = Files.writeString(tempDir.resolve("current.txt"), "current");
        Path target = Files.writeString(tempDir.resolve("target.txt"), "one two three");
        var context = new BufferContext(Rect.create(0, 0, 80, 20), current);
        var edit = new WorkspaceEdit(Map.of(
                target.toUri().toString(),
                List.of(new TextEdit(new Range(new Position(0, 4), new Position(0, 7)), "TWO"))));

        int applied = LspFeatureSupport.applyWorkspaceEditToOpenBuffersAndFiles(context, edit);

        assertEquals(1, applied);
        assertEquals("one TWO three", Files.readString(target));
        assertEquals("current", context.getBuffer().getString());
    }

    private static DocumentSymbol symbol(String name, SymbolKind kind, int startLine, int startCharacter,
            int endLine, int endCharacter) {
        var symbol = new DocumentSymbol();
        symbol.setName(name);
        symbol.setKind(kind);
        symbol.setRange(new Range(new Position(startLine, startCharacter), new Position(endLine, endCharacter)));
        symbol.setSelectionRange(new Range(new Position(startLine, startCharacter),
                new Position(startLine, startCharacter + Math.max(1, name.length()))));
        return symbol;
    }
}
