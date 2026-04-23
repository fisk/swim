package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;

class KeyStrokesTest {
    @Test
    void nextAdvancesOneKeyAtATime() {
        var keyStrokes = new KeyStrokes(List.of(
                new KeyStroke('a', false, false),
                new KeyStroke('b', false, false),
                new KeyStroke('c', false, false)));

        assertEquals('a', keyStrokes.current().getCharacter());
        assertTrue(keyStrokes.hasNext());

        assertEquals('b', keyStrokes.next().getCharacter());
        assertEquals('b', keyStrokes.current().getCharacter());
        assertTrue(keyStrokes.hasNext());

        assertEquals('c', keyStrokes.next().getCharacter());
        assertEquals('c', keyStrokes.current().getCharacter());
        assertFalse(keyStrokes.hasNext());
    }

    @Test
    void iteratorStartsFromCurrentKey() {
        var keyStrokes = new KeyStrokes(List.of(
                new KeyStroke('a', false, false),
                new KeyStroke('b', false, false),
                new KeyStroke('c', false, false)));
        keyStrokes.consume(1);

        var characters = new ArrayList<Character>();
        for (var keyStroke : keyStrokes) {
            characters.add(keyStroke.getCharacter());
        }

        assertEquals(List.of('b', 'c'), characters);
    }

    @Test
    void consumedTracksWhenSequenceHasBeenFullyProcessed() {
        var keyStrokes = new KeyStrokes(List.of(
                new KeyStroke('a', false, false),
                new KeyStroke('b', false, false),
                new KeyStroke('c', false, false)));

        assertEquals(2, keyStrokes.remaining());
        assertFalse(keyStrokes.consumed());

        keyStrokes.consume(3);

        assertTrue(keyStrokes.consumed());
    }
}
