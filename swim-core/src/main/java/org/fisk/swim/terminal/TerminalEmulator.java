package org.fisk.swim.terminal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.googlecode.lanterna.TextColor;

public final class TerminalEmulator {
    public enum MouseTrackingMode {
        OFF,
        CLICK,
        DRAG,
        MOVE
    }

    private enum State {
        GROUND,
        ESCAPE,
        CSI,
        OSC,
        OSC_ESCAPE
    }

    private final TerminalScreenBuffer _screen;
    private State _state = State.GROUND;
    private final StringBuilder _csi = new StringBuilder();
    private final StringBuilder _osc = new StringBuilder();
    private TerminalStyle _style = TerminalStyle.DEFAULT;
    private boolean _cursorVisible = true;
    private boolean _applicationCursorKeys;
    private boolean _applicationKeypad;
    private boolean _bracketedPasteMode;
    private boolean _focusReportingMode;
    private boolean _sgrMouseMode;
    private MouseTrackingMode _mouseTrackingMode = MouseTrackingMode.OFF;
    private char _lastPrintedCharacter = ' ';
    private TerminalStyle _lastPrintedStyle = TerminalStyle.DEFAULT;
    private Consumer<String> _deviceResponseHandler = ignored -> {
    };

    public TerminalEmulator(int width, int height) {
        _screen = new TerminalScreenBuffer(width, height);
    }

    public void resize(int width, int height) {
        _screen.resize(width, height);
    }

    public TerminalScreenBuffer screen() {
        return _screen;
    }

    public boolean cursorVisible() {
        return _cursorVisible;
    }

    public boolean applicationCursorKeys() {
        return _applicationCursorKeys;
    }

    public boolean applicationKeypad() {
        return _applicationKeypad;
    }

    public boolean bracketedPasteMode() {
        return _bracketedPasteMode;
    }

    public boolean focusReportingMode() {
        return _focusReportingMode;
    }

    public boolean sgrMouseMode() {
        return _sgrMouseMode;
    }

    public MouseTrackingMode mouseTrackingMode() {
        return _mouseTrackingMode;
    }

    public void setDeviceResponseHandler(Consumer<String> deviceResponseHandler) {
        _deviceResponseHandler = deviceResponseHandler == null ? ignored -> {
        } : deviceResponseHandler;
    }

    public void feed(String text) {
        for (int i = 0; i < text.length(); i++) {
            handleChar(text.charAt(i));
        }
    }

    private void handleChar(char character) {
        switch (_state) {
        case GROUND -> handleGround(character);
        case ESCAPE -> handleEscape(character);
        case CSI -> handleCsi(character);
        case OSC -> handleOsc(character);
        case OSC_ESCAPE -> handleOscEscape(character);
        }
    }

    private void handleGround(char character) {
        switch (character) {
        case 0x1b -> _state = State.ESCAPE;
        case '\r' -> _screen.carriageReturn();
        case '\n' -> _screen.lineFeed(_style);
        case '\b' -> _screen.backspace();
        case '\t' -> _screen.tab();
        case 0x07 -> {
        }
        default -> {
            if (character >= 0x20) {
                _screen.putChar(character, _style);
                _lastPrintedCharacter = character;
                _lastPrintedStyle = _style;
            }
        }
        }
    }

    private void handleEscape(char character) {
        _state = State.GROUND;
        switch (character) {
        case '[' -> {
            _state = State.CSI;
            _csi.setLength(0);
        }
        case ']' -> {
            _state = State.OSC;
            _osc.setLength(0);
        }
        case '7' -> _screen.saveCursor();
        case '8' -> _screen.restoreCursor();
        case 'H' -> _screen.setTabStopAtCursor();
        case 'D' -> _screen.lineFeed(_style);
        case 'E' -> {
            _screen.lineFeed(_style);
            _screen.carriageReturn();
        }
        case 'M' -> _screen.reverseIndex(_style);
        case 'c' -> reset();
        case '=' -> _applicationCursorKeys = true;
        case '>' -> _applicationCursorKeys = false;
        case 'Z' -> emitPrimaryDeviceAttributes();
        default -> {
        }
        }
    }

    private void handleCsi(char character) {
        if (character >= 0x40 && character <= 0x7e) {
            applyCsi(character, _csi.toString());
            _state = State.GROUND;
            _csi.setLength(0);
            return;
        }
        _csi.append(character);
    }

    private void handleOsc(char character) {
        if (character == 0x07) {
            applyOsc(_osc.toString());
            _osc.setLength(0);
            _state = State.GROUND;
            return;
        }
        if (character == 0x1b) {
            _state = State.OSC_ESCAPE;
            return;
        }
        _osc.append(character);
    }

    private void handleOscEscape(char character) {
        if (character == '\\') {
            applyOsc(_osc.toString());
            _osc.setLength(0);
            _state = State.GROUND;
            return;
        }
        _osc.append('\u001b');
        _osc.append(character);
        _state = State.OSC;
    }

    private void applyCsi(char command, String params) {
        boolean privateMode = params.startsWith("?");
        if (privateMode) {
            params = params.substring(1);
        }
        List<Integer> values = parseParams(params);
        switch (command) {
        case 'A' -> _screen.cursorUp(param(values, 0, 1));
        case 'B' -> _screen.cursorDown(param(values, 0, 1));
        case 'C', 'a' -> _screen.cursorForward(param(values, 0, 1));
        case 'D' -> _screen.cursorBackward(param(values, 0, 1));
        case 'E' -> {
            _screen.cursorDown(param(values, 0, 1));
            _screen.carriageReturn();
        }
        case 'F' -> {
            _screen.cursorUp(param(values, 0, 1));
            _screen.carriageReturn();
        }
        case 'G' -> _screen.cursorPosition(_screen.row(), Math.max(0, param(values, 0, 1) - 1));
        case 'H', 'f' -> _screen.cursorPosition(Math.max(0, param(values, 0, 1) - 1), Math.max(0, param(values, 1, 1) - 1));
        case 'J' -> _screen.clearScreen(param(values, 0, 0), _style);
        case 'K' -> _screen.clearLine(param(values, 0, 0), _style);
        case 'L' -> _screen.insertLines(param(values, 0, 1), _style);
        case 'M' -> _screen.deleteLines(param(values, 0, 1), _style);
        case '@' -> _screen.insertBlankChars(param(values, 0, 1), _style);
        case 'P' -> _screen.deleteChars(param(values, 0, 1), _style);
        case 'S' -> _screen.scrollUp(param(values, 0, 1), _style);
        case 'T' -> _screen.scrollDown(param(values, 0, 1), _style);
        case 'X' -> _screen.eraseChars(param(values, 0, 1), _style);
        case 'b' -> repeatLastCharacter(param(values, 0, 1));
        case 'c' -> emitPrimaryDeviceAttributes();
        case 'd' -> _screen.cursorLineAbsolute(Math.max(0, param(values, 0, 1) - 1));
        case 'e' -> _screen.cursorDown(param(values, 0, 1));
        case 'g' -> clearTabStops(param(values, 0, 0));
        case 'n' -> emitDeviceStatusReport(param(values, 0, 0));
        case 'r' -> _screen.setScrollRegion(Math.max(0, param(values, 0, 1) - 1), Math.max(0, param(values, 1, _screen.height()) - 1));
        case 'm' -> applySgr(values);
        case 's' -> _screen.saveCursor();
        case 'u' -> _screen.restoreCursor();
        case 'h' -> {
            if (privateMode) {
                applyPrivateMode(true, values);
            } else if (param(values, 0, 0) == 4) {
                _screen.setInsertMode(true);
            }
        }
        case 'l' -> {
            if (privateMode) {
                applyPrivateMode(false, values);
            } else if (param(values, 0, 0) == 4) {
                _screen.setInsertMode(false);
            }
        }
        default -> {
        }
        }
    }

    private void applyOsc(String params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        int separator = params.indexOf(';');
        String command = separator >= 0 ? params.substring(0, separator) : params;
        String value = separator >= 0 ? params.substring(separator + 1) : "";
        switch (command) {
        case "0", "1", "2" -> {
            // Window/icon title updates are ignored by the terminal UI.
        }
        case "10", "11" -> {
            if ("?".equals(value)) {
                emitOscColorQueryResponse(command);
            }
        }
        default -> {
        }
        }
    }

    private void applySgr(List<Integer> values) {
        if (values.isEmpty()) {
            _style = TerminalStyle.DEFAULT;
            return;
        }
        TextColor foreground = _style.foreground();
        TextColor background = _style.background();
        boolean bold = _style.bold();
        boolean inverse = _style.inverse();
        for (int index = 0; index < values.size(); index++) {
            int value = values.get(index);
            switch (value) {
            case 0 -> {
                foreground = TextColor.ANSI.DEFAULT;
                background = TextColor.ANSI.DEFAULT;
                bold = false;
                inverse = false;
            }
            case 1 -> bold = true;
            case 22 -> bold = false;
            case 7 -> inverse = true;
            case 27 -> inverse = false;
            case 39 -> foreground = TextColor.ANSI.DEFAULT;
            case 49 -> background = TextColor.ANSI.DEFAULT;
            case 38, 48 -> {
                var colour = parseExtendedColour(values, index, value == 48);
                if (colour.consumed() > 0) {
                    if (value == 38 && colour.colour() != null) {
                        foreground = colour.colour();
                    } else if (value == 48 && colour.colour() != null) {
                        background = colour.colour();
                    }
                    index += colour.consumed();
                }
            }
            default -> {
                TextColor maybeForeground = ansiColour(value, false);
                TextColor maybeBackground = ansiColour(value, true);
                if (maybeForeground != null) {
                    foreground = maybeForeground;
                }
                if (maybeBackground != null) {
                    background = maybeBackground;
                }
            }
            }
        }
        _style = new TerminalStyle(foreground, background, bold, inverse);
    }

    private static TextColor ansiColour(int value, boolean background) {
        if (!background && value >= 90 && value <= 97) {
            return switch (value - 90) {
            case 0 -> TextColor.ANSI.BLACK_BRIGHT;
            case 1 -> TextColor.ANSI.RED_BRIGHT;
            case 2 -> TextColor.ANSI.GREEN_BRIGHT;
            case 3 -> TextColor.ANSI.YELLOW_BRIGHT;
            case 4 -> TextColor.ANSI.BLUE_BRIGHT;
            case 5 -> TextColor.ANSI.MAGENTA_BRIGHT;
            case 6 -> TextColor.ANSI.CYAN_BRIGHT;
            case 7 -> TextColor.ANSI.WHITE_BRIGHT;
            default -> null;
            };
        }
        if (background && value >= 100 && value <= 107) {
            return switch (value - 100) {
            case 0 -> TextColor.ANSI.BLACK_BRIGHT;
            case 1 -> TextColor.ANSI.RED_BRIGHT;
            case 2 -> TextColor.ANSI.GREEN_BRIGHT;
            case 3 -> TextColor.ANSI.YELLOW_BRIGHT;
            case 4 -> TextColor.ANSI.BLUE_BRIGHT;
            case 5 -> TextColor.ANSI.MAGENTA_BRIGHT;
            case 6 -> TextColor.ANSI.CYAN_BRIGHT;
            case 7 -> TextColor.ANSI.WHITE_BRIGHT;
            default -> null;
            };
        }
        int normalized = background ? value - 40 : value - 30;
        if ((background && (value < 40 || value > 47)) || (!background && (value < 30 || value > 37))) {
            return null;
        }
        return switch (normalized) {
        case 0 -> TextColor.ANSI.BLACK;
        case 1 -> TextColor.ANSI.RED;
        case 2 -> TextColor.ANSI.GREEN;
        case 3 -> TextColor.ANSI.YELLOW;
        case 4 -> TextColor.ANSI.BLUE;
        case 5 -> TextColor.ANSI.MAGENTA;
        case 6 -> TextColor.ANSI.CYAN;
        case 7 -> TextColor.ANSI.WHITE;
        default -> null;
        };
    }

    private static List<Integer> parseParams(String params) {
        if (params == null || params.isBlank()) {
            return List.of();
        }
        var values = new ArrayList<Integer>();
        for (String part : params.split(";")) {
            if (part.isBlank()) {
                values.add(0);
                continue;
            }
            try {
                values.add(Integer.parseInt(part));
            } catch (NumberFormatException e) {
                values.add(0);
            }
        }
        return List.copyOf(values);
    }

    private static int param(List<Integer> values, int index, int fallback) {
        return index < values.size() && values.get(index) != 0 ? values.get(index) : fallback;
    }

    private void repeatLastCharacter(int count) {
        for (int i = 0; i < Math.max(1, count); i++) {
            _screen.putChar(_lastPrintedCharacter, _lastPrintedStyle);
        }
    }

    private void applyPrivateMode(boolean enabled, List<Integer> values) {
        for (int value : values) {
            switch (value) {
            case 1 -> _applicationCursorKeys = enabled;
            case 6 -> _screen.setOriginMode(enabled);
            case 7 -> _screen.setAutoWrap(enabled);
            case 9, 1000 -> _mouseTrackingMode = enabled ? MouseTrackingMode.CLICK : MouseTrackingMode.OFF;
            case 1002 -> _mouseTrackingMode = enabled ? MouseTrackingMode.DRAG : MouseTrackingMode.OFF;
            case 1003 -> _mouseTrackingMode = enabled ? MouseTrackingMode.MOVE : MouseTrackingMode.OFF;
            case 1004 -> _focusReportingMode = enabled;
            case 1006 -> _sgrMouseMode = enabled;
            case 2004 -> _bracketedPasteMode = enabled;
            case 25 -> _cursorVisible = enabled;
            case 66 -> _applicationKeypad = enabled;
            case 1048 -> {
                if (enabled) {
                    _screen.saveCursor();
                } else {
                    _screen.restoreCursor();
                }
            }
            case 47, 1047 -> _screen.useAlternateBuffer(enabled);
            case 1049 -> {
                if (enabled) {
                    _screen.saveCursor();
                    _screen.useAlternateBuffer(true);
                } else {
                    _screen.useAlternateBuffer(false);
                    _screen.restoreCursor();
                }
            }
            default -> {
            }
            }
        }
    }

    private static ExtendedColour parseExtendedColour(List<Integer> values, int index, boolean background) {
        if (index + 1 >= values.size()) {
            return ExtendedColour.NONE;
        }
        int mode = values.get(index + 1);
        if (mode == 5 && index + 2 < values.size()) {
            return new ExtendedColour(new TextColor.Indexed(values.get(index + 2)), 2);
        }
        if (mode == 2 && index + 4 < values.size()) {
            return new ExtendedColour(new TextColor.RGB(values.get(index + 2), values.get(index + 3), values.get(index + 4)),
                    4);
        }
        return ExtendedColour.NONE;
    }

    private void clearTabStops(int mode) {
        if (mode == 3) {
            _screen.clearAllTabStops();
        } else {
            _screen.clearTabStopAtCursor();
        }
    }

    private void reset() {
        _screen.reset();
        _style = TerminalStyle.DEFAULT;
        _cursorVisible = true;
        _applicationCursorKeys = false;
        _applicationKeypad = false;
        _bracketedPasteMode = false;
        _focusReportingMode = false;
        _sgrMouseMode = false;
        _mouseTrackingMode = MouseTrackingMode.OFF;
        _lastPrintedCharacter = ' ';
        _lastPrintedStyle = TerminalStyle.DEFAULT;
        _csi.setLength(0);
        _osc.setLength(0);
        _state = State.GROUND;
    }

    private void emitPrimaryDeviceAttributes() {
        _deviceResponseHandler.accept("\u001b[?1;2;4c");
    }

    private void emitDeviceStatusReport(int report) {
        if (report == 5) {
            _deviceResponseHandler.accept("\u001b[0n");
        } else if (report == 6) {
            _deviceResponseHandler.accept("\u001b[" + (_screen.row() + 1) + ";" + (_screen.column() + 1) + "R");
        }
    }

    private void emitOscColorQueryResponse(String command) {
        String response = formatOscColorQueryResponse(command,
                System.getenv("COLORFGBG"),
                System.getenv("TERM_PROGRAM"),
                System.getenv("TMUX"),
                System.getenv("SWIM_TERMINAL_FOREGROUND"),
                System.getenv("SWIM_TERMINAL_BACKGROUND"));
        if (response != null) {
            _deviceResponseHandler.accept(response);
        }
    }

    static String formatOscColorQueryResponse(String command, String colorFgbg, String termProgram, String tmux,
            String foregroundOverride, String backgroundOverride) {
        TextColor colour = switch (command) {
        case "10" -> terminalForegroundFrom(colorFgbg, termProgram, tmux, foregroundOverride);
        case "11" -> terminalBackgroundFrom(colorFgbg, termProgram, tmux, backgroundOverride);
        default -> null;
        };
        if (colour == null) {
            return null;
        }
        int[] rgb = rgbComponents(colour);
        return "\u001b]" + command + ";rgb:%04x/%04x/%04x\u0007".formatted(rgb[0] * 257, rgb[1] * 257, rgb[2] * 257);
    }

    static TextColor terminalForegroundFrom(String colorFgbg, String termProgram, String tmux, String override) {
        TextColor configured = parseConfiguredColour(override);
        if (configured != null) {
            return configured;
        }
        return useColorFgbgHint(termProgram, tmux) ? parseColorFgbgColour(colorFgbg, true) : null;
    }

    static TextColor terminalBackgroundFrom(String colorFgbg, String termProgram, String tmux, String override) {
        TextColor configured = parseConfiguredColour(override);
        if (configured != null) {
            return configured;
        }
        return useColorFgbgHint(termProgram, tmux) ? parseColorFgbgColour(colorFgbg, false) : null;
    }

    static TextColor parseColorFgbgColour(String colorFgbg, boolean foreground) {
        if (colorFgbg == null || colorFgbg.isBlank()) {
            return null;
        }
        String[] parts = colorFgbg.trim().split(";");
        int index = foreground ? 0 : parts.length - 1;
        if (index < 0 || index >= parts.length) {
            return null;
        }
        try {
            return ansiPaletteColour(Integer.parseInt(parts[index].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean useColorFgbgHint(String termProgram, String tmux) {
        if (tmux != null && !tmux.isBlank()) {
            return false;
        }
        return termProgram == null || !"tmux".equalsIgnoreCase(termProgram);
    }

    static TextColor parseConfiguredColour(String configured) {
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String trimmed = configured.trim();
        if (trimmed.startsWith("#")) {
            try {
                return TextColor.Factory.fromString(trimmed);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        try {
            return ansiPaletteColour(Integer.parseInt(trimmed));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TextColor ansiPaletteColour(int value) {
        return switch (value) {
        case 0 -> TextColor.ANSI.BLACK;
        case 1 -> TextColor.ANSI.RED;
        case 2 -> TextColor.ANSI.GREEN;
        case 3 -> TextColor.ANSI.YELLOW;
        case 4 -> TextColor.ANSI.BLUE;
        case 5 -> TextColor.ANSI.MAGENTA;
        case 6 -> TextColor.ANSI.CYAN;
        case 7 -> TextColor.ANSI.WHITE;
        case 8 -> TextColor.ANSI.BLACK_BRIGHT;
        case 9 -> TextColor.ANSI.RED_BRIGHT;
        case 10 -> TextColor.ANSI.GREEN_BRIGHT;
        case 11 -> TextColor.ANSI.YELLOW_BRIGHT;
        case 12 -> TextColor.ANSI.BLUE_BRIGHT;
        case 13 -> TextColor.ANSI.MAGENTA_BRIGHT;
        case 14 -> TextColor.ANSI.CYAN_BRIGHT;
        case 15 -> TextColor.ANSI.WHITE_BRIGHT;
        default -> null;
        };
    }

    private static int[] rgbComponents(TextColor colour) {
        if (colour instanceof TextColor.RGB rgb) {
            return new int[] { rgb.getRed(), rgb.getGreen(), rgb.getBlue() };
        }
        if (colour == TextColor.ANSI.BLACK) {
            return new int[] { 0x00, 0x00, 0x00 };
        }
        if (colour == TextColor.ANSI.RED) {
            return new int[] { 0xcd, 0x00, 0x00 };
        }
        if (colour == TextColor.ANSI.GREEN) {
            return new int[] { 0x00, 0xcd, 0x00 };
        }
        if (colour == TextColor.ANSI.YELLOW) {
            return new int[] { 0xcd, 0xcd, 0x00 };
        }
        if (colour == TextColor.ANSI.BLUE) {
            return new int[] { 0x00, 0x00, 0xee };
        }
        if (colour == TextColor.ANSI.MAGENTA) {
            return new int[] { 0xcd, 0x00, 0xcd };
        }
        if (colour == TextColor.ANSI.CYAN) {
            return new int[] { 0x00, 0xcd, 0xcd };
        }
        if (colour == TextColor.ANSI.WHITE) {
            return new int[] { 0xe5, 0xe5, 0xe5 };
        }
        if (colour == TextColor.ANSI.BLACK_BRIGHT) {
            return new int[] { 0x7f, 0x7f, 0x7f };
        }
        if (colour == TextColor.ANSI.RED_BRIGHT) {
            return new int[] { 0xff, 0x00, 0x00 };
        }
        if (colour == TextColor.ANSI.GREEN_BRIGHT) {
            return new int[] { 0x00, 0xff, 0x00 };
        }
        if (colour == TextColor.ANSI.YELLOW_BRIGHT) {
            return new int[] { 0xff, 0xff, 0x00 };
        }
        if (colour == TextColor.ANSI.BLUE_BRIGHT) {
            return new int[] { 0x5c, 0x5c, 0xff };
        }
        if (colour == TextColor.ANSI.MAGENTA_BRIGHT) {
            return new int[] { 0xff, 0x00, 0xff };
        }
        if (colour == TextColor.ANSI.CYAN_BRIGHT) {
            return new int[] { 0x00, 0xff, 0xff };
        }
        if (colour == TextColor.ANSI.WHITE_BRIGHT) {
            return new int[] { 0xff, 0xff, 0xff };
        }
        return new int[] { 0x00, 0x00, 0x00 };
    }

    private record ExtendedColour(TextColor colour, int consumed) {
        private static final ExtendedColour NONE = new ExtendedColour(null, 0);
    }
}
