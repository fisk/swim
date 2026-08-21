package org.fisk.swim.treesitter;

import java.util.List;

/** Parsed subset of Tree-sitter query syntax suitable for inspection and future query execution. */
public record TreeSitterQuery(List<TreeSitterQueryNode> patterns) {
    public TreeSitterQuery {
        patterns = patterns == null ? List.of() : List.copyOf(patterns);
    }
}
