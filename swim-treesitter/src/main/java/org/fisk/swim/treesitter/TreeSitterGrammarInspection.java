package org.fisk.swim.treesitter;

import java.nio.file.Path;
import java.util.List;

/** A deliberately small, stable view of Tree-sitter's generated grammar.json format. */
public record TreeSitterGrammarInspection(
        Path path,
        long version,
        String name,
        List<String> ruleNames,
        List<String> externalTokens,
        List<String> extraTokens,
        int conflictCount,
        boolean hasWordToken) {
    public TreeSitterGrammarInspection {
        ruleNames = ruleNames == null ? List.of() : List.copyOf(ruleNames);
        externalTokens = externalTokens == null ? List.of() : List.copyOf(externalTokens);
        extraTokens = extraTokens == null ? List.of() : List.copyOf(extraTokens);
    }
}
