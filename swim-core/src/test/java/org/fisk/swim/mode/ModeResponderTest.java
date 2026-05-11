package org.fisk.swim.mode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.SwimRuntime;
import org.fisk.swim.api.SwimHost;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.copy.Copy;
import org.fisk.swim.ui.ChatPanelView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.PluginPanelView;
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
            assertEquals("{12, 2, 12, 7}", absoluteBounds((org.fisk.swim.ui.View) window.getActiveView()).toString());

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
            assertEquals("{0, 2, 24, 7}", absoluteBounds(leftView).toString());
        }
    }

    @Test
    void treeBindingLoadsPluginPanelAndOpensSelectedFile() throws IOException {
        Path first = writeFile("tree-left.txt", "left\npane");
        Path second = writeFile("tree-right.txt", "right\npane");
        RecordingHost host = new RecordingHost(new FakeTreePanel(second));
        SwimRuntime.setHost(host);

        try (var harness = HeadlessWindowHarness.create(first, 24, 11)) {
            Window window = harness.getWindow();

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('t'));

            assertEquals("swim-tree-view", host.pluginId);
            assertTrue(window.getPanelView() instanceof PluginPanelView);
            assertEquals("{0, 2, 6, 7}", absoluteBounds((org.fisk.swim.ui.View) window.getPanelView()).toString());
            assertEquals("{6, 2, 18, 7}", absoluteBounds(window.getBufferContext().getBufferView()).toString());

            HeadlessWindowHarness.dispatch((PluginPanelView) window.getPanelView(), HeadlessWindowHarness.enter());

            assertEquals("right\npane", window.getBufferContext().getBuffer().getString());
            assertSame(window.getBufferContext().getBufferView(), window.getActiveView());

            HeadlessWindowHarness.dispatch(window.getCurrentMode(), HeadlessWindowHarness.key('t'));
            assertFalse(window.isShowingPanel());
        } finally {
            SwimRuntime.clear();
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

    private static final class RecordingHost implements SwimHost {
        private final SwimPanel panel;
        private String pluginId;

        private RecordingHost(SwimPanel panel) {
            this.panel = panel;
        }

        @Override
        public void requestReload(Path path) {
        }

        @Override
        public void requestRebuildAndReload(Path path) {
        }

        @Override
        public void requestLoadPlugin(String pluginId, Path path) {
            this.pluginId = pluginId;
        }

        @Override
        public SwimPanel getPanel(String pluginId) {
            return panel;
        }

        @Override
        public void requestExit() {
        }

        @Override
        public Path getBuildRoot() {
            return tempPath();
        }

        private Path tempPath() {
            return Path.of("/tmp");
        }
    }

    private static final class FakeTreePanel implements SwimPanel {
        private final Path _openPath;

        private FakeTreePanel(Path openPath) {
            _openPath = openPath;
        }

        @Override
        public String getId() {
            return "swim-tree-view";
        }

        @Override
        public String getTitle() {
            return "Tree";
        }

        @Override
        public List<String> render(int width, int height) {
            return List.of("Tree", "> - " + _openPath.getFileName());
        }

        @Override
        public SwimPanelResult handleInput(String input, int width, int height) {
            return "enter".equals(input) ? SwimPanelResult.success(_openPath) : SwimPanelResult.success();
        }
    }
}
