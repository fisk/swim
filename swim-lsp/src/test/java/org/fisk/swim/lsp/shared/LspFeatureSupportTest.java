package org.fisk.swim.lsp.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
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
}
