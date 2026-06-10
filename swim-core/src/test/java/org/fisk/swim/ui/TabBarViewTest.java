package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.List;

import org.fisk.swim.text.AttributedString;
import org.fisk.swim.text.Powerline;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class TabBarViewTest {
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
