package org.fisk.swim.terminal;

/** SWIM-owned, cell-oriented drawing surface for POSIX terminal renderers. */
public interface TerminalGraphics {
    void putString(int column, int row, String text, AnsiStyle style);

    default void setCharacter(int column, int row, char character, AnsiStyle style) {
        putString(column, row, String.valueOf(character), style);
    }

    default void fillRow(int column, int row, int width, AnsiStyle style) {
        if (width > 0) putString(column, row, " ".repeat(width), style);
    }

    default void fillRectangle(int column, int row, int width, int height, AnsiStyle style) {
        for (int offset = 0; offset < height; offset++) fillRow(column, row + offset, width, style);
    }
}
