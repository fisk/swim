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
    private record Source(Path path, JfrMetrics.Recording recording) { }

    private List<Source> sources = List.of();
    private String error;
    private boolean live;

    JfrPanel(Path path) { loadPaths(path == null ? List.of() : List.of(path)); }
    @Override public String getId() { return JfrPlugin.PLUGIN_ID; }
    @Override public String getTitle() { return "JFR Metrics"; }

    @Override public List<String> render(int width, int height) {
        int chartWidth = Math.max(1, width - 8);
        if (error != null) return List.of("JFR Metrics", error, "Use :jfr [recording.jfr[,other.jfr]].");
        if (sources.isEmpty() || sources.stream().allMatch(source -> source.recording().samples().isEmpty())) {
            return List.of("JFR Metrics", "No CPU or committed-heap samples found.");
        }
        var lines = new ArrayList<String>();
        lines.add("JFR Metrics  " + sources.size() + " recording" + (sources.size() == 1 ? "" : "s"));
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            lines.add(" [" + (index + 1) + "] " + glyph(index) + " " + source.path().getFileName() + "  " + duration(source.recording()));
        }
        lines.add("CPU utilization (JVM user + system, %; time normalized per recording)");
        lines.addAll(chart(chartWidth, 100.0, JfrMetrics.Sample::cpuPercent));
        lines.add("Committed heap memory (scale is largest committed heap across recordings)");
        long maximumCommitted = sources.stream().flatMap(source -> source.recording().samples().stream())
                .mapToLong(JfrMetrics.Sample::heapCommitted).max().orElse(1);
        lines.addAll(chart(chartWidth, maximumCommitted, sample -> sample.heapCommitted()));
        for (int index = 0; index < sources.size(); index++) {
            Source source = sources.get(index);
            if (!source.recording().samples().isEmpty()) {
                JfrMetrics.Sample last = source.recording().samples().getLast();
                lines.add(String.format(" [%d] latest CPU %.1f%%  committed heap %s", index + 1,
                        last.cpuPercent(), bytes(last.heapCommitted())));
            }
        }
        lines.add("Overlapping fills use ▒. r reloads; q closes.");
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
        if (isJfr(current)) {
            live = false;
            loadPaths(List.of(current));
        }
    }
    @Override public SwimPanelResult openPaths(List<Path> paths) {
        live = false;
        loadPaths(paths);
        return error == null ? SwimPanelResult.successMessage("Opened " + sources.size() + " JFR recording(s)")
                : new SwimPanelResult(false, null, error);
    }
    @Override public SwimPanelResult openDefault() { return refreshLiveRecording(); }

    private SwimPanelResult refreshLiveRecording() {
        try {
            loadPaths(List.of(JfrLiveRecording.snapshot()));
            live = error == null;
            return error == null ? SwimPanelResult.successMessage("Live JFR recording refreshed")
                    : new SwimPanelResult(false, null, error);
        } catch (IOException | RuntimeException e) {
            live = false;
            error = "Unable to create live JFR snapshot: " + e.getMessage();
            return new SwimPanelResult(false, null, error);
        }
    }

    private void loadPaths(List<Path> paths) {
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
                loaded.add(new Source(path, JfrMetrics.read(path)));
            } catch (IOException | RuntimeException e) {
                error = "Unable to read " + path.getFileName() + ": " + e.getMessage();
                return;
            }
        }
        sources = List.copyOf(loaded);
    }

    private List<String> chart(int width, double maximum, java.util.function.ToDoubleFunction<JfrMetrics.Sample> value) {
        final int rows = 10;
        var columns = new ArrayList<double[]>();
        for (Source source : sources) {
            double[] values = new double[width];
            JfrMetrics.Recording recording = source.recording();
            if (recording.start() != null && recording.end() != null) {
                long duration = Math.max(1, Duration.between(recording.start(), recording.end()).toNanos());
                for (JfrMetrics.Sample sample : recording.samples()) {
                    long offset = Math.max(0, Duration.between(recording.start(), sample.time()).toNanos());
                    int column = (int) Math.min(width - 1, offset * width / duration);
                    values[column] = Math.max(values[column], value.applyAsDouble(sample));
                }
            }
            columns.add(values);
        }
        var result = new ArrayList<String>(rows);
        double scale = Math.max(1, maximum);
        for (int row = rows; row >= 1; row--) {
            var line = new StringBuilder(row == rows
                    ? (maximum == 100 ? "100% │" : pad(bytes((long) maximum), 5) + " │")
                    : row == 1 && maximum == 100 ? "  0% │" : "      │");
            for (int column = 0; column < width; column++) {
                int visible = -1;
                for (int source = 0; source < columns.size(); source++) {
                    if (columns.get(source)[column] / scale * rows >= row) {
                        visible = visible == -1 ? source : -2;
                    }
                }
                line.append(visible == -2 ? '▒' : visible < 0 ? ' ' : glyph(visible));
            }
            result.add(line.toString());
        }
        return result;
    }

    private static boolean isJfr(Path value) {
        return value != null && Files.isRegularFile(value)
                && value.getFileName().toString().toLowerCase().endsWith(".jfr");
    }
    private static char glyph(int index) { return switch (index % 3) { case 0 -> '█'; case 1 -> '▓'; default -> '░'; }; }
    private static String duration(JfrMetrics.Recording recording) {
        return recording.start() == null || recording.end() == null ? "" : Duration.between(recording.start(), recording.end()).toString();
    }
    private static String pad(String text, int width) { return " ".repeat(Math.max(0, width - text.length())) + text; }
    private static String bytes(long value) { String[] u = {"B", "KiB", "MiB", "GiB", "TiB"}; int i = 0; double v = value; while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; } return String.format("%.1f %s", v, u[i]); }
}
