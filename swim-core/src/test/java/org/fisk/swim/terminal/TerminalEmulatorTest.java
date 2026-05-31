package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class TerminalEmulatorTest {
    @Test
    void carriageReturnOverwritesCurrentLine() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("abc\rXY");

        assertEquals('X', emulator.screen().cellAt(0, 0).character());
        assertEquals('Y', emulator.screen().cellAt(0, 1).character());
        assertEquals('c', emulator.screen().cellAt(0, 2).character());
    }

    @Test
    void csiCursorPositionPlacesTextAtRequestedCoordinates() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("a\u001b[2;5Hb");

        assertEquals('a', emulator.screen().cellAt(0, 0).character());
        assertEquals('b', emulator.screen().cellAt(1, 4).character());
    }

    @Test
    void sgrChangesForegroundColour() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("\u001b[31mR\u001b[0mN");

        assertEquals(TextColor.ANSI.RED, emulator.screen().cellAt(0, 0).style().foreground());
        assertEquals(TextColor.ANSI.DEFAULT, emulator.screen().cellAt(0, 1).style().foreground());
    }

    @Test
    void eraseLineClearsFromCursorToEnd() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("abcdef");
        emulator.feed("\r\u001b[2C\u001b[K");

        assertEquals('a', emulator.screen().cellAt(0, 0).character());
        assertEquals('b', emulator.screen().cellAt(0, 1).character());
        assertEquals(' ', emulator.screen().cellAt(0, 2).character());
        assertEquals(' ', emulator.screen().cellAt(0, 5).character());
    }

    @Test
    void scrollRegionScrollsOnlyWithinMargins() {
        var emulator = new TerminalEmulator(5, 4);

        emulator.feed("1\r\n2\r\n3\r\n4");
        emulator.feed("\u001b[2;3r");
        emulator.feed("\u001b[2;1H");
        emulator.feed("A\r\nB\r\n");

        assertEquals('1', emulator.screen().cellAt(0, 0).character());
        assertEquals('B', emulator.screen().cellAt(1, 0).character());
        assertEquals(' ', emulator.screen().cellAt(2, 0).character());
        assertEquals('4', emulator.screen().cellAt(3, 0).character());
    }

    @Test
    void originModeMakesCursorAddressingRelativeToScrollRegion() {
        var emulator = new TerminalEmulator(6, 5);

        emulator.feed("\u001b[2;4r");
        emulator.feed("\u001b[?6h");
        emulator.feed("\u001b[1;1HX");

        assertEquals('X', emulator.screen().cellAt(1, 0).character());
        assertEquals(' ', emulator.screen().cellAt(0, 0).character());
    }

    @Test
    void programmableTabStopsCanBeSetAndCleared() {
        var emulator = new TerminalEmulator(12, 3);

        emulator.feed("\u001b[3g");
        emulator.feed("\u001b[5G\u001bH");
        emulator.feed("\r\tX");

        assertEquals('X', emulator.screen().cellAt(0, 4).character());

        emulator.feed("\r\u001b[5G\u001b[g");
        emulator.feed("\r\tY");

        assertEquals('Y', emulator.screen().cellAt(0, 11).character());
    }

    @Test
    void autoWrapCanBeDisabled() {
        var emulator = new TerminalEmulator(4, 2);

        emulator.feed("\u001b[?7lABCDE");

        assertEquals('A', emulator.screen().cellAt(0, 0).character());
        assertEquals('B', emulator.screen().cellAt(0, 1).character());
        assertEquals('C', emulator.screen().cellAt(0, 2).character());
        assertEquals('E', emulator.screen().cellAt(0, 3).character());
        assertEquals(' ', emulator.screen().cellAt(1, 0).character());
    }

    @Test
    void cursorVisibilityTracksPrivateMode() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("\u001b[?25l");
        assertFalse(emulator.cursorVisible());

        emulator.feed("\u001b[?25h");
        assertTrue(emulator.cursorVisible());
    }

    @Test
    void sgrSupportsIndexedAndRgbColours() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("\u001b[38;5;196mI\u001b[38;2;12;34;56mR");

        var indexed = assertInstanceOf(TextColor.Indexed.class, emulator.screen().cellAt(0, 0).style().foreground());
        assertEquals(new TextColor.Indexed(196), indexed);
        var rgb = assertInstanceOf(TextColor.RGB.class, emulator.screen().cellAt(0, 1).style().foreground());
        assertEquals(12, rgb.getRed());
        assertEquals(34, rgb.getGreen());
        assertEquals(56, rgb.getBlue());
    }

    @Test
    void brightForegroundAndBackgroundCodesDoNotBleedIntoEachOther() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("\u001b[94mF\u001b[104mB");

        assertEquals(TextColor.ANSI.BLUE_BRIGHT, emulator.screen().cellAt(0, 0).style().foreground());
        assertEquals(TextColor.ANSI.DEFAULT, emulator.screen().cellAt(0, 0).style().background());
        assertEquals(TextColor.ANSI.BLUE_BRIGHT, emulator.screen().cellAt(0, 1).style().background());
    }

    @Test
    void decPrivateModesTrackMouseAndBracketedPasteState() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("\u001b[?1002h\u001b[?1006h\u001b[?2004h\u001b[?1004h");

        assertEquals(TerminalEmulator.MouseTrackingMode.DRAG, emulator.mouseTrackingMode());
        assertTrue(emulator.sgrMouseMode());
        assertTrue(emulator.bracketedPasteMode());
        assertTrue(emulator.focusReportingMode());

        emulator.feed("\u001b[?1002l\u001b[?1006l\u001b[?2004l\u001b[?1004l");

        assertEquals(TerminalEmulator.MouseTrackingMode.OFF, emulator.mouseTrackingMode());
        assertFalse(emulator.sgrMouseMode());
        assertFalse(emulator.bracketedPasteMode());
        assertFalse(emulator.focusReportingMode());
    }

    @Test
    void deviceStatusReportReturnsCursorPosition() {
        var emulator = new TerminalEmulator(10, 4);
        var response = new StringBuilder();
        emulator.setDeviceResponseHandler(response::append);

        emulator.feed("\u001b[2;5H\u001b[6n");

        assertEquals("\u001b[2;5R", response.toString());
    }

    @Test
    void primaryDeviceAttributesAdvertiseVt200Profile() {
        var emulator = new TerminalEmulator(10, 4);
        var response = new StringBuilder();
        emulator.setDeviceResponseHandler(response::append);

        emulator.feed("\u001b[c");

        assertEquals("\u001b[?1;2;4c", response.toString());
    }

    @Test
    void oscTitleSequenceIsIgnoredInsteadOfRendered() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("\u001b]2;swim title\u0007X");

        assertEquals('X', emulator.screen().cellAt(0, 0).character());
    }

    @Test
    void oscBackgroundColorQueryUsesColorFgbgHint() {
        assertEquals("\u001b]11;rgb:7f7f/7f7f/7f7f\u0007",
                TerminalEmulator.formatOscColorQueryResponse("11", "12;8", null, null, null, null));
    }

    @Test
    void oscBackgroundColorQueryIgnoresTmuxColorFgbgHint() {
        assertEquals(null,
                TerminalEmulator.formatOscColorQueryResponse("11", "12;8", "tmux", "/tmp/tmux", null, null));
    }

    @Test
    void oscBackgroundColorQueryPrefersExplicitOverride() {
        assertEquals("\u001b]11;rgb:1111/2222/3333\u0007",
                TerminalEmulator.formatOscColorQueryResponse("11", "12;8", "tmux", "/tmp/tmux", null, "#112233"));
    }

    @Test
    void oscBackgroundColorQueryWithoutHintReturnsNoResponse() {
        assertEquals(null,
                TerminalEmulator.formatOscColorQueryResponse("11", null, null, null, null, null));
    }

    @Test
    void alternateScreenPreservesNormalScreenContents() {
        var emulator = new TerminalEmulator(10, 4);

        emulator.feed("main");
        emulator.feed("\u001b[?1049hALT");
        emulator.feed("\u001b[?1049l");

        assertEquals('m', emulator.screen().cellAt(0, 0).character());
        assertEquals('a', emulator.screen().cellAt(0, 1).character());
        assertEquals('i', emulator.screen().cellAt(0, 2).character());
        assertEquals('n', emulator.screen().cellAt(0, 3).character());
    }
}
