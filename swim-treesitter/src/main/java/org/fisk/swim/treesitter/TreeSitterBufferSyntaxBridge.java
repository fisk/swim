package org.fisk.swim.treesitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.fisk.swim.EventThread;
import org.fisk.swim.api.SwimPluginWorkers;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Buffer;
import org.fisk.swim.ui.UiTheme;

import com.googlecode.lanterna.TextColor;

/**
 * Bridges a mutable editor buffer to immutable, concurrent Tree-sitter syntax snapshots.
 * Results return through the supplied UI dispatcher and are applied only if the buffer version
 * still matches, so a slow parse can never colour later text.
 */
public final class TreeSitterBufferSyntaxBridge implements AutoCloseable {
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final TreeSitterSyntaxAnalysisService _analysis;
    private final Consumer<Runnable> _uiDispatcher;
    private final TreeSitterSyntaxColourMapper _colourMapper;

    public TreeSitterBufferSyntaxBridge(SwimPluginWorkers workers, Consumer<Runnable> uiDispatcher,
            TreeSitterSyntaxColourMapper colourMapper) {
        _analysis = new TreeSitterSyntaxAnalysisService(workers);
        _uiDispatcher = Objects.requireNonNull(uiDispatcher, "uiDispatcher");
        _colourMapper = Objects.requireNonNull(colourMapper, "colourMapper");
    }

    /** Convenient dispatcher for a normal SWIM editor event thread. */
    public static Consumer<Runnable> onEventThread(EventThread eventThread) {
        Objects.requireNonNull(eventThread, "eventThread");
        return runnable -> eventThread.enqueue(new RunnableEvent(runnable));
    }

    /**
     * Captures the buffer on the caller's editor thread and parses that immutable revision off it.
     * Language modes should call this from didOpen/didInsert/didRemove and close this bridge at
     * plugin shutdown.
     */
    public long update(Buffer buffer, TreeSitterGrammar grammar, String startRule) {
        Objects.requireNonNull(buffer, "buffer");
        var snapshot = new TreeSitterSyntaxSnapshot(buffer.getPath(), buffer.getVersion(), buffer.getString(),
                Objects.requireNonNull(grammar, "grammar"), startRule);
        return _analysis.parse(buffer.getURI().toString(), snapshot, result -> {
            if (!result.succeeded()) return;
            List<AttributedString.FormatRange> overlays = overlays(result.tree());
            _uiDispatcher.accept(() -> buffer.setSyntaxFormatOverlays((int) result.snapshot().version(), overlays));
        });
    }

    /**
     * Keeps a buffer connected to parser snapshots until the returned registration is closed.
     * The grammar supplier is evaluated on the editor thread for every revision, allowing a
     * language plugin to swap grammar assets without retaining a stale one.
     */
    public Registration attach(Buffer buffer, Supplier<TreeSitterGrammar> grammar, Supplier<String> startRule) {
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(grammar, "grammar");
        Objects.requireNonNull(startRule, "startRule");
        Buffer.ContentChangeListener listener = changed -> update(changed,
                Objects.requireNonNull(grammar.get(), "grammar supplier returned null"), startRule.get());
        buffer.addContentChangeListener(listener);
        listener.contentChanged(buffer);
        return () -> buffer.removeContentChangeListener(listener);
    }

    private List<AttributedString.FormatRange> overlays(TreeSitterSyntaxTree tree) {
        String text = tree.snapshot().text();
        var overlays = new ArrayList<AttributedString.FormatRange>();
        for (TreeSitterSyntaxSpan span : tree.spans()) {
            int start = Math.min(span.start(), text.length());
            int end = Math.min(span.end(), text.length());
            if (end <= start) continue;
            TextColor colour = _colourMapper.colour(tree.snapshot(), span);
            if (colour != null) overlays.add(new AttributedString.FormatRange(start, end, colour, null));
        }
        return List.copyOf(overlays);
    }

    /**
     * A grammar-derived fallback mapper: identifier-like literal grammar tokens are keywords.
     * It intentionally has no maintained language keyword list; query captures can replace it.
     */
    public static TextColor grammarLiteralKeywordColour(TreeSitterSyntaxSnapshot snapshot, TreeSitterSyntaxSpan span) {
        if (!"STRING".equals(span.type()) || span.start() >= snapshot.text().length()) return null;
        char first = snapshot.text().charAt(span.start());
        return Character.isJavaIdentifierStart(first) ? UiTheme.SEMANTIC_KEYWORD : null;
    }

    @Override
    public void close() {
        _analysis.close();
    }
}
