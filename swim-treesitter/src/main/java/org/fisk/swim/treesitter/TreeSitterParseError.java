package org.fisk.swim.treesitter;

/** Parse failure retained in the result so editor callers can keep rendering partial syntax spans. */
public record TreeSitterParseError(int offset, String message) {
    public TreeSitterParseError {
        offset = Math.max(0, offset);
        message = message == null ? "Parse failed" : message;
    }
}
