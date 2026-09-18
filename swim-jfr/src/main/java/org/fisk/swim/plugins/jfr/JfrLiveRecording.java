package org.fisk.swim.plugins.jfrmetrics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import jdk.jfr.Configuration;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;

/** Owns the lightweight recording used by SWIM's live JFR metrics view. */
final class JfrLiveRecording {
    private static final String RECORDING_NAME = "SWIM live metrics";

    private JfrLiveRecording() {
    }

    /** Creates a snapshot of an existing recording, or starts SWIM's recording when none is running. */
    static synchronized Path snapshot() throws IOException {
        Recording recording = FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(candidate -> candidate.getState() == RecordingState.RUNNING)
                .filter(candidate -> RECORDING_NAME.equals(candidate.getName()))
                .findFirst()
                .orElseGet(JfrLiveRecording::anyRunningRecording);
        if (recording == null) {
            try {
                recording = new Recording(Configuration.getConfiguration("default"));
            } catch (ParseException e) {
                throw new IOException("Unable to load the default JFR configuration", e);
            }
            recording.setName(RECORDING_NAME);
            recording.start();
        }
        Path directory = Path.of(System.getProperty("user.home"), ".swim", "jfr");
        Files.createDirectories(directory);
        Path target = directory.resolve("swim-live-" + ProcessHandle.current().pid() + ".jfr");
        Files.deleteIfExists(target);
        recording.dump(target);
        return target;
    }

    private static Recording anyRunningRecording() {
        return FlightRecorder.getFlightRecorder().getRecordings().stream()
                .filter(candidate -> candidate.getState() == RecordingState.RUNNING)
                .findFirst()
                .orElse(null);
    }
}
