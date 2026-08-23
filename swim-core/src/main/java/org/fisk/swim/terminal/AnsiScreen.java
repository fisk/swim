package org.fisk.swim.terminal;

import java.util.Arrays;

/** POSIX terminal back-buffer that emits only changed cells as ANSI escape sequences. */
public final class AnsiScreen {
    private record Cell(char character, AnsiStyle style) { }
    private int width;
    private int height;
    private Cell[] current;
    private Cell[] displayed;

    public AnsiScreen(int width, int height) {
        resize(width, height);
    }

    public void resize(int width, int height) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Screen size must be positive");
        boolean firstAllocation = current == null;
        this.width = width;
        this.height = height;
        current = cells(width * height);
        // A live terminal retains whatever was drawn before a resize.  Invalidate
        // its previous image so the next flush repaints the complete new viewport.
        displayed = firstAllocation ? cells(width * height) : new Cell[width * height];
    }

    public void clear() { Arrays.fill(current, new Cell(' ', AnsiStyle.DEFAULT)); }

    public void set(int x, int y, char character, AnsiStyle style) {
        if (x < 0 || x >= width || y < 0 || y >= height) return;
        current[y * width + x] = new Cell(character, style == null ? AnsiStyle.DEFAULT : style);
    }

    /** Flushes changed cells, returning UTF-8-safe ANSI text for the caller's output stream. */
    public String flush() {
        var output = new StringBuilder();
        AnsiStyle style = null;
        for (int index = 0; index < current.length; index++) {
            if (current[index].equals(displayed[index])) continue;
            int x = index % width;
            int y = index / width;
            output.append("\u001b[").append(y + 1).append(';').append(x + 1).append('H');
            if (!current[index].style().equals(style)) {
                output.append(sgr(current[index].style()));
                style = current[index].style();
            }
            output.append(current[index].character());
            displayed[index] = current[index];
        }
        return output.toString();
    }

    private static Cell[] cells(int count) {
        var cells = new Cell[count];
        Arrays.fill(cells, new Cell(' ', AnsiStyle.DEFAULT));
        return cells;
    }

    private static String sgr(AnsiStyle style) {
        var result = new StringBuilder("\u001b[0");
        if (style.bold()) result.append(";1");
        if (style.underline()) result.append(";4");
        if (style.inverse()) result.append(";7");
        colour(result, 38, style.foreground());
        colour(result, 48, style.background());
        return result.append('m').toString();
    }

    private static void colour(StringBuilder output, int code, AnsiColour colour) {
        if (colour.defaultColour()) output.append(';').append(code == 38 ? 39 : 49);
        else output.append(';').append(code).append(";2;").append(colour.red()).append(';').append(colour.green()).append(';').append(colour.blue());
    }
}
