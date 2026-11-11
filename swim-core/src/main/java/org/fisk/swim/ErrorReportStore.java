package org.fisk.swim;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.UUID;

/** Writes complete reports atomically, without logging failures back into the reporter. */
final class ErrorReportStore {
    private static final int MAX_SAVED_REPORTS = 50;
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC);
    private static final String REPORT_NAME = "error-\\d{8}-\\d{6}-\\d{3}-\\d+-[a-f0-9]{8}-\\d+\\.txt";
    private final Path directory;
    private final String session = ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);

    ErrorReportStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    Path pathFor(ErrorReports.Report report) {
        return directory.resolve("error-" + TIMESTAMP.format(report.time()) + "-" + session + "-" + report.id() + ".txt");
    }

    synchronized ErrorReports.Report save(ErrorReports.Report report) {
        Path path = pathFor(report);
        var saved = report.withStorage(path, null);
        try {
            try {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException unsupported) {
                Files.createDirectories(directory);
            }
            String text = saved.text();
            atomicWrite(path, text);
            atomicWrite(directory.resolve("latest.txt"), text);
            prune(path);
            return saved;
        } catch (IOException | SecurityException failure) {
            // A disk failure must not hide the original exception or cause recursive reports.
            return report.withStorage(null, ErrorReports.safeText(failure.getMessage()));
        }
    }

    private void atomicWrite(Path target, String text) throws IOException {
        Path temporary = Files.getFileAttributeView(directory, PosixFileAttributeView.class) == null
                ? Files.createTempFile(directory, ".error-", ".tmp")
                : Files.createTempFile(directory, ".error-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, text);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void prune(Path current) throws IOException {
        try (var paths = Files.list(directory)) {
            var oldest = paths.filter(path -> path.getFileName().toString().matches(REPORT_NAME))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !path.equals(current))
                    .sorted(Comparator.reverseOrder())
                    .skip(MAX_SAVED_REPORTS - 1).toList();
            for (Path path : oldest) {
                Files.deleteIfExists(path);
            }
        }
    }
}
