package org.fisk.swim.lsp.shared;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.TextDocumentService;

public final class LspDocumentChangeBatcher {
    @FunctionalInterface
    public interface LocalChangeObserver {
        void didChange(String uri, Path path, TextDocumentContentChangeEvent change);
    }

    private final AsyncLspRequestQueue _requestQueue;
    private final Supplier<TextDocumentService> _textDocumentService;
    private final long _batchDelayMillis;
    private final LocalChangeObserver _localChangeObserver;
    private final Map<String, PendingDocumentChanges> _pendingDocumentChanges = new HashMap<>();

    public LspDocumentChangeBatcher(
            AsyncLspRequestQueue requestQueue,
            Supplier<TextDocumentService> textDocumentService,
            long batchDelayMillis) {
        this(requestQueue, textDocumentService, batchDelayMillis, null);
    }

    public LspDocumentChangeBatcher(
            AsyncLspRequestQueue requestQueue,
            Supplier<TextDocumentService> textDocumentService,
            long batchDelayMillis,
            LocalChangeObserver localChangeObserver) {
        _requestQueue = Objects.requireNonNull(requestQueue, "requestQueue");
        _textDocumentService = Objects.requireNonNull(textDocumentService, "textDocumentService");
        _batchDelayMillis = Math.max(0, batchDelayMillis);
        _localChangeObserver = localChangeObserver == null ? (uri, path, change) -> {} : localChangeObserver;
    }

    public void queue(
            String uri,
            VersionedTextDocumentIdentifier textDocument,
            List<TextDocumentContentChangeEvent> contentChanges) {
        queue(uri, null, textDocument, contentChanges);
    }

    public void queue(
            String uri,
            Path path,
            VersionedTextDocumentIdentifier textDocument,
            List<TextDocumentContentChangeEvent> contentChanges) {
        if (uri == null || textDocument == null || contentChanges == null || contentChanges.isEmpty()) {
            return;
        }
        for (var change : contentChanges) {
            _localChangeObserver.didChange(uri, path, change);
        }
        boolean scheduleFlush = false;
        synchronized (this) {
            var pending = _pendingDocumentChanges.computeIfAbsent(uri, ignored -> new PendingDocumentChanges());
            pending._textDocument = textDocument;
            pending._changes.addAll(contentChanges);
            if (!pending._flushScheduled) {
                pending._flushScheduled = true;
                scheduleFlush = true;
            }
        }
        if (scheduleFlush) {
            _requestQueue.schedule("didChange", () -> flush(uri), _batchDelayMillis);
        }
    }

    public void flush(String uri) {
        PendingDocumentChanges pending;
        synchronized (this) {
            pending = _pendingDocumentChanges.remove(uri);
        }
        if (pending == null || pending._changes.isEmpty()) {
            return;
        }
        TextDocumentService service = _textDocumentService.get();
        if (service == null) {
            return;
        }
        var params = new DidChangeTextDocumentParams();
        params.setTextDocument(pending._textDocument);
        params.setContentChanges(List.copyOf(pending._changes));
        service.didChange(params);
    }

    public synchronized void clear() {
        _pendingDocumentChanges.clear();
    }

    private static final class PendingDocumentChanges {
        private VersionedTextDocumentIdentifier _textDocument;
        private final List<TextDocumentContentChangeEvent> _changes = new ArrayList<>();
        private boolean _flushScheduled;
    }
}
