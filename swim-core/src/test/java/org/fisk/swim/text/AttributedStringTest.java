package org.fisk.swim.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Collectors;

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

    private static String fragments(AttributedString string) {
        return string.getFragments().stream()
                .map(AttributedString.AttributedStringFragment::toString)
                .collect(Collectors.joining(","));
    }
}
