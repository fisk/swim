package org.fisk.swim.plugins.jfrmetrics;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/** Extracts the periodic JVM CPU samples and heap summaries from a recording. */
final class JfrMetrics {
    record Sample(Instant time, double cpuPercent, long heapUsed, long heapCommitted,
                  double systemCpuPercent, long systemMemoryUsed, long systemMemoryTotal) {
        Sample(Instant time, double cpuPercent, long heapUsed, long heapCommitted) {
            this(time, cpuPercent, heapUsed, heapCommitted, Double.NaN, -1, -1);
        }
    }
    record Recording(Instant start, Instant end, List<Sample> samples) { }

    private static final class Update {
        boolean hasCpu;
        double cpu;
        double systemCpu = Double.NaN;
        boolean hasMemory;
        long memoryUsed;
        long memoryTotal;
        boolean hasHeap;
        long heapUsed;
        long heapCommitted;
    }

    static Recording read(Path path) throws IOException {
        checkInterrupted();
        // JFR events need not arrive in timestamp order. Coalesce both metrics in
        // one ordered index instead of building three trees and repeatedly looking up keys.
        var updates = new TreeMap<Instant, Update>();
        Instant start = null, end = null;
        try (RecordingFile recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                checkInterrupted();
                RecordedEvent event = recording.readEvent();
                Instant time = event.getEndTime();
                start = start == null || event.getStartTime().isBefore(start) ? event.getStartTime() : start;
                end = end == null || time.isAfter(end) ? time : end;
                String name = event.getEventType().getName();
                if ("jdk.CPULoad".equals(name)) {
                    Update update = updates.computeIfAbsent(time, ignored -> new Update());
                    update.hasCpu = true;
                    update.systemCpu = event.hasField("machineTotal")
                            ? 100.0 * event.getDouble("machineTotal") : Double.NaN;
                    update.cpu = 100.0 * (event.getDouble("jvmUser") + event.getDouble("jvmSystem"));
                } else if ("jdk.PhysicalMemory".equals(name)) {
                    Update update = updates.computeIfAbsent(time, ignored -> new Update());
                    update.hasMemory = true;
                    update.memoryUsed = event.getLong("usedSize");
                    update.memoryTotal = event.getLong("totalSize");
                } else if ("jdk.GCHeapSummary".equals(name)) {
                    RecordedObject space = event.getValue("heapSpace");
                    Update update = updates.computeIfAbsent(time, ignored -> new Update());
                    update.hasHeap = true;
                    update.heapUsed = event.getLong("heapUsed");
                    update.heapCommitted = space.getLong("committedSize");
                }
            }
        }
        if (start == null) return new Recording(null, null, List.of());
        var samples = new ArrayList<Sample>(updates.size());
        double latestCpu = Double.NaN, systemCpu = Double.NaN;
        long memoryUsed = -1, memoryTotal = -1;
        long heapUsed = -1, heapCommitted = -1;
        for (var entry : updates.entrySet()) {
            checkInterrupted();
            Update update = entry.getValue();
            if (update.hasCpu) {
                latestCpu = update.cpu;
                systemCpu = update.systemCpu;
            }
            if (update.hasMemory) {
                memoryUsed = update.memoryUsed;
                memoryTotal = update.memoryTotal;
            }
            if (update.hasHeap) {
                heapUsed = update.heapUsed;
                heapCommitted = update.heapCommitted;
            }
            samples.add(new Sample(entry.getKey(), latestCpu, heapUsed, heapCommitted,
                    systemCpu, memoryUsed, memoryTotal));
        }
        return new Recording(start, end, List.copyOf(samples));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("JFR loading cancelled");
        }
    }

    /** Limits a live recording to the most recent interval without changing file-backed recordings. */
    static Recording mostRecent(Recording recording, Duration duration) {
        if (recording == null || recording.end() == null || duration == null || duration.isNegative() || duration.isZero()) {
            return recording;
        }
        Instant start = recording.end().minus(duration);
        if (recording.start() != null && recording.start().isAfter(start)) {
            start = recording.start();
        }
        Instant cutoff = start;
        return new Recording(start, recording.end(), recording.samples().stream()
                .filter(sample -> !sample.time().isBefore(cutoff))
                .toList());
    }
}
