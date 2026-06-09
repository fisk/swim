package org.fisk.swim.copy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.event.RecordedKey;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyTest {
    @TempDir
    Path tempDir;

    private FakeClipboard _clipboard;

    @BeforeEach
    void resetCopy() {
        Copy.getInstance().clear();
        _clipboard = new FakeClipboard();
        Copy.setClipboardBridgeForTests(_clipboard);
    }

    @AfterEach
    void resetClipboard() {
        Copy.setClipboardBridgeForTests(null);
    }

    @Test
    void isSingleton() {
        assertSame(Copy.getInstance(), Copy.getInstance());
    }

    @Test
    void storesClipboardTextAndLineMode() {
        var copy = Copy.getInstance();
        copy.setText("hello", true);

        assertEquals("hello", copy.getText());
        assertEquals("hello", _clipboard.text());
        assertTrue(copy.isLine());

        copy.setText("world", false);

        assertEquals("world", copy.getText());
        assertFalse(copy.isLine());
    }

    @Test
    void storesNamedRegistersAndMacros() {
        var copy = Copy.getInstance();
        copy.setText("alpha", true, 'a');
        copy.setMacro('b', List.of(RecordedKey.parseToken("x"), RecordedKey.parseToken("<ESC>")));

        assertEquals("alpha", copy.getText('a'));
        assertTrue(copy.isLine('a'));
        assertEquals(List.of("x", "<ESC>"), copy.getMacro('b').stream().map(RecordedKey::notation).toList());
        assertEquals(Character.valueOf('b'), copy.getLastMacroRegister());
    }

    @Test
    void storesClassicDeleteAndAppendRegisters() {
        var copy = Copy.getInstance();

        copy.setDelete("x", false, null);
        assertEquals("x", copy.getText('-'));

        copy.setDelete("line\n", true, null);
        assertEquals("line\n", copy.getText('1'));

        copy.setYank("a", false, 'q');
        copy.setYank("b", false, 'Q');
        assertEquals("ab", copy.getText('q'));

        copy.setYank("discard", false, '_');
        assertEquals("ab", copy.getText('q'));
        assertEquals("line\n", copy.getText());
    }

    @Test
    void storesBlockPayloads() {
        var copy = Copy.getInstance();

        copy.setBlock(List.of("ab", "cd"), 'b');

        assertTrue(copy.isBlock('b'));
        assertEquals(List.of("ab", "cd"), copy.getValue('b').blockLines());
    }

    @Test
    void unnamedPasteReadsSystemClipboardWhenItChangedOutsideSwim() {
        var copy = Copy.getInstance();
        copy.setYank("inside", true, null);

        _clipboard.setExternalText("outside");

        assertEquals("outside", copy.getText());
        assertFalse(copy.isLine());
    }

    @Test
    void namedRegistersDoNotOverrideExplicitPasteSource() {
        var copy = Copy.getInstance();
        copy.setYank("system", false, null);
        copy.setYank("named", false, 'a');

        assertEquals("system", _clipboard.text());
        _clipboard.setExternalText("outside");

        assertEquals("named", copy.getText('a'));
        assertEquals("outside", copy.getText());
    }

    @Test
    void plusAndStarRegistersUseSystemClipboard() {
        var copy = Copy.getInstance();

        copy.setYank("plus", false, '+');
        assertEquals("plus", _clipboard.text());

        _clipboard.setExternalText("star");
        assertEquals("star", copy.getText('*'));
    }

    @Test
    void sandboxedPasteUsesInProcessRegisterInsteadOfSystemClipboard() throws IOException {
        Path path = tempDir.resolve("sandbox-paste.txt");
        Files.writeString(path, "start\n");
        var copy = Copy.getInstance();
        copy.setYank("inside\n", true, null);
        _clipboard.setExternalText("outside\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var result = harness.getWindow().driveEditorInput("p", 10);

            assertTrue(result.accepted(), result.message());
            assertEquals("start\ninside\n", harness.getWindow().getBufferContext().getBuffer().getString());
            assertEquals("outside\n", _clipboard.text());
        }
    }

    @Test
    void sandboxedYankDoesNotWriteSystemClipboard() throws IOException {
        Path path = tempDir.resolve("sandbox-yank.txt");
        Files.writeString(path, "secret\n");
        _clipboard.setExternalText("outside\n");

        try (var harness = HeadlessWindowHarness.create(path, 60, 12)) {
            var result = harness.getWindow().driveEditorInput("yy", 10);

            assertTrue(result.accepted(), result.message());
            assertEquals("outside\n", _clipboard.text());
            assertEquals("secret\n", Copy.getInstance().registerSnapshot().get('"').text());
        }
    }

    private static final class FakeClipboard implements Copy.ClipboardBridge {
        private String _text = "";

        @Override
        public String getText() {
            return _text;
        }

        @Override
        public void setText(String text) {
            _text = text == null ? "" : text;
        }

        String text() {
            return _text;
        }

        void setExternalText(String text) {
            _text = text == null ? "" : text;
        }
    }
}
