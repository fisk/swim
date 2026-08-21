package org.fisk.swim.treesitter;

/** A named query capture over a half-open source span. */
public record TreeSitterQueryCapture(String name, TreeSitterSyntaxSpan span) {
    public TreeSitterQueryCapture {
        name = name == null ? "" : name;
    }
}
