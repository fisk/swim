package org.fisk.swim.terminal;

/** Dimensions of a terminal viewport in character cells. */
public record TerminalDimensions(int columns, int rows) {
    public TerminalDimensions {
        if (columns < 1 || rows < 1) throw new IllegalArgumentException("Terminal dimensions must be positive");
    }
}
