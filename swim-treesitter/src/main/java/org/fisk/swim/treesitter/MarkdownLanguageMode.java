package org.fisk.swim.treesitter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.lsp4j.TextDocumentItem;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.lsp.LanguagePluginRegistry;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.EventThread;
import org.fisk.swim.api.SwimPluginWorkers;
import org.fisk.swim.ui.UiTheme;

import com.googlecode.lanterna.TextColor;

/** Markdown editing mode backed by bundled Tree-sitter Markdown grammar assets. */
public final class MarkdownLanguageMode implements LanguageMode {
    private static final Pattern HEADING = Pattern.compile("(?m)^ {0,3}(?:#{1,6}\\s+.+|.+\\n[=-]+\\s*)$");
    private static final Pattern QUOTE = Pattern.compile("(?m)^ {0,3}>.*$");
    private static final Pattern LIST = Pattern.compile("(?m)^ {0,3}(?:[-+*]|\\d+[.)])\\s+");
    private static final Pattern LINK = Pattern.compile("!?(?:\\[[^]\\n]+\\]\\([^)]*\\)|<https?://[^>]+>)");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\w)(?:\\*{1,3}|_{1,3}).+?(?:\\*{1,3}|_{1,3})(?!\\w)");
    private static final Pattern FENCE = Pattern.compile("(?m)^ {0,3}(```+|~~~+)\\s*([^\\s`]*)[^\\n]*\\n");
    private final MarkdownSyntaxAnalysisService analysis;

    public MarkdownLanguageMode() {
        this(SwimPluginWorkers.unmanaged());
    }

    MarkdownLanguageMode(SwimPluginWorkers workers) {
        // Validate that both upstream generated grammars are bundled when the mode is first used.
        TreeSitterBundledGrammars.load("markdown");
        TreeSitterBundledGrammars.load("markdown_inline");
        analysis = new MarkdownSyntaxAnalysisService(workers);
    }

    @Override public void didInsert(BufferContext context, int position, String text) { update(context); }
    @Override public void didRemove(BufferContext context, int start, int end) { update(context); }
    @Override public void willSave(BufferContext context) { }
    @Override public void didSave(BufferContext context) { }
    @Override public void didClose(BufferContext context) { }
    @Override public void didOpen(BufferContext context) { update(context); }
    @Override public int getIndentationLevel(BufferContext context) { return 0; }
    @Override public boolean isIndentationEnd(BufferContext context, String character) { return false; }
    @Override public TextDocumentItem getTextDocument(BufferContext context) { return null; }
    @Override public boolean trimTrailingWhitespaceOnSave(BufferContext context) { return true; }

    @Override
    public void applyColouring(BufferContext context, AttributedString text) {
        String source = text.toString();
        colour(text, HEADING, UiTheme.SEMANTIC_KEYWORD);
        colour(text, QUOTE, UiTheme.SEMANTIC_COMMENT);
        colour(text, LIST, UiTheme.SEMANTIC_KEYWORD);
        colour(text, LINK, UiTheme.SEMANTIC_STRING);
        colour(text, INLINE_CODE, UiTheme.SEMANTIC_STRING);
        colour(text, EMPHASIS, UiTheme.SEMANTIC_READONLY);
        colourFences(text, source);
    }

    private static void colourFences(AttributedString text, String source) {
        Matcher matcher = FENCE.matcher(source);
        while (matcher.find()) {
            text.format(matcher.start(), matcher.end(), UiTheme.SEMANTIC_KEYWORD, TextColor.ANSI.DEFAULT);
            String marker = matcher.group(1);
            int bodyStart = matcher.end();
            int close = source.indexOf("\n" + marker, bodyStart);
            int bodyEnd = close < 0 ? source.length() : close + 1;
            String language = matcher.group(2);
            if (!language.isBlank()) colourEmbedded(text, bodyStart, bodyEnd, language);
            if (close < 0) break;
            matcher.region(close + 1, source.length());
        }
    }

    private static void colourEmbedded(AttributedString target, int start, int end, String language) {
        AttributedString embedded = target.slice(start, end);
        if (!LanguagePluginRegistry.applySnippetColouring(language, embedded)) return;
        int offset = start;
        for (var fragment : embedded.getFragments()) {
            int next = offset + fragment.toString().length();
            var attributes = fragment.getAttributes();
            target.format(offset, next, attributes.foregroundColour(), attributes.backgroundColour());
            offset = next;
        }
    }

    private static void colour(AttributedString text, Pattern pattern, TextColor colour) {
        Matcher matcher = pattern.matcher(text.toString());
        while (matcher.find()) text.format(matcher.start(), matcher.end(), colour, TextColor.ANSI.DEFAULT);
    }

    private void update(BufferContext context) {
        if (context == null) return;
        var buffer = context.getBuffer();
        var snapshot = new MarkdownSyntaxAnalysisService.Snapshot(buffer.getPath(), buffer.getVersion(), buffer.getString(),
                TreeSitterBundledGrammars.load("markdown"), TreeSitterBundledGrammars.load("markdown_inline"));
        analysis.analyse(buffer.getURI().toString(), snapshot, result -> EventThread.getInstance().enqueue(
                new org.fisk.swim.event.RunnableEvent(() -> {
                    if (buffer.getVersion() != result.snapshot().version()) return;
                    var ranges = result.spans().stream().map(span -> new AttributedString.FormatRange(span.start(), span.end(),
                            "markdown.block".equals(span.type()) || "markdown.fence".equals(span.type())
                                    ? UiTheme.SEMANTIC_KEYWORD : UiTheme.SEMANTIC_STRING,
                            null)).toList();
                    buffer.setSyntaxFormatOverlays((int) result.snapshot().version(), ranges);
                })));
    }
}
