package org.fisk.swim.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.fisk.swim.event.RecordedKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CopyTest {
    @BeforeEach
    void resetCopy() {
        Copy.getInstance().clear();
    }

    @Test
    void isSingleton() {
        assertSame(Copy.getInstance(), Copy.getInstance());
    }

    @Test
    void storesClipboardTextAndLineMode() {
        var copy = Copy.getInstance();
        copy.setText("hello", true);

        assertEquals("hello", copy.getText());
        assertTrue(copy.isLine());

        copy.setText("world", false);

        assertEquals("world", copy.getText());
        assertFalse(copy.isLine());
    }

    @Test
    void storesNamedRegistersAndMacros() {
        var copy = Copy.getInstance();
        copy.setText("alpha", true, 'a');
        copy.setMacro('b', List.of(RecordedKey.parseToken("x"), RecordedKey.parseToken("<ESC>")));

        assertEquals("alpha", copy.getText('a'));
        assertTrue(copy.isLine('a'));
        assertEquals(List.of("x", "<ESC>"), copy.getMacro('b').stream().map(RecordedKey::notation).toList());
        assertEquals(Character.valueOf('b'), copy.getLastMacroRegister());
    }
}
