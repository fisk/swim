package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class WelcomePageTest {
    @Test
    void frameContainsLogoHelpTextSubmarineOceanLifeBubblesAndShell() {
        String rendered = String.join("\n", WelcomePage.renderFrame(80, 24, 3));

        assertTrue(rendered.contains("\\_/\\_/"));
        assertFalse(rendered.contains("____/|_____|"));
        assertTrue(rendered.contains(WelcomePage.HELP_TEXT));
        assertTrue(rendered.contains("___/___\\___"));
        assertTrue(rendered.contains("o     o     o"));
        assertTrue(rendered.contains("<=<"));
        assertTrue(rendered.contains("<|"));
        assertTrue(rendered.contains("|>"));
        assertTrue(rendered.contains("><") || rendered.contains("<>"));
        assertTrue(rendered.contains("o") || rendered.contains("O"));
        assertTrue(rendered.contains("/_|_\\"));
        assertTrue(rendered.chars().allMatch(c -> c == '\n' || c >= 32 && c < 127));
    }

    @Test
    void bubblesAreSparseAndMoveAcrossTheWater() {
        Set<Cell> first = bubblePositions(WelcomePage.renderFrame(80, 24, 0));
        Set<Cell> later = bubblePositions(WelcomePage.renderFrame(80, 24, 6));

        assertTrue(first.size() >= 4);
        assertTrue(first.size() < 20);
        assertTrue(first.stream().map(Cell::x).distinct().count() > 3);
        assertTrue(first.stream().map(Cell::y).distinct().count() > 2);
        assertNotEquals(first, later);
    }

    @Test
    void submarineMovesBetweenFrames() {
        List<String> first = WelcomePage.renderFrame(80, 24, 0);
        List<String> later = WelcomePage.renderFrame(80, 24, 6);

        assertNotEquals(first, later);
        assertNotEquals(indexOf(first, "o     o     o"), indexOf(later, "o     o     o"));
    }

    @Test
    void submarineBobsVerticallyWhileCruising() {
        List<String> high = WelcomePage.renderFrame(80, 24, 0);
        List<String> low = WelcomePage.renderFrame(80, 24, 20);

        assertNotEquals(lineIndexOf(high, "o     o     o"), lineIndexOf(low, "o     o     o"));
    }

    @Test
    void smallTerminalsStayWithinBounds() {
        List<String> frame = WelcomePage.renderFrame(24, 8, 4);

        assertEquals(8, frame.size());
        for (String line : frame) {
            assertEquals(24, line.length());
        }
        assertTrue(String.join("\n", frame).contains("<_o_>"));
    }

    private static int indexOf(List<String> frame, String needle) {
        for (String line : frame) {
            int index = line.indexOf(needle);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private static int lineIndexOf(List<String> frame, String needle) {
        for (int i = 0; i < frame.size(); i++) {
            if (frame.get(i).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static Set<Cell> bubblePositions(List<String> frame) {
        var positions = new HashSet<Cell>();
        for (int y = 0; y < frame.size(); y++) {
            String line = frame.get(y);
            if (line.contains(WelcomePage.HELP_TEXT)) {
                continue;
            }
            for (int x = 0; x < line.length(); x++) {
                char c = line.charAt(x);
                if (c == 'o' || c == 'O') {
                    positions.add(new Cell(x, y));
                }
            }
        }
        return positions;
    }

    private record Cell(int x, int y) {
    }
}
