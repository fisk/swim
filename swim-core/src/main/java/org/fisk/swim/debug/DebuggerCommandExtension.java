package org.fisk.swim.debug;

/** Adds a project- or runtime-specific command to the debugger command prompt. */
public interface DebuggerCommandExtension {
    String id();
    String description();
    boolean handles(String command);
    String execute(String command, DebuggerCommandContext context) throws Exception;
}
