package org.fisk.swim.lsp;

import java.util.Objects;

public record DiagnosticAction(String title, String detail, Runnable apply) {
    public DiagnosticAction {
        Objects.requireNonNull(title, "title");
        apply = apply == null ? () -> {
        } : apply;
        detail = detail == null ? "" : detail;
    }
}
