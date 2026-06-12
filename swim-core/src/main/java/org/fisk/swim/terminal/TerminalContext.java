package org.fisk.swim.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
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
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import com.googlecode.lanterna.terminal.ansi.StreamBasedTerminal;
import org.fisk.swim.session.SwimServerSessions;
import org.fisk.swim.session.SwimServerTerminalSize;

public class TerminalContext {
    public static final KeyType BRACKETED_PASTE_START_KEY = KeyType.F18;
    public static final KeyType BRACKETED_PASTE_END_KEY = KeyType.F19;
    private static final String ENABLE_BRACKETED_PASTE = "\u001b[?2004h";
    private static final String DISABLE_BRACKETED_PASTE = "\u001b[?2004l";
    private static final String ENABLE_SGR_MOUSE = "\u001b[?1006h";
    private static final String DISABLE_SGR_MOUSE = "\u001b[?1006l";
    private static final String EXIT_ALTERNATE_SCREEN = "\u001b[?1049l";
    private static final String SHOW_CURSOR = "\u001b[?25h";
    private static final String RESET_ATTRIBUTES = "\u001b[0m";
    static final String LAST_ROWS_PROPERTY = "swim.terminal.last_rows";
    static final String LAST_COLS_PROPERTY = "swim.terminal.last_cols";
    static final String FREEZE_SIZE_PROPERTY = "swim.terminal.freeze_size";
    static final String PRESERVE_SCREEN_ON_START_PROPERTY = "swim.terminal.preserve_screen_on_start";

    private record CreatedTerminal(Screen screen, Terminal terminal, boolean restoreTerminalOnShutdown) {
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

    public static void prepareForReloadRestart() {
        TerminalContext instance = _instance;
        if (instance != null) {
            try {
                rememberTerminalSize(instance.getTerminalSize());
            } catch (RuntimeException e) {
            }
        }
        System.setProperty(PRESERVE_SCREEN_ON_START_PROPERTY, "true");
    }

    private final Screen _screen;
    private final Terminal _terminal;
    private final TextGraphics _graphics;
    private final Supplier<TerminalSize> _terminalSizeSupplier;
    private final TerminalModeState _terminalModeState;
    private final boolean _restoreTerminalOnShutdown;
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
        this(screen, terminal, graphics, terminalSizeSupplier, terminalModeState, true);
    }

    TerminalContext(
            Screen screen,
            Terminal terminal,
            TextGraphics graphics,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalModeState terminalModeState,
            boolean restoreTerminalOnShutdown) {
        _screen = screen;
        _terminal = terminal;
        _graphics = graphics != null ? graphics : screen.newTextGraphics();
        _terminalSizeSupplier = terminalSizeSupplier != null ? terminalSizeSupplier : TerminalContext::queryTerminalSizeFromTty;
        _terminalModeState = terminalModeState;
        _restoreTerminalOnShutdown = restoreTerminalOnShutdown;
    }

    private TerminalContext(
            CreatedTerminal createdTerminal,
            TextGraphics graphics,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalModeState terminalModeState) {
        this(createdTerminal.screen(), createdTerminal.terminal(), graphics, terminalSizeSupplier, terminalModeState,
                createdTerminal.restoreTerminalOnShutdown());
    }

    private static CreatedTerminal createTerminal() {
        try {
            Terminal terminal;
            boolean restoreTerminalOnShutdown = true;
            boolean preserveExistingScreen = consumePreserveScreenOnStartFlag();
            if (SwimServerSessions.isAvailable()) {
                terminal = createServerStreamTerminal(System.in, System.out, TerminalContext::queryTerminalSizeFromTty,
                        preserveExistingScreen);
                restoreTerminalOnShutdown = false;
            } else {
                terminal = new DefaultTerminalFactory().createTerminal();
                configureTerminalInputDecoding(terminal);
                terminal = wrapTerminal(terminal, TerminalContext::queryTerminalSizeFromTty,
                        standardOutputControlWriter(), preserveExistingScreen);
            }
            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();
            return new CreatedTerminal(screen, terminal, restoreTerminalOnShutdown);
        } catch (IOException e) {
            throw new RuntimeException("Can't create screen", e);
        }
    }

    private static TerminalControlWriter standardOutputControlWriter() {
        return outputControlWriter(System.out);
    }

    private static TerminalControlWriter outputControlWriter(OutputStream output) {
        OutputStream target = output == null ? System.out : output;
        return sequence -> {
            synchronized (target) {
                target.write(sequence.getBytes(StandardCharsets.UTF_8));
                target.flush();
            }
        };
    }

    static Terminal createServerStreamTerminal(
            InputStream input,
            OutputStream output,
            Supplier<TerminalSize> terminalSizeSupplier) {
        return createServerStreamTerminal(input, output, terminalSizeSupplier, false);
    }

    private static Terminal createServerStreamTerminal(
            InputStream input,
            OutputStream output,
            Supplier<TerminalSize> terminalSizeSupplier,
            boolean preserveExistingScreen) {
        Terminal terminal = new ServerStreamTerminal(input, output, StandardCharsets.UTF_8);
        configureTerminalInputDecoding(terminal);
        return wrapTerminal(terminal, terminalSizeSupplier, outputControlWriter(output), preserveExistingScreen, false);
    }

    private static void configureTerminalInputDecoding(Terminal terminal) {
        if (!(terminal instanceof StreamBasedTerminal streamBasedTerminal)) {
            return;
        }
        KeyDecodingProfile profile = () -> List.of(
                new BasicCharacterPattern(new KeyStroke(KeyType.Enter), '\r'),
                new BasicCharacterPattern(new KeyStroke(KeyType.Enter), '\n'),
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
        if (_restoreTerminalOnShutdown) {
            restoreTerminalDisplayState();
            setCursorShape(TerminalCursorShape.DEFAULT, true);
        }
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

    private void restoreTerminalDisplayState() {
        if (_terminal == null) {
            return;
        }
        try {
            writeShutdownSequence(DISABLE_SGR_MOUSE);
            writeShutdownSequence(DISABLE_BRACKETED_PASTE);
            writeShutdownSequence(SHOW_CURSOR);
            writeShutdownSequence(EXIT_ALTERNATE_SCREEN);
            writeShutdownSequence(RESET_ATTRIBUTES);
            _terminal.flush();
        } catch (IOException | IllegalStateException e) {
        }
    }

    private void writeShutdownSequence(String sequence) throws IOException {
        if (_terminal instanceof TerminalControlWriter controlWriter) {
            controlWriter.writeControlSequence(sequence);
        } else {
            _terminal.putString(sequence);
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
            } else if (_terminal != null) {
                _terminal.putString(next.escapeSequence());
                _terminal.flush();
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
            return rememberTerminalSize(size);
        }
        return rememberTerminalSize(_screen.getTerminalSize());
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

    static TerminalSize queryTerminalSizeFromTty() {
        TerminalSize frozenSize = queryFrozenRememberedSize();
        if (frozenSize != null) {
            return frozenSize;
        }
        try {
            TerminalSize serverSize = queryServerTerminalSize();
            if (serverSize != null) {
                return rememberTerminalSize(serverSize);
            }
            TerminalSize ttyPathSize = queryConfiguredTtySize();
            if (ttyPathSize != null) {
                return rememberTerminalSize(ttyPathSize);
            }
            TerminalSize sttySize = querySttySize();
            if (sttySize != null) {
                return rememberTerminalSize(sttySize);
            }
            TerminalSize rememberedSize = queryRememberedSize();
            if (rememberedSize != null) {
                return rememberedSize;
            }
            TerminalSize envSize = queryEnvironmentSize();
            if (envSize != null) {
                return rememberTerminalSize(envSize);
            }
            return rememberTerminalSize(queryTputSize());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            TerminalSize rememberedSize = queryRememberedSize();
            if (rememberedSize != null) {
                return rememberedSize;
            }
            return rememberTerminalSize(queryEnvironmentSize());
        }
    }

    private static TerminalSize rememberTerminalSize(TerminalSize size) {
        if (size == null || size.getRows() <= 0 || size.getColumns() <= 0) {
            return size;
        }
        System.setProperty(LAST_ROWS_PROPERTY, Integer.toString(size.getRows()));
        System.setProperty(LAST_COLS_PROPERTY, Integer.toString(size.getColumns()));
        return size;
    }

    private static TerminalSize queryRememberedSize() {
        Integer rows = parsePositiveInt(System.getProperty(LAST_ROWS_PROPERTY));
        Integer cols = parsePositiveInt(System.getProperty(LAST_COLS_PROPERTY));
        if (rows == null || cols == null) {
            return null;
        }
        return new TerminalSize(cols, rows);
    }

    private static TerminalSize queryFrozenRememberedSize() {
        return Boolean.getBoolean(FREEZE_SIZE_PROPERTY) ? queryRememberedSize() : null;
    }

    private static boolean consumePreserveScreenOnStartFlag() {
        boolean preserve = Boolean.getBoolean(PRESERVE_SCREEN_ON_START_PROPERTY);
        System.clearProperty(PRESERVE_SCREEN_ON_START_PROPERTY);
        return preserve;
    }

    private static TerminalSize queryServerTerminalSize() {
        if (!SwimServerSessions.isAvailable()) {
            return null;
        }
        try {
            return SwimServerSessions.terminalSize()
                    .map(TerminalContext::toTerminalSize)
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static TerminalSize toTerminalSize(SwimServerTerminalSize size) {
        return size == null ? null : new TerminalSize(size.columns(), size.rows());
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
        return wrapTerminal(terminal, terminalSizeSupplier, controlWriter, false);
    }

    static Terminal wrapTerminal(
            Terminal terminal,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalControlWriter controlWriter,
            boolean preserveExistingScreenOnStart) {
        return wrapTerminal(terminal, terminalSizeSupplier, controlWriter, preserveExistingScreenOnStart, true);
    }

    private static Terminal wrapTerminal(
            Terminal terminal,
            Supplier<TerminalSize> terminalSizeSupplier,
            TerminalControlWriter controlWriter,
            boolean preserveExistingScreenOnStart,
            boolean restoreDisplayOnExitPrivateMode) {
        if (terminal == null) {
            throw new IllegalArgumentException("terminal must not be null");
        }
        return new SizeAwareTerminal(terminal, terminalSizeSupplier, controlWriter, preserveExistingScreenOnStart,
                restoreDisplayOnExitPrivateMode);
    }

    private static final class ServerStreamTerminal extends ANSITerminal {
        private ServerStreamTerminal(InputStream input, OutputStream output, Charset charset) {
            super(input, output, charset);
        }

        @Override
        protected TerminalSize findTerminalSize() throws IOException {
            TerminalSize size = queryTerminalSizeFromTty();
            return size == null ? new TerminalSize(80, 24) : size;
        }
    }

    private static final class SizeAwareTerminal implements Terminal, TerminalControlWriter {
        private final Terminal _delegate;
        private final Supplier<TerminalSize> _terminalSizeSupplier;
        private final TerminalControlWriter _controlWriter;
        private final Map<TerminalResizeListener, TerminalResizeListener> _resizeListeners = new IdentityHashMap<>();
        private final boolean _restoreDisplayOnExitPrivateMode;
        private boolean _preserveExistingScreenOnStart;
        private boolean _skipNextClearScreen;

        private SizeAwareTerminal(
                Terminal delegate,
                Supplier<TerminalSize> terminalSizeSupplier,
                TerminalControlWriter controlWriter,
                boolean preserveExistingScreenOnStart,
                boolean restoreDisplayOnExitPrivateMode) {
            _delegate = delegate;
            _terminalSizeSupplier = terminalSizeSupplier;
            _controlWriter = controlWriter != null ? controlWriter : standardOutputControlWriter();
            _preserveExistingScreenOnStart = preserveExistingScreenOnStart;
            _restoreDisplayOnExitPrivateMode = restoreDisplayOnExitPrivateMode;
        }

        @Override
        public void enterPrivateMode() throws IOException {
            if (_preserveExistingScreenOnStart) {
                _preserveExistingScreenOnStart = false;
                _skipNextClearScreen = true;
            } else {
                _delegate.enterPrivateMode();
            }
            setBracketedPasteMode(true);
            setMouseCaptureMode(true);
        }

        @Override
        public void exitPrivateMode() throws IOException {
            if (!_restoreDisplayOnExitPrivateMode) {
                return;
            }
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
            if (_skipNextClearScreen) {
                _skipNextClearScreen = false;
                return;
            }
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
            TerminalSize frozen = queryFrozenRememberedSize();
            if (frozen != null) {
                return frozen;
            }
            if (_terminalSizeSupplier != null) {
                try {
                    TerminalSize supplied = _terminalSizeSupplier.get();
                    if (supplied != null) {
                        return rememberTerminalSize(supplied);
                    }
                } catch (RuntimeException e) {
                }
            }
            TerminalSize remembered = queryRememberedSize();
            if (remembered != null) {
                return remembered;
            }
            return rememberTerminalSize(fallback);
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
