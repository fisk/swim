package org.fisk.swim.terminal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

public class TerminalContext {
    private record CreatedTerminal(Screen screen, Terminal terminal) {
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

    public TerminalContext() {
        this(createTerminal(), null, TerminalContext::queryTerminalSizeFromTty);
    }

    TerminalContext(Screen screen, Terminal terminal, TextGraphics graphics) {
        this(screen, terminal, graphics, TerminalContext::queryTerminalSizeFromTty);
    }

    TerminalContext(Screen screen, Terminal terminal, TextGraphics graphics, Supplier<TerminalSize> terminalSizeSupplier) {
        _screen = screen;
        _terminal = terminal;
        _graphics = graphics != null ? graphics : screen.newTextGraphics();
        _terminalSizeSupplier = terminalSizeSupplier != null ? terminalSizeSupplier : TerminalContext::queryTerminalSizeFromTty;
    }

    private TerminalContext(CreatedTerminal createdTerminal, TextGraphics graphics, Supplier<TerminalSize> terminalSizeSupplier) {
        this(createdTerminal.screen(), createdTerminal.terminal(), graphics, terminalSizeSupplier);
    }

    private static CreatedTerminal createTerminal() {
        var factory = new DefaultTerminalFactory();
        try {
            Terminal terminal = factory.createTerminal();
            Screen screen = new TerminalScreen(terminal);
            screen.startScreen();
            return new CreatedTerminal(screen, terminal);
        } catch (IOException e) {
            throw new RuntimeException("Can't create screen", e);
        }
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
        Path tty = Path.of("/dev/tty");
        if (!Files.isReadable(tty)) {
            return null;
        }
        try {
            var process = new ProcessBuilder("stty", "size")
                    .redirectInput(ProcessBuilder.Redirect.from(tty.toFile()))
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                return null;
            }
            return parseSttySize(output);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
