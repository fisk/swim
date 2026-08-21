package org.fisk.swim.treesitter;

import java.util.List;

/** A node pattern from a Tree-sitter query file such as {@code (identifier) @variable}. */
public record TreeSitterQueryNode(
        String type,
        String field,
        List<String> captures,
        List<TreeSitterQueryNode> children,
        List<TreeSitterQueryPredicate> predicates) {
    public TreeSitterQueryNode {
        type = type == null ? "" : type;
        field = field == null ? "" : field;
        captures = captures == null ? List.of() : List.copyOf(captures);
        children = children == null ? List.of() : List.copyOf(children);
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
    }
}
