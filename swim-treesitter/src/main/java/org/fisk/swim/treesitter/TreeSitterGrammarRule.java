package org.fisk.swim.treesitter;

import java.util.List;
import java.util.Map;

/** A lossless-enough structured node from a generated Tree-sitter grammar.json rule. */
public record TreeSitterGrammarRule(
        String type,
        Map<String, String> attributes,
        Map<String, List<TreeSitterGrammarRule>> children) {
    public TreeSitterGrammarRule {
        type = type == null ? "" : type;
        attributes = attributes == null || attributes.isEmpty()
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(attributes));
        if (children == null || children.isEmpty()) {
            children = Map.of();
        } else {
            var copy = new java.util.LinkedHashMap<String, List<TreeSitterGrammarRule>>();
            for (var entry : children.entrySet()) {
                copy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            children = java.util.Collections.unmodifiableMap(copy);
        }
    }

    public String attribute(String name) {
        return attributes.getOrDefault(name, "");
    }

    public List<TreeSitterGrammarRule> children(String name) {
        return children.getOrDefault(name, List.of());
    }
}
