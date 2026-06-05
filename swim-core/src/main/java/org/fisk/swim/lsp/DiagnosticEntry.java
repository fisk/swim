package org.fisk.swim.lsp;

import java.nio.file.Path;
import java.util.Objects;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;

public record DiagnosticEntry(String providerId, String uri, Path path, Diagnostic diagnostic) {
    public DiagnosticEntry {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(diagnostic, "diagnostic");
        path = path == null ? null : path.toAbsolutePath().normalize();
    }

    public int startLine() {
        return diagnostic.getRange() == null || diagnostic.getRange().getStart() == null
                ? 0
                : Math.max(0, diagnostic.getRange().getStart().getLine());
    }

    public int startCharacter() {
        return diagnostic.getRange() == null || diagnostic.getRange().getStart() == null
                ? 0
                : Math.max(0, diagnostic.getRange().getStart().getCharacter());
    }

    public int endLine() {
        return diagnostic.getRange() == null || diagnostic.getRange().getEnd() == null
                ? startLine()
                : Math.max(startLine(), diagnostic.getRange().getEnd().getLine());
    }

    public int endCharacter() {
        return diagnostic.getRange() == null || diagnostic.getRange().getEnd() == null
                ? startCharacter()
                : Math.max(0, diagnostic.getRange().getEnd().getCharacter());
    }

    public DiagnosticSeverity severity() {
        return diagnostic.getSeverity();
    }

    public boolean isError() {
        return DiagnosticSeverity.Error.equals(severity());
    }

    public boolean isWarning() {
        return DiagnosticSeverity.Warning.equals(severity());
    }

    public boolean isCounted() {
        return isError() || isWarning();
    }

    public boolean coversLogicalLine(int logicalLine) {
        return logicalLine >= startLine() && logicalLine <= endLine();
    }

    public String message() {
        return diagnostic.getMessage() == null ? "" : diagnostic.getMessage();
    }

    public String sourceLabel() {
        return diagnostic.getSource() == null ? "" : diagnostic.getSource();
    }

    public String codeLabel() {
        if (diagnostic.getCode() == null) {
            return "";
        }
        if (diagnostic.getCode().isLeft()) {
            return diagnostic.getCode().getLeft();
        }
        return Integer.toString(diagnostic.getCode().getRight());
    }

    public String detail() {
        StringBuilder builder = new StringBuilder();
        if (!sourceLabel().isBlank()) {
            builder.append(sourceLabel());
        }
        if (!codeLabel().isBlank()) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append("[").append(codeLabel()).append("]");
        }
        if (!message().isBlank()) {
            if (builder.length() > 0) {
                builder.append(": ");
            }
            builder.append(message().replace('\n', ' '));
        }
        return builder.toString();
    }
}
