package org.fisk.swim.terminal;

public enum TerminalCursorShape {
    DEFAULT(0),
    BLOCK(2),
    UNDERLINE(4),
    BAR(6);

    private final int _decscusrCode;

    TerminalCursorShape(int decscusrCode) {
        _decscusrCode = decscusrCode;
    }

    public String escapeSequence() {
        return "\u001b[" + _decscusrCode + " q";
    }
}
