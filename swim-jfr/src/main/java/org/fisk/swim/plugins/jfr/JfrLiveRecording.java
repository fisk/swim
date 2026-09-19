package org.fisk.swim.plugins.jfrmetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** Owns the lightweight recording used by SWIM's live JFR metrics view. */
final class JfrLiveRecording {
    private static final String RECORDING_NAME = "SWIM live metrics";
    private static Recording ownedRecording;

    private JfrLiveRecording() {
    }

    /** Always use our bounded recording, without modifying recordings owned by other tools. */
    static synchronized Recording liveRecording() {
        if (ownedRecording == null || ownedRecording.getState() != RecordingState.RUNNING) {
            if (ownedRecording != null) ownedRecording.close();
            Recording recording = createRecording();
            try {
                recording.start();
                ownedRecording = recording;
            } catch (RuntimeException | Error failure) {
                recording.close();
                throw failure;
            }
        }
        return ownedRecording;
    }

    /** Creates a snapshot of SWIM's dedicated recording. */
    static synchronized Path snapshot() throws IOException {
        Recording recording = liveRecording();
        Path directory = Path.of(System.getProperty("user.home"), ".swim", "jfr");
        Files.createDirectories(directory);
        Path target = directory.resolve("swim-live-" + ProcessHandle.current().pid() + ".jfr");
        Files.deleteIfExists(target);
        recording.dump(target);
        return target;
    }

    /** Only our own recording is constrained; never change another tool's settings. */
    static Recording createRecording() {
        Recording recording = new Recording();
        recording.setName(RECORDING_NAME);
        recording.enable("jdk.CPULoad").withPeriod(Duration.ofSeconds(1));
        recording.enable("jdk.GCHeapSummary").withoutStackTrace();
        recording.enable("jdk.PhysicalMemory").withPeriod(Duration.ofSeconds(1));
        // Match the live panel's hour of history and prevent unbounded disk growth.
        recording.setMaxAge(Duration.ofHours(1));
        recording.setMaxSize(64L * 1024 * 1024);
        return recording;
    }

}
