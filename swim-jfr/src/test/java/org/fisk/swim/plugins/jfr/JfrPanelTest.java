package org.fisk.swim.plugins.jfrmetrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JfrPanelTest {
    @Test
    void brailleCellPreservesCrossingLinesInsteadOfChoosingOneSeries() {
        var canvas = new JfrPanel.BrailleCanvas(1, 1);

        canvas.drawLine(0, 0, 1, 3);
        canvas.drawLine(0, 3, 1, 0);

        assertEquals("\u28ff", canvas.row(0));
    }

    @Test
    void drawingAnIntersectingLineOnlyAddsDots() {
        var canvas = new JfrPanel.BrailleCanvas(1, 1);
        canvas.plot(0, 0);
        canvas.plot(1, 3);
        String firstSeries = canvas.row(0);

        canvas.plot(0, 3);
        canvas.plot(1, 0);

        assertEquals("\u2881", firstSeries);
        assertEquals("\u28c9", canvas.row(0));
    }

    @Test
    void liveHistoryKeepsOnlyTheLatestHour() {
        Instant end = Instant.parse("2026-09-19T12:00:00Z");
        var recording = new JfrMetrics.Recording(end.minus(Duration.ofHours(2)), end, List.of(
                new JfrMetrics.Sample(end.minus(Duration.ofMinutes(90)), 1, 1, 1),
                new JfrMetrics.Sample(end.minus(Duration.ofMinutes(59)), 2, 2, 2)));

        var limited = JfrMetrics.mostRecent(recording, Duration.ofHours(1));

        assertEquals(end.minus(Duration.ofHours(1)), limited.start());
        assertEquals(1, limited.samples().size());
        assertEquals(end.minus(Duration.ofMinutes(59)), limited.samples().getFirst().time());
    }

    @Test
    void normalizedTimeAxisUsesLabelsThatFitTheChart() {
        String labels = JfrPanel.normalizedTimeLabels(30);

        assertEquals(30, labels.length());
        assertTrue(labels.contains("0%"));
        assertTrue(labels.contains("25%"));
        assertTrue(labels.contains("50%"));
        assertTrue(labels.contains("75%"));
        assertTrue(labels.contains("100%"));
    }
}
