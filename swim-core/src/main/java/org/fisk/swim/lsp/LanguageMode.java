package org.fisk.swim.lsp;

import org.eclipse.lsp4j.TextDocumentItem;
import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.BufferContext;
import org.fisk.swim.text.Settings;

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

    default boolean canReuseAttributedStringCacheAfterEdit(BufferContext bufferContext) {
        return false;
    }

    default String getIndentationString(BufferContext bufferContext) {
        return Settings.getIndentationString();
    }

    default void handleInsertedCharacter(BufferContext bufferContext, char insertedCharacter) {
    }

    default void handleBackspace(BufferContext bufferContext) {
    }

    default boolean hasCompletionSession() {
        return false;
    }

    default boolean selectNextCompletion() {
        return false;
    }

    default boolean selectPreviousCompletion() {
        return false;
    }

    default boolean pageNextCompletion() {
        return false;
    }

    default boolean pagePreviousCompletion() {
        return false;
    }

    default boolean acceptCompletion(BufferContext bufferContext) {
        return false;
    }

    default boolean acceptCompletionWithCharacter(BufferContext bufferContext, char character) {
        return false;
    }

    default boolean cancelCompletion() {
        return false;
    }

    default boolean triggerCompletion(BufferContext bufferContext) {
        return false;
    }

    default boolean isCommitCharacter(char character) {
        return false;
    }

    default boolean hasActiveSnippet() {
        return false;
    }

    default void cancelSnippet() {
    }

    default boolean jumpToNextSnippetStop(BufferContext bufferContext) {
        return false;
    }

    default boolean jumpToPreviousSnippetStop(BufferContext bufferContext) {
        return false;
    }

    default boolean handleSnippetCharacter(BufferContext bufferContext, char character) {
        return false;
    }

    default void handleSnippetBackspace(int startPosition, int endPosition) {
    }
}
