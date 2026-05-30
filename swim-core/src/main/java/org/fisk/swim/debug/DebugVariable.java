package org.fisk.swim.debug;

public record DebugVariable(String name, String type, String value) {
    public String displayLabel() {
        String typeLabel = type == null || type.isBlank() ? "" : " : " + type;
        return name + typeLabel + " = " + value;
    }
}
