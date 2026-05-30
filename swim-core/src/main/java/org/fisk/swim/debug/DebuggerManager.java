package org.fisk.swim.debug;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.ui.Window;

public final class DebuggerManager {
    private static DebuggerSession _session;
    private static DebugSnapshot _snapshot = DebugSnapshot.empty("No debugger session");

    private DebuggerManager() {
    }

    public static DebugSnapshot snapshot() {
        return _snapshot;
    }

    public static boolean hasSession() {
        return _session != null;
    }

    public static void launch(String providerId, Path currentPath, List<String> args) throws Exception {
        closeCurrentSession();
        var registration = DebuggerProviderRegistry.find(providerId);
        if (registration == null) {
            throw new IllegalArgumentException("Unknown debugger provider: " + providerId);
        }
        _snapshot = DebugSnapshot.empty("Launching " + registration.provider().displayName());
        _session = registration.provider().launch(new DebugLaunchRequest(currentPath, List.copyOf(args)));
        _session.setListener(DebuggerManager::handleSnapshotChanged);
        handleSnapshotChanged(_session.snapshot());
    }

    public static void closeCurrentSession() throws Exception {
        if (_session == null) {
            _snapshot = DebugSnapshot.empty("No debugger session");
            return;
        }
        try {
            _session.close();
        } finally {
            _session = null;
            _snapshot = DebugSnapshot.empty("No debugger session");
            refreshUi();
        }
    }

    public static void resume() throws Exception {
        requireSession().resume();
    }

    public static void stepOver() throws Exception {
        requireSession().stepOver();
    }

    public static void stepInto() throws Exception {
        requireSession().stepInto();
    }

    public static void stepOut() throws Exception {
        requireSession().stepOut();
    }

    public static void stop() throws Exception {
        requireSession().stop();
    }

    public static void toggleBreakpointAtCursor() throws Exception {
        var window = Window.getInstance();
        if (window == null || window.getBufferContext() == null) {
            return;
        }
        Path path = window.getBufferContext().getBuffer().getPath();
        if (path == null) {
            throw new IllegalStateException("Current buffer has no file path");
        }
        int line = window.getBufferContext().getBuffer().getCursor().getPhysicalLine().getY() + 1;
        requireSession().toggleBreakpoint(new DebugSourceLocation(path, line, 1, null));
    }

    public static void selectThread(int threadIndex) throws Exception {
        requireSession().selectThread(threadIndex);
    }

    public static void selectFrame(int frameIndex) throws Exception {
        requireSession().selectFrame(frameIndex);
    }

    public static String providersSummary() {
        return DebuggerProviderRegistry.list().stream()
                .map(registration -> registration.providerId() + " (" + registration.provider().displayName() + ")")
                .reduce((left, right) -> left + ", " + right)
                .orElse("No debugger providers registered");
    }

    public static String usage(String providerId) {
        var registration = DebuggerProviderRegistry.find(providerId);
        return registration == null ? null : registration.provider().usage();
    }

    public static void launchFromCommand(String providerId, Path currentPath, String argument) throws Exception {
        List<String> args = argument == null || argument.isBlank()
                ? List.of()
                : Arrays.asList(argument.trim().split("\\s+"));
        launch(providerId, currentPath, args);
    }

    private static DebuggerSession requireSession() {
        if (_session == null) {
            throw new IllegalStateException("No active debugger session");
        }
        return _session;
    }

    private static void handleSnapshotChanged(DebugSnapshot snapshot) {
        _snapshot = snapshot == null ? DebugSnapshot.empty("No debugger session") : snapshot;
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            var window = Window.getInstance();
            if (window == null) {
                return;
            }
            if (_snapshot.currentLocation() != null && _snapshot.currentLocation().path() != null) {
                window.openBufferLocation(_snapshot.currentLocation().path(), _snapshot.currentLocation().line(),
                        Math.max(1, _snapshot.currentLocation().column()));
            }
            if (_snapshot.message() != null && !_snapshot.message().isBlank()) {
                window.getCommandView().setMessage(_snapshot.message());
            }
            window.refreshChromeState();
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
        }));
    }

    private static void refreshUi() {
        var window = Window.getInstance();
        if (window != null) {
            window.refreshChromeState();
            if (window.getRootView() != null) {
                window.getRootView().setNeedsRedraw();
            }
        }
    }
}
