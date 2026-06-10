package org.fisk.swim.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.BasicCharacterPattern;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import com.googlecode.lanterna.terminal.MouseCaptureMode;
import com.googlecode.lanterna.terminal.Terminal;
import com.googlecode.lanterna.terminal.TerminalResizeListener;
import com.googlecode.lanterna.terminal.ansi.StreamBasedTerminal;

public class TerminalContext {
    public static final KeyType BRACKETED_PASTE_START_KEY = KeyType.F18;
    public static final KeyType BRACKETED_PASTE_END_KEY = KeyType.F19;
    private static final String ENABLE_BRACKETED_PASTE = "\u001b[?2004h";
    private static final String DISABLE_BRACKETED_PASTE = "\u001b[?2004l";
    private static final String ENABLE_SGR_MOUSE = "\u001b[?1006h";
    private static final String DISABLE_SGR_MOUSE = "\u001b[?1006l";

    private record CreatedTerminal(Screen screen, Terminal terminal) {
    }

    private record TerminalModeState(String ttyPath, String previousSettings) {
    }

    private static volatile TerminalContext _instance;
    private boolean _closed;

    public static TerminalContext getInstance() {
        TerminalContext instance = _instance;
        if (instance != null) {
            return instance;
        }
        synchronized (TerminalContext.class) {
            instance = _instance;
            if (instance != null) {
                return instance;
            }
            instance = new TerminalContext();
            _instance = instance;
        }
        return instance;
    }

    public static void shutdownInstance() {
        TerminalContext instance = _instance;
        if (instance == null) {
            return;
        }
        _instance = null;
        instance.shutdown();
    }

    private final Screen _screen;
    private final Terminal _terminal;
    private final TextGraphics _graphics;
    private final Supplier<TerminalSize> _terminalSizeSupplier;
    private final TerminalModeState _terminalModeState;
    private TerminalCursorShape _cursorShape = TerminalCursorShape.BLOCK;

    public TerminalContext() {
        this(createTerminal(), null, TerminalContext::queryTerminalSizeFromTty, configureTerminalShortcuts());
    }

    TerminalContext(Screen screen, Terminal terminal, TextGraphics graphics) {
        this(screen, terminal, graphics, TerminalContext::queryTerminalSizeFromTty, null);
    }

    TerminalContext(Screen screen, Terminal terminal, TextGraphics graphics, Supplier<TerminalSize> terminalSizeSupplier) {
        this(screen, terminal, graphics, terminalSizeSupplier, null);
    }

    TerminalContext(
            Screen screen,
            Terminal terminal,
            TextGraphics graphics,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalModeState terminalModeState) {
        _screen = screen;
        _terminal = terminal;
        _graphics = graphics != null ? graphics : screen.newTextGraphics();
        _terminalSizeSupplier = terminalSizeSupplier != null ? terminalSizeSupplier : TerminalContext::queryTerminalSizeFromTty;
        _terminalModeState = terminalModeState;
    }

    private TerminalContext(
            CreatedTerminal createdTerminal,
            TextGraphics graphics,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalModeState terminalModeState) {
        this(createdTerminal.screen(), createdTerminal.terminal(), graphics, terminalSizeSupplier, terminalModeState);
    }

    private static CreatedTerminal createTerminal() {
        try {
            var factory = new DefaultTerminalFactory();
            Terminal terminal = factory.createTerminal();
            configureTerminalInputDecoding(terminal);
            terminal = wrapTerminal(terminal, TerminalContext::queryTerminalSizeFromTty);
            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();
            return new CreatedTerminal(screen, terminal);
        } catch (IOException e) {
            throw new RuntimeException("Can't create screen", e);
        }
    }

    private static TerminalControlWriter standardOutputControlWriter() {
        return sequence -> {
            synchronized (System.out) {
                System.out.write(sequence.getBytes(StandardCharsets.UTF_8));
                System.out.flush();
            }
        };
    }

    private static void configureTerminalInputDecoding(Terminal terminal) {
        if (!(terminal instanceof StreamBasedTerminal streamBasedTerminal)) {
            return;
        }
        KeyDecodingProfile profile = () -> List.of(
                new BasicCharacterPattern(new KeyStroke(BRACKETED_PASTE_START_KEY),
                        '\u001b', '[', '2', '0', '0', '~'),
                new BasicCharacterPattern(new KeyStroke(BRACKETED_PASTE_END_KEY),
                        '\u001b', '[', '2', '0', '1', '~'),
                new SgrMouseCharacterPattern());
        streamBasedTerminal.getInputDecoder().addProfile(profile);
    }

    private synchronized void shutdown() {
        if (_closed) {
            return;
        }
        _closed = true;
        try {
            _screen.stopScreen();
        } catch (IOException | IllegalStateException e) {
        }
        setCursorShape(TerminalCursorShape.BLOCK, true);
        try {
            restoreTerminalShortcuts(_terminalModeState);
        } catch (RuntimeException e) {
        }
        try {
            if (_terminal != null) {
                _terminal.close();
            }
        } catch (IOException | IllegalStateException e) {
        }
    }

    public TextGraphics getGraphics() {
        return _graphics;
    }

    public Screen getScreen() {
        return _screen;
    }

    public Terminal getTerminal() {
        return _terminal;
    }

    public void setCursorShape(TerminalCursorShape shape) {
        setCursorShape(shape, false);
    }

    private synchronized void setCursorShape(TerminalCursorShape shape, boolean force) {
        TerminalCursorShape next = shape == null ? TerminalCursorShape.BLOCK : shape;
        if (!force && next == _cursorShape) {
            return;
        }
        _cursorShape = next;
        try {
            if (_terminal instanceof TerminalControlWriter controlWriter) {
                controlWriter.writeControlSequence(next.escapeSequence());
            }
        } catch (IOException | IllegalStateException e) {
        }
    }

    public TerminalSize getTerminalSize() {
        TerminalSize size = null;
        try {
            size = _terminalSizeSupplier.get();
        } catch (RuntimeException e) {
        }
        if (size != null) {
            return size;
        }
        return _screen.getTerminalSize();
    }

    static TerminalSize parseSttySize(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 2) {
            return null;
        }
        try {
            int rows = Integer.parseInt(parts[0]);
            int columns = Integer.parseInt(parts[1]);
            if (rows <= 0 || columns <= 0) {
                return null;
            }
            return new TerminalSize(columns, rows);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TerminalSize queryTerminalSizeFromTty() {
        try {
            TerminalSize ttyPathSize = queryConfiguredTtySize();
            if (ttyPathSize != null) {
                return ttyPathSize;
            }
            TerminalSize envSize = queryEnvironmentSize();
            if (envSize != null) {
                return envSize;
            }
            TerminalSize sttySize = querySttySize();
            if (sttySize != null) {
                return sttySize;
            }
            return queryTputSize();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static TerminalSize queryConfiguredTtySize() throws IOException, InterruptedException {
        String ttyPath = System.getenv("SWIM_TTY_PATH");
        if (ttyPath == null || ttyPath.isBlank()) {
            return null;
        }
        String output = runSttyOnPath(ttyPath, "-f");
        if (output == null) {
            output = runSttyOnPath(ttyPath, "-F");
        }
        return parseSttySize(output);
    }

    private static TerminalSize queryEnvironmentSize() {
        String swimRows = System.getenv("SWIM_TTY_ROWS");
        String swimCols = System.getenv("SWIM_TTY_COLS");
        Integer rows = parsePositiveInt(swimRows);
        Integer cols = parsePositiveInt(swimCols);
        if (rows != null && cols != null) {
            return new TerminalSize(cols, rows);
        }

        String lines = System.getenv("LINES");
        String columns = System.getenv("COLUMNS");
        rows = parsePositiveInt(lines);
        cols = parsePositiveInt(columns);
        if (rows != null && cols != null) {
            return new TerminalSize(cols, rows);
        }
        return null;
    }

    private static TerminalSize querySttySize() throws IOException, InterruptedException {
        String output = runTerminalQuery("stty", "size");
        return parseSttySize(output);
    }

    private static TerminalSize queryTputSize() throws IOException, InterruptedException {
        String columnsOutput = runTerminalQuery("tput", "cols");
        String rowsOutput = runTerminalQuery("tput", "lines");
        Integer columns = parsePositiveInt(columnsOutput);
        Integer rows = parsePositiveInt(rowsOutput);
        if (columns == null || rows == null) {
            return null;
        }
        return new TerminalSize(columns, rows);
    }

    private static String runTerminalQuery(String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            return null;
        }
        return output;
    }

    private static String runSttyOnPath(String ttyPath, String option) throws IOException, InterruptedException {
        return runSttyOnPath(ttyPath, option, "size");
    }

    private static String runSttyOnPath(String ttyPath, String option, String... args) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<String>();
        command.add("stty");
        command.add(option);
        command.add(ttyPath);
        for (String arg : args) {
            command.add(arg);
        }
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            return null;
        }
        return output;
    }

    private static TerminalModeState configureTerminalShortcuts() {
        try {
            String ttyPath = configuredTtyPath();
            if (ttyPath == null) {
                return null;
            }
            String previous = runSttyOnAnyOption(ttyPath, "-g");
            if (previous == null || previous.isBlank()) {
                return null;
            }
            if (runSttyOnAnyOption(ttyPath, "-ixon") == null) {
                return null;
            }
            return new TerminalModeState(ttyPath, previous.trim());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private static void restoreTerminalShortcuts(TerminalModeState state) {
        if (state == null || state.ttyPath() == null || state.previousSettings() == null || state.previousSettings().isBlank()) {
            return;
        }
        try {
            runSttyOnAnyOption(state.ttyPath(), state.previousSettings());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String configuredTtyPath() {
        String ttyPath = System.getenv("SWIM_TTY_PATH");
        if (ttyPath != null && !ttyPath.isBlank()) {
            return ttyPath;
        }
        java.nio.file.Path defaultTty = java.nio.file.Path.of("/dev/tty");
        return java.nio.file.Files.exists(defaultTty) ? defaultTty.toString() : null;
    }

    private static String runSttyOnAnyOption(String ttyPath, String... args) throws IOException, InterruptedException {
        String output = runSttyOnPath(ttyPath, "-f", args);
        if (output != null) {
            return output;
        }
        return runSttyOnPath(ttyPath, "-F", args);
    }

    static Integer parsePositiveInt(String output) {
        if (output == null) {
            return null;
        }
        String trimmed = output.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(trimmed);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Terminal wrapTerminal(Terminal terminal, Supplier<TerminalSize> terminalSizeSupplier) {
        return wrapTerminal(terminal, terminalSizeSupplier, standardOutputControlWriter());
    }

    static Terminal wrapTerminal(
            Terminal terminal,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalControlWriter controlWriter) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal must not be null");
        }
        return new SizeAwareTerminal(terminal, terminalSizeSupplier, controlWriter);
    }

    private static final class SizeAwareTerminal implements Terminal, TerminalControlWriter {
        private final Terminal _delegate;
        private final Supplier<TerminalSize> _terminalSizeSupplier;
        private final TerminalControlWriter _controlWriter;
        private final Map<TerminalResizeListener, TerminalResizeListener> _resizeListeners = new IdentityHashMap<>();

        private SizeAwareTerminal(
                Terminal delegate,
                Supplier<TerminalSize> terminalSizeSupplier,
                TerminalControlWriter controlWriter) {
            _delegate = delegate;
            _terminalSizeSupplier = terminalSizeSupplier;
            _controlWriter = controlWriter != null ? controlWriter : standardOutputControlWriter();
        }

        @Override
        public void enterPrivateMode() throws IOException {
            _delegate.enterPrivateMode();
            setBracketedPasteMode(true);
            setMouseCaptureMode(true);
        }

        @Override
        public void exitPrivateMode() throws IOException {
            setMouseCaptureMode(false);
            setBracketedPasteMode(false);
            _delegate.exitPrivateMode();
        }

        private void setBracketedPasteMode(boolean enabled) throws IOException {
            writeControlSequence(enabled ? ENABLE_BRACKETED_PASTE : DISABLE_BRACKETED_PASTE);
        }

        private void setMouseCaptureMode(boolean enabled) throws IOException {
            if (_delegate instanceof ExtendedTerminal extendedTerminal) {
                extendedTerminal.setMouseCaptureMode(enabled ? MouseCaptureMode.CLICK_RELEASE_DRAG : null);
            }
            writeControlSequence(enabled ? ENABLE_SGR_MOUSE : DISABLE_SGR_MOUSE);
        }

        @Override
        public void writeControlSequence(String sequence) throws IOException {
            if (sequence == null || sequence.isEmpty()) {
                return;
            }
            _controlWriter.writeControlSequence(sequence);
        }

        @Override
        public void clearScreen() throws IOException {
            _delegate.clearScreen();
        }

        @Override
        public void setCursorPosition(int x, int y) throws IOException {
            _delegate.setCursorPosition(x, y);
        }

        @Override
        public void setCursorPosition(TerminalPosition position) throws IOException {
            _delegate.setCursorPosition(position);
        }

        @Override
        public TerminalPosition getCursorPosition() throws IOException {
            return _delegate.getCursorPosition();
        }

        @Override
        public void setCursorVisible(boolean visible) throws IOException {
            _delegate.setCursorVisible(visible);
        }

        @Override
        public void putCharacter(char c) throws IOException {
            _delegate.putCharacter(c);
        }

        @Override
        public void putString(String string) throws IOException {
            _delegate.putString(string);
        }

        @Override
        public TextGraphics newTextGraphics() throws IOException {
            return _delegate.newTextGraphics();
        }

        @Override
        public void enableSGR(SGR sgr) throws IOException {
            _delegate.enableSGR(sgr);
        }

        @Override
        public void disableSGR(SGR sgr) throws IOException {
            _delegate.disableSGR(sgr);
        }

        @Override
        public void resetColorAndSGR() throws IOException {
            _delegate.resetColorAndSGR();
        }

        @Override
        public void setForegroundColor(TextColor color) throws IOException {
            _delegate.setForegroundColor(color);
        }

        @Override
        public void setBackgroundColor(TextColor color) throws IOException {
            _delegate.setBackgroundColor(color);
        }

        @Override
        public synchronized void addResizeListener(TerminalResizeListener listener) {
            if (listener == null) {
                return;
            }
            var wrapped = new TerminalResizeListener() {
                @Override
                public void onResized(Terminal ignored, TerminalSize size) {
                    listener.onResized(SizeAwareTerminal.this, getTerminalSizeUnchecked(size));
                }
            };
            _resizeListeners.put(listener, wrapped);
            _delegate.addResizeListener(wrapped);
        }

        @Override
        public synchronized void removeResizeListener(TerminalResizeListener listener) {
            var wrapped = _resizeListeners.remove(listener);
            if (wrapped != null) {
                _delegate.removeResizeListener(wrapped);
            }
        }

        @Override
        public TerminalSize getTerminalSize() throws IOException {
            TerminalSize supplied = getTerminalSizeUnchecked(null);
            if (supplied != null) {
                return supplied;
            }
            return _delegate.getTerminalSize();
        }

        private TerminalSize getTerminalSizeUnchecked(TerminalSize fallback) {
            if (_terminalSizeSupplier != null) {
                try {
                    TerminalSize supplied = _terminalSizeSupplier.get();
                    if (supplied != null) {
                        return supplied;
                    }
                } catch (RuntimeException e) {
                }
            }
            return fallback;
        }

        @Override
        public byte[] enquireTerminal(int timeout, java.util.concurrent.TimeUnit unit) throws IOException {
            return _delegate.enquireTerminal(timeout, unit);
        }

        @Override
        public void bell() throws IOException {
            _delegate.bell();
        }

        @Override
        public void flush() throws IOException {
            _delegate.flush();
        }

        @Override
        public void close() throws IOException {
            _delegate.close();
        }

        @Override
        public KeyStroke pollInput() throws IOException {
            return _delegate.pollInput();
        }

        @Override
        public KeyStroke readInput() throws IOException {
            return _delegate.readInput();
        }
    }
}
