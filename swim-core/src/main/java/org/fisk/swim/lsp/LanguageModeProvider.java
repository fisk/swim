package org.fisk.swim.lsp;

import java.nio.file.Path;

import org.eclipse.lsp4j.TextDocumentItem;
import org.fisk.swim.lsp.latex.LatexLSPClient;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.utils.LogFactory;
import org.slf4j.Logger;

public class LanguageModeProvider {
    private static LanguageModeProvider _instance = new LanguageModeProvider();
    private static final Logger _log = LogFactory.createLog();

    static {
        LanguagePluginRegistry.register("tex", null, path -> new LatexLSPClient());
    }

    public static LanguageModeProvider getInstance() {
        return _instance;
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
        var registration = LanguagePluginRegistry.find(path);
        if (registration != null) {
            try {
                var languageMode = registration.factory().create(path);
                if (languageMode != null) {
                    return languageMode;
                }
            } catch (RuntimeException e) {
                _log.error("Failed to initialize language mode for " + path, e);
            }
        }
        return getPlainLanguageMode();
    }
}
