package org.fisk.swim.plugins.jfrmetrics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.TreeMap;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JfrDataPerformanceTest {
    @TempDir Path directory;

    @Test
    void interruptedLoadPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> JfrMetrics.read(directory.resolve("does-not-exist.jfr")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void ownedRecordingOnlyEnablesMetricsAndBoundsRetention() {
        try (Recording recording = JfrLiveRecording.createRecording()) {
            assertEquals(Duration.ofHours(1), recording.getMaxAge());
            assertEquals(64L * 1024 * 1024, recording.getMaxSize());
            assertEquals("true", recording.getSettings().get("jdk.CPULoad#enabled"));
            assertEquals("true", recording.getSettings().get("jdk.GCHeapSummary#enabled"));
            assertTrue(recording.getSettings().keySet().stream().allMatch(key ->
                    key.startsWith("jdk.CPULoad#") || key.startsWith("jdk.GCHeapSummary#")
                            || key.startsWith("jdk.PhysicalMemory#")));
        }
    }

    @Test
    void liveRecordingNeverReusesOrChangesExternalRecording() {
        try (Recording external = new Recording()) {
            external.setName("SWIM live metrics");
            external.enable("jdk.ThreadSleep");
            external.start();
            var settings = external.getSettings();
            try (Recording live = JfrLiveRecording.liveRecording()) {
                assertNotSame(external, live);
                assertSame(live, JfrLiveRecording.liveRecording());
                assertEquals(settings, external.getSettings());
                assertEquals(jdk.jfr.RecordingState.RUNNING, external.getState());
                assertEquals(Duration.ofHours(1), live.getMaxAge());
                assertEquals("true", live.getSettings().get("jdk.PhysicalMemory#enabled"));
            }
            try (Recording restarted = JfrLiveRecording.liveRecording()) {
                assertEquals(jdk.jfr.RecordingState.RUNNING, restarted.getState());
                assertNotSame(external, restarted);
            }
        }
    }

    @Test
    void mergedIndexMatchesIndependentMetricTimelines() throws Exception {
        Path path = directory.resolve("metrics.jfr");
        try (Recording recording = JfrLiveRecording.createRecording()) {
            recording.start();
            System.gc();
            recording.stop();
            recording.dump(path);
        }
        var cpu = new TreeMap<Instant, Double>();
        var heap = new TreeMap<Instant, long[]>();
        var memory = new TreeMap<Instant, long[]>();
        var systemCpu = new TreeMap<Instant, Double>();
        var times = new TreeMap<Instant, Boolean>();
        Instant start = null, end = null;
        try (RecordingFile file = new RecordingFile(path)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                Instant time = event.getEndTime();
                if (start == null || event.getStartTime().isBefore(start)) start = event.getStartTime();
                if (end == null || time.isAfter(end)) end = time;
                if (event.getEventType().getName().equals("jdk.CPULoad")) {
                    systemCpu.put(time, 100 * event.getDouble("machineTotal"));
                    cpu.put(time, 100 * (event.getDouble("jvmUser") + event.getDouble("jvmSystem")));
                    times.put(time, true);
                } else if (event.getEventType().getName().equals("jdk.PhysicalMemory")) {
                    memory.put(time, new long[] { event.getLong("usedSize"), event.getLong("totalSize") });
                    times.put(time, true);
                } else if (event.getEventType().getName().equals("jdk.GCHeapSummary")) {
                    RecordedObject space = event.getValue("heapSpace");
                    heap.put(time, new long[] {event.getLong("heapUsed"), space.getLong("committedSize")});
                    times.put(time, true);
                }
            }
        }
        var actual = JfrMetrics.read(path);
        assertEquals(start, actual.start());
        assertEquals(end, actual.end());
        assertEquals(times.size(), actual.samples().size());
        for (var sample : actual.samples()) {
            var c = cpu.floorEntry(sample.time());
            var h = heap.floorEntry(sample.time());
            var m = memory.floorEntry(sample.time());
            var sc = systemCpu.floorEntry(sample.time());
            assertEquals(sc == null ? Double.NaN : sc.getValue(), sample.systemCpuPercent());
            assertEquals(m == null ? -1 : m.getValue()[0], sample.systemMemoryUsed());
            assertEquals(m == null ? -1 : m.getValue()[1], sample.systemMemoryTotal());
            assertEquals(c == null ? Double.NaN : c.getValue(), sample.cpuPercent());
            assertEquals(h == null ? -1 : h.getValue()[0], sample.heapUsed());
            assertEquals(h == null ? -1 : h.getValue()[1], sample.heapCommitted());
        }
    }
}
