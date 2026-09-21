package org.fisk.swim.plugins.jfrmetrics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveTimeSeriesPanelTest {
  @Test void singleChartUsesAvailableHeightAndSystemOnlySummary() {
    var panel = new LiveTimeSeriesPanel("test", "Benchmark");
    panel.startRun("A");
    panel.append("A", 0, 25, 2048);
    panel.startRun("B"); // Empty runs should not reserve a latest-value row.
    for (int height : new int[] {24, 40, 80}) {
      var lines = panel.render(100, height);
      assertEquals(height, lines.size());
      assertEquals(lines, panel.renderRich(100, height).stream().map(s -> s.text()).toList());
      assertTrue(lines.stream().anyMatch(s -> s.contains("latest system CPU 25.0%  memory used 2.0 KiB")));
      assertFalse(lines.stream().anyMatch(s -> s.contains("JVM") || s.contains("NaN")
          || s.contains("committed heap") || s.contains("both charts")));
    }
    panel.append("B", 0, Double.NaN, -1);
    var lines = panel.render(100, 40);
    assertEquals(40, lines.size());
    assertTrue(lines.stream().anyMatch(s -> s.contains("latest system CPU unavailable  memory used unavailable")));
    assertDoesNotThrow(() -> panel.renderRich(20, 5));
  }

  @Test void retainsRunsAndUpdatesCachedChart() {
    var panel = new LiveTimeSeriesPanel("test", "Benchmark");
    panel.startRun("A");
    var empty = panel.render(100, 30);
    panel.append("A", 0, 25, 1024);
    panel.append("A", 1, 50, 2048);
    panel.startRun("B");
    panel.append("B", 0, 80, 4096);
    var lines = panel.render(100, 30);
    assertNotEquals(empty, lines);
    assertEquals(List.of("A", "B"), panel.runNames());
    assertTrue(lines.stream().anyMatch(s -> s.contains("System · CPU")));
    assertFalse(lines.stream().anyMatch(s -> s.contains("JVM · CPU")));
    assertEquals(lines, panel.renderRich(100, 30).stream().map(s -> s.text()).toList());
    panel.startRun("A");
    assertEquals(List.of("A", "B"), panel.runNames());
    assertTrue(panel.render(100, 30).stream().anyMatch(s -> s.contains("4.0 KiB")));
  }

  @Test void keysInvokePluginCallbacksAndValidateSamples() {
    var panel = new LiveTimeSeriesPanel("test", "Benchmark", 2);
    var calls = new AtomicInteger();
    panel.onKey("a", "start A", () -> { calls.incrementAndGet(); panel.startRun("A"); });
    panel.onKey("b", "start B", () -> { calls.incrementAndGet(); panel.startRun("B"); });
    panel.handleInput("a", 80, 24);
    panel.handleInput("b", 80, 24);
    assertEquals(2, calls.get());
    panel.append("A", 1, Double.NaN, -1);
    panel.append("A", 2, 0, 0);
    panel.append("A", 3, 100, 1000);
    assertThrows(IllegalArgumentException.class, () -> panel.append("A", 2, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> panel.append("A", 4, 101, 0));
    assertThrows(IllegalArgumentException.class, () -> panel.append("C", 0, 0, 0));
    assertDoesNotThrow(() -> panel.renderRich(80, 24));
  }
}
