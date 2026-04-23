package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

class TextEventResponderTest {
    @Test
    void matchesCompleteMultiKeySequence() {
        var responder = new TextEventResponder("<SPACE> e i", () -> {});
        var events = new KeyStrokes(List.of(
                new KeyStroke(' ', false, false),
                new KeyStroke('e', false, false),
                new KeyStroke('i', false, false)));

        var response = responder.processEvent(events);

        assertEquals(Response.YES, response);
        assertTrue(events.consumed());
    }

    @Test
    void returnsMaybeForMatchingPrefix() {
        var responder = new TextEventResponder("<SPACE> e i", () -> {});
        var events = new KeyStrokes(List.of(
                new KeyStroke(' ', false, false),
                new KeyStroke('e', false, false)));

        var response = responder.processEvent(events);

        assertEquals(Response.MAYBE, response);
        assertFalse(events.consumed());
        assertEquals(0, events.remaining());
        assertEquals('e', events.current().getCharacter());
    }

    @Test
    void rejectsIncorrectModifierState() {
        var responder = new TextEventResponder("<CTRL>-r", () -> {});
        var events = new KeyStrokes(List.of(new KeyStroke('r', false, false)));

        var response = responder.processEvent(events);

        assertEquals(Response.NO, response);
        assertEquals('r', events.current().getCharacter());
    }

    @Test
    void handlesNamedSpecialKeys() {
        var responder = new TextEventResponder("<ESC>", () -> {});
        var events = new KeyStrokes(List.of(new KeyStroke(KeyType.Escape)));

        var response = responder.processEvent(events);

        assertEquals(Response.YES, response);
        assertTrue(events.consumed());
    }

    @Test
    void respondRunsActionAfterSuccessfulMatch() {
        var wasCalled = new AtomicBoolean(false);
        var responder = new TextEventResponder("x", () -> wasCalled.set(true));
        var events = new KeyStrokes(List.of(new KeyStroke('x', false, false)));

        assertEquals(Response.YES, responder.processEvent(events));

        responder.respond();

        assertTrue(wasCalled.get());
    }
}
