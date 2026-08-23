package org.fisk.swim.terminal;

import java.util.Objects;

/** Draws directly into SWIM's ANSI diff back buffer. */
public final class AnsiScreenGraphics implements TerminalGraphics {
    private final AnsiScreen screen;

    public AnsiScreenGraphics(AnsiScreen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    @Override
    public void putString(int column, int row, String text, AnsiStyle style) {
        if (text == null || text.isEmpty()) return;
        for (int index = 0; index < text.length(); index++) screen.set(column + index, row, text.charAt(index), style);
    }
}
