package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
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
    void decodesAnEscapeSequenceSplitByTransportDelay() throws Exception {
        try (var input = new PipedInputStream(); var writer = new PipedOutputStream(input)) {
            writer.write(0x1b);
            writer.flush();
            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(10);
                    writer.write('[');
                    writer.flush();
                    Thread.sleep(10);
                    writer.write('A');
                    writer.flush();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            var backend = new AnsiTerminalBackend(input, new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));

            assertEquals(KeyType.ArrowUp, backend.pollInput().getKeyType());
        }
    }

    @Test
    void suppressesStaleIdenticalKeyBurstAfterQuietTransportGap() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("jjjj".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));

        assertEquals('j', backend.pollInput().getCharacter());
        assertNull(backend.pollInput());
    }

    @Test
    void keepsRepeatedCharactersInBracketedPaste() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("\u001b[200~jjjj\u001b[201~".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));

        assertEquals(KeyType.F18, backend.pollInput().getKeyType());
        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals(KeyType.F19, backend.pollInput().getKeyType());
    }

    @Test
    void asynchronousRefreshDoesNotBlockOnSlowTerminalOutput() throws Exception {
        var output = new BlockingOutputStream();
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream(new byte[0]), output,
                () -> new TerminalDimensions(3, 2), true);
        backend.graphics().putString(0, 0, "x", AnsiStyle.DEFAULT);

        assertTimeout(Duration.ofMillis(100), backend::refresh);
        assertTrue(output.started.await(1, TimeUnit.SECONDS));
        backend.graphics().putString(1, 0, "y", AnsiStyle.DEFAULT);
        assertTimeout(Duration.ofMillis(100), backend::refresh);
        output.release.countDown();
    }

    private static final class BlockingOutputStream extends OutputStream {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
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
    void decodesUtf8TextAsOneCharacter() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("ö".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));

        var key = backend.pollInput();
        assertEquals('ö', key.getCharacter());
        assertNull(backend.pollInput());
    }

    @Test
    void preservesAltForUtf8Text() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("\u001bö".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));

        var key = backend.pollInput();
        assertEquals('ö', key.getCharacter());
        assertEquals(true, key.isAltDown());
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
