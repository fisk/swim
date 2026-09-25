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
import org.fisk.swim.event.KeyStroke;
import org.fisk.swim.event.MouseAction;
import org.fisk.swim.event.MouseActionType;
import org.junit.jupiter.api.Test;

class AnsiTerminalBackendTest {
    @Test
    void escapeFollowedByArrowPreservesBothKeys() throws Exception {
        for (String sequence : new String[] {"\u001b[D", "\u001bOD", "\u001b[1;5D"}) {
            var backend = new AnsiTerminalBackend(
                    new ByteArrayInputStream(("\u001b" + sequence + "i").getBytes(StandardCharsets.UTF_8)),
                    new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));
            assertEquals(KeyType.Escape, backend.pollInput().getKeyType());
            assertEquals(KeyType.ArrowLeft, backend.pollInput().getKeyType());
            assertEquals('i', backend.pollInput().getCharacter());
            assertNull(backend.pollInput());
        }
    }

    @Test
    void repeatedEscapePressesRemainSeparateKeys() throws Exception {
        var backend = new AnsiTerminalBackend(
                new ByteArrayInputStream("\u001b\u001b\u001b".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));
        for (int i = 0; i < 3; i++) {
            assertEquals(KeyType.Escape, backend.pollInput().getKeyType());
        }
        assertNull(backend.pollInput());
    }

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
    void preservesIdenticalKeyBurstAfterQuietTransportGap() throws Exception {
        var backend = new AnsiTerminalBackend(new ByteArrayInputStream("jjjj".getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream(), () -> new TerminalDimensions(80, 24));

        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals('j', backend.pollInput().getCharacter());
        assertEquals('j', backend.pollInput().getCharacter());
        assertNull(backend.pollInput());
    }

    @Test
    void retainsKnownPrefixesAcrossPollingTimeouts() throws Exception {
        for (String sequence : new String[] {"\u001b[D", "\u001bOD", "\u001b[1;5D", "\u001b[<0;2;3M"}) {
            for (int split = 2; split < sequence.length(); split++) {
                try (var input = new PipedInputStream(); var writer = new PipedOutputStream(input)) {
                    var backend = new AnsiTerminalBackend(input, new ByteArrayOutputStream(),
                            () -> new TerminalDimensions(80, 24));
                    writer.write(sequence.substring(0, split).getBytes(StandardCharsets.UTF_8));
                    assertNull(backend.pollInput());
                    writer.write((sequence.substring(split) + "x").getBytes(StandardCharsets.UTF_8));
                    assertEquals(sequence.contains("<") ? KeyType.MouseEvent : KeyType.ArrowLeft,
                            backend.pollInput().getKeyType());
                    assertEquals('x', backend.pollInput().getCharacter());
                    assertNull(backend.pollInput());
                }
            }
        }
    }

    @Test
    void consumesUnsupportedAndOverlongSequencesWithoutLeakingText() throws Exception {
        for (String sequence : new String[] {"\u001b[?1;2c", "\u001b[>0;1c", "\u001b[1 q",
                "\u001b[" + "1;".repeat(100) + "D", "\u001b]0;title\u0007",
                "\u001bPpayload\u001b\\", "\u001b(B", "\u001b[999~"}) {
            var backend = backend((sequence + "x").getBytes(StandardCharsets.UTF_8));
            assertEquals(KeyType.Unknown, backend.pollInput().getKeyType(), sequence);
            assertEquals('x', backend.pollInput().getCharacter());
            assertNull(backend.pollInput());
        }
    }

    @Test
    void recoversMalformedUtf8WithoutLosingFollowingTextOrEscape() throws Exception {
        var backend = backend(new byte[] {(byte) 0xc3, 'x', (byte) 0xe2, 0x1b, '[', 'D', 'y'});
        assertEquals('\ufffd', backend.pollInput().getCharacter());
        assertEquals('x', backend.pollInput().getCharacter());
        assertEquals('\ufffd', backend.pollInput().getCharacter());
        assertEquals(KeyType.ArrowLeft, backend.pollInput().getKeyType());
        assertEquals('y', backend.pollInput().getCharacter());
        assertNull(backend.pollInput());
    }

    @Test
    void preservesSupplementaryCharactersInUtf8AndKeyboardProtocols() throws Exception {
        for (String input : new String[] {"😀𐐀x", "\u001b[128512;1u\u001b[27;1;66560~x"}) {
            var backend = backend(input.getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder();
            for (KeyStroke key; (key = backend.pollInput()) != null;) {
                assertEquals(KeyType.Character, key.getKeyType());
                result.append(key.getCharacter());
            }
            assertEquals("😀𐐀x", result.toString());
        }
    }

    @Test
    void retainsUtf8AcrossTimeoutsAndRecoversBeforeAnArrow() throws Exception {
        try (var input = new PipedInputStream(); var writer = new PipedOutputStream(input)) {
            var backend = new AnsiTerminalBackend(input, new ByteArrayOutputStream(),
                    () -> new TerminalDimensions(80, 24));
            byte[] emoji = "😀".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < emoji.length - 1; i++) {
                writer.write(emoji[i]);
                assertNull(backend.pollInput());
            }
            writer.write(emoji[3]);
            assertEquals('\ud83d', backend.pollInput().getCharacter());
            assertEquals('\ude00', backend.pollInput().getCharacter());
            writer.write(0xe2);
            assertNull(backend.pollInput());
            writer.write("\u001b[Dx".getBytes(StandardCharsets.UTF_8));
            assertEquals('\ufffd', backend.pollInput().getCharacter());
            assertEquals(KeyType.ArrowLeft, backend.pollInput().getKeyType());
            assertEquals('x', backend.pollInput().getCharacter());
        }
    }

    @Test
    void retainsUnsupportedSequencesAcrossTimeoutsUntilTheirTerminator() throws Exception {
        for (String[] parts : new String[][] {{"\u001b[?1;", "2c"}, {"\u001b]title\u001b", "\\"},
                {"\u001b[" + "1;".repeat(100), "D"}}) {
            try (var input = new PipedInputStream(); var writer = new PipedOutputStream(input)) {
                var backend = new AnsiTerminalBackend(input, new ByteArrayOutputStream(),
                        () -> new TerminalDimensions(80, 24));
                writer.write(parts[0].getBytes(StandardCharsets.UTF_8));
                assertNull(backend.pollInput());
                writer.write((parts[1] + "x").getBytes(StandardCharsets.UTF_8));
                assertEquals(KeyType.Unknown, backend.pollInput().getKeyType());
                assertEquals('x', backend.pollInput().getCharacter());
            }
        }
    }

    @Test
    void interruptedSequenceResynchronizesAtNextEscape() throws Exception {
        var backend = backend("\u001b[12;\u001b[Dx".getBytes(StandardCharsets.UTF_8));
        assertEquals(KeyType.Unknown, backend.pollInput().getKeyType());
        assertEquals(KeyType.ArrowLeft, backend.pollInput().getKeyType());
        assertEquals('x', backend.pollInput().getCharacter());
    }

    private static AnsiTerminalBackend backend(byte[] bytes) {
        return new AnsiTerminalBackend(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(),
                () -> new TerminalDimensions(80, 24));
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
