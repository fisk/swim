package org.fisk.swim.treesitter;

/** Half-open source range emitted by the constrained Java runtime. */
public record TreeSitterSyntaxSpan(String type, String field, int start, int end) {
    public TreeSitterSyntaxSpan {
        type = type == null ? "" : type;
        field = field == null ? "" : field;
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid syntax span");
    }
}
