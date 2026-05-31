package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import java.lang.ProcessBuilder;

import org.fisk.swim.terminal.TerminalCell;
import org.fisk.swim.terminal.TerminalEmulator;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

class ShellPanelViewTest {
    @Test
    void altCharacterPrefixesEscape() {
        assertArrayEquals(new byte[] { 0x1b, 'x' }, ShellPanelView.encodeKeyStroke(new KeyStroke('x', false, true), false));
    }

    @Test
    void applicationCursorModeUsesSs3ArrowEncoding() {
        assertArrayEquals("\u001bOA".getBytes(), ShellPanelView.encodeKeyStroke(new KeyStroke(KeyType.ArrowUp), true));
    }

    @Test
    void normalCursorModeUsesCsiArrowEncoding() {
        assertArrayEquals("\u001b[A".getBytes(), ShellPanelView.encodeKeyStroke(new KeyStroke(KeyType.ArrowUp), false));
    }

    @Test
    void bracketedPasteWrapsPayloadWhenEnabled() {
        assertArrayEquals("\u001b[200~hello\u001b[201~".getBytes(), ShellPanelView.encodePaste("hello", true));
        assertArrayEquals("hello".getBytes(), ShellPanelView.encodePaste("hello", false));
    }

    @Test
    void mouseDragUsesSgrEncodingWhenEnabled() {
        var emulator = new TerminalEmulator(20, 10);
        emulator.feed("\u001b[?1002h\u001b[?1006h");
        var action = new MouseAction(MouseActionType.DRAG, 1, new TerminalPosition(6, 4));

        assertArrayEquals("\u001b[<32;5;4M".getBytes(),
                ShellPanelView.encodeMouseAction(action, emulator, Point.create(2, 1), Size.create(10, 8)));
    }

    @Test
    void shellDefaultAnsiColoursArePreserved() throws Exception {
        assertEquals(TextColor.ANSI.DEFAULT, invokeColourResolver("resolveShellForeground", TextColor.ANSI.DEFAULT));
        assertEquals(TextColor.ANSI.DEFAULT, invokeColourResolver("resolveShellBackground", TextColor.ANSI.DEFAULT));
    }

    @Test
    void shellBoldAttributeIsPreservedWhenRenderingCells() {
        var rendered = ShellPanelView.toTextCharacter(
                new TerminalCell('x', new org.fisk.swim.terminal.TerminalStyle(TextColor.ANSI.RED, TextColor.ANSI.DEFAULT, true, false)));

        assertEquals('x', rendered.getCharacter());
        assertEquals(TextColor.ANSI.RED, rendered.getForegroundColor());
        assertEquals(TextColor.ANSI.DEFAULT, rendered.getBackgroundColor());
        assertEquals(true, rendered.isBold());
    }

    @Test
    void shellReverseAttributeIsPreservedWhenRenderingCells() {
        var rendered = ShellPanelView.toTextCharacter(
                new TerminalCell('x', new org.fisk.swim.terminal.TerminalStyle(TextColor.ANSI.DEFAULT, TextColor.ANSI.DEFAULT, false, true)));

        assertEquals('x', rendered.getCharacter());
        assertEquals(TextColor.ANSI.DEFAULT, rendered.getForegroundColor());
        assertEquals(TextColor.ANSI.DEFAULT, rendered.getBackgroundColor());
        assertEquals(true, rendered.isReversed());
    }

    @Test
    void shellProcessUsesTerminalEnvironmentForEmbeddedTerminal() throws Exception {
        ProcessBuilder builder = invokeProcessBuilder("/bin/sh", Size.create(80, 24));

        assertEquals(true, builder.environment().containsKey("TERM"));
        assertEquals("80", builder.environment().get("COLUMNS"));
        assertEquals("24", builder.environment().get("LINES"));
        assertNull(builder.environment().get("TMUX"));
    }

    private static TextColor invokeColourResolver(String methodName, TextColor colour) throws Exception {
        Method method = ShellPanelView.class.getDeclaredMethod(methodName, TextColor.class);
        method.setAccessible(true);
        return (TextColor) method.invoke(null, colour);
    }

    private static ProcessBuilder invokeProcessBuilder(String shellCommand, Size size) throws Exception {
        Method method = ShellPanelView.class.getDeclaredMethod("createProcessBuilder", String.class, Size.class);
        method.setAccessible(true);
        return (ProcessBuilder) method.invoke(null, shellCommand, size);
    }
}
