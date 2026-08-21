package org.fisk.swim.treesitter;

import java.util.List;

/** Immutable subset-runtime result. A later runtime can preserve this boundary while changing internals. */
public record TreeSitterSyntaxTree(TreeSitterSyntaxSnapshot snapshot, List<TreeSitterSyntaxSpan> spans,
        List<TreeSitterParseError> errors) {
    public TreeSitterSyntaxTree {
        spans = spans == null ? List.of() : List.copyOf(spans);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public boolean succeeded() {
        return errors.isEmpty();
    }
}
