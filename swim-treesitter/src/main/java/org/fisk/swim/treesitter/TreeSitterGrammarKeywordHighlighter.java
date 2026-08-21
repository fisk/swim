package org.fisk.swim.treesitter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lexical fallback derived from STRING terminals in a generated grammar. It is intentionally
 * separate from the constrained structural runtime: recursive production grammars remain usable
 * for immediate keyword colouring while their full LR parser support is under development.
 */
public final class TreeSitterGrammarKeywordHighlighter {
    private final Set<String> _keywords;

    public TreeSitterGrammarKeywordHighlighter(TreeSitterGrammar grammar) {
        var keywords = new HashSet<String>();
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<TreeSitterGrammarRule, Boolean>());
        grammar.rules().values().forEach(rule -> collect(rule, keywords, visited));
        _keywords = Set.copyOf(keywords);
    }

    public List<TreeSitterSyntaxSpan> highlight(String source) {
        source = source == null ? "" : source;
        var spans = new java.util.ArrayList<TreeSitterSyntaxSpan>();
        for (int index = 0; index < source.length();) {
            char c = source.charAt(index);
            if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index = lineEnd(source, index + 2);
            } else if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
            } else if (c == '\'' || c == '\"') {
                index = quotedEnd(source, index, c);
            } else if (Character.isJavaIdentifierStart(c)) {
                int end = index + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                if (_keywords.contains(source.substring(index, end))) {
                    spans.add(new TreeSitterSyntaxSpan("grammar_keyword", "", index, end));
                }
                index = end;
            } else {
                index++;
            }
        }
        return List.copyOf(spans);
    }

    public Set<String> keywords() {
        return _keywords;
    }

    private static void collect(TreeSitterGrammarRule rule, Set<String> keywords, Set<TreeSitterGrammarRule> visited) {
        if (!visited.add(rule)) return;
        if ("STRING".equals(rule.type())) {
            String value = rule.attribute("value");
            if (isIdentifier(value)) keywords.add(value);
        }
        rule.children().values().forEach(children -> children.forEach(child -> collect(child, keywords, visited)));
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) return false;
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) return false;
        }
        return true;
    }

    private static int lineEnd(String source, int index) {
        int end = source.indexOf('\n', index);
        return end < 0 ? source.length() : end + 1;
    }

    private static int quotedEnd(String source, int index, char quote) {
        index++;
        while (index < source.length()) {
            if (source.charAt(index++) == '\\' && index < source.length()) index++;
            else if (source.charAt(index - 1) == quote) break;
        }
        return index;
    }
}
