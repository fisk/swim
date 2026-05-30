package org.fisk.swim.debug;

import java.util.List;

public record DebugSnapshot(
        String title,
        DebugState state,
        String message,
        DebugSourceLocation currentLocation,
        List<DebugBreakpoint> breakpoints,
        List<DebugThreadInfo> threads,
        int selectedThreadIndex,
        List<DebugFrameInfo> frames,
        int selectedFrameIndex,
        List<DebugVariable> variables) {
    public static DebugSnapshot empty(String message) {
        return new DebugSnapshot(
                "Debugger",
                DebugState.IDLE,
                message,
                null,
                List.of(),
                List.of(),
                -1,
                List.of(),
                -1,
                List.of());
    }
}
