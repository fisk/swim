package org.fisk.swim.treesitter;

import java.nio.file.Path;

/** Project-local Tree-sitter asset locations discovered without loading native code. */
public record TreeSitterGrammarAssets(Path root, Path grammarJson, Path highlightsQuery, Path injectionsQuery) {
    public TreeSitterGrammarAssets {
        root = normalize(root);
        grammarJson = normalize(grammarJson);
        highlightsQuery = normalize(highlightsQuery);
        injectionsQuery = normalize(injectionsQuery);
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }
}
