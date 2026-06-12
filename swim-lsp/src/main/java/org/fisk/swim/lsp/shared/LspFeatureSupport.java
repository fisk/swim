package org.fisk.swim.lsp.shared;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.eclipse.lsp4j.CallHierarchyCapabilities;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.CodeActionResolveSupportCapabilities;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.ColorInformation;
import org.eclipse.lsp4j.ColorPresentation;
import org.eclipse.lsp4j.ColorPresentationParams;
import org.eclipse.lsp4j.ColorProviderCapabilities;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.DeclarationCapabilities;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentColorParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightCapabilities;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkCapabilities;
import org.eclipse.lsp4j.DocumentLinkParams;
import org.eclipse.lsp4j.DocumentOnTypeFormattingOptions;
import org.eclipse.lsp4j.DocumentOnTypeFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeCapabilities;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationCapabilities;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.LinkedEditingRangeCapabilities;
import org.eclipse.lsp4j.LinkedEditingRangeParams;
import org.eclipse.lsp4j.LinkedEditingRanges;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.OnTypeFormattingCapabilities;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RangeFormattingCapabilities;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.SelectionRange;
import org.eclipse.lsp4j.SelectionRangeCapabilities;
import org.eclipse.lsp4j.SelectionRangeParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolKindCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.TypeHierarchyCapabilities;
import org.eclipse.lsp4j.TypeHierarchyItem;
import org.eclipse.lsp4j.TypeHierarchyPrepareParams;
import org.eclipse.lsp4j.TypeHierarchySubtypesParams;
import org.eclipse.lsp4j.TypeHierarchySupertypesParams;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.eclipse.lsp4j.services.LanguageServer;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.Settings;
import org.fisk.swim.ui.LspFeaturePopupView;
import org.fisk.swim.ui.Point;
import org.fisk.swim.ui.UiTheme;
import org.fisk.swim.ui.Window;
import org.slf4j.Logger;

import com.googlecode.lanterna.TextColor;

public final class LspFeatureSupport {
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private static final int MAX_POPUP_TEXT_LINES = 18;

    public interface Client {
        String displayName();
        boolean isAvailable();
        LanguageServer server();
        ServerCapabilities capabilities();
        AsyncLspRequestQueue requestQueue();
        void flushPendingDocumentChanges(String uri);
        void runOnEventThread(Runnable action);
        void applyWorkspaceEdit(BufferContext context, WorkspaceEdit edit);
        void applyCommand(BufferContext context, Command command);
        String displayPath(Path path);
        Logger log();
    }

    private record Snapshot(BufferContext context, String uri, int version, int cursor, Position position) {
        private static Snapshot capture(BufferContext context) {
            if (context == null || context.getBuffer() == null) {
                return null;
            }
            int cursor = context.getBuffer().getCursor().getPosition();
            return new Snapshot(
                    context,
                    context.getBuffer().getURI().toString(),
                    context.getBuffer().getVersionedTextDocumentID().getVersion(),
                    cursor,
                    positionAt(context, cursor));
        }

        private boolean stillCurrent() {
            return context != null
                    && context.getBuffer() != null
                    && uri.equals(context.getBuffer().getURI().toString())
                    && context.getBuffer().getVersionedTextDocumentID().getVersion() == version;
        }

        private TextDocumentIdentifier textDocument() {
            return context.getBuffer().getTextDocumentID();
        }
    }

    private record LocationTarget(Path path, Position position, String label, String detail) {
    }

    private record IndexedEdit(int start, int end, String text) {
    }

    private final Client _client;

    public LspFeatureSupport(Client client) {
        _client = Objects.requireNonNull(client, "client");
    }

    public static void installClientCapabilities(
            WorkspaceClientCapabilities workspace,
            TextDocumentClientCapabilities textDocument) {
        if (workspace != null) {
            workspace.setApplyEdit(true);
            workspace.setWorkspaceEdit(new WorkspaceEditCapabilities(true));
            workspace.setSymbol(new SymbolCapabilities(new SymbolKindCapabilities(Arrays.asList(SymbolKind.values())), true));
        }
        if (textDocument == null) {
            return;
        }
        textDocument.setHover(new HoverCapabilities(List.of("markdown", "plaintext"), true));
        textDocument.setSignatureHelp(new SignatureHelpCapabilities(true));
        textDocument.setDeclaration(new DeclarationCapabilities(true, true));
        textDocument.setDefinition(new DefinitionCapabilities(true, true));
        textDocument.setTypeDefinition(new TypeDefinitionCapabilities(true, true));
        textDocument.setImplementation(new ImplementationCapabilities(true, true));
        textDocument.setDocumentHighlight(new DocumentHighlightCapabilities(true));
        var documentSymbol = new DocumentSymbolCapabilities(new SymbolKindCapabilities(Arrays.asList(SymbolKind.values())), true, true);
        documentSymbol.setLabelSupport(true);
        textDocument.setDocumentSymbol(documentSymbol);
        textDocument.setFormatting(new FormattingCapabilities(true));
        textDocument.setRangeFormatting(new RangeFormattingCapabilities(true));
        textDocument.setOnTypeFormatting(new OnTypeFormattingCapabilities(true));
        textDocument.setCodeLens(new CodeLensCapabilities());
        textDocument.setDocumentLink(new DocumentLinkCapabilities(true, true));
        textDocument.setColorProvider(new ColorProviderCapabilities(true));
        textDocument.setRename(new RenameCapabilities(true, true));
        textDocument.setFoldingRange(new FoldingRangeCapabilities());
        textDocument.setTypeHierarchy(new TypeHierarchyCapabilities(true));
        textDocument.setCallHierarchy(new CallHierarchyCapabilities(true));
        textDocument.setSelectionRange(new SelectionRangeCapabilities(true));
        textDocument.setLinkedEditingRange(new LinkedEditingRangeCapabilities(true));
        textDocument.setInlayHint(new InlayHintCapabilities(true));
        var codeAction = textDocument.getCodeAction();
        if (codeAction == null) {
            codeAction = new CodeActionCapabilities(true);
            textDocument.setCodeAction(codeAction);
        }
        codeAction.setDataSupport(true);
        codeAction.setResolveSupport(new CodeActionResolveSupportCapabilities(List.of("edit", "command")));
        if (textDocument.getCompletion() == null) {
            var completionItem = new CompletionItemCapabilities();
            completionItem.setSnippetSupport(false);
            completionItem.setCommitCharactersSupport(true);
            completionItem.setDeprecatedSupport(true);
            completionItem.setPreselectSupport(true);
            completionItem.setInsertReplaceSupport(true);
            completionItem.setLabelDetailsSupport(true);
            var completion = new CompletionCapabilities();
            completion.setCompletionItem(completionItem);
            completion.setContextSupport(true);
            textDocument.setCompletion(completion);
        }
    }

    public void showHover(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getHoverProvider())) {
            status(_client.displayName() + " hover is not supported");
            return;
        }
        request("hover", context,
                snapshot -> _client.server().getTextDocumentService()
                        .hover(new HoverParams(snapshot.textDocument(), snapshot.position())).get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                (snapshot, hover) -> {
                    List<String> lines = hoverLines(hover);
                    showTextEntries(snapshot, "Hover", "DOC", lines, UiTheme.ACCENT_BLUE, "No hover information");
                });
    }

    public void showSignatureHelp(BufferContext context) {
        if (_client.capabilities() == null || _client.capabilities().getSignatureHelpProvider() == null) {
            status(_client.displayName() + " signature help is not supported");
            return;
        }
        request("signature help", context,
                snapshot -> _client.server().getTextDocumentService()
                        .signatureHelp(new SignatureHelpParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showSignatureHelp);
    }

    public void goToDefinition(BufferContext context) {
        requestLocations("Definitions", "definition", context,
                snapshot -> _client.server().getTextDocumentService()
                        .definition(new DefinitionParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void goToDeclaration(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getDeclarationProvider())) {
            status(_client.displayName() + " declaration is not supported");
            return;
        }
        requestLocations("Declarations", "declaration", context,
                snapshot -> _client.server().getTextDocumentService()
                        .declaration(new DeclarationParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void goToTypeDefinition(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getTypeDefinitionProvider())) {
            status(_client.displayName() + " type definition is not supported");
            return;
        }
        requestLocations("Type Definitions", "type definition", context,
                snapshot -> _client.server().getTextDocumentService()
                        .typeDefinition(new TypeDefinitionParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void goToImplementation(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getImplementationProvider())) {
            status(_client.displayName() + " implementation is not supported");
            return;
        }
        requestLocations("Implementations", "implementation", context,
                snapshot -> _client.server().getTextDocumentService()
                        .implementation(new ImplementationParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void findReferences(BufferContext context) {
        request("references", context,
                snapshot -> _client.server().getTextDocumentService()
                        .references(new ReferenceParams(snapshot.textDocument(), snapshot.position(), new ReferenceContext(true)))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                (snapshot, locations) -> showLocationTargets(snapshot, "References", locationTargets(locations, null), true));
    }

    public void showDocumentHighlights(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getDocumentHighlightProvider())) {
            status(_client.displayName() + " document highlight is not supported");
            return;
        }
        request("document highlight", context,
                snapshot -> _client.server().getTextDocumentService()
                        .documentHighlight(new DocumentHighlightParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showDocumentHighlights);
    }

    public void showDocumentSymbols(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getDocumentSymbolProvider())) {
            status(_client.displayName() + " document symbols are not supported");
            return;
        }
        request("document symbols", context,
                snapshot -> _client.server().getTextDocumentService()
                        .documentSymbol(new DocumentSymbolParams(snapshot.textDocument()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showDocumentSymbols);
    }

    public void promptWorkspaceSymbols(BufferContext context) {
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.showInputPrompt("Workspace Symbols", "query", "", query -> showWorkspaceSymbols(context, query));
    }

    public void showWorkspaceSymbols(BufferContext context, String query) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getWorkspaceSymbolProvider())) {
            status(_client.displayName() + " workspace symbols are not supported");
            return;
        }
        request("workspace symbols", context,
                snapshot -> _client.server().getWorkspaceService()
                        .symbol(new WorkspaceSymbolParams(query == null ? "" : query))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showWorkspaceSymbols);
    }

    public void showCodeActions(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getCodeActionProvider())) {
            status(_client.displayName() + " code actions are not supported");
            return;
        }
        request("code actions", context,
                snapshot -> resolveCodeActions(_client.server().getTextDocumentService()
                        .codeAction(new CodeActionParams(snapshot.textDocument(), currentLineRange(snapshot.context()),
                                new CodeActionContext(List.of())))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
                this::showCodeActions);
    }

    public void showCodeLens(BufferContext context) {
        if (_client.capabilities() == null || _client.capabilities().getCodeLensProvider() == null) {
            status(_client.displayName() + " code lens is not supported");
            return;
        }
        request("code lens", context,
                snapshot -> resolveCodeLenses(_client.server().getTextDocumentService()
                        .codeLens(new org.eclipse.lsp4j.CodeLensParams(snapshot.textDocument()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
                this::showCodeLens);
    }

    public void formatDocument(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getDocumentFormattingProvider())) {
            status(_client.displayName() + " document formatting is not supported");
            return;
        }
        requestEdit("format document", context,
                snapshot -> _client.server().getTextDocumentService()
                        .formatting(new DocumentFormattingParams(snapshot.textDocument(), formattingOptions(snapshot.context())))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void formatCurrentLine(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getDocumentRangeFormattingProvider())) {
            status(_client.displayName() + " range formatting is not supported");
            return;
        }
        requestEdit("format current line", context,
                snapshot -> _client.server().getTextDocumentService()
                        .rangeFormatting(new DocumentRangeFormattingParams(snapshot.textDocument(),
                                formattingOptions(snapshot.context()), currentLineRange(snapshot.context())))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void formatOnType(BufferContext context) {
        var provider = _client.capabilities() == null ? null : _client.capabilities().getDocumentOnTypeFormattingProvider();
        if (provider == null) {
            status(_client.displayName() + " on-type formatting is not supported");
            return;
        }
        Snapshot snapshot = Snapshot.capture(context);
        if (snapshot == null || snapshot.cursor() <= 0) {
            status("No trigger character before cursor");
            return;
        }
        String trigger = context.getBuffer().getCharacter(snapshot.cursor() - 1);
        if (trigger.isEmpty() || !onTypeTriggerCharacters(provider).contains(trigger)) {
            status("Character before cursor does not trigger on-type formatting");
            return;
        }
        requestEdit("format on type", context,
                current -> _client.server().getTextDocumentService()
                        .onTypeFormatting(new DocumentOnTypeFormattingParams(current.textDocument(),
                                formattingOptions(current.context()), current.position(), trigger))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    public void promptRename(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getRenameProvider())) {
            status(_client.displayName() + " rename is not supported");
            return;
        }
        String initial = context == null || context.getBuffer() == null ? "" : context.getBuffer().getInnerWord();
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.showInputPrompt("Rename Symbol", "new name", initial, value -> rename(context, value));
    }

    public void rename(BufferContext context, String newName) {
        if (newName == null || newName.isBlank()) {
            status("Rename requires a new name");
            return;
        }
        request("rename", context,
                snapshot -> {
                    prepareRenameIfSupported(snapshot);
                    return _client.server().getTextDocumentService()
                            .rename(new RenameParams(snapshot.textDocument(), snapshot.position(), newName.strip()))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                },
                (snapshot, edit) -> applyWorkspaceEdit(snapshot, edit, "rename"));
    }

    public void showInlayHints(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getInlayHintProvider())) {
            status(_client.displayName() + " inlay hints are not supported");
            return;
        }
        request("inlay hints", context,
                snapshot -> _client.server().getTextDocumentService()
                        .inlayHint(new InlayHintParams(snapshot.textDocument(), wholeDocumentRange(snapshot.context())))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showInlayHints);
    }

    public void applyFoldingRanges(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getFoldingRangeProvider())) {
            status(_client.displayName() + " folding ranges are not supported");
            return;
        }
        request("folding ranges", context,
                snapshot -> _client.server().getTextDocumentService()
                        .foldingRange(new FoldingRangeRequestParams(snapshot.textDocument()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::applyFoldingRanges);
    }

    public void showSelectionRanges(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getSelectionRangeProvider())) {
            status(_client.displayName() + " selection ranges are not supported");
            return;
        }
        request("selection ranges", context,
                snapshot -> _client.server().getTextDocumentService()
                        .selectionRange(new SelectionRangeParams(snapshot.textDocument(), List.of(snapshot.position())))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showSelectionRanges);
    }

    public void showCallHierarchy(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getCallHierarchyProvider())) {
            status(_client.displayName() + " call hierarchy is not supported");
            return;
        }
        request("call hierarchy", context,
                snapshot -> {
                    var items = _client.server().getTextDocumentService()
                            .prepareCallHierarchy(new CallHierarchyPrepareParams(snapshot.textDocument(), snapshot.position()))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (items == null || items.isEmpty()) {
                        return List.<LspFeaturePopupView.Entry>of();
                    }
                    CallHierarchyItem item = items.getFirst();
                    var incoming = _client.server().getTextDocumentService()
                            .callHierarchyIncomingCalls(new CallHierarchyIncomingCallsParams(item))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    var outgoing = _client.server().getTextDocumentService()
                            .callHierarchyOutgoingCalls(new CallHierarchyOutgoingCallsParams(item))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return callHierarchyEntries(incoming, outgoing);
                },
                (snapshot, entries) -> showEntries(snapshot, "Call Hierarchy", entries, "No call hierarchy found"));
    }

    public void showTypeHierarchy(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getTypeHierarchyProvider())) {
            status(_client.displayName() + " type hierarchy is not supported");
            return;
        }
        request("type hierarchy", context,
                snapshot -> {
                    var items = _client.server().getTextDocumentService()
                            .prepareTypeHierarchy(new TypeHierarchyPrepareParams(snapshot.textDocument(), snapshot.position()))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (items == null || items.isEmpty()) {
                        return List.<LspFeaturePopupView.Entry>of();
                    }
                    TypeHierarchyItem item = items.getFirst();
                    var supertypes = _client.server().getTextDocumentService()
                            .typeHierarchySupertypes(new TypeHierarchySupertypesParams(item))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    var subtypes = _client.server().getTextDocumentService()
                            .typeHierarchySubtypes(new TypeHierarchySubtypesParams(item))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return typeHierarchyEntries(supertypes, subtypes);
                },
                (snapshot, entries) -> showEntries(snapshot, "Type Hierarchy", entries, "No type hierarchy found"));
    }

    public void showDocumentLinks(BufferContext context) {
        if (_client.capabilities() == null || _client.capabilities().getDocumentLinkProvider() == null) {
            status(_client.displayName() + " document links are not supported");
            return;
        }
        request("document links", context,
                snapshot -> resolveDocumentLinks(_client.server().getTextDocumentService()
                        .documentLink(new DocumentLinkParams(snapshot.textDocument()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)),
                this::showDocumentLinks);
    }

    public void showLinkedEditingRanges(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getLinkedEditingRangeProvider())) {
            status(_client.displayName() + " linked editing ranges are not supported");
            return;
        }
        request("linked editing ranges", context,
                snapshot -> _client.server().getTextDocumentService()
                        .linkedEditingRange(new LinkedEditingRangeParams(snapshot.textDocument(), snapshot.position()))
                        .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::showLinkedEditingRanges);
    }

    public void showColorPresentations(BufferContext context) {
        if (!supported(_client.capabilities() == null ? null : _client.capabilities().getColorProvider())) {
            status(_client.displayName() + " color presentations are not supported");
            return;
        }
        request("color presentations", context,
                snapshot -> {
                    var colors = _client.server().getTextDocumentService()
                            .documentColor(new DocumentColorParams(snapshot.textDocument()))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    ColorInformation color = colorAt(colors, snapshot.position());
                    if (color == null) {
                        return List.<ColorPresentation>of();
                    }
                    return _client.server().getTextDocumentService()
                            .colorPresentation(new ColorPresentationParams(snapshot.textDocument(), color.getColor(), color.getRange()))
                            .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                },
                this::showColorPresentations);
    }

    public static int applyWorkspaceEditToOpenBuffersAndFiles(BufferContext context, WorkspaceEdit workspaceEdit) {
        if (workspaceEdit == null) {
            return 0;
        }
        Map<String, List<TextEdit>> editsByUri = workspaceTextEdits(workspaceEdit);
        int applied = 0;
        for (var entry : editsByUri.entrySet()) {
            Path path = pathForUri(entry.getKey());
            if (path == null) {
                continue;
            }
            boolean appliedToOpenBuffer = false;
            for (BufferContext open : openContexts(context)) {
                if (bufferPathEquals(open, path)) {
                    applied += applyTextEdits(open, entry.getValue());
                    appliedToOpenBuffer = true;
                }
            }
            if (!appliedToOpenBuffer) {
                applied += applyTextEditsToFile(path, entry.getValue());
            }
        }
        return applied;
    }

    private <T> void request(
            String description,
            BufferContext context,
            ThrowingFunction<Snapshot, T> request,
            BiConsumer<Snapshot, T> apply) {
        if (!available(description)) {
            return;
        }
        Snapshot snapshot = Snapshot.capture(context);
        if (snapshot == null) {
            status("No active buffer");
            return;
        }
        _client.requestQueue().execute(description, () -> {
            try {
                _client.flushPendingDocumentChanges(snapshot.uri());
                T result = request.apply(snapshot);
                _client.runOnEventThread(() -> {
                    if (!snapshot.stillCurrent()) {
                        status("Discarded stale " + description + " result");
                        return;
                    }
                    apply.accept(snapshot, result);
                });
            } catch (Exception e) {
                _client.log().debug(_client.displayName() + " " + description + " failed", e);
                _client.runOnEventThread(() -> status(_client.displayName() + " " + description + " failed"));
            }
        });
    }

    private void requestEdit(
            String description,
            BufferContext context,
            ThrowingFunction<Snapshot, List<? extends TextEdit>> request) {
        request(description, context, request,
                (snapshot, edits) -> {
                    WorkspaceEdit edit = new WorkspaceEdit(Map.of(snapshot.uri(), edits == null ? List.of() : List.copyOf(edits)));
                    applyWorkspaceEdit(snapshot, edit, description);
                });
    }

    private void requestLocations(
            String title,
            String description,
            BufferContext context,
            ThrowingFunction<Snapshot, Either<List<? extends Location>, List<? extends LocationLink>>> request) {
        request(description, context, request,
                (snapshot, response) -> showLocationTargets(snapshot, title,
                        response == null ? List.of()
                                : response.isLeft()
                                        ? locationTargets(response.getLeft(), null)
                                        : locationTargets(null, response.getRight()),
                        true));
    }

    private boolean available(String feature) {
        if (!_client.isAvailable() || _client.server() == null) {
            status(_client.displayName() + " unavailable for " + feature);
            return false;
        }
        return true;
    }

    private void showSignatureHelp(Snapshot snapshot, SignatureHelp help) {
        if (help == null || help.getSignatures() == null || help.getSignatures().isEmpty()) {
            status("No signature help");
            return;
        }
        int activeSignature = help.getActiveSignature() == null ? 0 : help.getActiveSignature();
        int activeParameter = help.getActiveParameter() == null ? -1 : help.getActiveParameter();
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (int i = 0; i < help.getSignatures().size(); i++) {
            SignatureInformation signature = help.getSignatures().get(i);
            String detail = documentation(signature.getDocumentation());
            if (signature.getActiveParameter() != null) {
                activeParameter = signature.getActiveParameter();
            }
            String parameter = parameterLabel(signature, activeParameter);
            if (!parameter.isBlank()) {
                detail = detail.isBlank() ? "active parameter " + parameter : detail + "  active parameter " + parameter;
            }
            entries.add(new LspFeaturePopupView.Entry(
                    i == activeSignature ? "ACTIVE" : "SIG",
                    nullToBlank(signature.getLabel()),
                    detail,
                    i == activeSignature ? UiTheme.ACCENT_GREEN : UiTheme.ACCENT_BLUE,
                    null));
        }
        showEntries(snapshot, "Signature Help", entries, "No signature help");
    }

    private void showDocumentHighlights(Snapshot snapshot, List<? extends DocumentHighlight> highlights) {
        if (highlights == null || highlights.isEmpty()) {
            status("No document highlights");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var highlight : highlights) {
            if (highlight == null || highlight.getRange() == null) {
                continue;
            }
            Range range = highlight.getRange();
            entries.add(new LspFeaturePopupView.Entry(
                    highlightKind(highlight.getKind()),
                    rangeLabel(range) + "  " + sourceText(snapshot.context(), range),
                    "jump to " + rangeLabel(range),
                    highlightAccent(highlight.getKind()),
                    () -> jumpToRange(snapshot.context(), range)));
        }
        showEntries(snapshot, "Document Highlights", entries, "No document highlights");
    }

    private void showDocumentSymbols(Snapshot snapshot,
            List<Either<SymbolInformation, DocumentSymbol>> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            status("No document symbols");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var symbol : symbols) {
            if (symbol == null) {
                continue;
            }
            if (symbol.isLeft()) {
                SymbolInformation info = symbol.getLeft();
                Location location = info.getLocation();
                LocationTarget target = location == null ? null : locationTarget(location.getUri(), location.getRange().getStart());
                if (target == null) {
                    continue;
                }
                entries.add(symbolEntry(info.getKind(), info.getName(), info.getContainerName(), target, 0));
            } else {
                collectDocumentSymbolEntries(entries, snapshot.uri(), symbol.getRight(), 0);
            }
        }
        showEntries(snapshot, "Document Symbols", entries, "No document symbols");
    }

    private void showWorkspaceSymbols(Snapshot snapshot,
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> response) {
        if (response == null) {
            status("No workspace symbols");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        if (response.isLeft()) {
            for (var info : response.getLeft()) {
                if (info == null || info.getLocation() == null) {
                    continue;
                }
                var target = locationTarget(info.getLocation().getUri(), info.getLocation().getRange().getStart());
                if (target != null) {
                    entries.add(symbolEntry(info.getKind(), info.getName(), info.getContainerName(), target, 0));
                }
            }
        } else {
            for (var symbol : response.getRight()) {
                if (symbol == null || symbol.getLocation() == null) {
                    continue;
                }
                var target = workspaceSymbolTarget(symbol.getLocation());
                if (target != null) {
                    entries.add(symbolEntry(symbol.getKind(), symbol.getName(), symbol.getContainerName(), target, 0));
                }
            }
        }
        showEntries(snapshot, "Workspace Symbols", entries, "No workspace symbols");
    }

    private void showCodeActions(Snapshot snapshot, List<Either<Command, CodeAction>> actions) {
        if (actions == null || actions.isEmpty()) {
            status("No code actions");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var either : actions) {
            if (either == null) {
                continue;
            }
            if (either.isLeft()) {
                Command command = either.getLeft();
                entries.add(new LspFeaturePopupView.Entry("CMD", command.getTitle(), command.getCommand(),
                        UiTheme.ACCENT_GREEN, () -> _client.applyCommand(snapshot.context(), command)));
            } else {
                CodeAction action = either.getRight();
                entries.add(new LspFeaturePopupView.Entry("ACTION", action.getTitle(), nullToBlank(action.getKind()),
                        UiTheme.ACCENT_GOLD, () -> applyCodeAction(snapshot, action)));
            }
        }
        showEntries(snapshot, "Code Actions", entries, "No code actions");
    }

    private void showCodeLens(Snapshot snapshot, List<? extends CodeLens> lenses) {
        if (lenses == null || lenses.isEmpty()) {
            status("No code lens entries");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var lens : lenses) {
            Command command = lens == null ? null : lens.getCommand();
            Range range = lens == null ? null : lens.getRange();
            entries.add(new LspFeaturePopupView.Entry("LENS",
                    command == null ? rangeLabel(range) : command.getTitle(),
                    command == null ? "No command" : command.getCommand(),
                    command == null ? UiTheme.TEXT_SUBTLE : UiTheme.ACCENT_GREEN,
                    command == null ? null : () -> _client.applyCommand(snapshot.context(), command)));
        }
        showEntries(snapshot, "Code Lens", entries, "No code lens entries");
    }

    private void showInlayHints(Snapshot snapshot, List<InlayHint> hints) {
        if (hints == null || hints.isEmpty()) {
            status("No inlay hints");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var hint : hints) {
            if (hint == null || hint.getPosition() == null) {
                continue;
            }
            String label = inlayHintLabel(hint);
            entries.add(new LspFeaturePopupView.Entry("HINT",
                    positionLabel(hint.getPosition()) + "  " + label,
                    tooltip(hint.getTooltip()),
                    UiTheme.ACCENT_BLUE,
                    () -> jumpToPosition(snapshot.context(), hint.getPosition())));
        }
        showEntries(snapshot, "Inlay Hints", entries, "No inlay hints");
    }

    private void applyFoldingRanges(Snapshot snapshot, List<FoldingRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            status("No folding ranges");
            return;
        }
        int created = 0;
        for (var range : ranges) {
            if (range == null || range.getEndLine() <= range.getStartLine()) {
                continue;
            }
            int startColumn = range.getStartCharacter() == null ? 0 : range.getStartCharacter();
            int endColumn = range.getEndCharacter() == null
                    ? Integer.MAX_VALUE
                    : range.getEndCharacter();
            int start = snapshot.context().getBuffer().getPositionAtLineColumn(range.getStartLine(), startColumn);
            int end = snapshot.context().getBuffer().getPositionAtLineColumn(range.getEndLine(), endColumn);
            if (snapshot.context().getBuffer().createFold(start, end)) {
                created++;
            }
        }
        snapshot.context().getBufferView().setNeedsRedraw();
        status(created == 1 ? "Created 1 fold" : "Created " + created + " folds");
    }

    private void showSelectionRanges(Snapshot snapshot, List<SelectionRange> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            status("No selection ranges");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        SelectionRange current = ranges.getFirst();
        int depth = 0;
        while (current != null) {
            Range range = current.getRange();
            entries.add(new LspFeaturePopupView.Entry("SEL",
                    depth + "  " + rangeLabel(range) + "  " + sourceText(snapshot.context(), range),
                    "jump to selection range",
                    depth == 0 ? UiTheme.ACCENT_GREEN : UiTheme.ACCENT_BLUE,
                    () -> jumpToRange(snapshot.context(), range)));
            current = current.getParent();
            depth++;
        }
        showEntries(snapshot, "Selection Ranges", entries, "No selection ranges");
    }

    private void showDocumentLinks(Snapshot snapshot, List<DocumentLink> links) {
        if (links == null || links.isEmpty()) {
            status("No document links");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var link : links) {
            String target = link == null ? null : link.getTarget();
            Range range = link == null ? null : link.getRange();
            entries.add(new LspFeaturePopupView.Entry("LINK",
                    (target == null || target.isBlank() ? rangeLabel(range) : target),
                    rangeLabel(range),
                    target == null || target.isBlank() ? UiTheme.TEXT_SUBTLE : UiTheme.ACCENT_GREEN,
                    target == null || target.isBlank() ? null : () -> openUrl(target)));
        }
        showEntries(snapshot, "Document Links", entries, "No document links");
    }

    private void showLinkedEditingRanges(Snapshot snapshot, LinkedEditingRanges ranges) {
        if (ranges == null || ranges.getRanges() == null || ranges.getRanges().isEmpty()) {
            status("No linked editing ranges");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (Range range : ranges.getRanges()) {
            entries.add(new LspFeaturePopupView.Entry("LINKED",
                    rangeLabel(range) + "  " + sourceText(snapshot.context(), range),
                    ranges.getWordPattern() == null ? "" : "word pattern " + ranges.getWordPattern(),
                    UiTheme.ACCENT_BLUE,
                    () -> jumpToRange(snapshot.context(), range)));
        }
        showEntries(snapshot, "Linked Editing", entries, "No linked editing ranges");
    }

    private void showColorPresentations(Snapshot snapshot, List<ColorPresentation> presentations) {
        if (presentations == null || presentations.isEmpty()) {
            status("No color presentation at cursor");
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (var presentation : presentations) {
            if (presentation == null) {
                continue;
            }
            entries.add(new LspFeaturePopupView.Entry("COLOR",
                    presentation.getLabel(),
                    presentation.getTextEdit() == null ? "" : rangeLabel(presentation.getTextEdit().getRange()),
                    UiTheme.ACCENT_ORANGE,
                    () -> applyColorPresentation(snapshot, presentation)));
        }
        showEntries(snapshot, "Color Presentations", entries, "No color presentations");
    }

    private List<LspFeaturePopupView.Entry> callHierarchyEntries(
            List<CallHierarchyIncomingCall> incoming,
            List<CallHierarchyOutgoingCall> outgoing) {
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        if (incoming != null) {
            for (var call : incoming) {
                CallHierarchyItem item = call == null ? null : call.getFrom();
                addHierarchyEntry(entries, "IN", item, UiTheme.ACCENT_GREEN);
            }
        }
        if (outgoing != null) {
            for (var call : outgoing) {
                CallHierarchyItem item = call == null ? null : call.getTo();
                addHierarchyEntry(entries, "OUT", item, UiTheme.ACCENT_BLUE);
            }
        }
        return entries;
    }

    private List<LspFeaturePopupView.Entry> typeHierarchyEntries(
            List<TypeHierarchyItem> supertypes,
            List<TypeHierarchyItem> subtypes) {
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        if (supertypes != null) {
            for (var item : supertypes) {
                addHierarchyEntry(entries, "SUPER", item, UiTheme.ACCENT_GREEN);
            }
        }
        if (subtypes != null) {
            for (var item : subtypes) {
                addHierarchyEntry(entries, "SUB", item, UiTheme.ACCENT_BLUE);
            }
        }
        return entries;
    }

    private void addHierarchyEntry(
            List<LspFeaturePopupView.Entry> entries,
            String kind,
            Object item,
            TextColor accent) {
        if (item instanceof CallHierarchyItem call) {
            LocationTarget target = locationTarget(call.getUri(), call.getSelectionRange().getStart());
            if (target != null) {
                entries.add(new LspFeaturePopupView.Entry(kind,
                        call.getName(),
                        nullToBlank(call.getDetail()) + "  " + target.label(),
                        accent,
                        () -> jumpToTarget(target)));
            }
        } else if (item instanceof TypeHierarchyItem type) {
            LocationTarget target = locationTarget(type.getUri(), type.getSelectionRange().getStart());
            if (target != null) {
                entries.add(new LspFeaturePopupView.Entry(kind,
                        type.getName(),
                        nullToBlank(type.getDetail()) + "  " + target.label(),
                        accent,
                        () -> jumpToTarget(target)));
            }
        }
    }

    private void applyWorkspaceEdit(Snapshot snapshot, WorkspaceEdit edit, String description) {
        if (edit == null || !hasWorkspaceEditChanges(edit)) {
            status("No edits from " + description);
            return;
        }
        int count = applyWorkspaceEditToOpenBuffersAndFiles(snapshot.context(), edit);
        Window window = Window.getInstance();
        if (window != null && window.getRootView() != null) {
            window.getRootView().setNeedsRedraw();
            window.refreshChromeState();
        }
        status(count == 1 ? "Applied 1 edit from " + description : "Applied " + count + " edits from " + description);
    }

    private void applyCodeAction(Snapshot snapshot, CodeAction action) {
        if (action == null) {
            return;
        }
        _client.applyWorkspaceEdit(snapshot.context(), action.getEdit());
        _client.applyCommand(snapshot.context(), action.getCommand());
    }

    private void applyColorPresentation(Snapshot snapshot, ColorPresentation presentation) {
        if (presentation == null) {
            return;
        }
        var edits = new ArrayList<TextEdit>();
        if (presentation.getTextEdit() != null) {
            edits.add(presentation.getTextEdit());
        }
        if (presentation.getAdditionalTextEdits() != null) {
            edits.addAll(presentation.getAdditionalTextEdits());
        }
        if (edits.isEmpty()) {
            status("Color presentation has no edits");
            return;
        }
        _client.applyWorkspaceEdit(snapshot.context(), new WorkspaceEdit(Map.of(snapshot.uri(), edits)));
    }

    private void prepareRenameIfSupported(Snapshot snapshot) throws Exception {
        var provider = _client.capabilities() == null ? null : _client.capabilities().getRenameProvider();
        boolean prepare = provider != null && provider.isRight()
                && Boolean.TRUE.equals(provider.getRight().getPrepareProvider());
        if (!prepare) {
            return;
        }
        Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior> result = _client.server().getTextDocumentService()
                .prepareRename(new PrepareRenameParams(snapshot.textDocument(), snapshot.position()))
                .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (result == null) {
            throw new IllegalStateException("No symbol can be renamed at cursor");
        }
    }

    private CodeAction resolveCodeAction(CodeAction action) {
        if (action == null || hasWorkspaceEditChanges(action.getEdit()) || action.getCommand() != null) {
            return action;
        }
        try {
            CodeAction resolved = _client.server().getTextDocumentService()
                    .resolveCodeAction(action)
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return resolved == null ? action : resolved;
        } catch (Exception e) {
            _client.log().debug("Resolving code action failed", e);
            return action;
        }
    }

    private List<Either<Command, CodeAction>> resolveCodeActions(List<Either<Command, CodeAction>> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        var resolved = new ArrayList<Either<Command, CodeAction>>();
        for (var action : actions) {
            if (action == null || action.isLeft()) {
                resolved.add(action);
            } else {
                resolved.add(Either.forRight(resolveCodeAction(action.getRight())));
            }
        }
        return resolved;
    }

    private CodeLens resolveCodeLens(CodeLens lens) {
        if (lens == null || lens.getCommand() != null) {
            return lens;
        }
        try {
            CodeLens resolved = _client.server().getTextDocumentService()
                    .resolveCodeLens(lens)
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return resolved == null ? lens : resolved;
        } catch (Exception e) {
            _client.log().debug("Resolving code lens failed", e);
            return lens;
        }
    }

    private List<CodeLens> resolveCodeLenses(List<? extends CodeLens> lenses) {
        if (lenses == null || lenses.isEmpty()) {
            return List.of();
        }
        var resolved = new ArrayList<CodeLens>();
        for (var lens : lenses) {
            CodeLens value = resolveCodeLens(lens);
            if (value != null) {
                resolved.add(value);
            }
        }
        return resolved;
    }

    private DocumentLink resolveDocumentLink(DocumentLink link) {
        if (link == null || link.getTarget() != null) {
            return link;
        }
        var provider = _client.capabilities() == null ? null : _client.capabilities().getDocumentLinkProvider();
        if (provider == null || !Boolean.TRUE.equals(provider.getResolveProvider())) {
            return link;
        }
        try {
            DocumentLink resolved = _client.server().getTextDocumentService()
                    .documentLinkResolve(link)
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return resolved == null ? link : resolved;
        } catch (Exception e) {
            _client.log().debug("Resolving document link failed", e);
            return link;
        }
    }

    private List<DocumentLink> resolveDocumentLinks(List<DocumentLink> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        var resolved = new ArrayList<DocumentLink>();
        for (var link : links) {
            DocumentLink value = resolveDocumentLink(link);
            if (value != null) {
                resolved.add(value);
            }
        }
        return resolved;
    }

    private void showTextEntries(
            Snapshot snapshot,
            String title,
            String kind,
            List<String> lines,
            TextColor accent,
            String emptyMessage) {
        if (lines == null || lines.isEmpty()) {
            status(emptyMessage);
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (String line : firstLines(lines)) {
            if (!line.isBlank()) {
                entries.add(new LspFeaturePopupView.Entry(kind, line, "", accent, null));
            }
        }
        showEntries(snapshot, title, entries, emptyMessage);
    }

    private void showLocationTargets(Snapshot snapshot, String title, List<LocationTarget> targets, boolean jumpSingle) {
        if (targets == null || targets.isEmpty()) {
            status("No " + title.toLowerCase());
            return;
        }
        if (jumpSingle && targets.size() == 1) {
            jumpToTarget(targets.getFirst());
            return;
        }
        var entries = new ArrayList<LspFeaturePopupView.Entry>();
        for (LocationTarget target : dedupeTargets(targets)) {
            entries.add(new LspFeaturePopupView.Entry("LOC", target.label(), target.detail(), UiTheme.ACCENT_BLUE,
                    () -> jumpToTarget(target)));
        }
        showEntries(snapshot, title, entries, "No " + title.toLowerCase());
    }

    private void showEntries(
            Snapshot snapshot,
            String title,
            List<LspFeaturePopupView.Entry> entries,
            String emptyMessage) {
        if (entries == null || entries.isEmpty()) {
            status(emptyMessage);
            return;
        }
        Window window = Window.getInstance();
        if (window == null) {
            return;
        }
        window.showLspFeaturePopup(title, entries, anchor(snapshot));
    }

    private List<LocationTarget> locationTargets(
            List<? extends Location> locations,
            List<? extends LocationLink> links) {
        var targets = new ArrayList<LocationTarget>();
        if (locations != null) {
            for (var location : locations) {
                if (location == null || location.getRange() == null) {
                    continue;
                }
                LocationTarget target = locationTarget(location.getUri(), location.getRange().getStart());
                if (target != null) {
                    targets.add(target);
                }
            }
        }
        if (links != null) {
            for (var link : links) {
                if (link == null) {
                    continue;
                }
                Range range = link.getTargetSelectionRange() != null ? link.getTargetSelectionRange() : link.getTargetRange();
                if (range == null) {
                    continue;
                }
                LocationTarget target = locationTarget(link.getTargetUri(), range.getStart());
                if (target != null) {
                    targets.add(target);
                }
            }
        }
        return dedupeTargets(targets);
    }

    private LocationTarget locationTarget(String uri, Position position) {
        if (uri == null || position == null) {
            return null;
        }
        Path path = pathForUri(uri);
        if (path == null) {
            return null;
        }
        String location = _client.displayPath(path) + ":" + (position.getLine() + 1) + ":" + (position.getCharacter() + 1);
        String preview = previewText(path, position.getLine());
        String label = preview.isBlank() ? location : location + "  " + preview;
        return new LocationTarget(path, position, label, path.toString());
    }

    private LocationTarget workspaceSymbolTarget(Either<Location, WorkspaceSymbolLocation> location) {
        if (location == null) {
            return null;
        }
        if (location.isLeft()) {
            Location left = location.getLeft();
            if (left == null || left.getRange() == null) {
                return null;
            }
            return locationTarget(left.getUri(), left.getRange().getStart());
        }
        WorkspaceSymbolLocation right = location.getRight();
        return right == null ? null : locationTarget(right.getUri(), new Position(0, 0));
    }

    private LspFeaturePopupView.Entry symbolEntry(
            SymbolKind kind,
            String name,
            String container,
            LocationTarget target,
            int depth) {
        String indent = depth <= 0 ? "" : "  ".repeat(Math.min(depth, 8));
        String label = indent + nullToBlank(name);
        String detail = (container == null || container.isBlank() ? "" : container + "  ") + target.label();
        return new LspFeaturePopupView.Entry(symbolKind(kind), label, detail, symbolAccent(kind), () -> jumpToTarget(target));
    }

    private void collectDocumentSymbolEntries(
            List<LspFeaturePopupView.Entry> entries,
            String uri,
            DocumentSymbol symbol,
            int depth) {
        if (symbol == null || symbol.getSelectionRange() == null) {
            return;
        }
        LocationTarget target = locationTarget(uri, symbol.getSelectionRange().getStart());
        if (target != null) {
            entries.add(symbolEntry(symbol.getKind(), symbol.getName(), symbol.getDetail(), target, depth));
        }
        if (symbol.getChildren() != null) {
            for (var child : symbol.getChildren()) {
                collectDocumentSymbolEntries(entries, uri, child, depth + 1);
            }
        }
    }

    private void jumpToTarget(LocationTarget target) {
        Window window = Window.getInstance();
        if (window == null || target == null) {
            return;
        }
        window.performJump(() -> window.openBufferLocation(
                target.path(),
                target.position().getLine() + 1,
                target.position().getCharacter() + 1));
    }

    private void jumpToRange(BufferContext context, Range range) {
        if (context == null || range == null) {
            return;
        }
        jumpToPosition(context, range.getStart());
    }

    private void jumpToPosition(BufferContext context, Position position) {
        if (context == null || position == null) {
            return;
        }
        int index = indexAt(context, position);
        context.getBuffer().getCursor().setPosition(index);
        context.getBufferView().adaptViewToCursor();
        context.getBufferView().setNeedsRedraw();
    }

    private void openUrl(String target) {
        Window window = Window.getInstance();
        if (window == null || target == null || target.isBlank()) {
            return;
        }
        if (!window.openExternalUrl(target)) {
            status("Unable to open " + target);
        }
    }

    private void status(String message) {
        Runnable update = () -> {
            Window window = Window.getInstance();
            if (window != null && window.getCommandView() != null) {
                window.getCommandView().setMessage(message);
            }
        };
        try {
            org.fisk.swim.EventThread eventThread = org.fisk.swim.EventThread.getInstance();
            if (eventThread.isAlive()) {
                eventThread.enqueue(new RunnableEvent(update));
            } else {
                update.run();
            }
        } catch (RuntimeException e) {
            update.run();
        }
    }

    private static Map<String, List<TextEdit>> workspaceTextEdits(WorkspaceEdit workspaceEdit) {
        var editsByUri = new LinkedHashMap<String, List<TextEdit>>();
        if (workspaceEdit.getChanges() != null) {
            for (var entry : workspaceEdit.getChanges().entrySet()) {
                editsByUri.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).addAll(entry.getValue());
            }
        }
        if (workspaceEdit.getDocumentChanges() != null) {
            for (var change : workspaceEdit.getDocumentChanges()) {
                if (!change.isLeft()) {
                    continue;
                }
                TextDocumentEdit documentEdit = change.getLeft();
                if (documentEdit == null || documentEdit.getTextDocument() == null) {
                    continue;
                }
                var edits = editsByUri.computeIfAbsent(documentEdit.getTextDocument().getUri(), ignored -> new ArrayList<>());
                for (var edit : documentEdit.getEdits()) {
                    edits.add(new TextEdit(edit.getRange(), edit.getNewText()));
                }
            }
        }
        return editsByUri;
    }

    private static List<BufferContext> openContexts(BufferContext fallback) {
        Window window = Window.getInstance();
        if (window != null) {
            return window.openBufferContextsSnapshot();
        }
        return fallback == null ? List.of() : List.of(fallback);
    }

    private static boolean bufferPathEquals(BufferContext context, Path path) {
        return context != null
                && context.getBuffer() != null
                && context.getBuffer().getPath() != null
                && context.getBuffer().getPath().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize());
    }

    private static int applyTextEdits(BufferContext context, List<TextEdit> edits) {
        if (context == null || edits == null || edits.isEmpty()) {
            return 0;
        }
        var indexed = new ArrayList<IndexedEdit>();
        for (var edit : edits) {
            if (edit == null || edit.getRange() == null) {
                continue;
            }
            indexed.add(new IndexedEdit(
                    indexAt(context, edit.getRange().getStart()),
                    indexAt(context, edit.getRange().getEnd()),
                    edit.getNewText() == null ? "" : edit.getNewText()));
        }
        indexed.sort(Comparator.comparingInt(IndexedEdit::start).reversed()
                .thenComparing(Comparator.comparingInt(IndexedEdit::end).reversed()));
        for (var edit : indexed) {
            context.getBuffer().remove(edit.start(), edit.end());
            context.getBuffer().insert(edit.start(), edit.text().replace("\t", "    "));
        }
        if (!indexed.isEmpty()) {
            context.getBuffer().commitUndo();
            context.getTextLayout().calculate();
            context.getBufferView().adaptViewToCursor();
            context.getBufferView().setNeedsRedraw();
        }
        return indexed.size();
    }

    private static int applyTextEditsToFile(Path path, List<TextEdit> edits) {
        if (path == null || edits == null || edits.isEmpty() || !Files.isRegularFile(path)) {
            return 0;
        }
        try {
            String text = Files.readString(path);
            String updated = applyTextEdits(text, edits);
            if (!text.equals(updated)) {
                Files.writeString(path, updated);
            }
            return edits.size();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String applyTextEdits(String text, List<TextEdit> edits) {
        var indexed = new ArrayList<IndexedEdit>();
        for (var edit : edits) {
            if (edit == null || edit.getRange() == null) {
                continue;
            }
            indexed.add(new IndexedEdit(
                    indexAt(text, edit.getRange().getStart()),
                    indexAt(text, edit.getRange().getEnd()),
                    edit.getNewText() == null ? "" : edit.getNewText()));
        }
        indexed.sort(Comparator.comparingInt(IndexedEdit::start).reversed()
                .thenComparing(Comparator.comparingInt(IndexedEdit::end).reversed()));
        StringBuilder builder = new StringBuilder(text);
        for (var edit : indexed) {
            builder.replace(edit.start(), edit.end(), edit.text());
        }
        return builder.toString();
    }

    private static int indexAt(BufferContext context, Position position) {
        return context.getTextLayout().getIndexForPhysicalLineCharacter(position.getLine(), position.getCharacter());
    }

    private static int indexAt(String text, Position position) {
        if (position == null) {
            return 0;
        }
        int line = Math.max(0, position.getLine());
        int character = Math.max(0, position.getCharacter());
        int index = 0;
        int currentLine = 0;
        while (index < text.length() && currentLine < line) {
            if (text.charAt(index++) == '\n') {
                currentLine++;
            }
        }
        int lineEnd = index;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        return Math.max(index, Math.min(index + character, lineEnd));
    }

    private static Position positionAt(BufferContext context, int index) {
        var line = context.getTextLayout().getPhysicalLineAt(index);
        return new Position(line.getY(), index - line.getStartPosition());
    }

    private static Range currentLineRange(BufferContext context) {
        int line = context.getBuffer().getCursor().getPhysicalLine().getY();
        int start = context.getBuffer().getLineStartByIndex(line);
        int end = context.getBuffer().getLineEndByIndex(line, false);
        return new Range(new Position(line, 0), new Position(line, Math.max(0, end - start)));
    }

    private static Range wholeDocumentRange(BufferContext context) {
        int lastLine = Math.max(0, context.getBuffer().getLineCount() - 1);
        int lastStart = context.getBuffer().getLineStartByIndex(lastLine);
        int lastEnd = context.getBuffer().getLineEndByIndex(lastLine, false);
        return new Range(new Position(0, 0), new Position(lastLine, Math.max(0, lastEnd - lastStart)));
    }

    private static FormattingOptions formattingOptions(BufferContext context) {
        String indentation = context == null || context.getBuffer() == null
                ? Settings.getIndentationString()
                : context.getBuffer().getLanguageMode().getIndentationString(context);
        boolean insertSpaces = !indentation.contains("\t");
        int tabSize = insertSpaces ? Math.max(1, indentation.length()) : 4;
        return new FormattingOptions(tabSize, insertSpaces);
    }

    private static boolean supported(Object provider) {
        if (provider == null) {
            return false;
        }
        if (provider instanceof Boolean value) {
            return value;
        }
        if (provider instanceof Either<?, ?> either) {
            if (either.isLeft() && either.getLeft() instanceof Boolean value) {
                return value;
            }
            return either.isRight() && either.getRight() != null;
        }
        return true;
    }

    private static boolean hasWorkspaceEditChanges(WorkspaceEdit workspaceEdit) {
        return workspaceEdit != null
                && (workspaceEdit.getChanges() != null && !workspaceEdit.getChanges().isEmpty()
                        || workspaceEdit.getDocumentChanges() != null && !workspaceEdit.getDocumentChanges().isEmpty());
    }

    private static List<String> onTypeTriggerCharacters(DocumentOnTypeFormattingOptions options) {
        var triggers = new ArrayList<String>();
        if (options.getFirstTriggerCharacter() != null) {
            triggers.add(options.getFirstTriggerCharacter());
        }
        if (options.getMoreTriggerCharacter() != null) {
            triggers.addAll(options.getMoreTriggerCharacter());
        }
        return triggers;
    }

    private static List<String> hoverLines(Hover hover) {
        if (hover == null || hover.getContents() == null) {
            return List.of();
        }
        if (hover.getContents().isRight()) {
            MarkupContent markup = hover.getContents().getRight();
            return splitContent(markup == null ? "" : markup.getValue());
        }
        var lines = new ArrayList<String>();
        for (Either<String, MarkedString> value : hover.getContents().getLeft()) {
            if (value == null) {
                continue;
            }
            if (value.isLeft()) {
                lines.addAll(splitContent(value.getLeft()));
            } else {
                MarkedString marked = value.getRight();
                if (marked != null) {
                    if (marked.getLanguage() != null && !marked.getLanguage().isBlank()) {
                        lines.add("[" + marked.getLanguage() + "]");
                    }
                    lines.addAll(splitContent(marked.getValue()));
                }
            }
        }
        return lines;
    }

    private static List<String> splitContent(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        var lines = new ArrayList<String>();
        for (String line : value.replace("\r\n", "\n").split("\n", -1)) {
            String cleaned = line.strip();
            if (!cleaned.isBlank()) {
                lines.add(cleaned);
            }
        }
        return lines;
    }

    private static List<String> firstLines(List<String> lines) {
        if (lines == null || lines.size() <= MAX_POPUP_TEXT_LINES) {
            return lines == null ? List.of() : lines;
        }
        var result = new ArrayList<String>(lines.subList(0, MAX_POPUP_TEXT_LINES));
        result.add("...");
        return result;
    }

    private static String documentation(Either<String, MarkupContent> documentation) {
        if (documentation == null) {
            return "";
        }
        return documentation.isLeft() ? nullToBlank(documentation.getLeft())
                : documentation.getRight() == null ? "" : nullToBlank(documentation.getRight().getValue());
    }

    private static String tooltip(Either<String, MarkupContent> tooltip) {
        return documentation(tooltip);
    }

    private static String parameterLabel(SignatureInformation signature, int activeParameter) {
        List<ParameterInformation> parameters = signature == null ? null : signature.getParameters();
        if (parameters == null || activeParameter < 0 || activeParameter >= parameters.size()) {
            return "";
        }
        var label = parameters.get(activeParameter).getLabel();
        if (label == null) {
            return "";
        }
        if (label.isLeft()) {
            return nullToBlank(label.getLeft());
        }
        return label.getRight() == null ? "" : label.getRight().getFirst() + ":" + label.getRight().getSecond();
    }

    private static String inlayHintLabel(InlayHint hint) {
        if (hint == null || hint.getLabel() == null) {
            return "";
        }
        if (hint.getLabel().isLeft()) {
            return nullToBlank(hint.getLabel().getLeft());
        }
        var builder = new StringBuilder();
        List<InlayHintLabelPart> parts = hint.getLabel().getRight();
        if (parts != null) {
            for (var part : parts) {
                if (part != null && part.getValue() != null) {
                    builder.append(part.getValue());
                }
            }
        }
        return builder.toString();
    }

    private static ColorInformation colorAt(List<ColorInformation> colors, Position position) {
        if (colors == null || position == null) {
            return null;
        }
        for (var color : colors) {
            if (color != null && contains(color.getRange(), position)) {
                return color;
            }
        }
        return null;
    }

    private static boolean contains(Range range, Position position) {
        if (range == null || position == null) {
            return false;
        }
        return compare(position, range.getStart()) >= 0 && compare(position, range.getEnd()) <= 0;
    }

    private static int compare(Position left, Position right) {
        int line = Integer.compare(left.getLine(), right.getLine());
        return line != 0 ? line : Integer.compare(left.getCharacter(), right.getCharacter());
    }

    private static String highlightKind(DocumentHighlightKind kind) {
        if (DocumentHighlightKind.Write.equals(kind)) {
            return "WRITE";
        }
        if (DocumentHighlightKind.Read.equals(kind)) {
            return "READ";
        }
        return "TEXT";
    }

    private static TextColor highlightAccent(DocumentHighlightKind kind) {
        if (DocumentHighlightKind.Write.equals(kind)) {
            return UiTheme.ACCENT_ORANGE;
        }
        if (DocumentHighlightKind.Read.equals(kind)) {
            return UiTheme.ACCENT_GREEN;
        }
        return UiTheme.ACCENT_BLUE;
    }

    private static String symbolKind(SymbolKind kind) {
        if (kind == null) {
            return "SYM";
        }
        return switch (kind) {
        case Class -> "CLASS";
        case Interface -> "IFACE";
        case Method -> "METHOD";
        case Function -> "FUNC";
        case Field -> "FIELD";
        case Variable -> "VAR";
        case Constructor -> "CTOR";
        case Enum -> "ENUM";
        case Struct -> "STRUCT";
        case Namespace -> "NS";
        case Package -> "PKG";
        default -> kind.name().toUpperCase(java.util.Locale.ROOT);
        };
    }

    private static TextColor symbolAccent(SymbolKind kind) {
        if (kind == null) {
            return UiTheme.ACCENT_BLUE;
        }
        return switch (kind) {
        case Class, Interface, Enum, Struct, TypeParameter -> UiTheme.ACCENT_GREEN;
        case Method, Function, Constructor -> UiTheme.ACCENT_BLUE;
        case Field, Property, Variable, Constant, EnumMember -> UiTheme.ACCENT_GOLD;
        default -> UiTheme.ACCENT_ORANGE;
        };
    }

    private static String rangeLabel(Range range) {
        if (range == null || range.getStart() == null) {
            return "";
        }
        return positionLabel(range.getStart()) + (range.getEnd() == null ? "" : "-" + positionLabel(range.getEnd()));
    }

    private static String positionLabel(Position position) {
        if (position == null) {
            return "";
        }
        return (position.getLine() + 1) + ":" + (position.getCharacter() + 1);
    }

    private static String sourceText(BufferContext context, Range range) {
        if (context == null || range == null) {
            return "";
        }
        int start = indexAt(context, range.getStart());
        int end = indexAt(context, range.getEnd());
        if (end <= start) {
            return "";
        }
        return context.getBuffer().getSubstring(start, Math.min(end, start + 120)).strip().replaceAll("\\s+", " ");
    }

    private static String previewText(Path path, int lineIndex) {
        try {
            var lines = Files.readAllLines(path);
            if (lineIndex < 0 || lineIndex >= lines.size()) {
                return "";
            }
            return lines.get(lineIndex).trim().replaceAll("\\s+", " ");
        } catch (Exception e) {
            return "";
        }
    }

    private static List<LocationTarget> dedupeTargets(List<LocationTarget> targets) {
        var deduped = new LinkedHashMap<String, LocationTarget>();
        for (var target : targets) {
            if (target == null) {
                continue;
            }
            String key = target.path().toAbsolutePath().normalize()
                    + ":" + target.position().getLine()
                    + ":" + target.position().getCharacter();
            deduped.putIfAbsent(key, target);
        }
        return List.copyOf(deduped.values());
    }

    private static Point anchor(Snapshot snapshot) {
        if (snapshot == null || snapshot.context() == null || snapshot.context().getBuffer() == null) {
            return Point.create(0, 0);
        }
        var cursor = snapshot.context().getBuffer().getCursor();
        return Point.create(cursor.getXOnScreen(), cursor.getYOnScreen());
    }

    private static Path pathForUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return null;
        }
        try {
            return Paths.get(URI.create(uri)).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
