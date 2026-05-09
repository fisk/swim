package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.text.AttributedString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.googlecode.lanterna.TextColor;
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
        assertEquals(List.of("nemo> *thinking*"), view.getDisplayLines());
    }

    @Test
    void replyPromptPrefixesUseDistinctColours() throws Exception {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        AttributedString meLine = renderLine(view, "me> hello");
        AttributedString nemoLine = renderLine(view, "nemo> hello");

        assertEquals(TextColor.ANSI.RED, foreground(meLine, 0));
        assertEquals("hello", meLine.getFragments().get(1).toString());
        assertEquals(TextColor.ANSI.GREEN, foreground(nemoLine, 0));
        assertEquals(TextColor.ANSI.DEFAULT, foreground(nemoLine, 1));
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

    private static AttributedString renderLine(ChatPanelView view, String line) throws Exception {
        Method method = ChatPanelView.class.getDeclaredMethod("renderLine", String.class);
        method.setAccessible(true);
        return (AttributedString) method.invoke(view, line);
    }

    private static TextColor foreground(AttributedString line, int fragmentIndex) throws Exception {
        var attributes = line.getFragments().get(fragmentIndex).getAttributes();
        var field = attributes.getClass().getDeclaredField("_foregroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attributes);
    }

    private Path writeFile(String name, String text) throws IOException {
        Path path = tempDir.resolve(name);
        Files.writeString(path, text);
        return path;
    }
}
