package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.fisk.swim.event.KeyStrokes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

class TextPanelViewTest {
    @TempDir
    Path tempDir;

    @Test
    void wrapsTextToViewWidth() {
        assertEquals(List.of("alpha beta", "gamma", "delta"),
                TextPanelView.wrapText("alpha beta gamma delta", 10));
    }

    @Test
    void scrollsDownAndBackUp() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("text-panel.txt", "abc"), 20, 8)) {
            var window = harness.getWindow();
            var panel = new TextPanelView(Rect.create(0, 0, 20, 4), "Nemo", "one\ntwo\nthree\nfour\nfive");

            dispatch(panel, new KeyStroke(KeyType.ArrowDown));
            dispatch(panel, new KeyStroke('j', false, false));

            assertEquals(2, panel.getStartLine());

            dispatch(panel, new KeyStroke(KeyType.ArrowUp));

            assertEquals(1, panel.getStartLine());
        }
    }

    @Test
    void escapeClosesActivePanel() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("text-panel-close.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            window.showTextPanel("Nemo", "alpha");
            var panel = (TextPanelView) HeadlessWindowHarness.getField(window, "_panelView");

            dispatch(panel, new KeyStroke(KeyType.Escape));

            assertFalse(window.isShowingPanel());
        }
    }

    private static void dispatch(TextPanelView view, KeyStroke key) {
        view.processEvent(new KeyStrokes(List.of(key)));
        view.respond();
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
