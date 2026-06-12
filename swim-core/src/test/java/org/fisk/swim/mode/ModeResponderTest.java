package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fisk.swim.copy.Copy;
import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.todo.TodoUiSupport;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.TodoWorkspaceView;
import org.fisk.swim.ui.Window;
import org.fisk.swim.ui.WindowLayoutTestSupport;
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

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('!'));
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
    void normalModeWordJumpUsesVisibleHints() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("jump.txt", "alpha beta bravo"), 30, 6)) {
            Window window = harness.getWindow();
            var cursor = window.getBufferContext().getBuffer().getCursor();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(),
                    HeadlessWindowHarness.key('g'),
                    HeadlessWindowHarness.key('w'),
                    HeadlessWindowHarness.key('b'),
                    HeadlessWindowHarness.key('b'));

            assertEquals(11, cursor.getPosition());
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
    void inputModeEnterInsideElseBlockKeepsTypingOnIndentedLine() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("ElseBlock.java", """
                class Demo {
                    void run() {
                        if (flag) {
                        } else {}
                    }
                }
                """), 80, 16)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            int insertPosition = buffer.getString().indexOf("else {}") + "else {".length();
            buffer.getCursor().setPosition(insertPosition);

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('x'));

            assertEquals("""
                    class Demo {
                        void run() {
                            if (flag) {
                            } else {
                                x
                            }
                        }
                    }
                    """, buffer.getString());
            assertEquals(buffer.getString().indexOf("x") + 1, buffer.getCursor().getPosition());
        }
    }

    @Test
    void inputModeElseBlockCursorRendersOnInsertedLine() throws IOException {
        var installedTerminal = TerminalContextTestSupport.install(80, 16);
        try (var harness = HeadlessWindowHarness.create(writeFile("ElseCursor.java", """
                class Demo {
                    void run() {
                        if (flag) {
                        } else {}
                    }
                }
                """), 80, 16)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            int insertPosition = buffer.getString().indexOf("else {}") + "else {".length();
            buffer.getCursor().setPosition(insertPosition);

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('i'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('x'));

            window.update(true);

            int xIndex = buffer.getString().indexOf("x");
            var line = window.getBufferContext().getTextLayout().getPhysicalLineAt(xIndex + 1);
            var bufferBounds = absoluteBounds(window.getBufferContext().getBufferView());
            int expectedColumn = bufferBounds.getPoint().getX()
                    + window.getBufferContext().getBufferView().getTextColumnStart()
                    + (xIndex + 1) - line.getStartPosition();
            int expectedRow = bufferBounds.getPoint().getY() + line.getY();
            var cursorPosition = installedTerminal.cursorPosition().get();
            assertEquals(expectedColumn, cursorPosition.getColumn());
            assertEquals(expectedRow, cursorPosition.getRow());
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void appendInsideElseBlockKeepsCursorAndInsertedTextOnSameLine() throws IOException {
        var installedTerminal = TerminalContextTestSupport.install(80, 16);
        try (var harness = HeadlessWindowHarness.create(writeFile("ElseAppend.java", """
                class Demo {
                    void run() {
                        if (flag) {
                        } else {}
                    }
                }
                """), 80, 16)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            int bracePosition = buffer.getString().indexOf("else {}") + "else ".length();
            buffer.getCursor().setPosition(bracePosition);

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('a'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.enter());
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('x'));

            window.update(true);

            assertEquals("""
class Demo {
    void run() {
        if (flag) {
        } else {
            x
        }
    }
}
""", buffer.getString());
            int xIndex = buffer.getString().indexOf("x");
            var line = window.getBufferContext().getTextLayout().getPhysicalLineAt(xIndex + 1);
            var bufferBounds = absoluteBounds(window.getBufferContext().getBufferView());
            int expectedColumn = bufferBounds.getPoint().getX()
                    + window.getBufferContext().getBufferView().getTextColumnStart()
                    + (xIndex + 1) - line.getStartPosition();
            int expectedRow = bufferBounds.getPoint().getY() + line.getY();
            var cursorPosition = installedTerminal.cursorPosition().get();
            assertEquals(expectedColumn, cursorPosition.getColumn());
            assertEquals(expectedRow, cursorPosition.getRow());
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    @Test
    void cursorRendersAtAbsolutePositionInSplitPane() throws IOException {
        var installedTerminal = TerminalContextTestSupport.install(80, 16);
        try (var harness = HeadlessWindowHarness.create(writeFile("SplitCursor.java", "alpha\nbeta\n"), 80, 16)) {
            Window window = harness.getWindow();
            window.splitActiveBufferHorizontally();
            var buffer = window.getBufferContext().getBuffer();
            int index = buffer.getString().indexOf("beta") + 2;
            buffer.getCursor().setPosition(index);

            window.update(true);

            var line = window.getBufferContext().getTextLayout().getPhysicalLineAt(index);
            var bufferBounds = absoluteBounds(window.getBufferContext().getBufferView());
            int expectedColumn = bufferBounds.getPoint().getX()
                    + window.getBufferContext().getBufferView().getTextColumnStart()
                    + index - line.getStartPosition();
            int expectedRow = bufferBounds.getPoint().getY() + line.getY();
            var cursorPosition = installedTerminal.cursorPosition().get();
            assertEquals(expectedColumn, cursorPosition.getColumn());
            assertEquals(expectedRow, cursorPosition.getRow());
        } finally {
            TerminalContext.shutdownInstance();
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

    @Test
    void leaderMovesAndIndentsVisualLineSelection() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("visual-leader.txt", """
                one
                two
                three
                four
                """), 30, 10)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('V'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key('j'));

            assertSame(window.getVisualLineMode(), window.getCurrentMode());
            assertEquals("""
                    three
                    one
                    two
                    four
                    """, buffer.getString());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key('l'));
            assertEquals("""
                    three
                        one
                        two
                    four
                    """, buffer.getString());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key(' '),
                    HeadlessWindowHarness.key('h'));
            assertEquals("""
                    three
                    one
                    two
                    four
                    """, buffer.getString());
        }
    }

    @Test
    void leaderMoveCountMovesVisualSelectionMultipleRows() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("visual-leader-count.txt", """
                one
                two
                three
                four
                """), 30, 10)) {
            Window window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('V'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('2'),
                    HeadlessWindowHarness.key(' '), HeadlessWindowHarness.key('j'));

            assertSame(window.getVisualLineMode(), window.getCurrentMode());
            assertEquals("""
                    three
                    four
                    one
                    two
                    """, buffer.getString());
        }
    }

    @Test
    void ctrlWPaneBindingsSplitFocusAndTargetTheFocusedBuffer() throws IOException {
        Path first = writeFile("pane-left.txt", "left\npane");
        Path second = writeFile("pane-right.txt", "right\npane");

        try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
            Window window = harness.getWindow();
            var leftView = window.getBufferContext().getBufferView();
            var leftBuffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.ctrl('w'),
                    HeadlessWindowHarness.key('v'));
            assertEquals(WindowLayoutTestSupport.absoluteRightSplitLeaf(24, 11).toString(),
                    absoluteBounds((org.fisk.swim.ui.View) window.getActiveView()).toString());

            window.setBufferPath(second);
            var rightBuffer = window.getBufferContext().getBuffer();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.ctrl('w'),
                    HeadlessWindowHarness.key('h'));
            assertSame(leftView, window.getActiveView());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('j'));
            assertEquals(5, leftBuffer.getCursor().getPosition());
            assertEquals(0, rightBuffer.getCursor().getPosition());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.ctrl('w'),
                    HeadlessWindowHarness.key('l'));
            assertEquals("right\npane", window.getBufferContext().getBuffer().getString());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.ctrl('w'),
                    HeadlessWindowHarness.key('q'));
            assertSame(leftView, window.getActiveView());
            assertEquals(WindowLayoutTestSupport.workspace(24, 11).toString(), absoluteBounds(leftView).toString());
        }
    }

    @Test
    void todoCommandOpensFullscreenTodoWorkspace() throws IOException {
        Path first = writeFile("todo-binding.txt", "left\npane");
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        TodoUiSupport.shutdownInstance();
        try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
            Window window = harness.getWindow();

            window.getCommandView().execute("todo");

            assertTrue(window.isShowingTodoWorkspace());
            assertTrue(window.getActiveView() instanceof TodoWorkspaceView);
            assertFalse(window.isShowingPanel());
            assertEquals(WindowLayoutTestSupport.workspace(24, 11).toString(),
                    absoluteBounds((org.fisk.swim.ui.View) window.getActiveView()).toString());

            HeadlessWindowHarness.dispatch((TodoWorkspaceView) window.getActiveView(), HeadlessWindowHarness.key('q'));
            assertFalse(window.isShowingTodoWorkspace());
            assertSame(window.getBufferContext().getBufferView(), window.getActiveView());
        } finally {
            TodoUiSupport.shutdownInstance();
            System.setProperty("user.home", previousHome);
        }
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }

    private static org.fisk.swim.ui.Rect absoluteBounds(org.fisk.swim.ui.View view) {
        int x = view.getBounds().getPoint().getX();
        int y = view.getBounds().getPoint().getY();
        org.fisk.swim.ui.View parent = view.getParent();
        while (parent != null) {
            x += parent.getBounds().getPoint().getX();
            y += parent.getBounds().getPoint().getY();
            parent = parent.getParent();
        }
        return org.fisk.swim.ui.Rect.create(x, y, view.getBounds().getSize().getWidth(),
                view.getBounds().getSize().getHeight());
    }

}
