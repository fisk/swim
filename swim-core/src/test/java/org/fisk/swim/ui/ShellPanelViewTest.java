package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.fisk.swim.terminal.TerminalEmulator;
import org.junit.jupiter.api.Test;

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
}
