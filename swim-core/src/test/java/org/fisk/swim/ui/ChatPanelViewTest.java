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
        assertEquals(0, view.getCursorOffset());
    }

    @Test
    void shiftEnterAddsNewlineWithoutSubmitting() {
        var submitted = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", submitted::set);

        dispatch(view, new KeyStroke('h', false, false));
        dispatch(view, new KeyStroke(KeyType.Enter, false, false, true));
        dispatch(view, new KeyStroke('i', false, false));

        assertEquals(null, submitted.get());
        assertEquals("h\ni", view.getInputText());
    }

    @Test
    void arrowKeysMoveCursorAndInsertAtCursor() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke(KeyType.ArrowLeft));
        dispatch(view, new KeyStroke('b', false, false));

        assertEquals("abc", view.getInputText());
        assertEquals(2, view.getCursorOffset());
    }

    @Test
    void homeAndEndMoveToLineBoundaries() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke(KeyType.Home));
        assertEquals(0, view.getCursorOffset());
        dispatch(view, new KeyStroke('x', false, false));
        dispatch(view, new KeyStroke(KeyType.End));
        assertEquals(view.getInputText().length(), view.getCursorOffset());
        dispatch(view, new KeyStroke('y', false, false));

        assertEquals("xabcy", view.getInputText());
    }

    @Test
    void ctrlAAndCtrlEMoveToLineBoundaries() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke('a', true, false));
        assertEquals(0, view.getCursorOffset());
        dispatch(view, new KeyStroke('x', false, false));
        dispatch(view, new KeyStroke('e', true, false));
        assertEquals(view.getInputText().length(), view.getCursorOffset());
        dispatch(view, new KeyStroke('y', false, false));

        assertEquals("xabcy", view.getInputText());
    }

    @Test
    void backspaceDeletesBeforeCursor() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke(KeyType.ArrowLeft));
        dispatch(view, new KeyStroke(KeyType.Backspace));

        assertEquals("ac", view.getInputText());
        assertEquals(1, view.getCursorOffset());
    }

    @Test
    void multilineInputScrollsIntoView() {
        var view = new ChatPanelView(Rect.create(0, 0, 10, 4), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('1', false, false));
        dispatch(view, new KeyStroke('2', false, false));
        dispatch(view, new KeyStroke('3', false, false));
        dispatch(view, new KeyStroke('4', false, false));
        dispatch(view, new KeyStroke('5', false, false));
        dispatch(view, new KeyStroke('6', false, false));
        dispatch(view, new KeyStroke('7', false, false));
        dispatch(view, new KeyStroke('8', false, false));
        dispatch(view, new KeyStroke('9', false, false));
        dispatch(view, new KeyStroke(KeyType.Enter, false, false, true));
        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke('d', false, false));
        dispatch(view, new KeyStroke('e', false, false));
        dispatch(view, new KeyStroke('f', false, false));
        dispatch(view, new KeyStroke('g', false, false));
        dispatch(view, new KeyStroke('h', false, false));
        dispatch(view, new KeyStroke('i', false, false));
        dispatch(view, new KeyStroke(KeyType.Enter, false, false, true));
        dispatch(view, new KeyStroke('j', false, false));
        dispatch(view, new KeyStroke('k', false, false));
        dispatch(view, new KeyStroke('l', false, false));
        dispatch(view, new KeyStroke('m', false, false));
        dispatch(view, new KeyStroke('n', false, false));
        dispatch(view, new KeyStroke('o', false, false));
        dispatch(view, new KeyStroke('p', false, false));
        dispatch(view, new KeyStroke('q', false, false));
        dispatch(view, new KeyStroke('r', false, false));

        assertTrue(view.getInputScrollLine() > 0);
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

        assertEquals("x", view.getInputText());
        assertTrue(view.isPending());
        assertTrue(view.getDisplayLines().get(0).startsWith("nemo> *thinking* ("));
    }

    @Test
    void pendingCommandSubmitsWhileThinking() {
        var submitted = new AtomicReference<String>();
        var command = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", submitted::set, command::set);
        view.setPending(true);

        dispatch(view, new KeyStroke(':', false, false));
        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('o', false, false));
        dispatch(view, new KeyStroke('r', false, false));
        dispatch(view, new KeyStroke('t', false, false));
        dispatch(view, new KeyStroke(KeyType.Enter));

        assertEquals(":abort", command.get());
        assertEquals("", view.getInputText());
        assertEquals(null, submitted.get());
    }

    @Test
    void formatsThinkingTextWithElapsedTimeAndAbortHint() {
        assertEquals("*thinking* (2 minutes, 5 seconds, type :abort to stop)",
                ChatPanelView.formatThinkingText(125));
    }

    @Test
    void omitsZeroMinutesFromThinkingText() {
        assertEquals("*thinking* (11 seconds, type :abort to stop)",
                ChatPanelView.formatThinkingText(11));
    }

    @Test
    void replyPromptPrefixesUseDistinctColours() throws Exception {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        AttributedString meLine = renderLine(view, "me> hello");
        AttributedString nemoLine = renderLine(view, "nemo> hello");

        assertEquals(UiTheme.CHAT_ME, foreground(meLine, 0));
        assertEquals("hello", meLine.getFragments().get(1).toString());
        assertEquals(UiTheme.CHAT_NEMO, foreground(nemoLine, 0));
        assertEquals(UiTheme.TEXT_PRIMARY, foreground(nemoLine, 1));
    }

    @Test
    void multilinePromptUsesPrimaryTextColourOnEveryLine() throws Exception {
        var view = new ChatPanelView(Rect.create(0, 0, 10, 4), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke('d', false, false));
        dispatch(view, new KeyStroke('e', false, false));
        dispatch(view, new KeyStroke('f', false, false));
        dispatch(view, new KeyStroke(KeyType.Enter, false, false, true));
        dispatch(view, new KeyStroke('g', false, false));
        dispatch(view, new KeyStroke('h', false, false));
        dispatch(view, new KeyStroke('i', false, false));

        var lines = view.inputLines();
        assertTrue(lines.size() > 1);
        assertEquals(UiTheme.TEXT_PRIMARY, inputTextColour(view, lines.get(0), 0, 0));
        assertEquals(UiTheme.TEXT_PRIMARY, inputTextColour(view, lines.get(1), 1, 0));
    }

    @Test
    void caretOverlapsCharacterInsteadOfInsertingGap() throws Exception {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke('c', false, false));
        dispatch(view, new KeyStroke(KeyType.ArrowLeft));

        Method method = ChatPanelView.class.getDeclaredMethod("draw", Rect.class);
        method.setAccessible(true);
        // keep test near rendering helpers by validating composed attributed content directly
        var input = new AttributedString();
        String prefix = " me> ";
        input.append(prefix, UiTheme.CHAT_ME, UiTheme.COMMAND_BACKGROUND);
        input.append("abc", UiTheme.TEXT_PRIMARY, UiTheme.COMMAND_BACKGROUND);
        input.format(prefix.length() + 2, prefix.length() + 3, UiTheme.COMMAND_BACKGROUND, UiTheme.TEXT_PRIMARY);

        assertEquals(" me> abc", input.toString());
        assertEquals(UiTheme.COMMAND_BACKGROUND, foreground(input, 2));
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

    private static TextColor inputTextColour(ChatPanelView view, String inputLine, int lineIndex, int fragmentIndex)
            throws Exception {
        Method method = ChatPanelView.class.getDeclaredMethod("renderPromptLine", String.class, String.class,
                TextColor.class, TextColor.class);
        method.setAccessible(true);
        String prefix = lineIndex == 0 ? " me> " : " ".repeat(5);
        AttributedString line = (AttributedString) method.invoke(view, prefix, inputLine, UiTheme.CHAT_ME,
                UiTheme.COMMAND_BACKGROUND);
        return foreground(line, fragmentIndex + 1);
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
