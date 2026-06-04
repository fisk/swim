package org.fisk.swim.mode;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.lsp.LanguageMode;
import org.fisk.swim.ui.Window;

import com.googlecode.lanterna.input.KeyType;

public class InputMode extends Mode {
    public InputMode(Window window) {
        super("INPUT", window);
        setupBasicResponders();
    }

    private void setupBasicResponders() {
        var window = _window;
        var bufferContext = window.getBufferContext();
        var buffer = bufferContext.getBuffer();
        var cursor = buffer.getCursor();
        _rootResponder.addEventResponder("<ESC>", () -> {
            var languageMode = languageMode(buffer);
            languageMode.cancelCompletion();
            languageMode.cancelSnippet();
            window.switchToMode(window.getNormalMode());
            buffer.getCursor().goLeft();
        });
        _rootResponder.addEventResponder(new EventResponder() {
            private char _character;

            @Override
            public Response processEvent(KeyStrokes events) {
                if (events.remaining() != 0) {
                    return Response.NO;
                }
                var event = events.current();
                if (event.getKeyType() == KeyType.Character && !event.isCtrlDown() && !event.isAltDown()) {
                    _character = event.getCharacter();
                    return Response.YES;
                }
                return Response.NO;
            }

            @Override
            public void respond() {
                buffer.insert(Character.toString(_character));
                languageMode(buffer).handleInsertedCharacter(bufferContext, _character);
                bufferContext.getBufferView().setNeedsRedraw();
                window.getModeLineView().setNeedsRedraw();
            }
        });
        _rootResponder.addEventResponder("<BACKSPACE>", () -> {
            int previousPosition = buffer.getCursor().getPosition();
            buffer.removeBefore();
            var languageMode = languageMode(buffer);
            if (languageMode.hasActiveSnippet() && previousPosition > 0) {
                languageMode.handleSnippetBackspace(previousPosition - 1, previousPosition);
            }
            languageMode.handleBackspace(bufferContext);
        });
        _rootResponder.addEventResponder("<ENTER>", () -> {
            var languageMode = languageMode(buffer);
            languageMode.cancelCompletion();
            languageMode.cancelSnippet();
            buffer.insert("\n");
        });
        _rootResponder.addEventResponder("<CTRL>-s", () -> {
            if (_window.sendActiveMailCompose()) {
                return;
            }
            if (_window.sendActiveSlackCompose()) {
                return;
            }
        });
        _rootResponder.addEventResponder("<LEFT>", () -> {
            var languageMode = languageMode(buffer);
            languageMode.cancelCompletion();
            languageMode.cancelSnippet();
            for (var c: buffer.getCursors()) {
                c.goLeft();
            }
        });
        _rootResponder.addEventResponder("<RIGHT>", () -> {
            var languageMode = languageMode(buffer);
            languageMode.cancelCompletion();
            languageMode.cancelSnippet();
            for (var c: buffer.getCursors()) {
                c.goRight();
            }
        });
        _rootResponder.addEventResponder("<DOWN>", () -> {
            var languageMode = languageMode(buffer);
            languageMode.cancelCompletion();
            languageMode.cancelSnippet();
            cursor.goDown();
        });
        _rootResponder.addEventResponder("<UP>", () -> {
            var languageMode = languageMode(buffer);
            languageMode.cancelCompletion();
            languageMode.cancelSnippet();
            cursor.goUp();
        });
        _rootResponder.addEventResponder(new EventResponder() {
            private enum CompletionAction {
                NONE,
                SNIPPET_TYPE,
                SNIPPET_NEXT,
                SNIPPET_PREVIOUS,
                NEXT,
                PREVIOUS,
                PAGE_NEXT,
                PAGE_PREVIOUS,
                ACCEPT,
                ACCEPT_COMMIT,
                CANCEL,
                TRIGGER
            }

            private CompletionAction _action = CompletionAction.NONE;
            private char _commitCharacter;
            private char _typedCharacter;

            @Override
            public Response processEvent(KeyStrokes events) {
                _action = CompletionAction.NONE;
                _commitCharacter = 0;
                _typedCharacter = 0;
                if (events.remaining() != 0) {
                    return Response.NO;
                }
                var event = events.current();
                var completion = languageMode(buffer);
                boolean visible = completion.hasCompletionSession();
                boolean snippetActive = completion.hasActiveSnippet();

                if (event.getKeyType() == KeyType.Tab && snippetActive && !visible) {
                    _action = CompletionAction.SNIPPET_NEXT;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.ReverseTab && snippetActive && !visible) {
                    _action = CompletionAction.SNIPPET_PREVIOUS;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.Character
                        && !event.isCtrlDown()
                        && !event.isAltDown()
                        && snippetActive
                        && !visible) {
                    _typedCharacter = event.getCharacter();
                    _action = CompletionAction.SNIPPET_TYPE;
                    return Response.YES;
                }

                if (event.getKeyType() == KeyType.ArrowDown && visible) {
                    _action = CompletionAction.NEXT;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.ArrowUp && visible) {
                    _action = CompletionAction.PREVIOUS;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.PageDown && visible) {
                    _action = CompletionAction.PAGE_NEXT;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.PageUp && visible) {
                    _action = CompletionAction.PAGE_PREVIOUS;
                    return Response.YES;
                }
                if ((event.getKeyType() == KeyType.Enter || event.getKeyType() == KeyType.Tab) && visible) {
                    _action = CompletionAction.ACCEPT;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.Escape && visible) {
                    _action = CompletionAction.CANCEL;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.Character
                        && !event.isCtrlDown()
                        && !event.isAltDown()
                        && visible
                        && completion.isCommitCharacter(event.getCharacter())) {
                    _commitCharacter = event.getCharacter();
                    _action = CompletionAction.ACCEPT_COMMIT;
                    return Response.YES;
                }
                if (event.getKeyType() == KeyType.Character && event.isCtrlDown()) {
                    if (event.getCharacter() == 'n') {
                        _action = visible ? CompletionAction.NEXT : CompletionAction.TRIGGER;
                        return Response.YES;
                    }
                    if (event.getCharacter() == 'p' && visible) {
                        _action = CompletionAction.PREVIOUS;
                        return Response.YES;
                    }
                    if (event.getCharacter() == 'v' && visible) {
                        _action = CompletionAction.PAGE_NEXT;
                        return Response.YES;
                    }
                    if (event.getCharacter() == 'g' && visible) {
                        _action = CompletionAction.CANCEL;
                        return Response.YES;
                    }
                }
                return Response.NO;
            }

            @Override
            public void respond() {
                var completion = languageMode(buffer);
                switch (_action) {
                case SNIPPET_TYPE:
                    completion.handleSnippetCharacter(bufferContext, _typedCharacter);
                    bufferContext.getBufferView().setNeedsRedraw();
                    window.getModeLineView().setNeedsRedraw();
                    break;
                case SNIPPET_NEXT:
                    completion.jumpToNextSnippetStop(bufferContext);
                    bufferContext.getBufferView().setNeedsRedraw();
                    window.getModeLineView().setNeedsRedraw();
                    break;
                case SNIPPET_PREVIOUS:
                    completion.jumpToPreviousSnippetStop(bufferContext);
                    bufferContext.getBufferView().setNeedsRedraw();
                    window.getModeLineView().setNeedsRedraw();
                    break;
                case NEXT:
                    completion.selectNextCompletion();
                    break;
                case PREVIOUS:
                    completion.selectPreviousCompletion();
                    break;
                case PAGE_NEXT:
                    completion.pageNextCompletion();
                    break;
                case PAGE_PREVIOUS:
                    completion.pagePreviousCompletion();
                    break;
                case ACCEPT:
                    completion.acceptCompletion(bufferContext);
                    break;
                case ACCEPT_COMMIT:
                    completion.acceptCompletionWithCharacter(bufferContext, _commitCharacter);
                    bufferContext.getBufferView().setNeedsRedraw();
                    window.getModeLineView().setNeedsRedraw();
                    break;
                case CANCEL:
                    completion.cancelCompletion();
                    break;
                case TRIGGER:
                    completion.triggerCompletion(bufferContext);
                    break;
                default:
                    break;
                }
            }
        });
    }

    private static LanguageMode languageMode(org.fisk.swim.text.Buffer buffer) {
        return buffer.getLanguageMode();
    }
}
