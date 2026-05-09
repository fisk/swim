package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.event.KeyStrokes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

class ChatPanelViewTest {
    @TempDir
    Path tempDir;

    @Test
    void enterSubmitsTypedMessageAndClearsInput() {
        var submitted = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", submitted::set);

        dispatch(view, new KeyStroke('h', false, false));
        dispatch(view, new KeyStroke('i', false, false));
        dispatch(view, new KeyStroke(KeyType.Enter));

        assertEquals("hi", submitted.get());
        assertEquals("", view.getInputText());
    }

    @Test
    void appendMessageAddsPrefixedTranscriptLines() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        view.appendMessage("me", "hello");
        view.appendMessage("nemo", "world");

        assertEquals(List.of("me> hello", "nemo> world"), view.getDisplayLines());
    }

    @Test
    void pendingStateBlocksTyping() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});
        view.setPending(true);

        dispatch(view, new KeyStroke('x', false, false));

        assertEquals("", view.getInputText());
        assertTrue(view.isPending());
    }

    @Test
    void escapeClosesActiveChatPanel() throws IOException {
        try (var harness = HeadlessWindowHarness.create(writeFile("chat-panel.txt", "abc"), 24, 11)) {
            var window = harness.getWindow();
            var panel = new ChatPanelView(Rect.create(0, 0, 0, 0), "Nemo", ignored -> {});
            window.showPanel(panel);

            dispatch(panel, new KeyStroke(KeyType.Escape));

            assertFalse(window.isShowingPanel());
        }
    }

    private static void dispatch(ChatPanelView view, KeyStroke key) {
        view.processEvent(new KeyStrokes(List.of(key)));
        view.respond();
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
