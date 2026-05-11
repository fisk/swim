package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SplitViewTest {
    @Test
    void horizontalSplitDistributesWidthAcrossChildren() {
        var left = new View(Rect.create(0, 0, 0, 0));
        var right = new View(Rect.create(0, 0, 0, 0));
        var split = new SplitView(Rect.create(0, 0, 20, 7), SplitView.Orientation.HORIZONTAL, left, right);

        assertEquals("{0, 0, 10, 7}", left.getBounds().toString());
        assertEquals("{10, 0, 10, 7}", right.getBounds().toString());

        split.setBounds(Rect.create(0, 0, 21, 7));

        assertEquals("{0, 0, 10, 7}", left.getBounds().toString());
        assertEquals("{10, 0, 11, 7}", right.getBounds().toString());
    }

    @Test
    void verticalSplitDistributesHeightAcrossChildren() {
        var top = new View(Rect.create(0, 0, 0, 0));
        var bottom = new View(Rect.create(0, 0, 0, 0));
        var split = new SplitView(Rect.create(0, 0, 12, 7), SplitView.Orientation.VERTICAL, top, bottom,
                2.0 / 3.0);

        assertEquals("{0, 0, 12, 4}", top.getBounds().toString());
        assertEquals("{0, 4, 12, 3}", bottom.getBounds().toString());
        assertSame(bottom, split.getSibling(top));
    }
}
