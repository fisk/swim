package org.fisk.swim.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.fisk.swim.EventThread;
import org.fisk.swim.event.KeyBindingHint;
import org.fisk.swim.event.KeyBindingHintProvider;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.fisk.swim.event.RunnableEvent;
import org.fisk.swim.terminal.TerminalCell;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalEmulator;
import org.fisk.swim.terminal.TerminalUtf8Decoder;
import org.fisk.swim.text.AttributedString;

import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

public class ShellPanelView extends View implements KeyBindingHintProvider {
    private static final class TerminalCursor extends Cursor {
        private final ShellPanelView _owner;

        private TerminalCursor(ShellPanelView owner) {
            super(null);
            _owner = owner;
        }

        @Override
        public int getXOnScreen() {
            return _owner.absoluteOrigin().getX() + _owner._emulator.screen().column();
        }

        @Override
        public int getYOnScreen() {
            return _owner.absoluteOrigin().getY() + _owner._emulator.screen().row();
        }
    }

    private final Process _process;
    private final AtomicBoolean _closed = new AtomicBoolean();
    private final OutputStream _stdin;
    private final String _title;
    private final TerminalEmulator _emulator;
    private final TerminalUtf8Decoder _outputDecoder = new TerminalUtf8Decoder();
    private final TerminalCursor _cursor;
    private Runnable _onExit = () -> {
    };
    private boolean _commandMode;
    private final StringBuilder _command = new StringBuilder();
    private byte[] _pendingBytes;
    private Runnable _pendingAction;

    public ShellPanelView(Rect bounds, String title, java.util.function.Consumer<String> ignored, String shellCommand)
            throws IOException {
        super(bounds);
        _title = title;
        setBackgroundColour(com.googlecode.lanterna.TextColor.ANSI.DEFAULT);
        _emulator = new TerminalEmulator(Math.max(1, bounds.getSize().getWidth()), Math.max(1, bounds.getSize().getHeight()));
        _emulator.setDeviceResponseHandler(this::writeTerminalResponse);
        _cursor = new TerminalCursor(this);
        _process = createProcessBuilder(shellCommand, bounds.getSize()).start();
        _stdin = _process.getOutputStream();
        startOutputPump();
        startExitWatcher();
    }

    public static ShellPanelView createDefault(Window window, Rect bounds) throws IOException {
        var shellView = new ShellPanelView(initialPanelBounds(window, bounds), "Shell", command -> {
        }, detectShellCommand());
        shellView.setOnExit(() -> {
            var currentWindow = Window.getInstance();
            if (currentWindow != null) {
                currentWindow.closeExitedShellView(shellView);
            }
        });
        return shellView;
    }

    public static ShellPanelView createWorkspace(Window window) throws IOException {
        var shellView = new ShellPanelView(initialWorkspaceBounds(window), "Shell", command -> {
        }, detectShellCommand());
        shellView.setOnExit(() -> {
            var currentWindow = Window.getInstance();
            if (currentWindow != null) {
                currentWindow.closeExitedShellView(shellView);
            }
        });
        return shellView;
    }

    private static Rect initialPanelBounds(Window window, Rect requestedBounds) {
        if (requestedBounds != null && requestedBounds.getSize().getWidth() > 0 && requestedBounds.getSize().getHeight() > 0) {
            return requestedBounds;
        }
        if (window == null || window.getActiveView() == null) {
            return Rect.create(0, 0, 80, 12);
        }
        var activeBounds = window.getActiveView().getBounds();
        int width = Math.max(1, activeBounds.getSize().getWidth());
        int height = Math.max(1, activeBounds.getSize().getHeight());
        int existingHeight = Math.max(1, Math.min(height - 1, (int) Math.floor(height * (2.0 / 3.0))));
        int panelHeight = Math.max(1, height - existingHeight);
        return Rect.create(0, existingHeight, width, panelHeight);
    }

    private static Rect initialWorkspaceBounds(Window window) {
        if (window == null || window.getActiveView() == null) {
            return Rect.create(0, 0, 80, 24);
        }
        var activeBounds = window.getActiveView().getBounds();
        return Rect.create(0, 0,
                Math.max(1, activeBounds.getSize().getWidth()),
                Math.max(1, activeBounds.getSize().getHeight()));
    }

    private static String detectShellCommand() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            return "zsh";
        }
        return shell;
    }

    private static ProcessBuilder createProcessBuilder(String shellCommand, Size size) {
        var command = new java.util.ArrayList<String>();
        if (Files.isExecutable(Path.of("/usr/bin/script"))) {
            command.add("/usr/bin/script");
            command.add("-q");
            command.add("/dev/null");
        }
        command.add(shellCommand);
        command.add("-i");
        var builder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        builder.environment().putIfAbsent("TERM", "xterm-256color");
        builder.environment().put("COLUMNS", Integer.toString(Math.max(1, size.getWidth())));
        builder.environment().put("LINES", Integer.toString(Math.max(1, size.getHeight())));
        builder.environment().remove("TMUX");
        return builder;
    }

    String getTitle() {
        return _title;
    }

    boolean isCommandInputActive() {
        return _commandMode;
    }

    String modeName() {
        return "INPUT";
    }

    void setOnExit(Runnable onExit) {
        _onExit = onExit == null ? () -> {
        } : onExit;
    }

    CommandView.CommandMenuState getCommandMenuState() {
        if (!_commandMode) {
            return CommandView.CommandMenuState.hidden();
        }
        return CommandView.CommandMenuState.forCommandText(_command.toString(), 0,
                List.of(new CommandView.CommandSpec("q", List.of("quit"), "", "close shell terminal"),
                        new CommandView.CommandSpec("c", List.of("create", "new"), "w|v|h", "create shell workspace or split shell"),
                        new CommandView.CommandSpec("e", List.of("editor", "buffer"), "", "return to the editor"),
                        new CommandView.CommandSpec("v", List.of("view", "browse"), "",
                                "browse terminal output as a read-only buffer"),
                        new CommandView.CommandSpec("w", List.of("window", "workspace"), "<number>", "switch workspace"),
                        new CommandView.CommandSpec("help", List.of("h"), "", "show shell terminal help")));
    }

    @Override
    public String keyHintContext() {
        return _commandMode ? "shell command mode" : "shell input";
    }

    @Override
    public List<KeyBindingHint> keyBindingHints() {
        if (_commandMode) {
            return commandModeKeyHints();
        }
        return shellInputKeyHints();
    }

    private List<KeyBindingHint> commandModeKeyHints() {
        return List.of(
                KeyBindingHint.of("q", "Shell", "close"),
                KeyBindingHint.of("c w", "Shell", "new workspace"),
                KeyBindingHint.of("c v", "Shell", "split right"),
                KeyBindingHint.of("c h", "Shell", "split below"),
                KeyBindingHint.of("e", "Shell", "return to editor"),
                KeyBindingHint.of("v", "Shell", "browse output"),
                KeyBindingHint.of("w <number>", "Shell", "switch workspace"),
                KeyBindingHint.of("h", "Shell", "help"),
                KeyBindingHint.of("<ENTER>", "Command", "run"),
                KeyBindingHint.of("<BACKSPACE>", "Command", "delete character"),
                KeyBindingHint.of("<ESC>", "Command", "browse output or cancel"));
    }

    private List<KeyBindingHint> shellInputKeyHints() {
        var hints = new java.util.ArrayList<KeyBindingHint>();
        hints.add(KeyBindingHint.of("<CTRL>-g", "Shell", "command mode"));
        hints.add(KeyBindingHint.of("<CHAR>", "Terminal", "send input"));
        if (isPanelShell()) {
            hints.add(KeyBindingHint.of("<ESC>", "Panel", "close"));
        }
        return List.copyOf(hints);
    }

    private static String formatHints(List<KeyBindingHint> hints) {
        return hints.stream()
                .map(hint -> displayHintKey(hint.key()) + " " + hint.summary())
                .collect(Collectors.joining("  •  "));
    }

    private static String displayHintKey(String pattern) {
        return List.of(pattern.split(" ")).stream()
                .map(UiTheme::displayKey)
                .collect(Collectors.joining(" "));
    }

    @Override
    public Cursor getCursor() {
        return _commandMode || !_emulator.cursorVisible() ? null : _cursor;
    }

    @Override
    public void setBounds(Rect rect) {
        super.setBounds(rect);
        _emulator.resize(Math.max(1, rect.getSize().getWidth()), Math.max(1, rect.getSize().getHeight()));
    }

    @Override
    public Response processEvent(KeyStrokes events) {
        if (events.remaining() != 0) {
            return Response.NO;
        }
        var window = Window.getInstance();
        if (window != null && window.isEditorDriveSandboxActive()) {
            _pendingAction = () -> window.blockEditorDriveAction("shell input",
                    "shell input through drive_editor is not allowed");
            return Response.YES;
        }
        var event = events.current();
        if (_commandMode) {
            _pendingAction = commandModeAction(event);
            return _pendingAction == null ? Response.NO : Response.YES;
        }
        if (event.getKeyType() == KeyType.Escape && isPanelShell()) {
            _pendingAction = () -> Window.getInstance().hidePanel();
            return Response.YES;
        }
        if (isCtrlG(event)) {
            _pendingAction = () -> {
                _commandMode = true;
                _command.setLength(0);
                Window.getInstance().refreshChromeState();
                setNeedsRedraw();
            };
            return Response.YES;
        }
        if (event instanceof MouseAction mouseAction) {
            _pendingBytes = encodeMouseAction(mouseAction, _emulator, absoluteOrigin(), getBounds().getSize());
            return _pendingBytes == null ? Response.NO : Response.YES;
        }
        _pendingBytes = encodeKeyStroke(event, _emulator.applicationCursorKeys());
        return _pendingBytes == null ? Response.NO : Response.YES;
    }

    @Override
    public void respond() {
        if (_pendingAction != null) {
            _pendingAction.run();
            _pendingAction = null;
            return;
        }
        if (_pendingBytes == null) {
            return;
        }
        try {
            _stdin.write(_pendingBytes);
            _stdin.flush();
        } catch (IOException e) {
            _emulator.feed("[write failed: " + e.getMessage() + "]\r\n");
            setNeedsRedraw();
        } finally {
            _pendingBytes = null;
        }
    }

    @Override
    public void draw(Rect rect) {
        super.draw(rect);
        var graphics = TerminalContext.getInstance().getGraphics();
        for (int row = 0; row < rect.getSize().getHeight(); row++) {
            for (int column = 0; column < rect.getSize().getWidth(); column++) {
                TerminalCell cell = _emulator.screen().cellAt(row, column);
                graphics.setCharacter(rect.getPoint().getX() + column, rect.getPoint().getY() + row, toTextCharacter(cell));
            }
        }
        if (_commandMode) {
            String overlay = ":" + _command;
            AttributedString.create(overlay, UiTheme.VISUAL_SELECTION_FOREGROUND,
                    UiTheme.VISUAL_SELECTION_BACKGROUND)
                    .drawAt(Point.create(rect.getPoint().getX(), rect.getPoint().getY() + rect.getSize().getHeight() - 1),
                            graphics);
        }
    }

    @Override
    public void removeFromParent() {
        closeProcess();
        super.removeFromParent();
    }

    void detachFromParentPreservingSession() {
        super.removeFromParent();
    }

    private void startOutputPump() {
        var thread = new Thread(() -> pumpOutput(_process.getInputStream()), "swim-shell-output");
        thread.setDaemon(true);
        thread.start();
    }

    private void startExitWatcher() {
        var thread = new Thread(() -> {
            try {
                _process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (_closed.get()) {
                return;
            }
            EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                if (!_closed.get()) {
                    _onExit.run();
                }
            }));
        }, "swim-shell-exit");
        thread.setDaemon(true);
        thread.start();
    }

    private void pumpOutput(InputStream stream) {
        try (stream) {
            var buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                var text = new StringBuilder();
                appendDecodedOutput(text, buffer, read);
                while (stream.available() > 0) {
                    int available = Math.min(buffer.length, Math.max(1, stream.available()));
                    int extraRead = stream.read(buffer, 0, available);
                    if (extraRead <= 0) {
                        break;
                    }
                    appendDecodedOutput(text, buffer, extraRead);
                }
                enqueueOutput(text.toString());
            }
            enqueueOutput(_outputDecoder.flush());
        } catch (IOException e) {
            if (!_closed.get()) {
                EventThread.getInstance().enqueue(new RunnableEvent(() -> {
                    _emulator.feed("[shell error: " + e.getMessage() + "]\r\n");
                    setNeedsRedraw();
                }));
            }
        }
    }

    private void appendDecodedOutput(StringBuilder output, byte[] buffer, int read) {
        String decoded = _outputDecoder.decode(buffer, read);
        if (!decoded.isEmpty()) {
            output.append(decoded);
        }
    }

    private void enqueueOutput(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            _emulator.feed(text);
            setNeedsRedraw();
        }));
    }

    private Runnable commandModeAction(com.googlecode.lanterna.input.KeyStroke event) {
        return switch (event.getKeyType()) {
        case Escape -> () -> {
            boolean browse = _command.length() == 0;
            _commandMode = false;
            _command.setLength(0);
            if (browse) {
                Window.getInstance().enterShellBrowse(this);
            }
            Window.getInstance().refreshChromeState();
            setNeedsRedraw();
        };
        case Backspace -> () -> {
            if (_command.length() > 0) {
                _command.deleteCharAt(_command.length() - 1);
            }
            Window.getInstance().refreshChromeState();
            setNeedsRedraw();
        };
        case Enter -> () -> {
            String command = _command.toString().trim().toLowerCase();
            _commandMode = false;
            _command.setLength(0);
            if ("q".equals(command) || "quit".equals(command)) {
                closeShell();
            } else if ("cw".equals(command) || "c w".equals(command) || "create w".equals(command) || "new w".equals(command)) {
                createShell("w");
            } else if ("cv".equals(command) || "c v".equals(command) || "create v".equals(command) || "new v".equals(command)) {
                createShell("v");
            } else if ("ch".equals(command) || "c h".equals(command) || "create h".equals(command) || "new h".equals(command)) {
                createShell("h");
            } else if ("e".equals(command) || "editor".equals(command) || "buffer".equals(command)) {
                Window.getInstance().returnToEditor();
            } else if ("v".equals(command) || "view".equals(command) || "browse".equals(command)) {
                Window.getInstance().enterShellBrowse(this);
            } else if (command.startsWith("w")) {
                switchWorkspace(command);
            } else if ("h".equals(command) || "help".equals(command)) {
                Window.getInstance().getCommandView().setMessage(formatHints(commandModeKeyHints()));
            }
            Window.getInstance().refreshChromeState();
            setNeedsRedraw();
        };
        case Character -> event.isCtrlDown() || event.isAltDown() ? null : () -> {
            char character = Character.toLowerCase(event.getCharacter());
            if (_command.length() == 1 && _command.charAt(0) == 'w') {
                if (Character.isDigit(character) || Character.isWhitespace(character)) {
                    _command.append(event.getCharacter());
                    Window.getInstance().refreshChromeState();
                    setNeedsRedraw();
                    return;
                }
            }
            if (_command.length() == 1 && _command.charAt(0) == 'c') {
                if (character == 'w' || character == 'v' || character == 'h') {
                    _commandMode = false;
                    _command.setLength(0);
                    createShell(String.valueOf(character));
                    Window.getInstance().refreshChromeState();
                    setNeedsRedraw();
                    return;
                }
                if (Character.isWhitespace(character)) {
                    _command.append(event.getCharacter());
                    Window.getInstance().refreshChromeState();
                    setNeedsRedraw();
                    return;
                }
            }
            if (_command.length() == 0 && character == 'q') {
                _commandMode = false;
                _command.setLength(0);
                closeShell();
            } else if (_command.length() == 0 && character == 'c') {
                _command.append(event.getCharacter());
                Window.getInstance().refreshChromeState();
                setNeedsRedraw();
                return;
            } else if (_command.length() == 0 && character == 'e') {
                _commandMode = false;
                _command.setLength(0);
                Window.getInstance().returnToEditor();
            } else if (_command.length() == 0 && character == 'v') {
                _commandMode = false;
                _command.setLength(0);
                Window.getInstance().enterShellBrowse(this);
            } else if (_command.length() == 0 && character == 'h') {
                _commandMode = false;
                _command.setLength(0);
                Window.getInstance().getCommandView().setMessage(formatHints(commandModeKeyHints()));
            } else if (_command.length() == 0 && character == 'w') {
                _command.append(event.getCharacter());
                Window.getInstance().refreshChromeState();
                setNeedsRedraw();
                return;
            } else {
                _command.append(event.getCharacter());
                Window.getInstance().refreshChromeState();
                setNeedsRedraw();
                return;
            }
            Window.getInstance().refreshChromeState();
            setNeedsRedraw();
        };
        default -> null;
        };
    }

    static byte[] encodeKeyStroke(com.googlecode.lanterna.input.KeyStroke event, boolean applicationCursorKeys) {
        if (event.getKeyType() == KeyType.Character) {
            byte[] payload = null;
            if (event.isCtrlDown()) {
                char c = Character.toLowerCase(event.getCharacter());
                if (c >= 'a' && c <= 'z') {
                    payload = new byte[] { (byte) (c - 'a' + 1) };
                }
            } else {
                payload = Character.toString(event.getCharacter()).getBytes(StandardCharsets.UTF_8);
            }
            if (payload == null) {
                return null;
            }
            if (event.isAltDown()) {
                return prefixEscape(payload);
            }
            return payload;
        }
        return switch (event.getKeyType()) {
        case Enter -> new byte[] { '\r' };
        case Tab -> new byte[] { '\t' };
        case Backspace -> new byte[] { 0x7f };
        case Escape -> new byte[] { 0x1b };
        case ArrowUp -> cursorSequence('A', applicationCursorKeys);
        case ArrowDown -> cursorSequence('B', applicationCursorKeys);
        case ArrowRight -> cursorSequence('C', applicationCursorKeys);
        case ArrowLeft -> cursorSequence('D', applicationCursorKeys);
        case Home -> "\u001b[H".getBytes(StandardCharsets.UTF_8);
        case End -> "\u001b[F".getBytes(StandardCharsets.UTF_8);
        case Delete -> "\u001b[3~".getBytes(StandardCharsets.UTF_8);
        case PageUp -> "\u001b[5~".getBytes(StandardCharsets.UTF_8);
        case PageDown -> "\u001b[6~".getBytes(StandardCharsets.UTF_8);
        default -> null;
        };
    }

    static byte[] encodePaste(String text, boolean bracketedPasteMode) {
        if (text == null || text.isEmpty()) {
            return new byte[0];
        }
        String payload = bracketedPasteMode ? "\u001b[200~" + text + "\u001b[201~" : text;
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeMouseAction(MouseAction action, TerminalEmulator emulator, Point absoluteOrigin, Size bounds) {
        if (action == null || emulator == null || absoluteOrigin == null || bounds == null) {
            return null;
        }
        if (emulator.mouseTrackingMode() == TerminalEmulator.MouseTrackingMode.OFF) {
            return null;
        }
        int localX = action.getPosition().getColumn() - absoluteOrigin.getX();
        int localY = action.getPosition().getRow() - absoluteOrigin.getY();
        if (localX < 0 || localY < 0 || localX >= bounds.getWidth() || localY >= bounds.getHeight()) {
            return null;
        }
        int code = switch (action.getActionType()) {
        case CLICK_DOWN -> mapButton(action.getButton());
        case CLICK_RELEASE -> 3;
        case DRAG -> {
            if (emulator.mouseTrackingMode() == TerminalEmulator.MouseTrackingMode.CLICK) {
                yield -1;
            }
            yield mapButton(action.getButton()) + 32;
        }
        case MOVE -> {
            if (emulator.mouseTrackingMode() != TerminalEmulator.MouseTrackingMode.MOVE) {
                yield -1;
            }
            yield 35;
        }
        case SCROLL_UP -> 64;
        case SCROLL_DOWN -> 65;
        };
        if (code < 0) {
            return null;
        }
        int x = localX + 1;
        int y = localY + 1;
        if (emulator.sgrMouseMode()) {
            char suffix = action.getActionType() == MouseActionType.CLICK_RELEASE ? 'm' : 'M';
            return ("\u001b[<" + code + ";" + x + ";" + y + suffix).getBytes(StandardCharsets.UTF_8);
        }
        return new byte[] { 0x1b, '[', 'M', (byte) (code + 32), (byte) (x + 32), (byte) (y + 32) };
    }

    private static byte[] prefixEscape(byte[] payload) {
        byte[] prefixed = new byte[payload.length + 1];
        prefixed[0] = 0x1b;
        System.arraycopy(payload, 0, prefixed, 1, payload.length);
        return prefixed;
    }

    private static byte[] cursorSequence(char finalChar, boolean applicationCursorKeys) {
        String prefix = applicationCursorKeys ? "\u001bO" : "\u001b[";
        return (prefix + finalChar).getBytes(StandardCharsets.UTF_8);
    }

    void sendFocusChanged(boolean focused) {
        if (!_emulator.focusReportingMode()) {
            return;
        }
        writeTerminalResponse(focused ? "\u001b[I" : "\u001b[O");
    }

    void sendPastedText(String text) {
        byte[] payload = encodePaste(text, _emulator.bracketedPasteMode());
        if (payload.length == 0 || _closed.get()) {
            return;
        }
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            try {
                _stdin.write(payload);
                _stdin.flush();
            } catch (IOException e) {
                _emulator.feed("[write failed: " + e.getMessage() + "]\r\n");
                setNeedsRedraw();
            }
        }));
    }

    private void writeTerminalResponse(String response) {
        if (response == null || response.isEmpty() || _closed.get()) {
            return;
        }
        EventThread.getInstance().enqueue(new RunnableEvent(() -> {
            try {
                _stdin.write(response.getBytes(StandardCharsets.UTF_8));
                _stdin.flush();
            } catch (IOException e) {
                _emulator.feed("[write failed: " + e.getMessage() + "]\r\n");
                setNeedsRedraw();
            }
        }));
    }

    private void closeProcess() {
        if (_closed.compareAndSet(false, true)) {
            try {
                _stdin.close();
            } catch (IOException ignored) {
            }
            _process.destroy();
        }
    }

    void closeForPanel() {
        closeProcess();
    }

    private Point absoluteOrigin() {
        int x = getBounds().getPoint().getX();
        int y = getBounds().getPoint().getY();
        for (var parent = getParent(); parent != null; parent = parent.getParent()) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
        }
        return Point.create(x, y);
    }

    private static int mapButton(int button) {
        return switch (button) {
        case 1 -> 0;
        case 2 -> 1;
        case 3 -> 2;
        default -> 0;
        };
    }

    private static boolean isCtrlG(com.googlecode.lanterna.input.KeyStroke event) {
        if (event.getKeyType() != KeyType.Character || event.getCharacter() == null) {
            return false;
        }
        return (event.isCtrlDown() && Character.toLowerCase(event.getCharacter()) == 'g') || event.getCharacter() == 0x07;
    }

    private static com.googlecode.lanterna.TextColor resolveShellForeground(com.googlecode.lanterna.TextColor colour) {
        return colour;
    }

    private static com.googlecode.lanterna.TextColor resolveShellBackground(com.googlecode.lanterna.TextColor colour) {
        return colour;
    }

    static TextCharacter toTextCharacter(TerminalCell cell) {
        var style = cell.style();
        java.util.EnumSet<SGR> modifiers = java.util.EnumSet.noneOf(SGR.class);
        if (style.bold()) {
            modifiers.add(SGR.BOLD);
        }
        if (style.inverse()) {
            modifiers.add(SGR.REVERSE);
        }
        return new TextCharacter(cell.character(), resolveShellForeground(style.foreground()),
                resolveShellBackground(style.background()), modifiers);
    }

    String buildBrowseText() {
        return String.join("\n", _emulator.screen().snapshotLines());
    }

    private boolean isPanelShell() {
        var window = Window.getInstance();
        return window != null && window.getPanelView() == this;
    }

    private void closeShell() {
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        if (window.getPanelView() == this) {
            window.closePanelShellSession();
        } else {
            window.closeShellView(this);
        }
    }

    private void createShell(String target) {
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        if (window.getPanelView() == this) {
            window.hidePanel();
        }
        boolean opened = switch (target) {
        case "v" -> window.showShellSplitHorizontally();
        case "h" -> window.showShellSplitVertically();
        default -> window.showShellWorkspace();
        };
        if (!opened) {
            window.getCommandView().setMessage("Failed to start shell workspace");
        }
    }

    private void switchWorkspace(String command) {
        var window = Window.getInstance();
        if (window == null) {
            return;
        }
        String digits = command.substring(1).trim();
        if (digits.isEmpty()) {
            window.getCommandView().setMessage("Usage: w <number>");
            return;
        }
        try {
            int index = Integer.parseInt(digits);
            if (!window.switchToRecentWindow(index)) {
                window.getCommandView().setMessage("No such workspace: " + index);
            }
        } catch (NumberFormatException e) {
            window.getCommandView().setMessage("Usage: w <number>");
        }
    }
}
