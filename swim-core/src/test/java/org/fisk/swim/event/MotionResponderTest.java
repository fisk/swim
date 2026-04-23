package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;

class MotionResponderTest {
    @Test
    void parsesExplicitCountBeforeMotion() {
        var count = new AtomicInteger();
        var responder = new MotionResponder("w", count::set);
        var events = new KeyStrokes(List.of(
                new KeyStroke('1', false, false),
                new KeyStroke('2', false, false),
                new KeyStroke('w', false, false)));

        assertEquals(Response.YES, responder.processEvent(events));

        responder.respond();

        assertEquals(12, count.get());
    }

    @Test
    void defaultsToSingleStepWhenNoCountIsPresent() {
        var count = new AtomicInteger();
        var responder = new MotionResponder("w", count::set);
        var events = new KeyStrokes(List.of(new KeyStroke('w', false, false)));

        assertEquals(Response.YES, responder.processEvent(events));

        responder.respond();

        assertEquals(1, count.get());
    }

    @Test
    void returnsMaybeWhenOnlyCountPrefixHasArrived() {
        var responder = new MotionResponder("w", ignored -> {});
        var events = new KeyStrokes(List.of(new KeyStroke('3', false, false)));

        assertEquals(Response.MAYBE, responder.processEvent(events));
    }
}
