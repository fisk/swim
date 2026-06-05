package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.event.RecordedKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

class EditorStateTest {
    @BeforeEach
    void clearRegisters() {
        Copy.getInstance().clear();
    }

    @Test
    void macroRecordingSkipsStartRegisterKeyAndStoresObservedKeys() {
        var state = new EditorState();

        assertTrue(state.startMacroRecording('a'));
        state.observeKeyStroke(new KeyStroke('a', false, false));
        state.observeKeyStroke(new KeyStroke('x', false, false));
        state.observeKeyStroke(new KeyStroke(KeyType.Enter));
        assertTrue(state.stopMacroRecording());

        List<RecordedKey> macro = Copy.getInstance().getMacro('a');
        assertEquals(List.of("x", "<ENTER>"), macro.stream().map(RecordedKey::notation).toList());
    }

    @Test
    void marksAndJumpsRetainRecordedLocations() {
        var state = new EditorState();
        state.setMark('a', Path.of("/tmp/demo.txt"), 42);
        assertEquals("/tmp/demo.txt:42", state.getMark('a').display());

        state.recordJump(new EditorLocation(Path.of("/tmp/demo.txt"), 3));
        state.recordJump(new EditorLocation(Path.of("/tmp/demo.txt"), 9));
        state.recordJump(new EditorLocation(Path.of("/tmp/other.txt"), 1));

        assertEquals("/tmp/demo.txt:9", state.jumpBack().display());
        assertEquals("/tmp/demo.txt:3", state.jumpBack().display());
        assertNull(state.jumpBack());
        assertEquals("/tmp/demo.txt:9", state.jumpForward().display());
    }
}
