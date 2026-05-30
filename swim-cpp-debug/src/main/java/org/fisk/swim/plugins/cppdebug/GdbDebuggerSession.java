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

final class GdbDebuggerSession implements DebuggerSession {
    private final GdbMiCli _cli;
    private final Path _sourceRoot;
    private final Map<String, DebugBreakpoint> _breakpoints = new LinkedHashMap<>();
    private final Map<String, String> _breakpointIds = new LinkedHashMap<>();
    private DebugSnapshot _snapshot = DebugSnapshot.empty("Debugger loaded");
    private DebugSessionListener _listener;
    private boolean _started;
    private boolean _terminated;
    private int _selectedThreadIndex;
    private int _selectedFrameIndex;

    private GdbDebuggerSession(GdbMiCli cli, Path sourceRoot) {
        _cli = cli;
        _sourceRoot = sourceRoot;
    }

    static GdbDebuggerSession launch(Path executable, Path sourceRoot, List<String> args) throws Exception {
        return new GdbDebuggerSession(GdbMiCli.launch(executable, args), sourceRoot);
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
        updateFromStopChunk(_cli.execute(_started ? "-exec-continue" : "-exec-run"), "Running");
        _started = true;
    }

    @Override
    public void stepOver() throws Exception {
        updateFromStopChunk(_cli.execute("-exec-next"), "Stepping over");
    }

    @Override
    public void stepInto() throws Exception {
        updateFromStopChunk(_cli.execute("-exec-step"), "Stepping into");
    }

    @Override
    public void stepOut() throws Exception {
        updateFromStopChunk(_cli.execute("-exec-finish"), "Stepping out");
    }

    @Override
    public void stop() throws Exception {
        _cli.close();
        _terminated = true;
        _snapshot = DebugSnapshot.empty("Debugger terminated");
        notifyListener();
    }

    @Override
    public void toggleBreakpoint(DebugSourceLocation location) throws Exception {
        String key = breakpointKey(location.path(), location.line());
        if (_breakpoints.containsKey(key)) {
            _breakpoints.remove(key);
            String breakpointId = _breakpointIds.remove(key);
            if (breakpointId != null) {
                _cli.execute("-break-delete " + breakpointId);
            }
        } else {
            DebugBreakpoint breakpoint = new DebugBreakpoint(location.path().toAbsolutePath().normalize(), location.line(), true, "");
            _breakpoints.put(key, breakpoint);
            String response = _cli.execute("-break-insert " + location.path() + ":" + location.line());
            var parsed = GdbMiParser.parseChunk(response);
            if (!parsed.resultRecords().isEmpty()) {
                GdbMiValue.Tuple result = parsed.resultRecords().getFirst();
                var bkptTuple = unwrapTuple(result.get("bkpt"));
                if (bkptTuple != null) {
                    _breakpointIds.put(key, stringValue(bkptTuple.get("number")));
                }
            }
        }
        refreshSnapshot("Breakpoints updated");
    }

    @Override
    public void selectThread(int threadIndex) throws Exception {
        _selectedThreadIndex = Math.max(0, threadIndex);
        refreshSnapshot(messageText(_snapshot));
    }

    @Override
    public void selectFrame(int frameIndex) throws Exception {
        _selectedFrameIndex = Math.max(0, frameIndex);
        refreshSnapshot(messageText(_snapshot));
    }

    @Override
    public void close() throws Exception {
        stop();
    }

    private void updateFromStopChunk(String chunk, String message) throws Exception {
        if (chunk.contains("exited-normally") || chunk.contains("exit-code") || chunk.contains("error,msg=")) {
            _terminated = true;
            _snapshot = DebugSnapshot.empty(chunk.contains("error,msg=") ? extractError(chunk) : "Debugger terminated");
            notifyListener();
            return;
        }
        refreshSnapshot(message);
    }

    private void refreshSnapshot(String message) throws Exception {
        if (!_started || _terminated) {
            _snapshot = DebugSnapshot.empty(_terminated ? "Debugger terminated" : message);
            notifyListener();
            return;
        }
        var threadInfo = GdbMiParser.parseChunk(_cli.execute("-thread-info"));
        List<DebugThreadInfo> threads = parseThreads(threadInfo);
        if (!threads.isEmpty()) {
            _selectedThreadIndex = Math.max(0, Math.min(_selectedThreadIndex, threads.size() - 1));
            _cli.execute("-thread-select " + threads.get(_selectedThreadIndex).id());
        }
        var frameInfo = GdbMiParser.parseChunk(_cli.execute("-stack-list-frames"));
        List<DebugFrameInfo> frames = parseFrames(frameInfo);
        if (!frames.isEmpty()) {
            _selectedFrameIndex = Math.max(0, Math.min(_selectedFrameIndex, frames.size() - 1));
            _cli.execute("-stack-select-frame " + _selectedFrameIndex);
        }
        var variableInfo = GdbMiParser.parseChunk(_cli.execute("-stack-list-variables --simple-values"));
        List<DebugVariable> variables = parseVariables(variableInfo);
        DebugSourceLocation location = frames.isEmpty() ? null : frames.get(_selectedFrameIndex).location();
        _snapshot = new DebugSnapshot(
                "C/C++ Debugger",
                location == null ? DebugState.RUNNING : DebugState.STOPPED,
                message,
                location,
                List.copyOf(_breakpoints.values()),
                List.copyOf(threads),
                _selectedThreadIndex,
                List.copyOf(frames),
                _selectedFrameIndex,
                List.copyOf(variables));
        notifyListener();
    }

    private List<DebugThreadInfo> parseThreads(GdbMiParser.ParsedChunk parsed) {
        var result = new ArrayList<DebugThreadInfo>();
        if (parsed.resultRecords().isEmpty()) {
            return result;
        }
        var root = parsed.resultRecords().getFirst();
        var threadsValue = root.get("threads");
        if (!(threadsValue instanceof GdbMiValue.Array array)) {
            return result;
        }
        for (GdbMiValue item : array.items()) {
            GdbMiValue.Tuple tuple = unwrapTuple(item);
            if (tuple == null) {
                continue;
            }
            String id = stringValue(tuple.get("id"));
            String targetId = stringValue(tuple.get("target-id"));
            String state = stringValue(tuple.get("state"));
            result.add(new DebugThreadInfo(id, targetId == null || targetId.isBlank() ? "thread " + id : targetId,
                    "stopped".equals(state)));
        }
        return result;
    }

    private List<DebugFrameInfo> parseFrames(GdbMiParser.ParsedChunk parsed) {
        var result = new ArrayList<DebugFrameInfo>();
        if (parsed.resultRecords().isEmpty()) {
            return result;
        }
        var root = parsed.resultRecords().getFirst();
        var stack = root.get("stack");
        if (!(stack instanceof GdbMiValue.Array array)) {
            return result;
        }
        for (GdbMiValue item : array.items()) {
            GdbMiValue.Tuple frameWrapper = unwrapTuple(item);
            if (frameWrapper == null) {
                continue;
            }
            GdbMiValue.Tuple frame = unwrapTuple(frameWrapper.get("frame"));
            if (frame == null) {
                frame = frameWrapper;
            }
            String level = stringValue(frame.get("level"));
            String func = stringValue(frame.get("func"));
            String file = stringValue(frame.get("fullname"));
            if (file == null) {
                file = stringValue(frame.get("file"));
            }
            DebugSourceLocation location = null;
            if (file != null) {
                Path path = Path.of(file);
                if (!path.isAbsolute() && _sourceRoot != null) {
                    path = _sourceRoot.resolve(path).normalize();
                }
                int line = parseInt(stringValue(frame.get("line")), 1);
                location = new DebugSourceLocation(path, line, 1, func);
            }
            result.add(new DebugFrameInfo(level, func == null ? "frame " + level : func + "()", location));
        }
        return result;
    }

    private List<DebugVariable> parseVariables(GdbMiParser.ParsedChunk parsed) {
        var result = new ArrayList<DebugVariable>();
        if (parsed.resultRecords().isEmpty()) {
            return result;
        }
        var root = parsed.resultRecords().getFirst();
        var variablesValue = root.get("variables");
        if (!(variablesValue instanceof GdbMiValue.Array array)) {
            return result;
        }
        for (GdbMiValue item : array.items()) {
            GdbMiValue.Tuple variable = unwrapTuple(item);
            if (variable == null) {
                continue;
            }
            result.add(new DebugVariable(
                    stringValue(variable.get("name")),
                    stringValue(variable.get("type")),
                    stringValue(variable.get("value"))));
        }
        return result;
    }

    private static GdbMiValue.Tuple unwrapTuple(GdbMiValue value) {
        if (value instanceof GdbMiValue.Tuple tuple) {
            if (tuple.fields().size() == 1) {
                var nested = tuple.fields().values().iterator().next();
                if (nested instanceof GdbMiValue.Tuple nestedTuple) {
                    return nestedTuple;
                }
            }
            return tuple;
        }
        return null;
    }

    private static String stringValue(GdbMiValue value) {
        if (value instanceof GdbMiValue.Const constant) {
            return constant.value();
        }
        return null;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String breakpointKey(Path path, int line) {
        return path.toAbsolutePath().normalize() + ":" + line;
    }

    private String messageText(DebugSnapshot snapshot) {
        return snapshot.message() == null ? "" : snapshot.message();
    }

    private String extractError(String chunk) {
        int index = chunk.indexOf("msg=\"");
        if (index < 0) {
            return "Debugger failed";
        }
        int end = chunk.indexOf('"', index + 5);
        return end < 0 ? "Debugger failed" : chunk.substring(index + 5, end);
    }

    private void notifyListener() {
        if (_listener != null) {
            _listener.onSnapshotChanged(_snapshot);
        }
    }
}
