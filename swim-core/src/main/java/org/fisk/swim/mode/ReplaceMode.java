package org.fisk.swim.mode;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.event.Response;
import org.fisk.swim.ui.Window;

import com.googlecode.lanterna.input.KeyType;

public class ReplaceMode extends Mode {
    public ReplaceMode(Window window) {
        super("REPLACE", window);
        setupBasicResponders();
    }

    private void setupBasicResponders() {
        var window = _window;
        var bufferContext = window.getBufferContext();
        var buffer = bufferContext.getBuffer();
        var cursor = buffer.getCursor();
        _rootResponder.addEventResponder("<ESC>", () -> {
            window.allowEditorDriveAction("exit replace mode");
            window.appendRepeatKey(new RecordedKey(KeyType.Escape, null, false, false));
            window.commitRepeatRecording();
            window.switchToMode(window.getNormalMode());
            buffer.getCursor().goLeft();
        });
        _rootResponder.addEventResponder("<BACKSPACE>", () -> {
            window.allowEditorDriveAction("buffer edit");
            buffer.removeBefore();
        });
        _rootResponder.addEventResponder("<ENTER>", () -> {
            window.allowEditorDriveAction("insert newline");
            buffer.insert("\n");
        });
        _rootResponder.addEventResponder("<LEFT>", () -> {
            window.allowEditorDriveAction("cursor motion");
            cursor.goLeft();
        });
        _rootResponder.addEventResponder("<RIGHT>", () -> {
            window.allowEditorDriveAction("cursor motion");
            cursor.goRight();
        });
        _rootResponder.addEventResponder("<DOWN>", () -> {
            window.allowEditorDriveAction("cursor motion");
            cursor.goDown();
        });
        _rootResponder.addEventResponder("<UP>", () -> {
            window.allowEditorDriveAction("cursor motion");
            cursor.goUp();
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
                window.allowEditorDriveAction("replace text");
                int position = cursor.getPosition();
                int lineEnd = buffer.getLineEndPosition(position, false);
                if (position < lineEnd && buffer.replaceAtCursor(_character, 1)) {
                    cursor.goRight();
                } else {
                    buffer.insert(Character.toString(_character));
                }
                bufferContext.getBufferView().setNeedsRedraw();
                window.getModeLineView().setNeedsRedraw();
            }
        });
    }
}
