package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.fisk.swim.event.EventResponder;
import org.fisk.swim.event.KeyStrokes;
import org.fisk.swim.event.Response;
import org.junit.jupiter.api.Test;

import com.googlecode.lanterna.input.KeyStroke;

class ViewTest {
    @Test
    void resizePreservesMarginsForSubviews() {
        var root = new View(Rect.create(0, 0, 100, 50));
        var child = new View(Rect.create(10, 5, 30, 10));
        root.addSubview(child);

        root.resize(Size.create(120, 60));

        assertEquals("{0, 0, 120, 60}", root.getBounds().toString());
        assertEquals("{10, 5, 50, 20}", child.getBounds().toString());
    }

    @Test
    void respondDelegatesToLastSuccessfulResponder() {
        var calls = new AtomicInteger();
        var view = new View(Rect.create(0, 0, 10, 10));
        view.setFirstResponder(new EventResponder() {
            @Override
            public Response processEvent(KeyStrokes events) {
                return Response.YES;
            }

            @Override
            public void respond() {
                calls.incrementAndGet();
            }
        });

        assertEquals(Response.YES, view.processEvent(new KeyStrokes(List.of(new KeyStroke('x', false, false)))));

        view.respond();

        assertEquals(1, calls.get());
    }
}
