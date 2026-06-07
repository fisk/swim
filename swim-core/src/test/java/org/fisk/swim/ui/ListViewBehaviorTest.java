package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.fisk.swim.event.KeyStrokes;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

class ListViewBehaviorTest {
    @Test
    void filterTreatsRegexCharactersLiterallyAndClearingMarksRedraw() {
        var alpha = item("alpha[1]");
        var beta = item("beta");
        var view = new ListView(Rect.create(0, 0, 20, 5), List.of(alpha, beta), "Files");

        dispatch(view, new KeyStroke('[', false, false));
        assertEquals(List.of(alpha), filteredList(view));

        clearNeedsRedraw(view);
        dispatch(view, new KeyStroke(KeyType.Backspace));

        assertEquals(List.of(alpha, beta), filteredList(view));
        assertTrue(view.needsRedraw());
    }

    @Test
    void downArrowClampsSelectionToFilteredRows() {
        var alpha = item("alpha");
        var beta = item("beta");
        var view = new ListView(Rect.create(0, 0, 20, 5), List.of(alpha, beta), "Files");

        dispatch(view, new KeyStroke(KeyType.ArrowDown));
        dispatch(view, new KeyStroke('l', false, false));
        dispatch(view, new KeyStroke(KeyType.ArrowDown));

        assertEquals(0, selection(view));
        assertSame(alpha, filteredList(view).get(selection(view)));
    }

    @Test
    void mouseClickSelectsListRowAndReleaseOpensIt() {
        var clicked = new AtomicInteger(-1);
        var alpha = item("alpha", () -> clicked.set(0));
        var beta = item("beta", () -> clicked.set(1));
        var view = new ListView(Rect.create(5, 3, 20, 6), List.of(alpha, beta), "Files");

        dispatch(view, new MouseAction(MouseActionType.CLICK_DOWN, 1, new TerminalPosition(7, 6)));

        assertEquals(1, selection(view));

        dispatch(view, new MouseAction(MouseActionType.CLICK_RELEASE, 1, new TerminalPosition(7, 6)));

        assertEquals(1, clicked.get());
    }

    @SuppressWarnings("unchecked")
    private static List<ListView.ListItem> filteredList(ListView view) {
        return (List<ListView.ListItem>) HeadlessWindowHarness.getField(view, "_filteredList");
    }

    private static int selection(ListView view) {
        return HeadlessWindowHarness.getField(view, "_selection", Integer.class);
    }

    private static void clearNeedsRedraw(View view) {
        try {
            var field = View.class.getDeclaredField("_needsRedraw");
            field.setAccessible(true);
            field.set(view, false);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void dispatch(ListView view, KeyStroke key) {
        view.processEvent(new KeyStrokes(List.of(key)));
        view.respond();
    }

    private static ListView.ListItem item(String label) {
        return item(label, () -> {
        });
    }

    private static ListView.ListItem item(String label, Runnable onClick) {
        return new ListView.ListItem() {
            @Override
            public void onClick() {
                onClick.run();
            }

            @Override
            public String displayString() {
                return label;
            }
        };
    }
}
