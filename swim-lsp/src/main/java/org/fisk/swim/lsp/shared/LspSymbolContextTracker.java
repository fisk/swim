package org.fisk.swim.lsp.shared;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.fisk.swim.lsp.LspContextService;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.ui.Window;

final class LspSymbolContextTracker {
    private static final long REQUEST_TIMEOUT_SECONDS = 5;
    private static final long REFRESH_DELAY_MILLIS = 250;
    private static final Set<SymbolKind> TYPE_KINDS = Set.of(
            SymbolKind.Class,
            SymbolKind.Interface,
            SymbolKind.Struct,
            SymbolKind.Enum,
            SymbolKind.Object);
    private static final Set<SymbolKind> CALLABLE_KINDS = Set.of(
            SymbolKind.Method,
            SymbolKind.Function,
            SymbolKind.Constructor);

    private final LspFeatureSupport.Client _client;
    private final Map<String, RefreshState> _refreshesByUri = new HashMap<>();

    LspSymbolContextTracker(LspFeatureSupport.Client client) {
        _client = client;
    }

    void refresh(BufferContext context) {
        if (!supportsDocumentSymbols() || context == null || context.getBuffer() == null) {
            return;
        }
        RefreshSnapshot snapshot = RefreshSnapshot.capture(context);
        if (snapshot == null) {
            return;
        }
        synchronized (this) {
            var existing = _refreshesByUri.get(snapshot.uri());
            if (existing != null) {
                existing._latestContext = context;
                existing._latestVersion = snapshot.version();
                existing._reschedule = true;
                return;
            }
            _refreshesByUri.put(snapshot.uri(), new RefreshState(snapshot));
        }
        _client.requestQueue().schedule(
                "document symbol context",
                () -> refresh(snapshot),
                REFRESH_DELAY_MILLIS);
    }

    void clear(BufferContext context) {
        if (context == null || context.getBuffer() == null) {
            return;
        }
        String uri = context.getBuffer().getURI().toString();
        synchronized (this) {
            _refreshesByUri.remove(uri);
        }
        LspContextService.getInstance().clear(providerId(), uri);
        requestModeLineRedraw();
    }

    void clearAll() {
        synchronized (this) {
            _refreshesByUri.clear();
        }
        LspContextService.getInstance().clearProvider(providerId());
        requestModeLineRedraw();
    }

    private void refresh(RefreshSnapshot snapshot) {
        boolean reschedule = false;
        BufferContext latestContext = null;
        try {
            synchronized (this) {
                var current = _refreshesByUri.get(snapshot.uri());
                if (current == null || current._snapshot != snapshot) {
                    return;
                }
            }
            _client.flushPendingDocumentChanges(snapshot.uri());
            var symbols = _client.server().getTextDocumentService()
                    .documentSymbol(new DocumentSymbolParams(snapshot.textDocument()))
                    .get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            var scopes = scopesFor(symbols);
            LspContextService.getInstance().publish(
                    providerId(),
                    snapshot.uri(),
                    snapshot.path(),
                    snapshot.version(),
                    scopes);
            requestModeLineRedraw();
        } catch (Exception e) {
            _client.log().debug("LSP document symbol context refresh failed", e);
        } finally {
            synchronized (this) {
                var current = _refreshesByUri.get(snapshot.uri());
                if (current != null && current._snapshot == snapshot) {
                    reschedule = current._reschedule && current._latestVersion != snapshot.version();
                    latestContext = current._latestContext;
                    _refreshesByUri.remove(snapshot.uri());
                }
            }
            if (reschedule && latestContext != null) {
                refresh(latestContext);
            }
        }
    }

    private boolean supportsDocumentSymbols() {
        return _client.isAvailable()
                && _client.capabilities() != null
                && LspFeatureSupport.supported(_client.capabilities().getDocumentSymbolProvider());
    }

    private String providerId() {
        String displayName = _client.displayName();
        return displayName == null || displayName.isBlank() ? "lsp" : displayName;
    }

    private static void requestModeLineRedraw() {
        Window window = Window.getInstance();
        if (window != null && window.getRootView() != null) {
            window.getRootView().setNeedsRedraw();
        }
    }

    static List<LspContextService.Scope> scopesFor(List<Either<SymbolInformation, DocumentSymbol>> symbols) {
        if (symbols == null || symbols.isEmpty()) {
            return List.of();
        }
        var scopes = new ArrayList<LspContextService.Scope>();
        for (var symbol : symbols) {
            if (symbol == null) {
                continue;
            }
            if (symbol.isLeft()) {
                collectSymbolInformationScope(scopes, symbol.getLeft());
            } else {
                collectDocumentSymbolScopes(scopes, symbol.getRight(), List.of());
            }
        }
        return List.copyOf(scopes);
    }

    private static void collectDocumentSymbolScopes(
            List<LspContextService.Scope> scopes,
            DocumentSymbol symbol,
            List<String> typePath) {
        if (symbol == null) {
            return;
        }
        String name = shortName(symbol.getName());
        List<String> nextTypePath = typePath;
        String label = labelFor(symbol.getKind(), name, typePath);
        if (!label.isBlank()) {
            addScope(scopes, label, symbol.getRange());
        }
        if (isTypeKind(symbol.getKind()) && !name.isBlank()) {
            nextTypePath = append(typePath, name);
        }
        if (symbol.getChildren() != null) {
            for (var child : symbol.getChildren()) {
                collectDocumentSymbolScopes(scopes, child, nextTypePath);
            }
        }
    }

    private static void collectSymbolInformationScope(
            List<LspContextService.Scope> scopes,
            SymbolInformation symbol) {
        if (symbol == null || symbol.getLocation() == null) {
            return;
        }
        String name = shortName(symbol.getName());
        String container = shortContainerName(symbol.getContainerName());
        List<String> containers = container.isBlank() ? List.of() : List.of(container);
        String label = labelFor(symbol.getKind(), name, containers);
        if (!label.isBlank()) {
            addScope(scopes, label, symbol.getLocation().getRange());
        }
    }

    private static void addScope(List<LspContextService.Scope> scopes, String label, Range range) {
        if (label == null || label.isBlank() || range == null || range.getStart() == null || range.getEnd() == null) {
            return;
        }
        Position start = range.getStart();
        Position end = range.getEnd();
        scopes.add(new LspContextService.Scope(
                label,
                start.getLine(),
                start.getCharacter(),
                end.getLine(),
                end.getCharacter()));
    }

    private static String labelFor(SymbolKind kind, String name, List<String> typePath) {
        if (name == null || name.isBlank()) {
            return "";
        }
        if (isCallableKind(kind)) {
            var parts = new ArrayList<String>(typePath);
            parts.add(name);
            return String.join(".", parts);
        }
        if (isTypeKind(kind)) {
            var parts = new ArrayList<String>(typePath);
            parts.add(name);
            return String.join(".", parts);
        }
        return "";
    }

    private static boolean isTypeKind(SymbolKind kind) {
        return kind != null && TYPE_KINDS.contains(kind);
    }

    private static boolean isCallableKind(SymbolKind kind) {
        return kind != null && CALLABLE_KINDS.contains(kind);
    }

    private static List<String> append(List<String> values, String value) {
        var next = new ArrayList<String>(values);
        next.add(value);
        return List.copyOf(next);
    }

    private static String shortName(String name) {
        if (name == null) {
            return "";
        }
        name = name.trim();
        int parameters = name.indexOf('(');
        if (parameters > 0) {
            name = name.substring(0, parameters).trim();
        }
        return name;
    }

    private static String shortContainerName(String container) {
        if (container == null) {
            return "";
        }
        container = shortName(container);
        int cxx = container.lastIndexOf("::");
        if (cxx >= 0 && cxx + 2 < container.length()) {
            return container.substring(cxx + 2);
        }
        int dot = container.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < container.length()) {
            return container.substring(dot + 1);
        }
        return container;
    }

    private static final class RefreshState {
        private final RefreshSnapshot _snapshot;
        private BufferContext _latestContext;
        private int _latestVersion;
        private boolean _reschedule;

        private RefreshState(RefreshSnapshot snapshot) {
            _snapshot = snapshot;
            _latestContext = snapshot.context();
            _latestVersion = snapshot.version();
        }
    }

    private record RefreshSnapshot(
            BufferContext context,
            String uri,
            Path path,
            int version,
            org.eclipse.lsp4j.TextDocumentIdentifier textDocument) {
        private static RefreshSnapshot capture(BufferContext context) {
            if (context == null || context.getBuffer() == null) {
                return null;
            }
            return new RefreshSnapshot(
                    context,
                    context.getBuffer().getURI().toString(),
                    context.getBuffer().getPath(),
                    context.getBuffer().getVersionedTextDocumentID().getVersion(),
                    context.getBuffer().getTextDocumentID());
        }
    }
}
