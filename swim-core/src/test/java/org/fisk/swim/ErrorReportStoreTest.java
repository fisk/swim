package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorReportStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesReadableReportAndStableLatestCopyWithPrivatePermissions() throws Exception {
        Path directory = tempDir.resolve("reports");
        var reports = new ErrorReports(directory);
        reports.setEditorContext("File: Example.java\nCursor: line 12, column 4");
        reports.capture("Reading symbols", "worker", new IllegalStateException("symbol lookup failed"));

        var report = reports.history().getFirst();
        assertNull(report.saveFailure());
        assertNotNull(report.savedPath());
        assertEquals(report.text(), Files.readString(report.savedPath()));
        assertEquals(report.text(), Files.readString(directory.resolve("latest.txt")));
        assertTrue(report.text().contains("Saved report: " + report.savedPath()));
        assertTrue(report.text().contains("Example.java"));
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(report.savedPath()));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(directory.resolve("latest.txt")));
        }
    }

    @Test
    void reportsSurviveMemoryResetAndSessionIdsDoNotOverwriteHistory() throws Exception {
        var firstSession = new ErrorReports(tempDir);
        firstSession.capture("First session", "ui", new RuntimeException("first error"));
        Path firstPath = firstSession.history().getFirst().savedPath();
        firstSession.clear();
        assertTrue(Files.readString(firstPath).contains("first error"));

        var secondSession = new ErrorReports(tempDir);
        secondSession.capture("Second session", "ui", new RuntimeException("second error"));
        Path secondPath = secondSession.history().getFirst().savedPath();
        assertNotEquals(firstPath, secondPath);
        assertTrue(Files.isRegularFile(firstPath));
        assertEquals(Files.readString(secondPath), Files.readString(tempDir.resolve("latest.txt")));
    }

    @Test
    void recurringOlderErrorBecomesLatestAndUpdatesItsOccurrenceCount() throws Exception {
        var reports = new ErrorReports(tempDir);
        var recurring = new IllegalStateException("recurring error");
        reports.capture("Render", "ui", recurring);
        Path originalPath = reports.history().getFirst().savedPath();
        reports.capture("Save", "ui", new RuntimeException("different error"));
        reports.takePending();
        reports.capture("Render", "ui", recurring);

        assertEquals(originalPath, reports.history().getFirst().savedPath());
        String latest = Files.readString(tempDir.resolve("latest.txt"));
        assertTrue(latest.contains("recurring error"));
        assertTrue(latest.contains("Occurrences: 2"));
        assertEquals(latest, Files.readString(originalPath));
        assertTrue(reports.takePending().isEmpty());
    }

    @Test
    void retainsFiftyReportsAndLeavesOtherFilesAlone() throws Exception {
        Path note = tempDir.resolve("keep.txt");
        Files.writeString(note, "keep this file");
        var reports = new ErrorReports(tempDir);
        for (int i = 0; i < 55; i++) {
            reports.capture("Action " + i, "ui", new RuntimeException("error " + i));
        }
        try (var files = Files.list(tempDir)) {
            assertEquals(50, files.filter(path -> path.getFileName().toString().startsWith("error-")).count());
        }
        assertEquals("keep this file", Files.readString(note));
        assertTrue(Files.readString(tempDir.resolve("latest.txt")).contains("error 54"));
        assertTrue(Files.isRegularFile(reports.history().getLast().savedPath()));
    }

    @Test
    void diskFailureLeavesOriginalReportAvailableWithoutRecursiveErrors() throws Exception {
        Path notDirectory = tempDir.resolve("file.txt");
        Files.writeString(notDirectory, "original file");
        var reports = new ErrorReports(notDirectory);
        assertDoesNotThrow(() -> reports.capture("Render", "ui", new IllegalStateException("original failure")));

        var report = reports.history().getFirst();
        assertTrue(report.text().contains("original failure"));
        assertTrue(report.text().contains("Saving report failed:"));
        assertNull(report.savedPath());
        assertNotNull(report.saveFailure());
        assertEquals(1, reports.history().size());
        assertEquals("original file", Files.readString(notDirectory));
    }

    @Test
    void replacingLatestSymlinkDoesNotOverwriteItsTarget() throws Exception {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "keep outside content");
        Path directory = Files.createDirectory(tempDir.resolve("reports"));
        Files.createSymbolicLink(directory.resolve("latest.txt"), outside);
        var reports = new ErrorReports(directory);
        reports.capture("Action", "ui", new RuntimeException("fresh failure"));

        assertEquals("keep outside content", Files.readString(outside));
        assertFalse(Files.isSymbolicLink(directory.resolve("latest.txt")));
        assertTrue(Files.readString(directory.resolve("latest.txt")).contains("fresh failure"));
    }
}
