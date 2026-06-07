package org.fisk.swim.terminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;

class SgrMouseCharacterPatternTest {
    @Test
    void decodesClickDown() {
        MouseAction action = decode("\u001b[<0;5;3M");

        assertEquals(MouseActionType.CLICK_DOWN, action.getActionType());
        assertEquals(1, action.getButton());
        assertEquals(new TerminalPosition(4, 2), action.getPosition());
    }

    @Test
    void decodesDrag() {
        MouseAction action = decode("\u001b[<32;8;4M");

        assertEquals(MouseActionType.DRAG, action.getActionType());
        assertEquals(1, action.getButton());
        assertEquals(new TerminalPosition(7, 3), action.getPosition());
    }

    @Test
    void decodesMove() {
        MouseAction action = decode("\u001b[<35;9;5M");

        assertEquals(MouseActionType.MOVE, action.getActionType());
        assertEquals(0, action.getButton());
        assertEquals(new TerminalPosition(8, 4), action.getPosition());
    }

    @Test
    void decodesReleaseWithoutButton() {
        MouseAction action = decode("\u001b[<0;9;5m");

        assertEquals(MouseActionType.CLICK_RELEASE, action.getActionType());
        assertEquals(0, action.getButton());
        assertEquals(new TerminalPosition(8, 4), action.getPosition());
    }

    @Test
    void decodesScroll() {
        MouseAction up = decode("\u001b[<64;9;5M");
        MouseAction down = decode("\u001b[<65;9;5M");

        assertEquals(MouseActionType.SCROLL_UP, up.getActionType());
        assertEquals(4, up.getButton());
        assertEquals(MouseActionType.SCROLL_DOWN, down.getActionType());
        assertEquals(5, down.getButton());
    }

    @Test
    void reportsPartialAndInvalidSequences() {
        var pattern = new SgrMouseCharacterPattern();

        assertSame(CharacterPattern.Matching.NOT_YET, pattern.match(chars("\u001b[<0;9")));
        assertNull(pattern.match(chars("\u001b[0;9;5M")));
        assertNull(pattern.match(chars("\u001b[<0;;5M")));
        assertNull(pattern.match(chars("\u001b[<0;0;5M")));
    }

    private static MouseAction decode(String sequence) {
        CharacterPattern.Matching match = new SgrMouseCharacterPattern().match(chars(sequence));

        return assertInstanceOf(MouseAction.class, match.fullMatch);
    }

    private static List<Character> chars(String sequence) {
        return sequence.chars()
                .mapToObj(value -> (char) value)
                .toList();
    }
}
