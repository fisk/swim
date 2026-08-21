package org.fisk.swim.treesitter;

import java.util.List;
import java.util.Map;

/** Portable, generated Tree-sitter grammar model; no native parser is involved. */
public record TreeSitterGrammar(
        String name,
        Map<String, TreeSitterGrammarRule> rules,
        List<TreeSitterGrammarRule> externals,
        List<TreeSitterGrammarRule> extras,
        List<List<String>> conflicts,
        String wordToken) {
    public TreeSitterGrammar {
        name = name == null ? "" : name;
        rules = rules == null || rules.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(rules));
        externals = externals == null ? List.of() : List.copyOf(externals);
        extras = extras == null ? List.of() : List.copyOf(extras);
        conflicts = conflicts == null ? List.of() : conflicts.stream().map(List::copyOf).toList();
        wordToken = wordToken == null ? "" : wordToken;
    }
}
