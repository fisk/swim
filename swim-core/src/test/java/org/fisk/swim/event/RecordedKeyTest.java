package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

class RecordedKeyTest {
    @Test
    void parsesAndRendersNotation() {
        assertEquals(List.of("\"", "a", "<ESC>", "<CTRL>-s", "<TAB>"),
                RecordedKey.parseSequence("\" a <ESC> <CTRL>-s <TAB>").stream()
                        .map(RecordedKey::notation)
                        .toList());
    }

    @Test
    void roundTripsLanternaKeyStrokes() {
        KeyStroke stroke = new KeyStroke('x', true, false);
        assertEquals(stroke.getCharacter(), RecordedKey.fromKeyStroke(stroke).toKeyStroke().getCharacter());
        assertEquals(KeyType.Character, RecordedKey.fromKeyStroke(stroke).toKeyStroke().getKeyType());
    }
}
