package org.fisk.swim.debug;

public interface DebuggerProvider {
    String id();
    String displayName();
    String usage();
    DebuggerSession launch(DebugLaunchRequest request) throws Exception;
}
