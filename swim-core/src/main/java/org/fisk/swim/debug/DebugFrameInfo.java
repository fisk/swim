package org.fisk.swim.debug;

public record DebugFrameInfo(String id, String label, DebugSourceLocation location) {
    public String displayLabel() {
        if (location == null || location.path() == null) {
            return label;
        }
        return label + " — " + location.path().getFileName() + ":" + location.line();
    }
}
