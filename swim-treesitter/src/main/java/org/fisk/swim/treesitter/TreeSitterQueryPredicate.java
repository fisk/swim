package org.fisk.swim.treesitter;

import java.util.List;

/** A query predicate such as {@code (#match? @name "^[A-Z]")}. */
public record TreeSitterQueryPredicate(String name, List<String> arguments) {
    public TreeSitterQueryPredicate {
        name = name == null ? "" : name;
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }
}
