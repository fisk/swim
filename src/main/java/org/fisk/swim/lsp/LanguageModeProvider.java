package org.fisk.swim.lsp;

import java.nio.file.Path;

import org.eclipse.lsp4j.TextDocumentItem;
import org.fisk.swim.lsp.java.JavaLSPClient;
import org.fisk.swim.lsp.latex.LatexLSPClient;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;

public class LanguageModeProvider {
    private static LanguageModeProvider _instance = new LanguageModeProvider();

    public static LanguageModeProvider getInstance() {
        return _instance;
    }

    private boolean endsIn(Path path, String ending) {
        String extension = "";
        String fileName = path.getFileName().toString();

        int i = fileName.lastIndexOf('.');
        if (i >= 0) {
            extension = fileName.substring(i + 1);
        }
        return extension.equals(ending);
    }
    
    private LanguageMode getPlainLanguageMode() {
        return new LanguageMode() {
            @Override
            public void didInsert(BufferContext bufferContext, int position, String str) {
            }

            @Override
            public void didRemove(BufferContext bufferContext, int startPosition, int endPosition) {
            }

            @Override
            public void willSave(BufferContext bufferContext) {
            }

            @Override
            public void didSave(BufferContext bufferContext) {
            }

            @Override
            public void didClose(BufferContext bufferContext) {
            }

            @Override
            public void didOpen(BufferContext bufferContext) {
            }

            @Override
            public int getIndentationLevel(BufferContext bufferContext) {
                return 0;
            }
            
            @Override
            public boolean isIndentationEnd(BufferContext bufferContext, String character) {
                return false;
            }

            @Override
            public TextDocumentItem getTextDocument(BufferContext bufferContext) {
                return null;
            }

            @Override
            public void applyColouring(BufferContext bufferContext, AttributedString str) {
            }
        };
    }
    
    public LanguageMode getLanguageMode(Path path) {
        if (endsIn(path, "java")) {
            var lsp = JavaLSPClient.getInstance();
            if (lsp.isEnabled() && !lsp.hasStarted()) {
                lsp.start();
                lsp.ensureInit();
            }
            return lsp;
        }
        if (endsIn(path, "tex")) {
            return new LatexLSPClient();
        }
        return getPlainLanguageMode();
    }
}
