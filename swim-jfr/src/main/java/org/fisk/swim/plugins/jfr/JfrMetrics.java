package org.fisk.swim.plugins.jfrmetrics;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;

/** Extracts the periodic JVM CPU samples and heap summaries from a recording. */
final class JfrMetrics {
    record Sample(Instant time, double cpuPercent, long heapUsed, long heapCommitted) { }
    record Recording(Instant start, Instant end, List<Sample> samples) { }

    static Recording read(Path path) throws IOException {
        var cpu = new TreeMap<Instant, Double>();
        var heap = new TreeMap<Instant, long[]>();
        Instant start = null, end = null;
        try (RecordingFile recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                Instant time = event.getEndTime();
                start = start == null || event.getStartTime().isBefore(start) ? event.getStartTime() : start;
                end = end == null || time.isAfter(end) ? time : end;
                if ("jdk.CPULoad".equals(event.getEventType().getName())) {
                    // jvmUser and jvmSystem are fractions of one CPU, not durations.
                    cpu.put(time, 100.0 * (event.getDouble("jvmUser") + event.getDouble("jvmSystem")));
                } else if ("jdk.GCHeapSummary".equals(event.getEventType().getName())) {
                    RecordedObject space = event.getValue("heapSpace");
                    heap.put(time, new long[] { event.getLong("heapUsed"), space.getLong("committedSize") });
                }
            }
        }
        if (start == null) return new Recording(null, null, List.of());
        var times = new TreeMap<Instant, Boolean>();
        cpu.keySet().forEach(t -> times.put(t, Boolean.TRUE));
        heap.keySet().forEach(t -> times.put(t, Boolean.TRUE));
        var samples = new ArrayList<Sample>();
        double latestCpu = 0;
        long[] latestHeap = { 0, 0 };
        for (Instant time : times.keySet()) {
            if (cpu.containsKey(time)) latestCpu = cpu.get(time);
            if (heap.containsKey(time)) latestHeap = heap.get(time);
            samples.add(new Sample(time, latestCpu, latestHeap[0], latestHeap[1]));
        }
        return new Recording(start, end, List.copyOf(samples));
    }
}
