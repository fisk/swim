package org.fisk.swim.terminal;

import java.io.IOException;

import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.Terminal;

public class TerminalContext {
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

    public TerminalContext() {
        var factory = new DefaultTerminalFactory();
        try {
            _terminal = factory.createTerminal();
            _screen = new TerminalScreen(_terminal);
            _screen.startScreen();
        } catch (IOException e) {
            throw new RuntimeException("Can't create screen", e);
        }
        _graphics = _screen.newTextGraphics();
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
}
