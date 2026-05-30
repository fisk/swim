package org.fisk.swim.debug;

import java.nio.file.Path;

public record DebugBreakpoint(Path path, int line, boolean verified, String description) {
    public String displayLabel() {
        String status = verified ? "●" : "○";
        String label = description == null || description.isBlank() ? "" : " " + description;
        return status + " " + path.getFileName() + ":" + line + label;
    }
}
