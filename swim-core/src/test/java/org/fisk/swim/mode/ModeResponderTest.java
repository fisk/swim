package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.fisk.swim.copy.Copy;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModeResponderTest {
    @TempDir
    Path tempDir;

    @Test
    void normalModeNavigationAndModeSwitchingWorkHeadlessly() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("mode.txt", "alpha\nbeta\ngamma"), 20, 4)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            var cursor = buffer.getCursor();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('G'));
            assertEquals(buffer.getLength(), cursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('g'), HeadlessWindowHarness.key('g'));
            assertEquals(0, cursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            assertEquals(6, cursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('k'));
            assertEquals(0, cursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('$'));
            assertEquals(5, cursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('^'));
            assertEquals(0, cursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.escape());
            assertTrue(window.isShowingPanel());
            assertTrue(HeadlessWindowHarness.getField(window, "_panelView") instanceof ChatPanelView);

            window.hidePanel();
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            assertSame(window.getInputMode(), window.getCurrentMode());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.escape());
            assertSame(window.getNormalMode(), window.getCurrentMode());
            assertEquals(0, cursor.getPosition());
        }
    }

    @Test
    void inputModeCharacterAndArrowRespondersUpdateAllCursors() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("input.txt", "ab\ncd"), 20, 7)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            var secondCursor = new org.fisk.swim.ui.Cursor(window.getBufferContext());
            buffer.addCursor(secondCursor);
            buffer.getCursor().setPosition(0);
            secondCursor.setPosition(3);

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.right());
            assertEquals(1, buffer.getCursor().getPosition());
            assertEquals(4, secondCursor.getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('Z'));
            assertEquals("aZb\ncZd", buffer.getString());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.backspace());
            assertEquals("ab\ncd", buffer.getString());
        }
    }

    @Test
    void visualModesOperateOnSelectionsAndMultiCursorInsert() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("visual.txt", "word\nline\nmore"), 20, 8)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            var cursor = buffer.getCursor();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('v'));
            assertSame(window.getVisualMode(), window.getCurrentMode());
            assertEquals(2, buffer.getCursors().size());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('l'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('l'));
            assertTrue(((VisualMode) window.getCurrentMode()).isSelected(1));

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('y'));
            assertSame(window.getNormalMode(), window.getCurrentMode());
            assertEquals("wor", Copy.getInstance().getText());
            assertFalse(Copy.getInstance().isLine());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('V'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            assertTrue(((VisualLineMode) window.getCurrentMode()).isSelected(6));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('d'));
            assertSame(window.getNormalMode(), window.getCurrentMode());
            assertEquals("more", buffer.getString());

            window.setBufferPath(writeFile("block.txt", "ax\nby\ncz"));
            cursor = window.getBufferContext().getBuffer().getCursor();
            buffer = window.getBufferContext().getBuffer();
            cursor.setPosition(0);
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.ctrl('v'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            assertTrue(((VisualBlockMode) window.getCurrentMode()).isSelected(0));
            assertTrue(((VisualBlockMode) window.getCurrentMode()).isSelected(6));

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('I'));
            assertSame(window.getInputMode(), window.getCurrentMode());
            assertEquals(3, buffer.getCursors().size());
        }
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
