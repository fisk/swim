package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TerminalDimensionsTest {
    @Test
    void retainsCellDimensions() {
        var dimensions = new TerminalDimensions(132, 41);
        assertEquals(132, dimensions.columns());
        assertEquals(41, dimensions.rows());
    }

    @Test
    void rejectsEmptyViewport() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalDimensions(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new TerminalDimensions(80, 0));
    }
}
