package org.fisk.swim.session;

public record SwimServerTerminalSize(int rows, int columns) {
    public SwimServerTerminalSize {
        rows = Math.max(1, rows);
        columns = Math.max(1, columns);
    }
}
