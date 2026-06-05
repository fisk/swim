package org.fisk.swim.lsp;

public record DiagnosticCounts(int errors, int warnings) {
    public static final DiagnosticCounts EMPTY = new DiagnosticCounts(0, 0);

    public int total() {
        return errors + warnings;
    }

    public boolean isEmpty() {
        return total() == 0;
    }
}
