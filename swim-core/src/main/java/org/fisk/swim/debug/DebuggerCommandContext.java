package org.fisk.swim.debug;

/** Context made available to debugger command extensions. */
public interface DebuggerCommandContext {
    DebugSnapshot snapshot();

    /** Executes a command understood by the active debugger backend. */
    String executeBackendCommand(String command) throws Exception;
}
