package org.fisk.swim.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CopyTest {
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
}
