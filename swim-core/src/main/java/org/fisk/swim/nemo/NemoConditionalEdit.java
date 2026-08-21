package org.fisk.swim.nemo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Pure, fail-closed text transforms used by Nemo's structured edit tools. */
final class NemoConditionalEdit {
    record Change(String before, String after, int startLine, int endLine) { }

    /**
     * The source changed after Nemo inspected it.  This is an expected
     * optimistic-concurrency outcome, rather than a broken edit operation.
     */
    static final class PreconditionFailedException extends IOException {
        PreconditionFailedException(String message) {
            super(message);
        }
    }

    private NemoConditionalEdit() { }

    static Change replaceLines(String text, int startLine, int endLine, String expected, String replacement)
            throws IOException {
        if (startLine < 1 || endLine < startLine) throw new IOException("Invalid line range " + startLine + "-" + endLine);
        List<String> lines = splitLines(text);
        if (endLine > lines.size()) throw new IOException("Line range exceeds file length (" + lines.size() + ")");
        String actual = String.join("\n", lines.subList(startLine - 1, endLine));
        if (!actual.equals(expected)) throw new PreconditionFailedException("expected text no longer matches lines "
                + startLine + "-" + endLine);
        List<String> changed = new ArrayList<>(lines.subList(0, startLine - 1));
        changed.addAll(splitReplacement(replacement));
        changed.addAll(lines.subList(endLine, lines.size()));
        return new Change(text, joinLines(changed, text.endsWith("\n")), startLine, endLine);
    }

    static Change replaceFunctionBody(String text, String function, String expectedBody, String replacement)
            throws IOException {
        if (function == null || function.isBlank()) throw new IOException("function is required");
        int match = -1;
        int open = -1;
        int from = 0;
        while ((from = text.indexOf(function, from)) >= 0) {
            int candidate = text.indexOf('{', from + function.length());
            int semicolon = text.indexOf(';', from + function.length());
            if (candidate >= 0 && (semicolon < 0 || candidate < semicolon)) {
                if (match >= 0) throw new IOException("Function anchor is ambiguous: " + function);
                match = from;
                open = candidate;
            }
            from += function.length();
        }
        if (match < 0) throw new IOException("Function anchor not found: " + function);
        int close = matchingBrace(text, open);
        if (close < 0) throw new IOException("Function anchor has an unmatched body brace: " + function);
        String actual = text.substring(open + 1, close);
        if (!actual.equals(expectedBody)) {
            throw new PreconditionFailedException("expected body no longer matches " + function);
        }
        String after = text.substring(0, open + 1) + replacement + text.substring(close);
        int start = lineAt(text, open + 1);
        int end = lineAt(text, close);
        return new Change(text, after, start, end);
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        boolean lineComment = false, blockComment = false, quote = false, character = false, escape = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i), next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (quote || character) {
                if (escape) { escape = false; continue; }
                if (c == '\\') { escape = true; continue; }
                if (quote && c == '"') quote = false;
                if (character && c == '\'') character = false;
                continue;
            }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '"') { quote = true; continue; }
            if (c == '\'') { character = true; continue; }
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> splitLines(String text) { return List.of(text.split("\\n", -1)); }
    private static List<String> splitReplacement(String replacement) { return List.of(replacement.split("\\n", -1)); }
    private static String joinLines(List<String> lines, boolean trailingNewline) {
        String result = String.join("\n", lines);
        return trailingNewline && !result.endsWith("\n") ? result + "\n" : result;
    }
    private static int lineAt(String text, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, text.length()); i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
