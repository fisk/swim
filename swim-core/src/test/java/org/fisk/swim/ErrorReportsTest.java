package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class ErrorReportsTest {
    @Test
    void reportIncludesCauseSuppressedExceptionsAndEditorContext() {
        var reports = new ErrorReports();
        reports.setEditorContext("File: Example.java\nCursor: line 12, column 4");
        var failure = new IllegalStateException("save failed", new java.io.IOException("disk failed"));
        failure.addSuppressed(new IllegalArgumentException("cleanup failed"));
        reports.capture("Saving file", "worker", failure);

        String text = reports.takePending().getFirst().text();
        assertTrue(text.contains("IOException: disk failed"));
        assertTrue(text.contains("Suppressed:"));
        assertTrue(text.contains("cleanup failed"));
        assertTrue(text.contains("Example.java"));
        assertTrue(text.contains("line 12, column 4"));
        assertTrue(text.contains("Saving file"));
        assertTrue(text.contains("worker"));
        assertTrue(reports.takePending().isEmpty());
    }

    @Test
    void duplicateFailuresDoNotScheduleAnErrorRedrawLoop() {
        var reports = new ErrorReports();
        var failure = new IllegalStateException("drawing failed");
        reports.capture("Rendering", "ui", failure);
        reports.takePending();
        for (int i = 0; i < 100; i++) {
            reports.capture("Rendering", "ui", failure);
        }

        assertTrue(reports.takePending().isEmpty());
        assertEquals(1, reports.history().size());
        assertEquals(101, reports.history().getFirst().occurrences());
    }

    @Test
    void historyAndHugeTracesAreBoundedAndTerminalEscapesAreNeutralized() {
        var reports = new ErrorReports();
        for (int i = 0; i < 30; i++) {
            reports.capture("Action " + i, "ui", new RuntimeException("\u001b[2J" + "x".repeat(100_000)));
        }
        assertEquals(20, reports.history().size());
        String details = reports.history().getLast().details();
        assertTrue(details.length() < 66_000);
        assertFalse(details.contains("\u001b"));
        assertTrue(details.contains("[truncated]"));
    }

    @Test
    void cancellationIsNotPresentedAsABug() {
        var reports = new ErrorReports();
        reports.capture("Cancel", "ui", new RuntimeException(new InterruptedException()));
        reports.capture("Cancel", "ui", new CancellationException());
        assertTrue(reports.history().isEmpty());
    }
}
