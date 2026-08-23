package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AnsiScreenTest {
    @Test
    void firstFlushEmitsOnlyChangedCellsAndSecondFlushIsEmpty() {
        var screen = new AnsiScreen(3, 2);
        screen.set(1, 0, 'A', new AnsiStyle(AnsiColour.rgb(1, 2, 3), AnsiColour.DEFAULT, true, false, false));

        assertEquals("\u001b[1;2H\u001b[0;1;38;2;1;2;3;49mA", screen.flush());
        assertEquals("", screen.flush());
    }

    @Test
    void styleChangesAreEmittedWithRgbSgr() {
        var screen = new AnsiScreen(2, 1);
        screen.set(0, 0, 'x', AnsiStyle.DEFAULT);
        screen.set(1, 0, 'y', new AnsiStyle(AnsiColour.DEFAULT, AnsiColour.rgb(4, 5, 6), false, true, true));

        assertEquals("\u001b[1;1H\u001b[0;39;49mx\u001b[1;2H\u001b[0;4;7;39;48;2;4;5;6my", screen.flush());
    }

    @Test
    void resizeInvalidatesTheDisplayedImageForACompleteRepaint() {
        var screen = new AnsiScreen(1, 1);
        screen.set(0, 0, 'x', AnsiStyle.DEFAULT);
        screen.flush();

        screen.resize(2, 1);

        assertEquals("\u001b[1;1H\u001b[0;39;49m \u001b[1;2H ", screen.flush());
    }
}
