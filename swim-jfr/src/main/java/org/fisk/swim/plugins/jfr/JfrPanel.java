package org.fisk.swim.plugins.jfrmetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.fisk.swim.api.SwimKeyBindingHint;
import org.fisk.swim.api.SwimPanel;
import org.fisk.swim.api.SwimPanelResult;

final class JfrPanel implements SwimPanel {
    private static final String EXPLICIT_RECORDINGS_PREFIX = "swim-jfr-recordings:";
    private static final long LIVE_REFRESH_NANOS = 1_000_000_000L;
    private static final Duration LIVE_HISTORY = Duration.ofHours(1);
    private record Source(Path path, JfrMetrics.Recording recording) { }

    private List<Source> sources = List.of();
    private String error;
    private boolean live;
    private boolean followActivePath = true;
    private long lastLiveRefreshNanos;

    JfrPanel(Path path) { loadPaths(path == null ? List.of() : List.of(path)); }
    @Override public String getId() { return JfrPlugin.PLUGIN_ID; }
    @Override public String getTitle() { return "JFR Metrics"; }

    @Override public List<String> render(int width, int height) {
        refreshLiveRecordingIfDue();
        if (error != null) return List.of("JFR Metrics", error, "Use :jfr [recording.jfr[,other.jfr]].");
        if (sources.isEmpty() || sources.stream().allMatch(source -> source.recording().samples().isEmpty())) {
            return List.of("JFR Metrics", "No CPU or committed-heap samples found.");
        }
        var lines = new ArrayList<String>();
        lines.add("JFR Metrics  " + sources.size() + " recording" + (sources.size() == 1 ? "" : "s"));
        lines.add(legend(width));
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            lines.add(" [" + (index + 1) + "] " + source.path().getFileName() + "  " + duration(source.recording()));
        }
        long maximumCommitted = sources.stream().flatMap(source -> source.recording().samples().stream())
                .mapToLong(JfrMetrics.Sample::heapCommitted).max().orElse(1);
        int labelWidth = Math.max("100%".length(), bytes(maximumCommitted).length());
        int chartWidth = Math.max(1, width - labelWidth - 2); // Label plus " │"
        lines.add("CPU utilization (JVM user + system, %; time normalized per recording)");
        lines.addAll(chart(chartWidth, labelWidth, 100.0, JfrMetrics.Sample::cpuPercent));
        lines.add("Committed heap memory (scale is largest committed heap across recordings)");
        lines.addAll(chart(chartWidth, labelWidth, maximumCommitted, sample -> sample.heapCommitted()));
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            if (!source.recording().samples().isEmpty()) {
                JfrMetrics.Sample last = source.recording().samples().getLast();
                lines.add(String.format(" [%d] latest CPU %.1f%%  committed heap %s", index + 1,
                        last.cpuPercent(), bytes(last.heapCommitted())));
            }
        }
        lines.add("Braille traces preserve crossings within a character. r reloads; q closes.");
        return lines;
    }

    @Override public SwimPanelResult handleInput(String input, int width, int height) {
        if (!"r".equals(input)) return SwimPanelResult.ignored();
        if (live) return refreshLiveRecording();
        loadPaths(sources.stream().map(Source::path).toList());
        return SwimPanelResult.successMessage(error == null ? "JFR recordings reloaded" : error);
    }
    @Override public List<SwimKeyBindingHint> keyBindingHints() {
        return List.of(new SwimKeyBindingHint("r", "JFR", "reload recordings"), new SwimKeyBindingHint("q", "Panel", "close"));
    }
    @Override public void syncToCurrentPath(Path current) {
        if (current == null) {
            followActivePath = false;
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
        if (!followActivePath) return;
        live = false;
        loadPaths(splitPaths(current));
    }

    private SwimPanelResult refreshLiveRecording() {
        try {
            loadPaths(List.of(JfrLiveRecording.snapshot()), LIVE_HISTORY);
            live = error == null;
            lastLiveRefreshNanos = System.nanoTime();
            return error == null ? SwimPanelResult.successMessage("Live JFR recording refreshed")
                    : new SwimPanelResult(false, null, error);
        } catch (IOException | RuntimeException e) {
            live = false;
            error = "Unable to create live JFR snapshot: " + e.getMessage();
            return new SwimPanelResult(false, null, error);
        }
    }

    private void refreshLiveRecordingIfDue() {
        if (live && System.nanoTime() - lastLiveRefreshNanos >= LIVE_REFRESH_NANOS) {
            refreshLiveRecording();
        }
    }

    private void loadPaths(List<Path> paths) {
        loadPaths(paths, null);
    }

    private void loadPaths(List<Path> paths, Duration history) {
        sources = List.of();
        error = null;
        if (paths == null || paths.isEmpty()) return;
        var loaded = new ArrayList<Source>();
        for (Path path : paths) {
            if (!isJfr(path)) {
                error = "Not a .jfr recording: " + path;
                return;
            }
            try {
                JfrMetrics.Recording recording = JfrMetrics.read(path);
                loaded.add(new Source(path, history == null ? recording : JfrMetrics.mostRecent(recording, history)));
            } catch (IOException | RuntimeException e) {
                error = "Unable to read " + path.getFileName() + ": " + e.getMessage();
                return;
            }
        }
        sources = List.copyOf(loaded);
    }

    private String legend(int width) {
        var text = new StringBuilder("Legend (traces in both charts): ");
        for (int index = 0; index < sources.size(); index++) {
            if (index > 0) text.append("  ");
            text.append('[').append(index + 1).append("] ").append(sources.get(index).path().getFileName());
        }
        return ellipsize(text.toString(), Math.max(1, width));
    }

    private static List<Path> splitPaths(Path combined) {
        return splitPaths(combined.toString());
    }

    private static List<Path> splitPaths(String paths) {
        return java.util.Arrays.stream(paths.split(",", -1))
                .map(Path::of)
                .toList();
    }

    private List<String> chart(int width, int labelWidth, double maximum,
            java.util.function.ToDoubleFunction<JfrMetrics.Sample> value) {
        final int rows = 10;
        final String maximumLabel = maximum == 100.0 ? "100%" : bytes((long) maximum);
        final String minimumLabel = maximum == 100.0 ? "0%" : "";
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
                long duration = Math.max(1, Duration.between(recording.start(), recording.end()).toNanos());
                for (JfrMetrics.Sample sample : recording.samples()) {
                    long offset = Math.max(0, Duration.between(recording.start(), sample.time()).toNanos());
                    int column = Math.min(dotColumns - 1,
                            (int) Math.floor(offset / (double) duration * dotColumns));
                    totals[column] += value.applyAsDouble(sample);
                    counts[column]++;
                }
            }
            double[] values = new double[dotColumns];
            boolean[] present = new boolean[dotColumns];
            for (int column = 0; column < dotColumns; column++) {
                if (counts[column] != 0) values[column] = totals[column] / counts[column];
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
                if (!values.present()[column]) continue;
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
            String label = row == rows ? maximumLabel : row == 1 ? minimumLabel : "";
            result.add(pad(label, labelWidth) + " │" + canvas.row(rows - row));
        }
        result.add(" ".repeat(labelWidth) + " └" + "─".repeat(width));
        result.add(" ".repeat(labelWidth + 2) + normalizedTimeLabels(width));
        return result;
    }

    static String normalizedTimeLabels(int width) {
        record Tick(double position, String label) { }
        List<Tick> ticks = width >= 24
                ? List.of(new Tick(0, "0%"), new Tick(.25, "25%"), new Tick(.5, "50%"), new Tick(.75, "75%"), new Tick(1, "100%"))
                : width >= 11 ? List.of(new Tick(0, "0%"), new Tick(.5, "50%"), new Tick(1, "100%"))
                : List.of(new Tick(0, "0%"), new Tick(1, "100%"));
        char[] labels = new char[width];
        java.util.Arrays.fill(labels, ' ');
        for (Tick tick : ticks) {
            int center = (int) Math.round(tick.position() * (width - 1));
            int start = Math.max(0, Math.min(width - tick.label().length(), center - tick.label().length() / 2));
            for (int column = 0; column < tick.label().length() && start + column < width; column++) {
                labels[start + column] = tick.label().charAt(column);
            }
        }
        return new String(labels);
    }

    private static int scaleToDotRow(double value, double scale, int dotHeight) {
        double normalized = Math.max(0.0, Math.min(1.0, value / scale));
        return (int) Math.round((1.0 - normalized) * (dotHeight - 1));
    }

    private record Series(double[] values, boolean[] present) { }

    /**
     * A compact raster backed by Unicode Braille. plot() only sets dots, so
     * drawing another recording into the same cell preserves both paths.
     */
    static final class BrailleCanvas {
        static final int DOTS_PER_CELL_WIDTH = 2;
        private static final int DOTS_PER_CELL_HEIGHT = 4;
        private static final int[] DOT_BITS = { 0x01, 0x02, 0x04, 0x40, 0x08, 0x10, 0x20, 0x80 };

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
            if (x < 0 || x >= width * DOTS_PER_CELL_WIDTH || y < 0 || y >= dotHeight()) return;
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
                if (fromX == toX && fromY == toY) return;
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
        return value != null && Files.isRegularFile(value)
                && value.getFileName().toString().toLowerCase().endsWith(".jfr");
    }
    private static String duration(JfrMetrics.Recording recording) {
        return recording.start() == null || recording.end() == null ? "" : Duration.between(recording.start(), recording.end()).toString();
    }
    private static String pad(String text, int width) { return " ".repeat(Math.max(0, width - text.length())) + text; }
    private static String ellipsize(String text, int width) {
        return text.length() <= width ? text : width <= 1 ? text.substring(0, width) : text.substring(0, width - 1) + "…";
    }
    private static String bytes(long value) { String[] u = {"B", "KiB", "MiB", "GiB", "TiB"}; int i = 0; double v = value; while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; } return String.format("%.1f %s", v, u[i]); }
}
