package org.fisk.swim.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;

class ListEventResponderTest {
    @Test
    void returnsMaybeWhenAnyResponderNeedsMoreInput() {
        var responder = new ListEventResponder();
        responder.addEventResponder(stub(Response.MAYBE, new AtomicInteger()));
        responder.addEventResponder(stub(Response.YES, new AtomicInteger()));

        var result = responder.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false))));

        assertEquals(Response.MAYBE, result);
    }

    @Test
    void respondInvokesWinningResponder() {
        var called = new AtomicInteger();
        var responder = new ListEventResponder();
        responder.addEventResponder(stub(Response.NO, new AtomicInteger()));
        responder.addEventResponder(stub(Response.YES, called));

        assertEquals(Response.YES, responder.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false)))));

        responder.respond();

        assertEquals(1, called.get());
    }

    @Test
    void returnsNoWhenNothingMatches() {
        var responder = new ListEventResponder();
        responder.addEventResponder(stub(Response.NO, new AtomicInteger()));

        var result = responder.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false))));

        assertEquals(Response.NO, result);
    }

    @Test
    void higherLayerYesOverridesLowerLayerYes() {
        var lowerCalled = new AtomicInteger();
        var higherCalled = new AtomicInteger();
        var responder = new ListEventResponder();
        responder.addEventResponder(stub(Response.YES, lowerCalled));
        responder.addLayer().addEventResponder(stub(Response.YES, higherCalled));

        assertEquals(Response.YES, responder.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false)))));

        responder.respond();

        assertEquals(0, lowerCalled.get());
        assertEquals(1, higherCalled.get());
    }

    @Test
    void higherLayerYesOverridesLowerLayerMaybe() {
        var called = new AtomicInteger();
        var responder = new ListEventResponder();
        responder.addEventResponder(new TextEventResponder("x y", () -> {}));
        responder.addLayer().addEventResponder(stub(Response.YES, called));

        assertEquals(Response.YES, responder.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false)))));

        responder.respond();

        assertEquals(1, called.get());
    }

    @Test
    void higherLayerMaybeBlocksLowerLayerYes() {
        var responder = new ListEventResponder();
        responder.addEventResponder(stub(Response.YES, new AtomicInteger()));
        responder.addLayer().addEventResponder(new TextEventResponder("x y", () -> {}));

        var result = responder.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false))));

        assertEquals(Response.MAYBE, result);
    }

    @Test
    void exposesDocumentedKeyBindingHintsFromLayers() {
        var responder = new ListEventResponder();
        responder.addEventResponder("j", "Navigation", "down", () -> {});
        responder.addLayer().addEventResponder("<ENTER>", "Actions", "open", () -> {});

        var hints = responder.keyBindingHints();

        assertEquals(List.of(
                KeyBindingHint.of("<ENTER>", "Actions", "open"),
                KeyBindingHint.of("j", "Navigation", "down")), hints);
    }

    private static EventResponder stub(Response response, AtomicInteger calls) {
        return new EventResponder() {
            @Override
            public Response processEvent(KeyStrokes events) {
                return response;
            }

            @Override
            public void respond() {
                calls.incrementAndGet();
            }
        };
    }
}
