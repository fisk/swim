package org.fisk.swim.treesitter;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.fisk.swim.api.SwimPluginWorkers;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Buffer;
import org.fisk.swim.ui.UiTheme;

/** Concurrent buffer bridge for grammar-derived keyword fallback highlighting. */
public final class TreeSitterGrammarKeywordBridge implements AutoCloseable {
    public interface Registration extends AutoCloseable {
        @Override void close();
    }

    private final SwimPluginWorkers _workers;
    private final Consumer<Runnable> _uiDispatcher;
    private final TreeSitterGrammarKeywordHighlighter _highlighter;
    private final AtomicLong _sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> _latest = new ConcurrentHashMap<>();
    private final AtomicBoolean _closed = new AtomicBoolean();

    public TreeSitterGrammarKeywordBridge(SwimPluginWorkers workers, Consumer<Runnable> uiDispatcher,
            TreeSitterGrammar grammar) {
        _workers = workers == null ? SwimPluginWorkers.unmanaged() : workers;
        _uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        _highlighter = new TreeSitterGrammarKeywordHighlighter(Objects.requireNonNull(grammar, "grammar"));
    }

    public Registration attach(Buffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        Buffer.ContentChangeListener listener = this::update;
        buffer.addContentChangeListener(listener);
        update(buffer);
        return () -> buffer.removeContentChangeListener(listener);
    }

    private void update(Buffer buffer) {
        if (_closed.get() || _workers.isClosed()) return;
        var snapshot = new TreeSitterGrammarSnapshot(buffer.getPath(), buffer.getVersion(), buffer.getString());
        String documentKey = buffer.getURI().toString();
        long request = _sequence.incrementAndGet();
        _latest.put(documentKey, request);
        _workers.start(() -> {
            List<TreeSitterSyntaxSpan> spans = _highlighter.highlight(snapshot.text());
            if (_closed.get() || !Long.valueOf(request).equals(_latest.get(documentKey))) return;
            var ranges = spans.stream().map(span -> new AttributedString.FormatRange(span.start(), span.end(),
                    UiTheme.SEMANTIC_KEYWORD, null)).toList();
            _uiDispatcher.accept(() -> buffer.setSyntaxFormatOverlays((int) snapshot.version(), ranges));
        });
    }

    @Override
    public void close() {
        _closed.set(true);
        _latest.clear();
    }
}
