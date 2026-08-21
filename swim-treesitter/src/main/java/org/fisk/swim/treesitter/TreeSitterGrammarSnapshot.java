package org.fisk.swim.treesitter;

import java.nio.file.Path;

/** Immutable source captured before grammar analysis leaves the event thread. */
public record TreeSitterGrammarSnapshot(Path path, long version, String text) {
    public TreeSitterGrammarSnapshot {
        path = path == null ? null : path.toAbsolutePath().normalize();
        text = text == null ? "" : text;
    }
}
