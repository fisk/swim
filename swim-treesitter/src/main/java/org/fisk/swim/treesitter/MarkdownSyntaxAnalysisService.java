package org.fisk.swim.treesitter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fisk.swim.api.SwimPluginWorkers;

/**
 * Markdown's upstream grammar is split into block and inline parsers. This is
 * the immutable asynchronous boundary for that model; the current Java
 * implementation recognizes the portable highlight constructs while the full
 * LR/query runtime is filled in behind the same snapshot API.
 */
public final class MarkdownSyntaxAnalysisService implements AutoCloseable {
    public record Snapshot(Path path, long version, String text, TreeSitterGrammar blockGrammar,
            TreeSitterGrammar inlineGrammar) {
        public Snapshot {
            path = path == null ? null : path.toAbsolutePath().normalize();
            text = text == null ? "" : text;
        }
    }
    public record Result(Snapshot snapshot, List<TreeSitterSyntaxSpan> spans, List<TreeSitterQueryCapture> captures) { }

    private static final Pattern HEADING = Pattern.compile("(?m)^ {0,3}(#{1,6})\\s+(.+)$");
    private static final Pattern QUOTE = Pattern.compile("(?m)^ {0,3}(>)");
    private static final Pattern LIST = Pattern.compile("(?m)^ {0,3}([-+*]|\\d+[.)])\\s+");
    private static final Pattern INLINE = Pattern.compile("!?(?:\\[[^]\\n]+\\]\\([^)]*\\)|<https?://[^>]+>)|`[^`\\n]+`|(?<!\\w)(?:\\*{1,3}|_{1,3}).+?(?:\\*{1,3}|_{1,3})(?!\\w)");
    private static final Pattern FENCE = Pattern.compile("(?m)^ {0,3}(```+|~~~+)\\s*([^\\s`]*)[^\\n]*$");
    private final SwimPluginWorkers workers;
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentHashMap<String, Long> latest = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public MarkdownSyntaxAnalysisService(SwimPluginWorkers workers) {
        this.workers = workers == null ? SwimPluginWorkers.unmanaged() : workers;
    }

    public long analyse(String document, Snapshot snapshot, Consumer<Result> consumer) {
        long request = sequence.incrementAndGet();
        latest.put(document, request);
        workers.start(() -> {
            List<TreeSitterSyntaxSpan> spans = scan(snapshot.text());
            var tree = new TreeSitterSyntaxTree(new TreeSitterSyntaxSnapshot(snapshot.path(), snapshot.version(), snapshot.text(),
                    snapshot.blockGrammar(), "document"), spans, List.of());
            var captures = new ArrayList<TreeSitterQueryCapture>();
            // These capture names are taken directly from the upstream
            // highlights query. They remain stable when the full query
            // executor replaces this portable structural adapter.
            spans.stream().filter(span -> "inline".equals(span.type()))
                    .forEach(span -> captures.add(new TreeSitterQueryCapture("text.title", span)));
            spans.stream().filter(span -> span.type().startsWith("atx_h"))
                    .forEach(span -> captures.add(new TreeSitterQueryCapture("punctuation.special", span)));
            // The upstream injection query expresses this same fenced-block
            // relationship. Keep it explicit until the portable executor adds
            // #set! and anchored sibling constraints.
            spans.stream().filter(span -> "language".equals(span.type()))
                    .forEach(span -> captures.add(new TreeSitterQueryCapture("injection.language", span)));
            spans.stream().filter(span -> "code_fence_content".equals(span.type()))
                    .forEach(span -> captures.add(new TreeSitterQueryCapture("injection.content", span)));
            if (!closed.get() && Long.valueOf(request).equals(latest.get(document))) consumer.accept(new Result(snapshot, spans, List.copyOf(captures)));
        });
        return request;
    }

    private static List<TreeSitterSyntaxSpan> scan(String source) {
        var result = new ArrayList<TreeSitterSyntaxSpan>();
        Matcher headings = HEADING.matcher(source);
        while (headings.find()) {
            result.add(new TreeSitterSyntaxSpan("atx_heading", "", headings.start(), headings.end()));
            result.add(new TreeSitterSyntaxSpan("atx_h" + headings.group(1).length() + "_marker", "", headings.start(1), headings.end(1)));
            result.add(new TreeSitterSyntaxSpan("inline", "", headings.start(2), headings.end(2)));
        }
        add(result, QUOTE.matcher(source), "block_quote_marker");
        add(result, LIST.matcher(source), "list_marker_minus");
        add(result, INLINE.matcher(source), "markdown.inline");
        Matcher fences = FENCE.matcher(source);
        while (fences.find()) {
            String marker = fences.group(1);
            int close = source.indexOf("\n" + marker, fences.end());
            int end = close < 0 ? source.length() : source.indexOf('\n', close + 1) + 1;
            if (end <= close) end = source.length();
            result.add(new TreeSitterSyntaxSpan("fenced_code_block", "", fences.start(), end));
            result.add(new TreeSitterSyntaxSpan("fenced_code_block_delimiter", "", fences.start(), fences.end()));
            if (!fences.group(2).isBlank()) {
                result.add(new TreeSitterSyntaxSpan("info_string", "", fences.start(2), fences.end(2)));
                result.add(new TreeSitterSyntaxSpan("language", "", fences.start(2), fences.end(2)));
            }
            result.add(new TreeSitterSyntaxSpan("code_fence_content", "", fences.end(), close < 0 ? source.length() : close + 1));
        }
        return List.copyOf(result);
    }

    private static void add(List<TreeSitterSyntaxSpan> spans, Matcher matcher, String type) {
        while (matcher.find()) spans.add(new TreeSitterSyntaxSpan(type, "", matcher.start(), matcher.end()));
    }

    @Override public void close() { closed.set(true); latest.clear(); }
}
