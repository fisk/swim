package org.fisk.swim.plugins.javadebug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.fisk.swim.debug.DebugBreakpoint;
import org.fisk.swim.debug.DebugFrameInfo;
import org.fisk.swim.debug.DebugSnapshot;
import org.fisk.swim.debug.DebugSourceLocation;
import org.fisk.swim.debug.DebugState;
import org.fisk.swim.debug.DebugThreadInfo;
import org.fisk.swim.debug.DebugVariable;
import org.fisk.swim.debug.DebuggerSession;
import org.fisk.swim.debug.DebugSessionListener;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.event.VMStartEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;

final class JavaDebuggerSession implements DebuggerSession {
    private final VirtualMachine _vm;
    private final Path _sourceRoot;
    private final Map<String, DebugBreakpoint> _desiredBreakpoints = new LinkedHashMap<>();
    private final Map<String, List<BreakpointRequest>> _installedBreakpoints = new LinkedHashMap<>();
    private final AtomicBoolean _closed = new AtomicBoolean();
    private final Thread _eventThread;
    private volatile DebugSnapshot _snapshot = DebugSnapshot.empty("Launching Java debugger");
    private volatile DebugSessionListener _listener;
    private volatile String _selectedThreadId;
    private volatile int _selectedFrameIndex;
    private volatile ThreadReference _currentThread;

    private JavaDebuggerSession(VirtualMachine vm, Path sourceRoot) {
        _vm = vm;
        _sourceRoot = sourceRoot;
        _eventThread = Thread.ofVirtual().name("swim-java-debug").start(this::runEvents);
    }

    static JavaDebuggerSession launch(String mainClass, Path classPath, Path sourceRoot, List<String> programArgs)
            throws Exception {
        LaunchingConnector connector = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("main").setValue(mainClass + (programArgs.isEmpty() ? "" : " " + String.join(" ", programArgs)));
        arguments.get("options").setValue("-classpath " + classPath);
        arguments.get("suspend").setValue("true");
        VirtualMachine vm = connector.launch(arguments);
        var session = new JavaDebuggerSession(vm, sourceRoot);
        session.installInitialRequests();
        session.captureInitialPause();
        return session;
    }

    @Override
    public String providerId() {
        return "java";
    }

    @Override
    public String displayName() {
        return "Java";
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
    public void resume() {
        _snapshot = withState(DebugState.RUNNING, "Running");
        notifyListener();
        _vm.resume();
    }

    @Override
    public void stepOver() throws Exception {
        startStep(StepRequest.STEP_OVER, "Stepping over");
    }

    @Override
    public void stepInto() throws Exception {
        startStep(StepRequest.STEP_INTO, "Stepping into");
    }

    @Override
    public void stepOut() throws Exception {
        startStep(StepRequest.STEP_OUT, "Stepping out");
    }

    @Override
    public void stop() {
        close();
    }

    @Override
    public void toggleBreakpoint(DebugSourceLocation location) throws Exception {
        String key = breakpointKey(location.path(), location.line());
        if (_desiredBreakpoints.containsKey(key)) {
            _desiredBreakpoints.remove(key);
            var requests = _installedBreakpoints.remove(key);
            if (requests != null) {
                _vm.eventRequestManager().deleteEventRequests(requests);
            }
        } else {
            var breakpoint = new DebugBreakpoint(location.path().toAbsolutePath().normalize(), location.line(), false, "");
            _desiredBreakpoints.put(key, breakpoint);
            for (ReferenceType refType : _vm.allClasses()) {
                installBreakpointForReference(key, breakpoint, refType);
            }
        }
        refreshSnapshot(messageText(_snapshot));
    }

    @Override
    public void selectThread(int threadIndex) throws Exception {
        var threads = _snapshot.threads();
        if (threadIndex < 0 || threadIndex >= threads.size()) {
            return;
        }
        _selectedThreadId = threads.get(threadIndex).id();
        ThreadReference selected = findThread(_selectedThreadId);
        if (selected != null) {
            _currentThread = selected;
            _selectedFrameIndex = 0;
        }
        refreshSnapshot(messageText(_snapshot));
    }

    @Override
    public void selectFrame(int frameIndex) throws Exception {
        _selectedFrameIndex = Math.max(0, frameIndex);
        refreshSnapshot(messageText(_snapshot));
    }

    @Override
    public void close() {
        if (_closed.compareAndSet(false, true)) {
            try {
                _vm.dispose();
            } catch (Throwable e) {
            }
            _snapshot = DebugSnapshot.empty("Debugger stopped");
            notifyListener();
        }
    }

    private void installInitialRequests() {
        EventRequestManager manager = _vm.eventRequestManager();
        ClassPrepareRequest classPrepareRequest = manager.createClassPrepareRequest();
        classPrepareRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        classPrepareRequest.enable();
    }

    private void captureInitialPause() {
        for (ThreadReference thread : _vm.allThreads()) {
            if (thread.isSuspended() && "main".equals(thread.name())) {
                handleStopped(thread, "Debugger started");
                return;
            }
        }
        for (ThreadReference thread : _vm.allThreads()) {
            if (thread.isSuspended()) {
                handleStopped(thread, "Debugger started");
                return;
            }
        }
        _snapshot = withState(DebugState.STOPPED, "Debugger started");
        notifyListener();
    }

    private void runEvents() {
        try {
            while (!_closed.get()) {
                EventSet set = _vm.eventQueue().remove();
                boolean resume = true;
                for (Event event : set) {
                    if (event instanceof VMStartEvent startEvent) {
                        handleStopped(startEvent.thread(), "Debugger started");
                        resume = false;
                    } else if (event instanceof BreakpointEvent breakpointEvent) {
                        handleStopped(breakpointEvent.thread(), "Breakpoint hit");
                        clearStepRequests();
                        resume = false;
                    } else if (event instanceof StepEvent stepEvent) {
                        handleStopped(stepEvent.thread(), "Stopped");
                        clearStepRequests();
                        resume = false;
                    } else if (event instanceof ClassPrepareEvent classPrepareEvent) {
                        installBreakpointsForReference(classPrepareEvent.referenceType());
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) {
                        _snapshot = DebugSnapshot.empty("Debugger terminated");
                        notifyListener();
                        _closed.set(true);
                        return;
                    }
                }
                if (resume) {
                    set.resume();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            _snapshot = DebugSnapshot.empty(e.getMessage() == null ? "Debugger failed" : e.getMessage());
            notifyListener();
        }
    }

    private void startStep(int depth, String message) throws Exception {
        ThreadReference thread = currentThread();
        if (thread == null) {
            throw new IllegalStateException("No suspended thread selected");
        }
        clearStepRequests();
        StepRequest stepRequest = _vm.eventRequestManager()
                .createStepRequest(thread, StepRequest.STEP_LINE, depth);
        stepRequest.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        stepRequest.addCountFilter(1);
        stepRequest.enable();
        _snapshot = withState(DebugState.RUNNING, message);
        notifyListener();
        _vm.resume();
    }

    private void clearStepRequests() {
        _vm.eventRequestManager().deleteEventRequests(new ArrayList<>(_vm.eventRequestManager().stepRequests()));
    }

    private void handleStopped(ThreadReference thread, String message) {
        _currentThread = thread;
        _selectedThreadId = Long.toString(thread.uniqueID());
        _selectedFrameIndex = 0;
        refreshSnapshot(message);
    }

    private void refreshSnapshot(String message) {
        List<ThreadReference> threads = _vm.allThreads();
        var threadInfos = new ArrayList<DebugThreadInfo>();
        int selectedThreadIndex = -1;
        for (int i = 0; i < threads.size(); i++) {
            ThreadReference thread = threads.get(i);
            boolean suspended = thread.isSuspended();
            String id = Long.toString(thread.uniqueID());
            threadInfos.add(new DebugThreadInfo(id, thread.name(), suspended));
            if (id.equals(_selectedThreadId)) {
                selectedThreadIndex = i;
            }
        }
        ThreadReference selectedThread = selectedThreadIndex >= 0 ? threads.get(selectedThreadIndex) : _currentThread;
        var frames = new ArrayList<DebugFrameInfo>();
        var variables = new ArrayList<DebugVariable>();
        DebugSourceLocation currentLocation = null;
        if (selectedThread != null && selectedThread.isSuspended()) {
            try {
                List<StackFrame> stackFrames = selectedThread.frames();
                for (int i = 0; i < stackFrames.size(); i++) {
                    StackFrame frame = stackFrames.get(i);
                    DebugSourceLocation location = sourceLocation(frame.location());
                    frames.add(new DebugFrameInfo(Integer.toString(i), frame.location().method().name() + "()",
                            location));
                }
                if (!frames.isEmpty()) {
                    _selectedFrameIndex = Math.max(0, Math.min(_selectedFrameIndex, frames.size() - 1));
                    currentLocation = frames.get(_selectedFrameIndex).location();
                    StackFrame frame = stackFrames.get(_selectedFrameIndex);
                    try {
                        var values = frame.getValues(frame.visibleVariables());
                        frame.visibleVariables().forEach(local -> {
                            Object value = values.get(local);
                            variables.add(new DebugVariable(local.name(), local.typeName(), value == null ? "null" : value.toString()));
                        });
                    } catch (AbsentInformationException e) {
                    }
                }
            } catch (Exception e) {
            }
        }
        DebugState state = selectedThread != null && selectedThread.isSuspended() ? DebugState.STOPPED : DebugState.RUNNING;
        _snapshot = new DebugSnapshot(
                "Java Debugger",
                state,
                message,
                currentLocation,
                new ArrayList<>(_desiredBreakpoints.values()),
                List.copyOf(threadInfos),
                selectedThreadIndex,
                List.copyOf(frames),
                _selectedFrameIndex,
                List.copyOf(variables));
        notifyListener();
    }

    private void installBreakpointsForReference(ReferenceType referenceType) {
        for (var entry : _desiredBreakpoints.entrySet()) {
            installBreakpointForReference(entry.getKey(), entry.getValue(), referenceType);
        }
    }

    private void installBreakpointForReference(String key, DebugBreakpoint breakpoint, ReferenceType referenceType) {
        try {
            if (!matchesReference(breakpoint.path(), referenceType)) {
                return;
            }
            List<Location> locations = referenceType.locationsOfLine(breakpoint.line());
            if (locations.isEmpty()) {
                return;
            }
            BreakpointRequest request = _vm.eventRequestManager().createBreakpointRequest(locations.getFirst());
            request.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            request.enable();
            _installedBreakpoints.computeIfAbsent(key, ignored -> new ArrayList<>()).add(request);
        } catch (AbsentInformationException e) {
        }
    }

    private boolean matchesReference(Path sourcePath, ReferenceType referenceType) throws AbsentInformationException {
        String sourceName = referenceType.sourceName();
        return sourceName.equals(sourcePath.getFileName().toString());
    }

    private DebugSourceLocation sourceLocation(Location location) {
        try {
            Path path = _sourceRoot == null ? Path.of(location.sourceName()) : _sourceRoot.resolve(location.sourcePath()).normalize();
            return new DebugSourceLocation(path, location.lineNumber(), 1, location.method().name());
        } catch (AbsentInformationException e) {
            return null;
        }
    }

    private ThreadReference currentThread() {
        if (_currentThread != null && _currentThread.isSuspended()) {
            return _currentThread;
        }
        if (_selectedThreadId != null) {
            ThreadReference selected = findThread(_selectedThreadId);
            if (selected != null && selected.isSuspended()) {
                _currentThread = selected;
                return selected;
            }
        }
        return null;
    }

    private ThreadReference findThread(String id) {
        for (ThreadReference thread : _vm.allThreads()) {
            if (Long.toString(thread.uniqueID()).equals(id)) {
                return thread;
            }
        }
        return null;
    }

    private DebugSnapshot withState(DebugState state, String message) {
        return new DebugSnapshot(
                _snapshot.title(),
                state,
                message,
                _snapshot.currentLocation(),
                _snapshot.breakpoints(),
                _snapshot.threads(),
                _snapshot.selectedThreadIndex(),
                _snapshot.frames(),
                _snapshot.selectedFrameIndex(),
                _snapshot.variables());
    }

    private String messageText(DebugSnapshot snapshot) {
        return snapshot.message() == null ? "" : snapshot.message();
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
