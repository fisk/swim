package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class CommandViewTest {
    @Test
    void deactivateIsSafeWhenWindowHasBeenDisposed() {
        var view = new CommandView(Rect.create(0, 0, 10, 1));

        assertDoesNotThrow(view::deactivate);
    }
}
