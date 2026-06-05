package org.fisk.swim.lsp;

import java.util.List;

import org.fisk.swim.text.BufferContext;

public interface DiagnosticActionProvider {
    List<DiagnosticAction> diagnosticActions(BufferContext bufferContext, int logicalLine, List<DiagnosticEntry> lineDiagnostics);
}
