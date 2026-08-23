package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.fisk.swim.event.KeyStroke;
import org.fisk.swim.event.KeyType;

class RecordedKeyTest {
    @Test
    void parsesAndRendersNotation() {
        assertEquals(List.of("\"", "a", "<ESC>", "<CTRL>-s", "<TAB>"),
                RecordedKey.parseSequence("\" a <ESC> <CTRL>-s <TAB>").stream()
                        .map(RecordedKey::notation)
                        .toList());
    }

    @Test
    void roundTripsTerminalKeyStrokes() {
        KeyStroke stroke = new KeyStroke('x', true, false);
        assertEquals(stroke.getCharacter(), RecordedKey.fromKeyStroke(stroke).toKeyStroke().getCharacter());
        assertEquals(KeyType.Character, RecordedKey.fromKeyStroke(stroke).toKeyStroke().getKeyType());
    }
}
