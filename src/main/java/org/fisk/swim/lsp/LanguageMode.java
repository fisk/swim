package org.fisk.swim.lsp;

import org.eclipse.lsp4j.TextDocumentItem;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;

public interface LanguageMode {
    void didInsert(BufferContext bufferContext, int position, String str);
    void didRemove(BufferContext bufferContext, int startPosition, int endPosition);
    void willSave(BufferContext bufferContext);
    void didSave(BufferContext bufferContext);
    void didClose(BufferContext bufferContext);
    void didOpen(BufferContext bufferContext);
    int getIndentationLevel(BufferContext bufferContext);
    boolean isIndentationEnd(BufferContext bufferContext, String chracter);
    TextDocumentItem getTextDocument(BufferContext bufferContext);
    void applyColouring(BufferContext bufferContext, AttributedString str);
}
