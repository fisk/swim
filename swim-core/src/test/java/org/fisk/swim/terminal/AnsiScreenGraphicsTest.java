package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnsiScreenGraphicsTest {
    @Test
    void writesTextIntoOwnedScreenCells() {
        var screen = new AnsiScreen(4, 1);
        new AnsiScreenGraphics(screen).putString(1, 0, "ok", AnsiStyle.DEFAULT);

        assertEquals("\u001b[1;2H\u001b[0;39;49mo\u001b[1;3Hk", screen.flush());
    }
}
