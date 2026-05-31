package org.fisk.swim.terminal;

public record TerminalCell(char character, TerminalStyle style) {
    public static final TerminalCell BLANK = new TerminalCell(' ', TerminalStyle.DEFAULT);
}
