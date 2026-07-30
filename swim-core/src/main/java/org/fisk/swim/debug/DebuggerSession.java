package org.fisk.swim.debug;

public interface DebuggerSession extends AutoCloseable {
    String providerId();
    String displayName();
    DebugSnapshot snapshot();
    void setListener(DebugSessionListener listener);
    void resume() throws Exception;
    void stepOver() throws Exception;
    void stepInto() throws Exception;
    void stepOut() throws Exception;
    void stop() throws Exception;
    void toggleBreakpoint(DebugSourceLocation location) throws Exception;
    void selectThread(int threadIndex) throws Exception;
    void selectFrame(int frameIndex) throws Exception;

    /** Sends a backend-native debugger command, such as a GDB console command. */
    default String executeCommand(String command) throws Exception {
        throw new UnsupportedOperationException("Debugger commands are not supported by " + displayName());
    }

    @Override
    void close() throws Exception;
}
