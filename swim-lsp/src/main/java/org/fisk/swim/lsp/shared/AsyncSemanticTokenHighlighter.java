package org.fisk.swim.lsp.shared;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.slf4j.Logger;

import com.googlecode.lanterna.TextColor;

public final class AsyncSemanticTokenHighlighter {
    public interface Document {
        String uri();

        int version();

        String text();

        void requestSemanticRedraw();
    }

    @FunctionalInterface
    public interface SemanticTokenColorMapper {
        TextColor colorFor(String tokenType, int modifiersBitset, List<String> tokenModifiers);
    }

    public record Snapshot(String uri, int version, String text) {
    }

    public record Highlight(int start, int end, TextColor foregroundColor) {
    }

    public record CachedSemanticTokens(int version, List<Highlight> highlights) {
    }

    private interface SemanticMutation {
        List<Highlight> apply(List<Highlight> highlights);
    }

    private record InsertSemanticMutation(int position, int length) implements SemanticMutation {
        @Override
        public List<Highlight> apply(List<Highlight> highlights) {
            return transformInsert(highlights, position, length);
        }
    }

    private record DeleteSemanticMutation(int start, int end) implements SemanticMutation {
        @Override
        public List<Highlight> apply(List<Highlight> highlights) {
            return transformDelete(highlights, start, end);
        }
    }

    private static final class SemanticRefresh {
        private final Snapshot _snapshot;
        private final List<SemanticMutation> _mutations = new ArrayList<>();
        private int _attempts;
        private boolean _reschedule;

        private SemanticRefresh(Snapshot snapshot) {
            _snapshot = snapshot;
        }
    }

    private final AsyncLspRequestQueue _requestQueue;
    private final Logger _log;
    private final String _requestDescription;
    private final BooleanSupplier _supportsSemanticTokens;
    private final Consumer<String> _flushDocumentChanges;
    private final Function<Snapshot, List<Highlight>> _fetchHighlights;
    private final long _retryDelayMillis;
    private final int _maxAttempts;
    private final Map<String, CachedSemanticTokens> _semanticTokensCache = new HashMap<>();
    private final Map<String, SemanticRefresh> _semanticRefreshes = new HashMap<>();

    public AsyncSemanticTokenHighlighter(
            AsyncLspRequestQueue requestQueue,
            Logger log,
            String requestDescription,
            BooleanSupplier supportsSemanticTokens,
            Consumer<String> flushDocumentChanges,
            Function<Snapshot, List<Highlight>> fetchHighlights,
            long retryDelayMillis,
            int maxAttempts) {
        _requestQueue = Objects.requireNonNull(requestQueue, "requestQueue");
        _log = Objects.requireNonNull(log, "log");
        _requestDescription = requestDescription == null || requestDescription.isBlank()
                ? "semantic token refresh"
                : requestDescription;
        _supportsSemanticTokens = supportsSemanticTokens == null ? () -> true : supportsSemanticTokens;
        _flushDocumentChanges = flushDocumentChanges == null ? ignored -> {} : flushDocumentChanges;
        _fetchHighlights = Objects.requireNonNull(fetchHighlights, "fetchHighlights");
        _retryDelayMillis = Math.max(0, retryDelayMillis);
        _maxAttempts = Math.max(1, maxAttempts);
    }

    public synchronized Map<String, CachedSemanticTokens> cacheView() {
        return _semanticTokensCache;
    }

    public synchronized void clear() {
        _semanticTokensCache.clear();
        _semanticRefreshes.clear();
    }

    public synchronized void clear(String uri) {
        if (uri == null) {
            return;
        }
        _semanticTokensCache.remove(uri);
        _semanticRefreshes.remove(uri);
    }

    public List<Highlight> getHighlights(Document document) {
        if (!_supportsSemanticTokens.getAsBoolean() || document == null || document.uri() == null) {
            return List.of();
        }
        String uri = document.uri();
        int version = document.version();
        List<Highlight> staleHighlights = List.of();
        synchronized (this) {
            var cached = _semanticTokensCache.get(uri);
            if (cached != null && cached.version() == version) {
                return cached.highlights();
            }
            if (cached != null) {
                staleHighlights = cached.highlights();
            }
        }
        scheduleRefresh(document);
        return staleHighlights;
    }

    public void scheduleRefresh(Document document) {
        if (!_supportsSemanticTokens.getAsBoolean() || document == null || document.uri() == null) {
            return;
        }
        String uri = document.uri();
        int version = document.version();
        synchronized (this) {
            var queuedRefresh = _semanticRefreshes.get(uri);
            if (queuedRefresh != null) {
                if (version > queuedRefresh._snapshot.version()) {
                    queuedRefresh._reschedule = true;
                }
                return;
            }
        }

        var snapshot = new Snapshot(uri, version, document.text());
        synchronized (this) {
            var queuedRefresh = _semanticRefreshes.get(uri);
            if (queuedRefresh != null) {
                if (version > queuedRefresh._snapshot.version()) {
                    queuedRefresh._reschedule = true;
                }
                return;
            }
            _semanticRefreshes.put(uri, new SemanticRefresh(snapshot));
        }
        enqueueRefresh(snapshot, document, 0);
    }

    public void recordInsert(Document document, int position, int length) {
        recordMutation(document, new InsertSemanticMutation(position, length));
    }

    public void recordDelete(Document document, int start, int end) {
        recordMutation(document, new DeleteSemanticMutation(start, end));
    }

    private void enqueueRefresh(Snapshot snapshot, Document document, long delayMillis) {
        _requestQueue.schedule(
                _requestDescription,
                () -> refresh(snapshot, document),
                delayMillis);
    }

    private void refresh(Snapshot snapshot, Document document) {
        boolean reschedule = false;
        if (!_supportsSemanticTokens.getAsBoolean()) {
            return;
        }
        synchronized (this) {
            var queuedRefresh = _semanticRefreshes.get(snapshot.uri());
            if (queuedRefresh == null || queuedRefresh._snapshot != snapshot) {
                return;
            }
            queuedRefresh._attempts++;
        }

        _flushDocumentChanges.accept(snapshot.uri());
        List<Highlight> highlights = defaultList(_fetchHighlights.apply(snapshot));
        if (!highlights.isEmpty()) {
            synchronized (this) {
                var queuedRefresh = _semanticRefreshes.get(snapshot.uri());
                if (queuedRefresh == null || queuedRefresh._snapshot != snapshot) {
                    return;
                }
                for (var mutation : queuedRefresh._mutations) {
                    highlights = mutation.apply(highlights);
                }
                int currentVersion = document.version();
                _semanticTokensCache.put(snapshot.uri(), new CachedSemanticTokens(currentVersion, highlights));
                reschedule = queuedRefresh._reschedule && currentVersion != snapshot.version();
                _semanticRefreshes.remove(snapshot.uri());
            }
            document.requestSemanticRedraw();
            if (reschedule) {
                scheduleRefresh(document);
            }
            return;
        }

        synchronized (this) {
            var queuedRefresh = _semanticRefreshes.get(snapshot.uri());
            if (queuedRefresh == null || queuedRefresh._snapshot != snapshot) {
                return;
            }
            if (queuedRefresh._attempts < _maxAttempts) {
                enqueueRefresh(snapshot, document, _retryDelayMillis);
                return;
            }
            reschedule = queuedRefresh._reschedule && document.version() != snapshot.version();
            _semanticRefreshes.remove(snapshot.uri());
        }
        if (reschedule) {
            scheduleRefresh(document);
        }
    }

    private void recordMutation(Document document, SemanticMutation mutation) {
        if (document == null || document.uri() == null || mutation == null) {
            return;
        }
        String uri = document.uri();
        int version = document.version();
        synchronized (this) {
            var cached = _semanticTokensCache.get(uri);
            if (cached != null) {
                _semanticTokensCache.put(uri, new CachedSemanticTokens(version, mutation.apply(cached.highlights())));
            }
            var queuedRefresh = _semanticRefreshes.get(uri);
            if (queuedRefresh != null) {
                queuedRefresh._mutations.add(mutation);
                queuedRefresh._reschedule = true;
            }
        }
    }

    public static List<Highlight> decodeSemanticHighlights(
            String text,
            SemanticTokens tokens,
            SemanticTokensLegend legend,
            SemanticTokenColorMapper colorMapper,
            Logger log) {
        if (text == null || tokens == null || tokens.getData() == null || legend == null
                || legend.getTokenTypes() == null || colorMapper == null) {
            return List.of();
        }
        var tokenTypes = legend.getTokenTypes();
        var tokenModifiers = legend.getTokenModifiers() == null ? List.<String>of() : legend.getTokenModifiers();
        var data = tokens.getData();
        var highlights = new ArrayList<Highlight>();
        int line = 0;
        int character = 0;

        for (int i = 0; i + 4 < data.size(); i += 5) {
            int deltaLine = data.get(i);
            int deltaStart = data.get(i + 1);
            int length = data.get(i + 2);
            int tokenTypeIndex = data.get(i + 3);
            int modifiersBitset = data.get(i + 4);

            line += deltaLine;
            character = deltaLine == 0 ? character + deltaStart : deltaStart;
            if (length <= 0 || tokenTypeIndex < 0 || tokenTypeIndex >= tokenTypes.size()) {
                continue;
            }

            try {
                TextColor color = colorMapper.colorFor(tokenTypes.get(tokenTypeIndex), modifiersBitset, tokenModifiers);
                if (color == null || TextColor.ANSI.DEFAULT.equals(color)) {
                    continue;
                }
                int start = indexForLineCharacter(text, line, character);
                int end = indexForLineCharacter(text, line, character + length);
                highlights.add(new Highlight(start, end, color));
            } catch (RuntimeException e) {
                if (log != null) {
                    log.debug("Skipping invalid semantic token at line " + line + " character " + character, e);
                }
            }
        }
        return merge(highlights);
    }

    public static List<Highlight> transformInsert(List<Highlight> highlights, int position, int length) {
        if (length <= 0 || highlights == null || highlights.isEmpty()) {
            return highlights == null ? List.of() : highlights;
        }
        TextColor insertedColor = adjacentSemanticColour(highlights, position);
        var transformed = new ArrayList<Highlight>(highlights.size() + 1);
        for (var highlight : highlights) {
            if (highlight.end() <= position) {
                transformed.add(highlight);
            } else if (highlight.start() >= position) {
                transformed.add(new Highlight(
                        highlight.start() + length,
                        highlight.end() + length,
                        highlight.foregroundColor()));
            } else {
                transformed.add(new Highlight(
                        highlight.start(),
                        highlight.end() + length,
                        highlight.foregroundColor()));
            }
        }
        if (insertedColor != null && !TextColor.ANSI.DEFAULT.equals(insertedColor)) {
            transformed.add(new Highlight(position, position + length, insertedColor));
        }
        return merge(transformed);
    }

    public static List<Highlight> transformDelete(List<Highlight> highlights, int start, int end) {
        if (end <= start || highlights == null || highlights.isEmpty()) {
            return highlights == null ? List.of() : highlights;
        }
        int length = end - start;
        var transformed = new ArrayList<Highlight>(highlights.size());
        for (var highlight : highlights) {
            if (highlight.end() <= start) {
                transformed.add(highlight);
            } else if (highlight.start() >= end) {
                transformed.add(new Highlight(
                        highlight.start() - length,
                        highlight.end() - length,
                        highlight.foregroundColor()));
            } else {
                int nextStart = highlight.start() < start ? highlight.start() : start;
                int nextEnd = highlight.end() > end ? highlight.end() - length : start;
                if (nextEnd > nextStart) {
                    transformed.add(new Highlight(nextStart, nextEnd, highlight.foregroundColor()));
                }
            }
        }
        return merge(transformed);
    }

    public static List<Highlight> merge(List<Highlight> highlights) {
        if (highlights == null || highlights.isEmpty()) {
            return List.of();
        }
        var sorted = new ArrayList<Highlight>(highlights.size());
        for (var highlight : highlights) {
            if (highlight == null
                    || highlight.end() <= highlight.start()
                    || highlight.foregroundColor() == null
                    || TextColor.ANSI.DEFAULT.equals(highlight.foregroundColor())) {
                continue;
            }
            sorted.add(highlight);
        }
        if (sorted.isEmpty()) {
            return List.of();
        }
        sorted.sort(Comparator.comparingInt(Highlight::start).thenComparingInt(Highlight::end));
        var merged = new ArrayList<Highlight>(sorted.size());
        for (var highlight : sorted) {
            if (!merged.isEmpty()) {
                int lastIndex = merged.size() - 1;
                var last = merged.get(lastIndex);
                if (last.foregroundColor().equals(highlight.foregroundColor()) && highlight.start() <= last.end()) {
                    merged.set(lastIndex, new Highlight(
                            last.start(),
                            Math.max(last.end(), highlight.end()),
                            last.foregroundColor()));
                    continue;
                }
            }
            merged.add(highlight);
        }
        return List.copyOf(merged);
    }

    private static TextColor adjacentSemanticColour(List<Highlight> highlights, int position) {
        TextColor left = null;
        TextColor right = null;
        for (var highlight : highlights) {
            if (highlight.start() <= position && position < highlight.end()) {
                return highlight.foregroundColor();
            }
            if (highlight.end() == position) {
                left = highlight.foregroundColor();
            }
            if (highlight.start() == position && right == null) {
                right = highlight.foregroundColor();
            }
        }
        return left != null ? left : right;
    }

    private static int indexForLineCharacter(String text, int targetLine, int targetCharacter) {
        if (targetLine < 0 || targetCharacter < 0) {
            throw new IllegalArgumentException("Negative semantic token position");
        }
        int lineStart = 0;
        for (int line = 0; line < targetLine; line++) {
            int nextLine = text.indexOf('\n', lineStart);
            if (nextLine < 0) {
                throw new IllegalArgumentException("Semantic token line outside snapshot");
            }
            lineStart = nextLine + 1;
        }
        int lineEnd = text.indexOf('\n', lineStart);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        return Math.min(lineStart + targetCharacter, lineEnd);
    }

    private static List<Highlight> defaultList(List<Highlight> value) {
        return value == null ? List.of() : value;
    }
}
