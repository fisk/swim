package org.fisk.swim.treesitter;

import java.nio.file.Path;

/** Immutable document text captured before subset parsing begins on a plugin worker. */
public record TreeSitterSyntaxSnapshot(Path path, long version, String text, TreeSitterGrammar grammar, String startRule) {
    public TreeSitterSyntaxSnapshot {
        path = path == null ? null : path.toAbsolutePath().normalize();
        text = text == null ? "" : text;
        if (grammar == null) throw new IllegalArgumentException("A Tree-sitter grammar is required");
        startRule = startRule == null || startRule.isBlank() ? grammar.rules().keySet().stream().findFirst().orElse("") : startRule;
    }
}
