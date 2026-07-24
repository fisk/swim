package org.fisk.swim.lsp;

import java.nio.file.Path;

import org.fisk.swim.text.BufferContext;

/**
 * Structured, non-UI LSP access for Nemo analysis documents. Implementations
 * must not navigate the editor or apply workspace edits.
 */
public interface NemoLspBackend {
    String analyze(Path workspaceRoot, BufferContext bufferContext, String command, int line, int column, String query);
}
