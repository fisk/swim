package org.fisk.swim.plugins.cppdebug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.fisk.swim.debug.DebugBreakpoint;
import org.fisk.swim.debug.DebugFrameInfo;
import org.fisk.swim.debug.DebugSnapshot;
import org.fisk.swim.debug.DebugSourceLocation;
import org.fisk.swim.debug.DebugState;
import org.fisk.swim.debug.DebugThreadInfo;
import org.fisk.swim.debug.DebugVariable;
import org.fisk.swim.debug.DebuggerSession;
import org.fisk.swim.debug.DebugSessionListener;

final class CppDebuggerSession implements DebuggerSession {
    private final LldbCli _cli;
    private final Path _sourceRoot;
    private final Map<String, DebugBreakpoint> _desiredBreakpoints = new LinkedHashMap<>();
    private final Map<String, String> _lldbBreakpointIds = new LinkedHashMap<>();
    private DebugSnapshot _snapshot = DebugSnapshot.empty("Debugger loaded");
    private DebugSessionListener _listener;
    private boolean _started;
    private boolean _terminated;
    private int _selectedThreadIndex;
    private int _selectedFrameIndex;

    private CppDebuggerSession(LldbCli cli, Path sourceRoot) {
        _cli = cli;
        _sourceRoot = sourceRoot;
    }

    static CppDebuggerSession launch(Path executable, Path sourceRoot, List<String> args) throws Exception {
        return new CppDebuggerSession(LldbCli.launch(executable, args), sourceRoot);
    }

    @Override
    public String providerId() {
        return "cpp";
    }

    @Override
    public String displayName() {
        return "C/C++";
    }

    @Override
    public DebugSnapshot snapshot() {
        return _snapshot;
    }

    @Override
    public void setListener(DebugSessionListener listener) {
        _listener = listener;
    }

    @Override
    public void resume() throws Exception {
        String response = _cli.execute(_started ? "continue" : "run");
        _started = true;
        updateFromStopResponse(response, "Running");
    }

    @Override
    public void stepOver() throws Exception {
        updateFromStopResponse(_cli.execute("next"), "Stepping over");
    }

    @Override
    public void stepInto() throws Exception {
        updateFromStopResponse(_cli.execute("step"), "Stepping into");
    }

    @Override
    public void stepOut() throws Exception {
        updateFromStopResponse(_cli.execute("finish"), "Stepping out");
    }

    @Override
    public void stop() throws Exception {
        if (_started && !_terminated) {
            _cli.execute("kill");
        }
        _terminated = true;
        _snapshot = DebugSnapshot.empty("Debugger terminated");
        notifyListener();
    }

    @Override
    public void toggleBreakpoint(DebugSourceLocation location) throws Exception {
        String key = breakpointKey(location.path(), location.line());
        if (_desiredBreakpoints.containsKey(key)) {
            _desiredBreakpoints.remove(key);
            String breakpointId = _lldbBreakpointIds.remove(key);
            if (breakpointId != null) {
                _cli.execute("breakpoint delete " + breakpointId);
            }
        } else {
            DebugBreakpoint breakpoint = new DebugBreakpoint(location.path().toAbsolutePath().normalize(), location.line(), true, "");
            _desiredBreakpoints.put(key, breakpoint);
            String response = _cli.execute("breakpoint set --file " + location.path() + " --line " + location.line());
            String breakpointId = LldbResponseParser.breakpointId(response);
            if (breakpointId != null) {
                _lldbBreakpointIds.put(key, breakpointId);
            }
        }
        refreshSnapshot("Breakpoints updated");
    }

    @Override
    public void selectThread(int threadIndex) throws Exception {
        _selectedThreadIndex = Math.max(0, threadIndex);
        refreshSnapshot("Thread selected");
    }

    @Override
    public void selectFrame(int frameIndex) throws Exception {
        _selectedFrameIndex = Math.max(0, frameIndex);
        refreshSnapshot("Frame selected");
    }

    @Override
    public void close() throws Exception {
        _terminated = true;
        _cli.close();
    }

    private void updateFromStopResponse(String response, String fallbackMessage) throws Exception {
        if (LldbResponseParser.terminated(response)) {
            _terminated = true;
            _snapshot = DebugSnapshot.empty("Debugger terminated");
            notifyListener();
            return;
        }
        refreshSnapshot(response.contains("breakpoint") || response.contains("stopped") ? "Stopped" : fallbackMessage);
    }

    private void refreshSnapshot(String message) throws Exception {
        if (!_started || _terminated) {
            _snapshot = new DebugSnapshot(
                    "C/C++ Debugger",
                    _terminated ? DebugState.TERMINATED : DebugState.IDLE,
                    _terminated ? "Debugger terminated" : message,
                    null,
                    List.copyOf(_desiredBreakpoints.values()),
                    List.of(),
                    -1,
                    List.of(),
                    -1,
                    List.of());
            notifyListener();
            return;
        }
        String threadList = _cli.execute("thread list");
        var threads = LldbResponseParser.threads(threadList);
        if (!_terminated && !_started) {
            threads = List.of();
        }
        if (!threads.isEmpty()) {
            _selectedThreadIndex = Math.max(0, Math.min(_selectedThreadIndex, threads.size() - 1));
            _cli.execute("thread select " + (_selectedThreadIndex + 1));
        }
        String backtrace = _cli.execute("bt");
        var frames = LldbResponseParser.frames(backtrace, _sourceRoot);
        if (!frames.isEmpty()) {
            _selectedFrameIndex = Math.max(0, Math.min(_selectedFrameIndex, frames.size() - 1));
            _cli.execute("frame select " + _selectedFrameIndex);
        }
        String variablesOutput = _cli.execute("frame variable");
        var variables = LldbResponseParser.variables(variablesOutput);
        DebugSourceLocation location = frames.isEmpty() ? LldbResponseParser.sourceLocation(threadList, _sourceRoot)
                : frames.get(_selectedFrameIndex).location();
        _snapshot = new DebugSnapshot(
                "C/C++ Debugger",
                location == null ? DebugState.RUNNING : DebugState.STOPPED,
                message,
                location,
                List.copyOf(_desiredBreakpoints.values()),
                List.copyOf(threads),
                _selectedThreadIndex,
                List.copyOf(frames),
                _selectedFrameIndex,
                List.copyOf(variables));
        notifyListener();
    }

    private void notifyListener() {
        if (_listener != null) {
            _listener.onSnapshotChanged(_snapshot);
        }
    }

    private static String breakpointKey(Path path, int line) {
        return path.toAbsolutePath().normalize() + ":" + line;
    }
}
