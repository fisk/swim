package org.fisk.swim.debug;

public record DebugThreadInfo(String id, String name, boolean suspended) {
    public String displayLabel() {
        return (suspended ? "⏸ " : "▶ ") + name;
    }
}
