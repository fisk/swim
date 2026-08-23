package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.event.KeyType;
import org.fisk.swim.event.MouseAction;
import org.fisk.swim.event.MouseActionType;
import org.junit.jupiter.api.Test;

class AnsiTerminalBackendTest {
    @Test
    void rendersThroughOwnedScreenAndDetectsResize() throws Exception {
        var output = new ByteArrayOutputStream();
        var dimensions = new AtomicReference<>(new TerminalDimensions(3, 2));
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream(new byte[0]), output, dimensions::get);

        backend.start();
        backend.graphics().putString(1, 0, "x", AnsiStyle.DEFAULT);
        backend.refresh();
        assertEquals("\u001b[?1049h\u001b[?25l\u001b[?2004h\u001b[?1000h\u001b[?1006h\u001b[>4;2m\u001b[1;2H\u001b[0;39;49mx", output.toString());
        assertNull(backend.resizeIfNeeded());

        dimensions.set(new TerminalDimensions(4, 2));
        assertEquals(new TerminalDimensions(4, 2), backend.resizeIfNeeded());
    }

    @Test
    void decodesBasicAnsiCursorKeys() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("\u001b[A".getBytes()), new ByteArrayOutputStream(),
                () -> new TerminalDimensions(80, 24));
        assertEquals(KeyType.ArrowUp, backend.pollInput().getKeyType());
    }

    @Test
    void decodesAsciiControlBytesAsCtrlModifiedCharacters() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream(new byte[] { 0x05, 0x19 }), new ByteArrayOutputStream(),
                () -> new TerminalDimensions(80, 24));

        var ctrlE = backend.pollInput();
        assertEquals('e', ctrlE.getCharacter());
        assertEquals(true, ctrlE.isCtrlDown());
        var ctrlY = backend.pollInput();
        assertEquals('y', ctrlY.getCharacter());
        assertEquals(true, ctrlY.isCtrlDown());
    }

    @Test
    void preservesModifiersForAltAndProtocolEncodedCharacters() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream(
                "\u001bx\u001b\u0005\u001b[27;3;120~\u001b[121;5u\u001b[1;5A".getBytes()), new ByteArrayOutputStream(),
                () -> new TerminalDimensions(80, 24));

        var altX = backend.pollInput();
        assertEquals('x', altX.getCharacter());
        assertEquals(true, altX.isAltDown());
        var altCtrlE = backend.pollInput();
        assertEquals('e', altCtrlE.getCharacter());
        assertEquals(true, altCtrlE.isAltDown());
        assertEquals(true, altCtrlE.isCtrlDown());
        var protocolAltX = backend.pollInput();
        assertEquals('x', protocolAltX.getCharacter());
        assertEquals(true, protocolAltX.isAltDown());
        var protocolCtrlY = backend.pollInput();
        assertEquals('y', protocolCtrlY.getCharacter());
        assertEquals(true, protocolCtrlY.isCtrlDown());
        var ctrlUp = backend.pollInput();
        assertEquals(KeyType.ArrowUp, ctrlUp.getKeyType());
        assertEquals(true, ctrlUp.isCtrlDown());
    }

    @Test
    void decodesSgrMousePress() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("\u001b[<0;7;4M".getBytes()), new ByteArrayOutputStream(),
                () -> new TerminalDimensions(80, 24));
        var action = (MouseAction) backend.pollInput();
        assertEquals(MouseActionType.CLICK_DOWN, action.getActionType());
        assertEquals(1, action.getButton());
        assertEquals(new MouseAction.Position(6, 3), action.getPosition());
    }

    @Test
    void decodesBracketedPasteAndModifiedEnter() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("\u001b[200~\u001b[27;2;13~".getBytes()),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));
        assertEquals(KeyType.F18, backend.pollInput().getKeyType());
        var enter = backend.pollInput();
        assertEquals(KeyType.Enter, enter.getKeyType());
        assertEquals(true, enter.isShiftDown());
    }
}
