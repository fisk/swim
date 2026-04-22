package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RangeTest {
    @Test
    void intersectionReturnsOverlappingSegment() {
        var intersection = Range.create(2, 8).intersection(Range.create(5, 10));

        assertEquals(5, intersection.getStart());
        assertEquals(8, intersection.getEnd());
        assertEquals(3, intersection.getLength());
    }

    @Test
    void intersectionReturnsEmptyRangeWhenDisjoint() {
        var intersection = Range.create(1, 3).intersection(Range.create(5, 7));

        assertEquals(0, intersection.getStart());
        assertEquals(0, intersection.getEnd());
        assertEquals(0, intersection.getLength());
    }

    @Test
    void toStringUsesStartAndEndCoordinates() {
        assertEquals("{4, 9}", Range.create(4, 9).toString());
    }
}
