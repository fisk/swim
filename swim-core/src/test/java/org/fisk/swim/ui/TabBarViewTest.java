package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.List;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;
import org.junit.jupiter.api.Test;

import org.fisk.swim.terminal.TextColor;

class TabBarViewTest {
    @Test
    void wrapsWholeTabsAndShrinksWhenWidened() {
        var view = new TabBarView(Rect.create(0, 0, 16, 3));
        view.setTabs(List.of(new TabBarView.Tab(1, "one", false, null),
                new TabBarView.Tab(2, "two", true, null), new TabBarView.Tab(3, "three", false, null)));
        assertEquals(2, view.preferredHeight(16));
        assertEquals(" 1:one " + Powerline.SYMBOL_FILLED_RIGHT_ARROW
                + " 2:two " + Powerline.SYMBOL_FILLED_RIGHT_ARROW, view.buildLines(16).getFirst().toString());
        assertEquals(" 3:three " + Powerline.SYMBOL_FILLED_RIGHT_ARROW, view.buildLines(16).getLast().toString());
        assertEquals(1, view.preferredHeight(80));
        assertEquals(3, view.preferredHeight(4));
        for (var line : view.buildLines(4)) {
            assertEquals(4, line.length());
        }
    }

    @Test
    void nemoTabLabelsOmitSessionIdentifiers() {
        assertEquals("Nemo", Window.compactNemoTabLabel("Nemo session-1788272724537 | Session 1788272724537"));
        assertEquals("Nemo: Fix tests", Window.compactNemoTabLabel("Nemo session-123 | Fix tests"));
    }

    @Test
    void wrappedRowsKeepTheirClickTargets() {
        var selected = new java.util.concurrent.atomic.AtomicInteger();
        var view = new TabBarView(Rect.create(0, 0, 8, 2));
        view.setTabs(List.of(new TabBarView.Tab(1, "one", false, () -> selected.set(1)),
                new TabBarView.Tab(2, "two", true, () -> selected.set(2))));
        view.buildLines(8).get(1).clickAt(3);
        assertEquals(2, selected.get());
    }

    @Test
    void adjacentTabsUsePowerlineTransitionsWithoutSpacer() throws Exception {
        var view = new TabBarView(Rect.create(0, 0, 80, 1));
        view.setTabs(List.of(
                new TabBarView.Tab(0, "scratch", true, null),
                new TabBarView.Tab(1, "scratch", false, null)));

        AttributedString line = view.buildLine(80);

        assertEquals(" 0:scratch " + Powerline.SYMBOL_FILLED_RIGHT_ARROW
                + " 1:scratch " + Powerline.SYMBOL_FILLED_RIGHT_ARROW, line.toString());
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 1));
        assertEquals(UiTheme.ACCENT_BLUE, foreground(line, 1));
        assertEquals(UiTheme.SURFACE_ACCENT, background(line, 1));
        assertEquals(Powerline.SYMBOL_FILLED_RIGHT_ARROW, fragmentText(line, 3));
        assertEquals(UiTheme.SURFACE_ACCENT, foreground(line, 3));
        assertEquals(UiTheme.MODELINE_BACKGROUND, background(line, 3));
    }

    private static String fragmentText(AttributedString line, int fragmentIndex) {
        return line.getFragments().get(fragmentIndex).toString();
    }

    private static TextColor foreground(AttributedString line, int fragmentIndex) throws Exception {
        Object attributes = line.getFragments().get(fragmentIndex).getAttributes();
        Field field = attributes.getClass().getDeclaredField("_foregroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attributes);
    }

    private static TextColor background(AttributedString line, int fragmentIndex) throws Exception {
        Object attributes = line.getFragments().get(fragmentIndex).getAttributes();
        Field field = attributes.getClass().getDeclaredField("_backgroundColour");
        field.setAccessible(true);
        return (TextColor) field.get(attributes);
    }
}
