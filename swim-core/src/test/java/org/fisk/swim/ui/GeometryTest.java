package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeometryTest {
    @Test
    void pointEqualityIsCoordinateBased() {
        assertTrue(Point.create(1, 2).equals(Point.create(1, 2)));
        assertFalse(Point.create(1, 2).equals(Point.create(2, 1)));
    }

    @Test
    void sizeEqualityIsDimensionBased() {
        assertTrue(Size.create(3, 4).equals(Size.create(3, 4)));
        assertFalse(Size.create(3, 4).equals(Size.create(4, 3)));
    }

    @Test
    void rectFactoryAndEqualityWork() {
        var rect = Rect.create(1, 2, 3, 4);

        assertEquals(1, rect.getPoint().getX());
        assertEquals(2, rect.getPoint().getY());
        assertEquals(3, rect.getSize().getWidth());
        assertEquals(4, rect.getSize().getHeight());
        assertTrue(rect.equals(Rect.create(1, 2, 3, 4)));
        assertFalse(rect.equals(Rect.create(0, 2, 3, 4)));
        assertEquals("{1, 2, 3, 4}", rect.toString());
    }
}
