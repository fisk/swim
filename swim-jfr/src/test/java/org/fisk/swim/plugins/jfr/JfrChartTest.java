package org.fisk.swim.plugins.jfrmetrics;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class JfrChartTest {
    @Test void shorterRecordingsStopAtTheirElapsedPosition() {
        assertEquals(25, JfrPanel.elapsedColumn(5, 20, 101));
        assertEquals(100, JfrPanel.elapsedColumn(20, 20, 101));
        assertEquals(50, JfrPanel.elapsedColumn(10, 20, 101));
    }

    @Test void degenerateAndOutOfRangeTimesStayInsideCanvas() {
        assertEquals(0, JfrPanel.elapsedColumn(0, 0, 101));
        assertEquals(0, JfrPanel.elapsedColumn(-1, 20, 101));
        assertEquals(100, JfrPanel.elapsedColumn(40, 20, 101));
        assertEquals(0, JfrPanel.elapsedColumn(10, 20, 1));
    }

    @Test void chartHeightsAdaptToAvailableSpace() {
        assertEquals(2, JfrPanel.chartRows(10, 1));
        assertEquals(6, JfrPanel.chartRows(24, 1));
        assertEquals(14, JfrPanel.chartRows(40, 1));
        assertTrue(JfrPanel.chartRows(40, 4) < JfrPanel.chartRows(40, 1));
        assertEquals(24, JfrPanel.chartRows(1000, 1));
    }

    @Test void timeLabelsFitEvenTinyWidths() {
        for (int width = 0; width < 100; width++) {
            for (double seconds : new double[] {0, .25, 10, 120, 7200}) {
                String labels = JfrPanel.elapsedTimeLabels(width, seconds);
                assertEquals(width, labels.length());
                assertFalse(labels.contains("%"));
            }
        }
        assertTrue(JfrPanel.elapsedTimeLabels(30, .25).endsWith("250ms"));
        assertTrue(JfrPanel.elapsedTimeLabels(30, 7200).endsWith("2h"));
    }
    @Test void dualChartsHaveColoredAxesAndCachedRichOutput() throws Exception {
        var panel = new JfrPanel((java.nio.file.Path) null);
        var start = java.time.Instant.EPOCH;
        var recording = new JfrMetrics.Recording(start, start.plusSeconds(20), java.util.List.of(
                new JfrMetrics.Sample(start, 25, 512, 1024, 75, 2048, 4096),
                new JfrMetrics.Sample(start.plusSeconds(20), 50, 512, 1024, 60, 3072, 4096)));
        setRecording(panel, recording);
        var rich = panel.renderRich(80, 40);
        assertSame(rich, panel.renderRich(80, 40));
        assertTrue(rich.stream().anyMatch(line -> line.text().startsWith("JVM ·")));
        assertTrue(rich.stream().anyMatch(line -> line.text().startsWith("System ·")));
        assertTrue(rich.stream().flatMap(line -> line.spans().stream()).anyMatch(span -> "#69db7c".equals(span.foreground())));
        assertTrue(rich.stream().flatMap(line -> line.spans().stream()).anyMatch(span -> "#ff6b6b".equals(span.foreground())));
        assertTrue(rich.stream().anyMatch(line -> line.text().contains("20s")));
        assertFalse(rich.stream().anyMatch(line -> line.text().contains("\u2800")));
        assertEquals(panel.render(80, 40), rich.stream().map(org.fisk.swim.api.SwimPanelLine::text).toList());
    }

    @Test void memoryUnitTransitionsKeepPlotCellsAligned() throws Exception {
        for (long maximum : new long[] {1024, 1024 * 1024, 1024L * 1024 * 1024}) {
            var panel = new JfrPanel((java.nio.file.Path) null);
            var start = java.time.Instant.EPOCH;
            setRecording(panel, new JfrMetrics.Recording(start, start.plusSeconds(20), java.util.List.of(
                    new JfrMetrics.Sample(start, 0, 0, maximum, 0, maximum, maximum),
                    new JfrMetrics.Sample(start.plusSeconds(20), 0, 0, maximum, 0, maximum, maximum))));
            var lines = panel.render(80, 40);
            for (String line : lines) {
                int left = line.indexOf('│');
                int right = line.lastIndexOf('│');
                if (left < 0 || right == left) {
                    continue;
                }
                assertTrue(line.length() <= 80, line);
                String plot = line.substring(left + 1, right);
                assertTrue(plot.chars().allMatch(c -> c == ' ' || (c >= 0x2801 && c <= 0x28ff)), line);
                if (line.startsWith(" 50%")) {
                    assertTrue(plot.isBlank(), line);
                }
            }
        }
    }

    @Test void absentSystemEventsAreExplicitlyUnavailable() throws Exception {
        var panel = new JfrPanel((java.nio.file.Path) null);
        var start = java.time.Instant.EPOCH;
        setRecording(panel, new JfrMetrics.Recording(start, start.plusSeconds(1), java.util.List.of(
                new JfrMetrics.Sample(start, 20, 512, 1024))));
        assertTrue(panel.render(80, 40).stream().anyMatch(line -> line.contains("CPU unavailable · Memory unavailable")));
    }

    @Test void identicalTextRowsRetainTheirOwnMetricColors() throws Exception {
        var panel = new JfrPanel((java.nio.file.Path) null);
        var start = java.time.Instant.EPOCH;
        setRecording(panel, new JfrMetrics.Recording(start, start.plusSeconds(20), java.util.List.of(
                new JfrMetrics.Sample(start, 25, 0, 100, 100, 25, 100),
                new JfrMetrics.Sample(start.plusSeconds(20), 25, 0, 100, 100, 25, 100),
                new JfrMetrics.Sample(start.plusSeconds(20), 25, 0, 100, 100, 175, 200))));
        var rich = panel.renderRich(80, 40);
        // Both charts have the same memory maximum (100 after bucket averaging).
        // Use the row-index map directly to verify no styled rows were overwritten.
        var stylesField = JfrPanel.class.getDeclaredField("chartStyles");
        stylesField.setAccessible(true);
        var styles = (java.util.Map<?, ?>) stylesField.get(panel);
        assertEquals(2 * JfrPanel.chartRows(40, 1), styles.size());
        for (var entry : styles.entrySet()) {
            assertInstanceOf(Integer.class, entry.getKey());
            assertSame(entry.getValue(), rich.get((Integer) entry.getKey()));
        }
    }

    @Test void missingMetricsKeepBothTimeAxesAndHideSentinels() throws Exception {
        var panel = new JfrPanel((java.nio.file.Path) null);
        var start = java.time.Instant.EPOCH;
        setRecording(panel, new JfrMetrics.Recording(start, start.plusSeconds(20), java.util.List.of(
                new JfrMetrics.Sample(start, Double.NaN, -1, -1, 50, -1, -1))));
        var lines = panel.render(80, 40);
        assertEquals(2, lines.stream().filter(line -> line.stripLeading().startsWith("0s") && line.endsWith("20s")).count());
        assertTrue(lines.stream().anyMatch(line -> line.contains("latest CPU unavailable  committed heap unavailable")));
        assertFalse(lines.stream().anyMatch(line -> line.contains("NaN") || line.contains("-1 B")));
        assertEquals(lines, panel.renderRich(80, 40).stream().map(org.fisk.swim.api.SwimPanelLine::text).toList());
    }

    private static void setRecording(JfrPanel panel, JfrMetrics.Recording recording) throws Exception {
        Class<?> source = Class.forName(JfrPanel.class.getName() + "$Source");
        var constructor = source.getDeclaredConstructor(java.nio.file.Path.class, JfrMetrics.Recording.class);
        constructor.setAccessible(true);
        var sources = JfrPanel.class.getDeclaredField("sources");
        sources.setAccessible(true);
        sources.set(panel, java.util.List.of(constructor.newInstance(java.nio.file.Path.of("sample.jfr"), recording)));
    }
}
