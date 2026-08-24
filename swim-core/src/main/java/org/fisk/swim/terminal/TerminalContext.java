package org.fisk.swim.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.fisk.swim.event.IOThread;
import org.fisk.swim.session.SwimServerSessions;
import org.fisk.swim.session.SwimServerTerminalSize;

/** Application-wide owner of SWIM's terminal transport. */
public class TerminalContext {
    public static final org.fisk.swim.event.KeyType BRACKETED_PASTE_START_KEY = org.fisk.swim.event.KeyType.F18;
    public static final org.fisk.swim.event.KeyType BRACKETED_PASTE_END_KEY = org.fisk.swim.event.KeyType.F19;
    static final String LAST_ROWS_PROPERTY = "swim.terminal.last_rows";
    static final String LAST_COLS_PROPERTY = "swim.terminal.last_cols";
    static final String FREEZE_SIZE_PROPERTY = "swim.terminal.freeze_size";

    private static volatile TerminalContext _instance;
    private final TerminalBackend _backend;
    private boolean _closed;
    private boolean _cursorVisible;
    private TerminalCursorShape _cursorShape = TerminalCursorShape.BLOCK;

    public static TerminalContext getInstance() {
        TerminalContext instance = _instance;
        if (instance != null) return instance;
        synchronized (TerminalContext.class) {
            instance = _instance;
            if (instance == null) _instance = instance = new TerminalContext();
            return instance;
        }
    }

    public static boolean isInitialized() { return _instance != null; }
    public static boolean isTerminalSizeFrozen() { return Boolean.getBoolean(FREEZE_SIZE_PROPERTY); }

    public static void shutdownInstance() {
        TerminalContext instance = _instance;
        if (instance == null) return;
        _instance = null;
        instance.shutdown();
    }

    public static void prepareForReloadRestart() {
        TerminalContext instance = _instance;
        if (instance != null) rememberTerminalSize(instance.getTerminalDimensions());
    }

    public TerminalContext() { this(createNativeBackend()); }

    /** Test seam; production always constructs the native ANSI backend above. */
    TerminalContext(TerminalBackend backend) { _backend = Objects.requireNonNull(backend, "backend"); }

    private static AnsiTerminalBackend createNativeBackend() {
        var backend = new AnsiTerminalBackend(System.in, System.out, TerminalContext::queryTerminalDimensionsFromTty);
        try {
            backend.start();
            return backend;
        } catch (IOException e) {
            throw new IllegalStateException("Can't start ANSI terminal backend", e);
        }
    }

    private synchronized void shutdown() {
        if (_closed) return;
        _closed = true;
        try { _backend.stop(); } catch (IOException ignored) { }
    }

    public TerminalGraphics getTerminalGraphics() { return _backend.graphics(); }
    public TerminalDimensions getTerminalDimensions() { return _backend.dimensions(); }
    public TerminalDimensions resizeIfNeeded() { return isTerminalSizeFrozen() ? null : _backend.resizeIfNeeded(); }
    public void clear() { _backend.clear(); }
    public void setCursorPosition(int column, int row) { _backend.setCursorPosition(column, row); }
    public void setCursorVisible(boolean visible) {
        if (visible == _cursorVisible) return;
        _cursorVisible = visible;
        _backend.setCursorVisible(visible);
    }
    public void refresh(boolean complete) throws IOException { _backend.refresh(); }
    public IOThread newInputThread() {
        return new IOThread(() -> {
            try { return _backend.pollInput(); }
            catch (IOException e) { return null; }
        });
    }

    public void setCursorShape(TerminalCursorShape shape) {
        TerminalCursorShape next = shape == null ? TerminalCursorShape.BLOCK : shape;
        if (next == _cursorShape) return;
        _cursorShape = next;
        _backend.setCursorShape(next);
    }

    static TerminalDimensions parseSttySize(String output) {
        if (output == null || output.isBlank()) return null;
        String[] parts = output.trim().split("\\s+");
        if (parts.length != 2) return null;
        try {
            int rows = Integer.parseInt(parts[0]);
            int columns = Integer.parseInt(parts[1]);
            return rows > 0 && columns > 0 ? new TerminalDimensions(columns, rows) : null;
        } catch (NumberFormatException e) { return null; }
    }

    static TerminalDimensions queryTerminalDimensionsFromTty() {
        TerminalDimensions frozen = isTerminalSizeFrozen() ? queryRememberedSize() : null;
        if (frozen != null) return frozen;
        TerminalDimensions size = queryServerTerminalSize();
        if (size == null) size = queryConfiguredTtySize();
        if (size == null) size = querySttySize();
        if (size == null) size = queryRememberedSize();
        if (size == null) size = queryEnvironmentSize();
        return rememberTerminalSize(size == null ? new TerminalDimensions(80, 24) : size);
    }

    private static TerminalDimensions queryServerTerminalSize() {
        if (!SwimServerSessions.isAvailable()) return null;
        try {
            return SwimServerSessions.terminalSize().map(TerminalContext::toDimensions).orElse(null);
        } catch (IOException ignored) { return null; }
    }

    private static TerminalDimensions toDimensions(SwimServerTerminalSize size) {
        return size == null ? null : new TerminalDimensions(size.columns(), size.rows());
    }

    private static TerminalDimensions queryConfiguredTtySize() {
        String ttyPath = System.getenv("SWIM_TTY_PATH");
        if (ttyPath == null || ttyPath.isBlank()) return null;
        return runStty(ttyPath, "-f");
    }

    private static TerminalDimensions querySttySize() {
        String ttyPath = System.getenv("SWIM_TTY_PATH");
        if (ttyPath == null || ttyPath.isBlank()) ttyPath = Files.exists(Path.of("/dev/tty")) ? "/dev/tty" : null;
        return ttyPath == null ? null : runStty(ttyPath, "-f");
    }

    private static TerminalDimensions runStty(String ttyPath, String option) {
        try {
            var process = new ProcessBuilder("stty", option, ttyPath, "size").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            return process.waitFor() == 0 ? parseSttySize(output) : null;
        } catch (IOException e) { return null;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    private static TerminalDimensions queryEnvironmentSize() {
        Integer rows = parsePositiveInt(System.getenv("SWIM_TTY_ROWS"));
        Integer columns = parsePositiveInt(System.getenv("SWIM_TTY_COLS"));
        if (rows == null || columns == null) {
            rows = parsePositiveInt(System.getenv("LINES"));
            columns = parsePositiveInt(System.getenv("COLUMNS"));
        }
        return rows != null && columns != null ? new TerminalDimensions(columns, rows) : null;
    }

    private static TerminalDimensions rememberTerminalSize(TerminalDimensions size) {
        if (size != null) {
            System.setProperty(LAST_ROWS_PROPERTY, Integer.toString(size.rows()));
            System.setProperty(LAST_COLS_PROPERTY, Integer.toString(size.columns()));
        }
        return size;
    }

    private static TerminalDimensions queryRememberedSize() {
        Integer rows = parsePositiveInt(System.getProperty(LAST_ROWS_PROPERTY));
        Integer columns = parsePositiveInt(System.getProperty(LAST_COLS_PROPERTY));
        return rows != null && columns != null ? new TerminalDimensions(columns, rows) : null;
    }

    static Integer parsePositiveInt(String output) {
        if (output == null || output.isBlank()) return null;
        try { int value = Integer.parseInt(output.trim()); return value > 0 ? value : null;
        } catch (NumberFormatException e) { return null; }
    }
}
