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
import org.fisk.swim.terminal.TerminalContext;
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
    void ctrlJAddsNewlineWithoutSubmitting() {
        var submitted = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", submitted::set);

        dispatch(view, new KeyStroke('h', false, false));
        dispatch(view, new KeyStroke('j', true, false));
        dispatch(view, new KeyStroke('i', false, false));

        assertEquals(null, submitted.get());
        assertEquals("h\ni", view.getInputText());
    }

    @Test
    void batchedMultilinePasteAddsDraftTextWithoutSubmitting() {
        var submitted = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", submitted::set);

        dispatch(view, List.of(
                new KeyStroke('a', false, false),
                new KeyStroke(KeyType.Enter),
                new KeyStroke('b', false, false)));

        assertEquals(null, submitted.get());
        assertEquals("a\nb", view.getInputText());
    }

    @Test
    void bracketedPasteKeepsEnterAndTabInDraft() {
        var submitted = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", submitted::set);

        dispatch(view, new KeyStroke(TerminalContext.BRACKETED_PASTE_START_KEY));
        dispatch(view, new KeyStroke('a', false, false));
        dispatch(view, new KeyStroke(KeyType.Tab));
        dispatch(view, new KeyStroke(KeyType.Enter));
        dispatch(view, new KeyStroke('b', false, false));
        dispatch(view, new KeyStroke(TerminalContext.BRACKETED_PASTE_END_KEY));

        assertEquals(null, submitted.get());
        assertEquals("a\t\nb", view.getInputText());

        dispatch(view, new KeyStroke(KeyType.Enter));

        assertEquals("a\t\nb", submitted.get());
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
    void commandMenuStateAppearsOnlyForColonPrefixedInput() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        dispatch(view, new KeyStroke('h', false, false));
        dispatch(view, new KeyStroke('i', false, false));
        assertFalse(view.isCommandInputActive());
        assertFalse(view.getCommandMenuState().visible());

        var commandView = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});
        dispatch(commandView, new KeyStroke(':', false, false));
        dispatch(commandView, new KeyStroke('s', false, false));

        assertTrue(commandView.isCommandInputActive());
        assertTrue(commandView.getCommandMenuState().visible());
        assertEquals("s", commandView.getCommandMenuState().prefix());
    }


    @Test
    void chatCommandMenuCanUseNemoSpecificCommands() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {}, ignored -> {}, ignored -> {},
                text -> CommandView.CommandMenuState.forCommandText(text, 0,
                        List.of(new CommandView.CommandSpec("sessions", List.of(), "", "list sessions"),
                                new CommandView.CommandSpec("switch", List.of(), "<session-id>", "switch session"))));

        dispatch(view, new KeyStroke(':', false, false));
        dispatch(view, new KeyStroke('s', false, false));

        assertTrue(view.isCommandInputActive());
        assertTrue(view.getCommandMenuState().visible());
        assertEquals("sessions", view.getCommandMenuState().selectedMatch().primaryName());
    }

    @Test
    void chatCommandMenuCanNavigateAndCompleteSelectedCommand() {
        var changed = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {}, ignored -> {},
                changed::set,
                text -> CommandView.CommandMenuState.forCommandText(text, 0,
                        List.of(new CommandView.CommandSpec("approve", List.of(), "approval-1",
                                "approve once", "approve approval-1", true, "Approve once"),
                                new CommandView.CommandSpec("deny", List.of(), "approval-1",
                                        "deny", "deny approval-1", true, "Deny"))));

        dispatch(view, new KeyStroke(':', false, false));
        assertEquals("Approve once", view.getCommandMenuState().selectedMatch().displayLabel());

        dispatch(view, new KeyStroke(KeyType.ArrowDown));
        assertEquals("Deny", view.getCommandMenuState().selectedMatch().displayLabel());

        dispatch(view, new KeyStroke(KeyType.Tab));
        assertEquals(":deny approval-1", view.getInputText());
        assertEquals(view.getInputText().length(), view.getCursorOffset());
        assertEquals(":deny approval-1", changed.get());
    }

    @Test
    void enterSubmitsSelectedCompleteCommandMenuAction() {
        var command = new AtomicReference<String>();
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {}, command::set, ignored -> {},
                text -> CommandView.CommandMenuState.forCommandText(text, 0,
                        List.of(new CommandView.CommandSpec("approve", List.of(), "approval-1",
                                "approve once", "approve approval-1", true, "Approve once"),
                                new CommandView.CommandSpec("deny", List.of(), "approval-1",
                                        "deny", "deny approval-1", true, "Deny"))));

        dispatch(view, new KeyStroke(':', false, false));
        dispatch(view, new KeyStroke(KeyType.ArrowDown));
        dispatch(view, new KeyStroke(KeyType.Enter));

        assertEquals(":deny approval-1", command.get());
        assertEquals("", view.getInputText());
    }

    @Test
    void openCommandInputIfEmptyShowsCommandMenuWithoutReplacingTypedText() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {}, ignored -> {}, ignored -> {},
                text -> CommandView.CommandMenuState.forCommandText(text, 0,
                        List.of(new CommandView.CommandSpec("approve", List.of(), "approval-1",
                                "approve once", "approve approval-1", true, "Approve once")),
                        "approval options"));

        assertTrue(view.openCommandInputIfEmpty());
        assertEquals(":", view.getInputText());
        assertTrue(view.getCommandMenuState().visible());
        assertEquals("approval options", view.getCommandMenuState().title());
        assertEquals("approve", view.getCommandMenuState().selectedMatch().primaryName());

        dispatch(view, new KeyStroke('x', false, false));
        assertFalse(view.openCommandInputIfEmpty());
        assertEquals(":x", view.getInputText());
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
    void contextUsagePercentIsStoredAndClamped() {
        var view = new ChatPanelView(Rect.create(0, 0, 20, 5), "Nemo", ignored -> {});

        view.setContextUsagePercent(120);
        assertEquals(100, view.getContextUsagePercent());

        view.setContextUsagePercent(-5);
        assertEquals(0, view.getContextUsagePercent());

        view.setContextUsagePercent(null);
        assertEquals(null, view.getContextUsagePercent());
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
        assertEquals(UiTheme.TEXT_PRIMARY, inputTextColour(view, lines.get(0), 0, 1));
        assertEquals(UiTheme.TEXT_PRIMARY, inputTextColour(view, lines.get(1), 1, -1));
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
        assertEquals("abc", view.getInputText());
        assertEquals(2, view.getCursorOffset());
    }

    private static void dispatch(ChatPanelView view, KeyStroke keyStroke) {
        dispatch(view, List.of(keyStroke));
    }

    private static void dispatch(ChatPanelView view, List<KeyStroke> keyStrokes) {
        var response = view.processEvent(new KeyStrokes(keyStrokes));
        if (response == org.fisk.swim.event.Response.YES) {
            view.respond();
        }
    }

    private static AttributedString renderLine(ChatPanelView view, String line) throws Exception {
        Method renderLine = ChatPanelView.class.getDeclaredMethod("renderLine", String.class);
        renderLine.setAccessible(true);
        return (AttributedString) renderLine.invoke(view, line);
    }

    private static TextColor foreground(AttributedString string, int fragmentIndex) throws Exception {
        Object attrs = string.getFragments().get(fragmentIndex).getAttributes();
        var field = attrs.getClass().getDeclaredField("_foregroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attrs);
    }

    private static TextColor inputTextColour(ChatPanelView view, String inputLine, int lineIndex, int cursorColumn)
            throws Exception {
        if (lineIndex == 0) {
            var input = new AttributedString();
            String prefix = " ! ";
            input.append(prefix, UiTheme.CHAT_ME, UiTheme.COMMAND_BACKGROUND);
            input.append(inputLine, UiTheme.TEXT_PRIMARY, UiTheme.COMMAND_BACKGROUND);
            if (cursorColumn >= 0 && cursorColumn < inputLine.length()) {
                input.format(prefix.length() + cursorColumn, prefix.length() + cursorColumn + 1,
                        UiTheme.COMMAND_BACKGROUND, UiTheme.TEXT_PRIMARY);
            }
            Object attrs = input.getFragments().get(1).getAttributes();
            var field = attrs.getClass().getDeclaredField("_foregroundColour");
            field.setAccessible(true);
            return (TextColor) field.get(attrs);
        }
        return UiTheme.TEXT_PRIMARY;
    }
}
