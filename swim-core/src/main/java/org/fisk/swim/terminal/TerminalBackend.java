package org.fisk.swim.terminal;

import java.io.IOException;

import org.fisk.swim.event.KeyStroke;

/**
 * SWIM's complete terminal transport boundary. Implementations own terminal
 * lifecycle, drawing, sizing, and input; editor code must not depend on a
 * third-party screen or terminal type.
 */
public interface TerminalBackend extends AutoCloseable {
    void start() throws IOException;

    void stop() throws IOException;

    void clear();

    void refresh() throws IOException;

    TerminalDimensions dimensions();

    /** Returns a new size only when the viewport has changed. */
    TerminalDimensions resizeIfNeeded();

    TerminalGraphics graphics();

    KeyStroke pollInput() throws IOException;

    void setCursorPosition(int column, int row);

    void setCursorVisible(boolean visible);

    default void setCursorShape(TerminalCursorShape shape) { }

    @Override
    default void close() throws IOException { stop(); }
}
