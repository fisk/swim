package org.fisk.swim.plugins.jfrmetrics;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fisk.swim.api.SwimKeyBindingHint;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelLine;
import org.fisk.swim.api.SwimPanelResult;

/**
 * Live CPU-percent/memory-bytes charts using the same renderer as {@code :jfr}.
 * Producers may append from background threads. Runs have independent elapsed-time
 * origins and remain visible until explicitly replaced or removed. No sampling,
 * recording, process execution or filesystem access is performed by this panel.
 */
public final class LiveTimeSeriesPanel implements SwimPanel {
  private record Binding(String description, Runnable action) {}
  private final String id;
  private final String title;
  private final JfrPanel chart;
  private final int maxSamplesPerRun;
  private final Map<String, ArrayList<JfrMetrics.Sample>> runs = new LinkedHashMap<>();
  private final Map<String, Binding> bindings = new LinkedHashMap<>();
  private boolean dirty = true;

  public LiveTimeSeriesPanel(String id, String title) {
    this(id, title, 100_000);
  }

  /** Retains at most maxSamplesPerRun samples per run (oldest samples are dropped). */
  public LiveTimeSeriesPanel(String id, String title, int maxSamplesPerRun) {
    this.id = Objects.requireNonNull(id);
    this.title = Objects.requireNonNull(title);
    if (maxSamplesPerRun < 1) throw new IllegalArgumentException("Sample limit must be positive");
    this.maxSamplesPerRun = maxSamplesPerRun;
    chart = new JfrPanel(title);
  }

  /** Starts or replaces only the named run, retaining all other runs. */
  public synchronized void startRun(String name) {
    validateName(name);
    runs.put(name, new ArrayList<>());
    dirty = true;
  }

  private static void validateName(String name) {
    Objects.requireNonNull(name);
    if (name.isBlank() || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
        || name.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Run name must be a nonempty single-line label without slashes");
    }
  }

  /**
   * Appends a sample to an existing run; timestamps must be nonnegative and
   * nondecreasing. NaN CPU and negative memory mean unavailable. CPU uses 0..100.
   */
  public synchronized void append(String name, double elapsedSeconds, double cpuPercent, long memoryBytes) {
    var samples = runs.get(name);
    if (samples == null) throw new IllegalArgumentException("Unknown run: " + name);
    if (!Double.isFinite(elapsedSeconds) || elapsedSeconds < 0 || elapsedSeconds > Long.MAX_VALUE / 1e9) {
      throw new IllegalArgumentException("Invalid elapsed time");
    }
    if (!Double.isNaN(cpuPercent) && (!Double.isFinite(cpuPercent) || cpuPercent < 0 || cpuPercent > 100)) {
      throw new IllegalArgumentException("CPU must be 0..100 or NaN");
    }
    Instant time = Instant.EPOCH.plusNanos((long) (elapsedSeconds * 1e9));
    if (!samples.isEmpty() && time.isBefore(samples.getLast().time())) {
      throw new IllegalArgumentException("Samples must be in elapsed-time order");
    }
    if (samples.size() == maxSamplesPerRun) samples.removeFirst();
    samples.add(new JfrMetrics.Sample(time, Double.NaN, -1, -1, cpuPercent, memoryBytes, -1));
    dirty = true;
  }

  public synchronized void removeRun(String name) {
    runs.remove(name);
    dirty = true;
  }

  public synchronized List<String> runNames() { return List.copyOf(runs.keySet()); }

  /** Callbacks execute on the host input thread, outside the model lock; keep them short. */
  public synchronized LiveTimeSeriesPanel onKey(String key, String description, Runnable action) {
    bindings.put(Objects.requireNonNull(key), new Binding(Objects.requireNonNull(description), Objects.requireNonNull(action)));
    return this;
  }

  private void refresh() {
    if (!dirty) return;
    var recordings = new ArrayList<JfrMetrics.Recording>();
    for (var samples : runs.values()) {
      recordings.add(new JfrMetrics.Recording(Instant.EPOCH,
          samples.isEmpty() ? Instant.EPOCH : samples.getLast().time(), List.copyOf(samples)));
    }
    chart.setRuns(List.copyOf(runs.keySet()), recordings);
    dirty = false;
  }

  @Override public String getId() { return id; }
  @Override public String getTitle() { return title; }
  @Override public synchronized List<String> render(int width, int height) {
    refresh();
    return chart.render(width, height);
  }
  @Override public synchronized List<SwimPanelLine> renderRich(int width, int height) {
    refresh();
    return chart.renderRich(width, height);
  }
  @Override public void syncToCurrentPath(Path ignored) { }
  @Override public SwimPanelResult handleInput(String input, int width, int height) {
    Binding binding;
    synchronized (this) { binding = bindings.get(input); }
    if (binding == null) return SwimPanelResult.ignored();
    binding.action().run();
    return SwimPanelResult.successMessage(binding.description());
  }
  @Override public synchronized List<SwimKeyBindingHint> keyBindingHints() {
    var hints = new ArrayList<SwimKeyBindingHint>();
    bindings.forEach((key, binding) -> hints.add(new SwimKeyBindingHint(key, title, binding.description())));
    hints.add(new SwimKeyBindingHint("q", "Panel", "close"));
    return List.copyOf(hints);
  }
}
