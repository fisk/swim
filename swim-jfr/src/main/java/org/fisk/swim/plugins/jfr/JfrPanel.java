package org.fisk.swim.plugins.jfrmetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fisk.swim.api.SwimKeyBindingHint;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelLine;
import org.fisk.swim.api.SwimPanelResult;
import org.fisk.swim.api.SwimTextSpan;

final class JfrPanel implements SwimPanel {
  private static final String EXPLICIT_RECORDINGS_PREFIX = "swim-jfr-recordings:";
  private static final long LIVE_REFRESH_NANOS = 1_000_000_000L;
  private static final Duration LIVE_HISTORY = Duration.ofHours(1);

  private record Source(Path path, JfrMetrics.Recording recording) {}

  private List<Source> sources = List.of();
  private String error;
  private boolean live;
  private boolean followActivePath = true;
  private long lastLiveRefreshNanos;
  private static final java.util.concurrent.ExecutorService LOADER =
      java.util.concurrent.Executors.newSingleThreadExecutor(
          r -> {
            Thread thread = new Thread(r, "swim-jfr-loader");
            thread.setDaemon(true);
            return thread;
          });

  private record LoadResult(List<Source> sources, String error) {}

  private java.util.concurrent.Future<LoadResult> pending;
  private List<Path> requestedPaths = List.of();
  private List<String> cachedLines;
  private int cachedWidth, cachedHeight;

  JfrPanel(Path path) {
    loadPaths(path == null ? List.of() : List.of(path));
  }

  @Override
  public String getId() {
    return JfrPlugin.PLUGIN_ID;
  }

  @Override
  public String getTitle() {
    return "JFR Metrics";
  }

  @Override
  public List<String> render(int width, int height) {
    collectLoaded();
    refreshLiveRecordingIfDue();
    if (cachedLines == null || cachedWidth != width || cachedHeight != height) {
      cachedWidth = width;
      cachedHeight = height;
      cachedLines = List.copyOf(renderContent(width, height));
    }
    return cachedLines;
  }

  private List<String> renderContent(int width, int height) {
    chartStyles.clear();
    if (pending != null && sources.isEmpty()) {
      return List.of("JFR Metrics", "Loading recording metrics…");
    }
    if (error != null) {
      return List.of("JFR Metrics", error, "Use :jfr [recording.jfr[,other.jfr]].");
    }
    if (sources.isEmpty()
        || sources.stream().allMatch(source -> source.recording().samples().isEmpty())) {
      return List.of("JFR Metrics", "No CPU or committed-heap samples found.");
    }
    var lines = new ArrayList<String>();
    lines.add("JFR Metrics  " + sources.size() + " recording" + (sources.size() == 1 ? "" : "s"));
    lines.add(legend(width));
    for (int index = 0; index < sources.size(); index++) {
      Source source = sources.get(index);
      lines.add(
          " ["
              + (index + 1)
              + "] "
              + source.path().getFileName()
              + "  "
              + duration(source.recording()));
    }
    double elapsedSeconds =
        sources.stream().mapToDouble(source -> elapsedSeconds(source.recording())).max().orElse(0);
    int rows = chartRows(height, sources.size());
    lines.add("JVM · CPU (green, left) / heap capacity (red, right)");
    lines.addAll(
        dualChart(
            width,
            rows,
            elapsedSeconds,
            lines,
            JfrMetrics.Sample::cpuPercent,
            sample -> sample.heapCommitted()));
    lines.add("System · CPU (green, left) / memory used (red, right)");
    lines.addAll(
        dualChart(
            width,
            rows,
            elapsedSeconds,
            lines,
            JfrMetrics.Sample::systemCpuPercent,
            sample -> sample.systemMemoryUsed()));
    for (int index = 0; index < sources.size(); index++) {
      Source source = sources.get(index);
      if (!source.recording().samples().isEmpty()) {
        JfrMetrics.Sample last = source.recording().samples().getLast();
        lines.add(
            String.format(
                    " [%d] latest CPU %s  committed heap %s",
                    index + 1,
                    Double.isFinite(last.cpuPercent()) && last.cpuPercent() >= 0
                        ? String.format(java.util.Locale.ROOT, "%.1f%%", last.cpuPercent())
                        : "unavailable",
                    last.heapCommitted() >= 0 ? bytes(last.heapCommitted()) : "unavailable")
                + (!Double.isFinite(last.systemCpuPercent()) ? " · system CPU unavailable" : "")
                + (last.systemMemoryUsed() < 0 ? " · system memory unavailable" : ""));
      }
    }
    lines.add("Elapsed from each recording start · gold = overlap · r reload · q close");
    return lines;
  }

  @Override
  public SwimPanelResult handleInput(String input, int width, int height) {
    if (!"r".equals(input)) {
      return SwimPanelResult.ignored();
    }
    if (live) {
      return refreshLiveRecording();
    }
    loadPaths(requestedPaths);
    return SwimPanelResult.successMessage("Reloading JFR recordings…");
  }

  @Override
  public List<SwimKeyBindingHint> keyBindingHints() {
    return List.of(
        new SwimKeyBindingHint("r", "JFR", "reload recordings"),
        new SwimKeyBindingHint("q", "Panel", "close"));
  }

  @Override
  public void syncToCurrentPath(Path current) {
    boolean explicitLive = current != null && "swim-jfr-live:".equals(current.toString());
    if (current == null || explicitLive) {
      if (!explicitLive && !followActivePath) {
        return;
      }
      followActivePath = false;
      if (!live) {
        if (pending != null) {
          pending.cancel(true);
        }
        pending = null;
        sources = List.of();
      }
      refreshLiveRecording();
      return;
    }
    String encoded = current.toString();
    if (encoded.startsWith(EXPLICIT_RECORDINGS_PREFIX)) {
      followActivePath = false;
      live = false;
      loadPaths(splitPaths(encoded.substring(EXPLICIT_RECORDINGS_PREFIX.length())));
      return;
    }
    if (!followActivePath) {
      return;
    }
    live = false;
    List<Path> paths = splitPaths(current);
    if (!paths.equals(requestedPaths)) {
      loadPaths(paths);
    }
  }

  private SwimPanelResult refreshLiveRecording() {
    live = true;
    if (pending == null) {
      error = null;
      cachedLines = null;
      pending =
          LOADER.submit(
              () -> {
                try {
                  return readPaths(List.of(JfrLiveRecording.snapshot()), LIVE_HISTORY);
                } catch (IOException | RuntimeException e) {
                  return new LoadResult(
                      List.of(), "Unable to create live JFR snapshot: " + e.getMessage());
                }
              });
    }
    return SwimPanelResult.successMessage("Refreshing live JFR metrics…");
  }

  private void collectLoaded() {
    if (pending == null || !pending.isDone()) {
      return;
    }
    try {
      LoadResult result = pending.get();
      if (result.error() == null) {
        sources = result.sources();
      }
      error = result.error();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      error = "JFR loading interrupted";
    } catch (java.util.concurrent.ExecutionException e) {
      error = "Unable to load JFR metrics: " + e.getCause().getMessage();
    }
    pending = null;
    lastLiveRefreshNanos = System.nanoTime();
    cachedLines = null;
  }

  private void refreshLiveRecordingIfDue() {
    if (live && pending == null && System.nanoTime() - lastLiveRefreshNanos >= LIVE_REFRESH_NANOS) {
      refreshLiveRecording();
    }
  }

  private void loadPaths(List<Path> paths) {
    requestedPaths = paths == null ? List.of() : List.copyOf(paths);
    if (pending != null) {
      pending.cancel(true);
    }
    pending = null;
    sources = List.of();
    error = null;
    cachedLines = null;
    if (!requestedPaths.isEmpty()) {
      List<Path> request = requestedPaths;
      pending = LOADER.submit(() -> readPaths(request, null));
    }
  }

  private static LoadResult readPaths(List<Path> paths, Duration history) {
    var loaded = new ArrayList<Source>();
    for (Path path : paths) {
      if (Thread.currentThread().isInterrupted()) {
        return new LoadResult(List.of(), "JFR loading cancelled");
      }
      if (!isJfr(path)) {
        return new LoadResult(List.of(), "Not a .jfr recording: " + path);
      }
      try {
        JfrMetrics.Recording recording = JfrMetrics.read(path);
        loaded.add(
            new Source(
                path, history == null ? recording : JfrMetrics.mostRecent(recording, history)));
      } catch (IOException | RuntimeException e) {
        return new LoadResult(
            List.of(), "Unable to read " + path.getFileName() + ": " + e.getMessage());
      }
    }
    return new LoadResult(List.copyOf(loaded), null);
  }

  private String legend(int width) {
    var text = new StringBuilder("Legend (traces in both charts): ");
    for (int index = 0; index < sources.size(); index++) {
      if (index > 0) {
        text.append("  ");
      }
      text.append('[')
          .append(index + 1)
          .append("] ")
          .append(sources.get(index).path().getFileName());
    }
    return ellipsize(text.toString(), Math.max(1, width));
  }

  private static List<Path> splitPaths(Path combined) {
    return splitPaths(combined.toString());
  }

  private static List<Path> splitPaths(String paths) {
    return java.util.Arrays.stream(paths.split(",", -1)).map(Path::of).toList();
  }

  private final java.util.Map<Integer, SwimPanelLine> chartStyles = new java.util.HashMap<>();

  private List<String> richSourceLines;
  private List<SwimPanelLine> richLines;

  @Override
  public List<SwimPanelLine> renderRich(int width, int height) {
    List<String> plain = render(width, height);
    if (plain != richSourceLines) {
      richLines =
          java.util.stream.IntStream.range(0, plain.size())
              .mapToObj(row -> chartStyles.getOrDefault(row, SwimPanelLine.plain(plain.get(row))))
              .toList();
      richSourceLines = plain;
    }
    return richLines;
  }

  private List<String> dualChart(
      int width,
      int rows,
      double seconds,
      List<String> lines,
      java.util.function.ToDoubleFunction<JfrMetrics.Sample> cpu,
      java.util.function.ToDoubleFunction<JfrMetrics.Sample> memory) {
    boolean cpuAvailable =
        sources.stream()
            .flatMap(source -> source.recording().samples().stream())
            .mapToDouble(cpu)
            .anyMatch(value -> Double.isFinite(value) && value >= 0);
    boolean memoryAvailable =
        sources.stream()
            .flatMap(source -> source.recording().samples().stream())
            .mapToDouble(memory)
            .anyMatch(value -> Double.isFinite(value) && value >= 0);
    double maximum =
        sources.stream()
            .flatMap(source -> source.recording().samples().stream())
            .mapToDouble(memory)
            .filter(value -> Double.isFinite(value) && value >= 0)
            .max()
            .orElse(0);
    // The midpoint may use a smaller unit and need more space (1.0 GiB vs
    // 512.0 MiB). Every label must fit before extracting the plot columns.
    int rightWidth =
        Math.max(3, Math.max(bytes((long) maximum).length(), bytes((long) (maximum / 2)).length()));
    int plotWidth = Math.max(1, width - 9 - rightWidth);
    List<String> cpuRows = chart(plotWidth, 4, rows, 100, true, seconds, cpu);
    List<String> memoryRows = chart(plotWidth, rightWidth, rows, maximum, false, seconds, memory);
    var result = new ArrayList<String>();
    for (int row = 0; row < rows; row++) {
      String left =
          cpuAvailable ? cpuRows.get(row).substring(0, 6) : pad(row == 0 ? "N/A" : "", 4) + " │";
      String right =
          "│ "
              + pad(
                  !memoryAvailable
                      ? row == 0 ? "N/A" : ""
                      : row == 0
                          ? bytes((long) maximum)
                          : row == rows - 1
                              ? "0 B"
                              : rows >= 5 && row == rows / 2 ? bytes((long) (maximum / 2)) : "",
                  rightWidth);
      var spans = new ArrayList<SwimTextSpan>();
      spans.add(SwimTextSpan.styled(left, "#69db7c", null));
      for (int column = 0; column < plotWidth; column++) {
        char c = cpuRows.get(row).charAt(6 + column);
        char m = memoryRows.get(row).charAt(rightWidth + 2 + column);
        int cpuDots = c == ' ' ? 0 : c - 0x2800;
        int memoryDots = m == ' ' ? 0 : m - 0x2800;
        // A terminal cell has one foreground: retain both dot masks and mark overlap gold.
        int dots = cpuDots | memoryDots;
        // U+2800 is the Unicode Braille blank, not an ordinary space. Some
        // terminal renderers show it as a visible glyph, so preserve a real
        // space where neither series has a dot.
        char merged = dots == 0 ? ' ' : (char) (0x2800 | dots);
        String color =
            cpuDots != 0 && memoryDots != 0 ? "#ffd166" : memoryDots != 0 ? "#ff6b6b" : "#69db7c";
        spans.add(SwimTextSpan.styled(String.valueOf(merged), color, null));
      }
      spans.add(SwimTextSpan.styled(right, "#ff6b6b", null));
      SwimPanelLine rich = new SwimPanelLine(spans);
      result.add(rich.text());
      chartStyles.put(lines.size() + row, rich);
    }
    result.add(cpuRows.get(rows));
    result.add(cpuRows.get(rows + 1));
    if (!cpuAvailable || !memoryAvailable) {
      int titleRow = lines.size() - 1;
      lines.set(
          titleRow,
          lines.get(titleRow)
              + " · "
              + (!cpuAvailable ? "CPU unavailable" : "")
              + (!cpuAvailable && !memoryAvailable ? " · " : "")
              + (!memoryAvailable ? "Memory unavailable" : ""));
    }
    return result;
  }

  private List<String> chart(
      int width,
      int labelWidth,
      int rows,
      double maximum,
      boolean percent,
      double elapsedSeconds,
      java.util.function.ToDoubleFunction<JfrMetrics.Sample> value) {
    final String maximumLabel = percent ? "100%" : bytes((long) maximum);
    final String minimumLabel = percent ? "0%" : "0 B";
    // A Braille cell contains two columns by four rows of independently
    // addressable dots. Render at that resolution so crossings and nearby
    // series remain visible when they share a terminal character.
    int dotColumns = width * BrailleCanvas.DOTS_PER_CELL_WIDTH;
    var series = new ArrayList<Series>();
    for (Source source : sources) {
      double[] totals = new double[dotColumns];
      int[] counts = new int[dotColumns];
      JfrMetrics.Recording recording = source.recording();
      if (recording.start() != null && recording.end() != null) {
        for (JfrMetrics.Sample sample : recording.samples()) {
          double offset =
              Math.max(
                  0,
                  Duration.between(recording.start(), sample.time()).toNanos() / 1_000_000_000.0);
          int column = elapsedColumn(offset, elapsedSeconds, dotColumns);
          double sampleValue = value.applyAsDouble(sample);
          if (!Double.isFinite(sampleValue) || sampleValue < 0) {
            continue;
          }
          totals[column] += sampleValue;
          counts[column]++;
        }
      }
      double[] values = new double[dotColumns];
      boolean[] present = new boolean[dotColumns];
      for (int column = 0; column < dotColumns; column++) {
        if (counts[column] != 0) {
          values[column] = totals[column] / counts[column];
        }
        present[column] = counts[column] != 0;
      }
      series.add(new Series(values, present));
    }
    var result = new ArrayList<String>(rows + 2);
    double scale = Math.max(1, maximum);
    var canvas = new BrailleCanvas(width, rows);
    for (Series values : series) {
      int previousColumn = -1;
      int previousRow = -1;
      for (int column = 0; column < dotColumns; column++) {
        if (!values.present()[column]) {
          continue;
        }
        int row = scaleToDotRow(values.values()[column], scale, canvas.dotHeight());
        if (previousColumn >= 0) {
          canvas.drawLine(previousColumn, previousRow, column, row);
        } else {
          canvas.plot(column, row);
        }
        previousColumn = column;
        previousRow = row;
      }
    }
    for (int row = rows; row >= 1; row--) {
      String label =
          row == rows
              ? maximumLabel
              : row == 1
                  ? minimumLabel
                  : rows >= 5 && row == (rows + 1) / 2
                      ? percent ? "50%" : bytes((long) (maximum / 2))
                      : "";
      result.add(pad(label, labelWidth) + " │" + canvas.row(rows - row));
    }
    result.add(" ".repeat(labelWidth) + " └" + "─".repeat(width));
    result.add(" ".repeat(labelWidth + 2) + elapsedTimeLabels(width, elapsedSeconds));
    return result;
  }

  // Header, source descriptions, chart titles/axes, latest values and footer.
  static int chartRows(int height, int sourceCount) {
    return Math.max(2, Math.min(24, (height - 9 - 2 * sourceCount) / 2));
  }

  static double elapsedSeconds(JfrMetrics.Recording recording) {
    if (recording.start() == null || recording.end() == null) {
      return 0;
    }
    return Math.max(
        0, Duration.between(recording.start(), recording.end()).toNanos() / 1_000_000_000.0);
  }

  static int elapsedColumn(double offsetSeconds, double longestSeconds, int columns) {
    if (columns <= 1 || longestSeconds <= 0) {
      return 0;
    }
    return (int)
        Math.round(Math.max(0, Math.min(1, offsetSeconds / longestSeconds)) * (columns - 1));
  }

  static String elapsedTimeLabels(int width, double seconds) {
    if (width <= 0) {
      return "";
    }
    char[] labels = new char[width];
    java.util.Arrays.fill(labels, ' ');
    String end = timeLabel(seconds);
    if (end.length() + 3 > width) {
      String start = "0s";
      return start.substring(0, Math.min(width, start.length()))
          + " ".repeat(Math.max(0, width - start.length()));
    }
    placeLabel(labels, 0, "0s");
    int endStart = width - end.length();
    placeLabel(labels, endStart, end);
    int previousEnd = 2;
    int intervals = width >= 48 ? 4 : 2;
    for (int tick = 1; tick < intervals; tick++) {
      String text = timeLabel(seconds * tick / intervals);
      int start = (int) Math.round((width - 1.0) * tick / intervals) - text.length() / 2;
      if (start > previousEnd && start + text.length() < endStart) {
        placeLabel(labels, start, text);
        previousEnd = start + text.length();
      }
    }
    return new String(labels);
  }

  private static void placeLabel(char[] labels, int start, String text) {
    text.getChars(0, text.length(), labels, start);
  }

  private static String timeLabel(double seconds) {
    if (seconds == 0) {
      return "0s";
    }
    double amount =
        seconds >= 3600
            ? seconds / 3600
            : seconds >= 60 ? seconds / 60 : seconds < 1 ? seconds * 1000 : seconds;
    String unit = seconds >= 3600 ? "h" : seconds >= 60 ? "m" : seconds < 1 ? "ms" : "s";
    return String.format(
        java.util.Locale.ROOT, amount == Math.rint(amount) ? "%.0f%s" : "%.1f%s", amount, unit);
  }

  private static int scaleToDotRow(double value, double scale, int dotHeight) {
    double normalized = Math.max(0.0, Math.min(1.0, value / scale));
    return (int) Math.round((1.0 - normalized) * (dotHeight - 1));
  }

  private record Series(double[] values, boolean[] present) {}

  /**
   * A compact raster backed by Unicode Braille. plot() only sets dots, so drawing another recording
   * into the same cell preserves both paths.
   */
  static final class BrailleCanvas {
    static final int DOTS_PER_CELL_WIDTH = 2;
    private static final int DOTS_PER_CELL_HEIGHT = 4;
    private static final int[] DOT_BITS = {0x01, 0x02, 0x04, 0x40, 0x08, 0x10, 0x20, 0x80};

    private final int width;
    private final int height;
    private final int[] cells;

    BrailleCanvas(int width, int height) {
      this.width = width;
      this.height = height;
      this.cells = new int[width * height];
    }

    int dotHeight() {
      return height * DOTS_PER_CELL_HEIGHT;
    }

    void plot(int x, int y) {
      if (x < 0 || x >= width * DOTS_PER_CELL_WIDTH || y < 0 || y >= dotHeight()) {
        return;
      }
      int cellX = x / DOTS_PER_CELL_WIDTH;
      int cellY = y / DOTS_PER_CELL_HEIGHT;
      int dot = (x % DOTS_PER_CELL_WIDTH) * DOTS_PER_CELL_HEIGHT + y % DOTS_PER_CELL_HEIGHT;
      cells[cellY * width + cellX] |= DOT_BITS[dot];
    }

    void drawLine(int fromX, int fromY, int toX, int toY) {
      int dx = Math.abs(toX - fromX);
      int sx = fromX < toX ? 1 : -1;
      int dy = -Math.abs(toY - fromY);
      int sy = fromY < toY ? 1 : -1;
      int error = dx + dy;
      while (true) {
        plot(fromX, fromY);
        if (fromX == toX && fromY == toY) {
          return;
        }
        int twiceError = 2 * error;
        if (twiceError >= dy) {
          error += dy;
          fromX += sx;
        }
        if (twiceError <= dx) {
          error += dx;
          fromY += sy;
        }
      }
    }

    String row(int row) {
      var text = new StringBuilder(width);
      for (int column = 0; column < width; column++) {
        int dots = cells[row * width + column];
        text.append(dots == 0 ? ' ' : (char) (0x2800 + dots));
      }
      return text.toString();
    }
  }

  private static boolean isJfr(Path value) {
    return value != null
        && Files.isRegularFile(value)
        && value.getFileName().toString().toLowerCase().endsWith(".jfr");
  }

  private static String duration(JfrMetrics.Recording recording) {
    return recording.start() == null || recording.end() == null
        ? ""
        : Duration.between(recording.start(), recording.end()).toString();
  }

  private static String pad(String text, int width) {
    return " ".repeat(Math.max(0, width - text.length())) + text;
  }

  private static String ellipsize(String text, int width) {
    return text.length() <= width
        ? text
        : width <= 1 ? text.substring(0, width) : text.substring(0, width - 1) + "…";
  }

  private static String bytes(long value) {
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
    int index = 0;
    double number = value;
    while (number >= 1024 && index < units.length - 1) {
      number /= 1024;
      index++;
    }
    return String.format("%.1f %s", number, units[index]);
  }
}
