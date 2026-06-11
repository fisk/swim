package org.fisk.swim.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.fisk.swim.terminal.TerminalContext;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.Point;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TextColor;

class AttributedStringTest {
    @Test
    void formatSplitsFragmentWithoutChangingText() {
        var string = AttributedString.create("hello", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);

        string.format(1, 4, TextColor.ANSI.RED, TextColor.ANSI.BLACK);

        assertEquals("hello", string.toString());
        assertEquals(5, string.length());
        assertEquals(3, string.getFragments().size());
        assertEquals("h,ell,o", fragments(string));
    }

    @Test
    void batchFormatAppliesRangesInOrder() {
        var string = AttributedString.create("abcdef", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);

        string.format(List.of(
                new AttributedString.FormatRange(1, 5, TextColor.ANSI.RED, TextColor.ANSI.BLACK),
                new AttributedString.FormatRange(3, 4, TextColor.ANSI.GREEN, TextColor.ANSI.BLACK)));

        assertEquals("abcdef", string.toString());
        assertEquals("a,bc,d,e,f", fragments(string));
        assertEquals(TextColor.ANSI.RED, foreground(string, 1));
        assertEquals(TextColor.ANSI.GREEN, foreground(string, 3));
        assertEquals(TextColor.ANSI.RED, foreground(string, 4));
    }

    @Test
    void batchFormatIgnoresEmptyRanges() {
        var string = new AttributedString();
        string.append("a", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);
        string.append("b", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);

        string.format(List.of(new AttributedString.FormatRange(1, 1, TextColor.ANSI.RED, TextColor.ANSI.BLACK)));

        assertEquals("a,b", fragments(string));
    }

    @Test
    void insertSplitsExistingFragment() {
        var string = AttributedString.create("heo", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);

        string.insert("l", 2, TextColor.ANSI.GREEN, TextColor.ANSI.BLACK);

        assertEquals("helo", string.toString());
        assertEquals(4, string.length());
        assertEquals("he,l,o", fragments(string));
    }

    @Test
    void insertAllowsAppendingAtEnd() {
        var string = AttributedString.create("hello", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);

        string.insert("!", string.length(), TextColor.ANSI.YELLOW, TextColor.ANSI.BLACK);

        assertEquals("hello!", string.toString());
        assertEquals(6, string.length());
        assertEquals("hello,!", fragments(string));
    }

    @Test
    void removeCanSpanMultipleFragments() {
        var string = new AttributedString();
        string.append("ab", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);
        string.append("cd", TextColor.ANSI.RED, TextColor.ANSI.BLACK);
        string.append("ef", TextColor.ANSI.GREEN, TextColor.ANSI.BLACK);

        string.remove(1, 5);

        assertEquals("af", string.toString());
        assertEquals(2, string.length());
        assertEquals("a,f", fragments(string));
    }

    @Test
    void sliceKeepsOnlyRequestedRange() {
        var string = new AttributedString();
        string.append("ab", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);
        string.append("cd", TextColor.ANSI.RED, TextColor.ANSI.BLACK);
        string.append("ef", TextColor.ANSI.GREEN, TextColor.ANSI.BLACK);

        var slice = string.slice(1, 5);

        assertEquals("bcde", slice.toString());
        assertEquals(4, slice.length());
        assertEquals("b,cd,e", fragments(slice));
    }

    @Test
    void clickRangeReceivesIndexIntoRange() {
        var clicked = new AtomicInteger(-1);
        var string = AttributedString.create("hello", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);
        string.onClick(1, 4, clicked::set);

        string.clickAt(3);

        assertEquals(2, clicked.get());
    }

    @Test
    void slicePreservesClickableSubranges() {
        var clicked = new AtomicInteger(-1);
        var string = AttributedString.create("abcdef", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);
        string.onClick(2, 5, clicked::set);

        var slice = string.slice(1, 4);
        slice.clickAt(2);

        assertEquals(1, clicked.get());
    }

    @Test
    void drawAtRegistersClickableScreenRange() {
        TerminalContextTestSupport.install(20, 5);
        try {
            var clicked = new AtomicInteger(-1);
            var string = AttributedString.create("hello", TextColor.ANSI.DEFAULT, TextColor.ANSI.BLACK);
            string.onClick(1, 4, clicked::set);

            AttributedString.clearRenderedClickRanges();
            string.drawAt(Point.create(5, 2), TerminalContext.getInstance().getGraphics());
            Runnable action = AttributedString.clickActionAt(Point.create(7, 2));
            action.run();

            assertEquals(1, clicked.get());
        } finally {
            TerminalContext.shutdownInstance();
        }
    }

    private static String fragments(AttributedString string) {
        return string.getFragments().stream()
                .map(AttributedString.AttributedStringFragment::toString)
                .collect(Collectors.joining(","));
    }

    private static TextColor foreground(AttributedString string, int position) {
        return string.getCharacter(position).getFragments().get(0).getAttributes().foregroundColour();
    }
}
