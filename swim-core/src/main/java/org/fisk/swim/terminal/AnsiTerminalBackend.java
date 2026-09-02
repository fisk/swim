package org.fisk.swim.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

import org.fisk.swim.event.KeyStroke;
import org.fisk.swim.event.KeyType;
import org.fisk.swim.event.MouseAction;
import org.fisk.swim.event.MouseActionType;

/** POSIX ANSI terminal transport backed solely by SWIM's cell buffer. */
public final class AnsiTerminalBackend implements TerminalBackend {
    private static final String ENTER_ALTERNATE_SCREEN = "\u001b[?1049h";
    private static final String EXIT_ALTERNATE_SCREEN = "\u001b[?1049l";
    private static final String HIDE_CURSOR = "\u001b[?25l";
    private static final String SHOW_CURSOR = "\u001b[?25h";
    private static final String ENABLE_BRACKETED_PASTE = "\u001b[?2004h";
    private static final String DISABLE_BRACKETED_PASTE = "\u001b[?2004l";
    private static final String ENABLE_MOUSE = "\u001b[?1000h\u001b[?1006h";
    private static final String DISABLE_MOUSE = "\u001b[?1006l\u001b[?1000l";
    private static final String ENABLE_MODIFY_OTHER_KEYS = "\u001b[>4;2m";
    private static final String DISABLE_MODIFY_OTHER_KEYS = "\u001b[>4m";

    private final InputStream input;
    private final OutputStream output;
    private final Supplier<TerminalDimensions> dimensionsSupplier;
    private TerminalDimensions dimensions;
    private AnsiScreen screen;
    private AnsiScreenGraphics graphics;
    private final TerminalUtf8Decoder utf8Decoder = new TerminalUtf8Decoder();
    private boolean decodingUtf8;
    private boolean utf8Alt;
    private boolean started;

    public AnsiTerminalBackend(InputStream input, OutputStream output, Supplier<TerminalDimensions> dimensionsSupplier) {
        this.input = input;
        this.output = Objects.requireNonNull(output, "output");
        this.dimensionsSupplier = Objects.requireNonNull(dimensionsSupplier, "dimensionsSupplier");
        this.dimensions = requireDimensions(dimensionsSupplier.get());
        this.screen = new AnsiScreen(dimensions.columns(), dimensions.rows());
        this.graphics = new AnsiScreenGraphics(screen);
    }

    @Override public void start() throws IOException {
        if (started) return;
        started = true;
        write(ENTER_ALTERNATE_SCREEN + HIDE_CURSOR + ENABLE_BRACKETED_PASTE + ENABLE_MOUSE + ENABLE_MODIFY_OTHER_KEYS);
    }

    @Override public void stop() throws IOException {
        if (!started) return;
        started = false;
        write(DISABLE_MODIFY_OTHER_KEYS + DISABLE_MOUSE + DISABLE_BRACKETED_PASTE + SHOW_CURSOR + EXIT_ALTERNATE_SCREEN + "\u001b[0m");
    }

    @Override public void clear() { screen.clear(); }

    @Override public void refresh() throws IOException { write(screen.flush()); }

    @Override public TerminalDimensions dimensions() { return dimensions; }

    @Override public TerminalDimensions resizeIfNeeded() {
        TerminalDimensions next = requireDimensions(dimensionsSupplier.get());
        if (next.equals(dimensions)) return null;
        dimensions = next;
        screen.resize(next.columns(), next.rows());
        return next;
    }

    @Override public TerminalGraphics graphics() { return graphics; }

    @Override public KeyStroke pollInput() throws IOException {
        if (input == null || input.available() == 0) return null;
        int first = input.read();
        if (first < 0) return new KeyStroke(KeyType.EOF);
        if (first != 0x1b) return readTextInput(first, false);
        if (input.available() == 0) return new KeyStroke(KeyType.Escape);
        int second = input.read();
        if (second != '[' && second != 'O') return readTextInput(second, true);
        if (input.available() == 0) return new KeyStroke(KeyType.Escape);
        int third = input.read();
        if (second == 'O') return ss3Key((char) third);
        if (third == '<') return readSgrMouse();
        if (third >= '0' && third <= '9') return readCsi((char) third);
        return cursorKey((char) third, false, false, false);
    }

    private static KeyStroke decodeByte(int value, boolean alt) {
        if (value == '\r' || value == '\n') return new KeyStroke(KeyType.Enter, false, alt);
        if (value == '\t') return new KeyStroke(KeyType.Tab, false, alt);
        if (value == 0x7f || value == 0x08) return new KeyStroke(KeyType.Backspace, false, alt);
        if (value >= 1 && value <= 26) return new KeyStroke((char) ('a' + value - 1), true, alt);
        if (value >= 28 && value <= 31) return new KeyStroke((char) ('\\' + value - 28), true, alt);
        if (value == 0) return new KeyStroke(' ', true, alt);
        return new KeyStroke((char) value, false, alt);
    }

    /**
     * Terminal input is a byte stream, while editor text events are Unicode
     * characters.  Keep an incomplete UTF-8 sequence until all of its bytes
     * arrive instead of turning each byte into a separate Latin-1 character.
     */
    private KeyStroke readTextInput(int value, boolean alt) throws IOException {
        KeyStroke stroke = decodeTextByte(value, alt);
        while (stroke == null && input.available() > 0) {
            stroke = decodeTextByte(input.read(), alt);
        }
        return stroke;
    }

    private KeyStroke decodeTextByte(int value, boolean alt) {
        if (value < 0) return new KeyStroke(KeyType.EOF);
        if (!decodingUtf8 && value < 0x80) return decodeByte(value, alt);
        if (!decodingUtf8) {
            decodingUtf8 = true;
            utf8Alt = alt;
        }
        String decoded = utf8Decoder.decode(new byte[] { (byte) value }, 1);
        if (decoded.isEmpty()) return null;
        decodingUtf8 = false;
        // KeyStroke currently represents BMP character input.  Reject an
        // invalid/multi-code-point sequence rather than inserting its raw
        // transport bytes into a buffer.
        if (decoded.codePointCount(0, decoded.length()) != 1 || decoded.length() != 1) {
            return new KeyStroke(KeyType.Unknown, false, utf8Alt);
        }
        return new KeyStroke(decoded.charAt(0), false, utf8Alt);
    }

    private static KeyStroke ss3Key(char key) {
        return switch (key) {
        case 'P' -> new KeyStroke(KeyType.F1);
        case 'Q' -> new KeyStroke(KeyType.F2);
        case 'R' -> new KeyStroke(KeyType.F3);
        case 'S' -> new KeyStroke(KeyType.F4);
        default -> cursorKey(key, false, false, false);
        };
    }

    private KeyStroke readCsi(char initial) throws IOException {
        var sequence = new StringBuilder().append(initial);
        while (input.available() > 0 && sequence.length() < 32) {
            char character = (char) input.read();
            sequence.append(character);
            if (character >= '@' && character <= '~') break;
        }
        if (sequence.isEmpty()) return new KeyStroke(KeyType.Escape);
        char finalCharacter = sequence.charAt(sequence.length() - 1);
        if (finalCharacter < '@' || finalCharacter > '~') return new KeyStroke(KeyType.Escape);
        String parameters = sequence.substring(0, sequence.length() - 1);
        if (finalCharacter == '~') return tildeKey(parameters);
        if (finalCharacter == 'u') return kittyKey(parameters);
        int modifier = modifier(parameters);
        return cursorKey(finalCharacter, modifier == 5 || modifier == 6 || modifier == 7 || modifier == 8,
                modifier == 3 || modifier == 4 || modifier == 7 || modifier == 8,
                modifier == 2 || modifier == 4 || modifier == 6 || modifier == 8);
    }

    private static KeyStroke cursorKey(char key, boolean ctrl, boolean alt, boolean shift) {
        return switch (key) {
        case 'A' -> new KeyStroke(KeyType.ArrowUp, ctrl, alt, shift);
        case 'B' -> new KeyStroke(KeyType.ArrowDown, ctrl, alt, shift);
        case 'C' -> new KeyStroke(KeyType.ArrowRight, ctrl, alt, shift);
        case 'D' -> new KeyStroke(KeyType.ArrowLeft, ctrl, alt, shift);
        case 'H' -> new KeyStroke(KeyType.Home, ctrl, alt, shift);
        case 'F' -> new KeyStroke(KeyType.End, ctrl, alt, shift);
        case 'Z' -> new KeyStroke(KeyType.ReverseTab, ctrl, alt, true);
        default -> new KeyStroke(KeyType.Escape);
        };
    }

    private static KeyStroke tildeKey(String parameters) {
        String[] parts = parameters.split(";", -1);
        int code = integer(parts, 0);
        int modifier = integer(parts, 1);
        boolean ctrl = modifier == 5 || modifier == 6 || modifier == 7 || modifier == 8;
        boolean alt = modifier == 3 || modifier == 4 || modifier == 7 || modifier == 8;
        boolean shift = modifier == 2 || modifier == 4 || modifier == 6 || modifier == 8;
        if (code == 27 && parts.length > 2) {
            if (integer(parts, 2) == 13) return new KeyStroke(KeyType.Enter, ctrl, alt, shift);
            return characterKey(integer(parts, 2), ctrl, alt, shift);
        }
        KeyType type = switch (code) {
        case 1, 7 -> KeyType.Home;
        case 2 -> KeyType.Insert;
        case 3 -> KeyType.Delete;
        case 4, 8 -> KeyType.End;
        case 5 -> KeyType.PageUp;
        case 6 -> KeyType.PageDown;
        case 11, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 24, 25, 26, 28, 29, 31, 32, 33 -> functionKey(code);
        case 200 -> KeyType.F18;
        case 201 -> KeyType.F19;
        default -> KeyType.Unknown;
        };
        return new KeyStroke(type, ctrl, alt, shift);
    }

    private static KeyStroke kittyKey(String parameters) {
        String[] parts = parameters.split(";", -1);
        int code = integer(parts, 0);
        int modifier = integer(parts, 1);
        boolean ctrl = modifier == 5 || modifier == 6 || modifier == 7 || modifier == 8;
        boolean alt = modifier == 3 || modifier == 4 || modifier == 7 || modifier == 8;
        boolean shift = modifier == 2 || modifier == 4 || modifier == 6 || modifier == 8;
        if (code == 13) return new KeyStroke(KeyType.Enter, ctrl, alt, shift);
        return characterKey(code, ctrl, alt, shift);
    }

    private static KeyStroke characterKey(int codePoint, boolean ctrl, boolean alt, boolean shift) {
        return Character.isValidCodePoint(codePoint) && Character.charCount(codePoint) == 1
                ? new KeyStroke((char) codePoint, ctrl, alt, shift)
                : new KeyStroke(KeyType.Unknown, ctrl, alt, shift);
    }

    private static KeyType functionKey(int code) {
        return KeyType.values()[KeyType.F1.ordinal() + functionOffset(code)];
    }

    private static int functionOffset(int code) {
        return switch (code) {
        case 11 -> 0; case 12 -> 1; case 13 -> 2; case 14 -> 3; case 15 -> 4; case 17 -> 5;
        case 18 -> 6; case 19 -> 7; case 20 -> 8; case 21 -> 9; case 23 -> 10; case 24 -> 11;
        case 25 -> 12; case 26 -> 13; case 28 -> 14; case 29 -> 15; case 31 -> 16; case 32 -> 17;
        case 33 -> 18; default -> 0;
        };
    }

    private static int modifier(String parameters) {
        String[] parts = parameters.split(";", -1);
        return integer(parts, parts.length - 1);
    }

    private static int integer(String[] parts, int index) {
        if (index < 0 || index >= parts.length || parts[index].isEmpty()) return 0;
        try { return Integer.parseInt(parts[index]); }
        catch (NumberFormatException e) { return 0; }
    }

    private KeyStroke readSgrMouse() throws IOException {
        var sequence = new StringBuilder();
        while (input.available() > 0 && sequence.length() < 32) {
            char character = (char) input.read();
            sequence.append(character);
            if (character == 'M' || character == 'm') break;
        }
        if (sequence.isEmpty()) return new KeyStroke(KeyType.Escape);
        char terminator = sequence.charAt(sequence.length() - 1);
        if (terminator != 'M' && terminator != 'm') return new KeyStroke(KeyType.Escape);
        String[] parts = sequence.substring(0, sequence.length() - 1).split(";", -1);
        if (parts.length != 3) return new KeyStroke(KeyType.Escape);
        try {
            int code = Integer.parseInt(parts[0]);
            int column = Integer.parseInt(parts[1]) - 1;
            int row = Integer.parseInt(parts[2]) - 1;
            if (column < 0 || row < 0) return new KeyStroke(KeyType.Escape);
            return new MouseAction(mouseActionType(code, terminator == 'm'), mouseButton(code),
                    new MouseAction.Position(column, row));
        } catch (NumberFormatException e) {
            return new KeyStroke(KeyType.Escape);
        }
    }

    private static MouseActionType mouseActionType(int code, boolean release) {
        if (release || (code & 3) == 3 && (code & 32) == 0) return MouseActionType.CLICK_RELEASE;
        if ((code & 64) != 0) return (code & 3) == 0 ? MouseActionType.SCROLL_UP : MouseActionType.SCROLL_DOWN;
        if ((code & 32) != 0) return (code & 3) == 3 ? MouseActionType.MOVE : MouseActionType.DRAG;
        return MouseActionType.CLICK_DOWN;
    }

    private static int mouseButton(int code) {
        if ((code & 64) != 0) return (code & 3) == 0 ? 4 : 5;
        int button = code & 3;
        return button == 3 ? 0 : button + 1;
    }

    @Override public void setCursorPosition(int column, int row) {
        try { write("\u001b[" + (row + 1) + ';' + (column + 1) + 'H'); }
        catch (IOException e) { throw new IllegalStateException("Unable to position terminal cursor", e); }
    }

    @Override public void setCursorVisible(boolean visible) {
        try { write(visible ? SHOW_CURSOR : HIDE_CURSOR); }
        catch (IOException e) { throw new IllegalStateException("Unable to update terminal cursor", e); }
    }

    @Override public void setCursorShape(TerminalCursorShape shape) {
        try { write((shape == null ? TerminalCursorShape.DEFAULT : shape).escapeSequence()); }
        catch (IOException e) { throw new IllegalStateException("Unable to update terminal cursor shape", e); }
    }

    private void write(String text) throws IOException {
        if (text.isEmpty()) return;
        synchronized (output) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static TerminalDimensions requireDimensions(TerminalDimensions dimensions) {
        return Objects.requireNonNull(dimensions, "dimensionsSupplier returned null");
    }
}
