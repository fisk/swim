package org.fisk.swim;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Bounded bug reports with optional disk persistence. No buffer content is collected. */
public final class ErrorReports {
    public static final String DIRECTORY_PROPERTY = "swim.error.dir";
    private static final ErrorReports INSTANCE = new ErrorReports();
    private static final int MAX_REPORTS = 20;
    private static final int MAX_TEXT = 64 * 1024;
    private final List<Report> reports = new ArrayList<>();
    private volatile String editorContext = "Editor context: starting up";
    private boolean pending;
    private long nextId;
    private ErrorReportStore store;

    public ErrorReports() {
    }

    ErrorReports(Path directory) {
        store = new ErrorReportStore(directory);
    }

    public record Report(long id, Instant time, String summary, String operation, String thread,
            String context, String details, int occurrences, String fingerprint, Path savedPath, String saveFailure) {
        public String text() {
            return "SWIM ERROR REPORT #" + id + "\n\n"
                    + summary + "\n\n"
                    + "Time: " + time + "\n"
                    + "Operation: " + operation + "\n"
                    + "Thread: " + thread + "\n"
                    + "Occurrences: " + occurrences + "\n"
                    + context + "\n"
                    + "Swim: " + swimVersion() + "\n"
                    + "Java: " + System.getProperty("java.runtime.version") + "\n"
                    + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + "\n"
                    + "Log: " + System.getProperty("swim.log.path", "unavailable") + "\n"
                    + storageDescription() + "\n\n"
                    + "Details\n-------\n" + details;
        }

        Report withStorage(Path path, String failure) {
            return new Report(id, time, summary, operation, thread, context, details, occurrences, fingerprint, path, failure);
        }

        private String storageDescription() {
            if (saveFailure != null) {
                return "Saving report failed: " + saveFailure;
            }
            return savedPath == null ? "Saved report: not enabled"
                    : "Saved report: " + savedPath + "\nLatest report: " + savedPath.getParent().resolve("latest.txt");
        }

        private static String swimVersion() {
            var descriptor = Swim.class.getModule().getDescriptor();
            return descriptor == null ? "development" : descriptor.rawVersion().orElse("development");
        }
    }

    public static ErrorReports getInstance() {
        return INSTANCE;
    }

    public static Path reportDirectory() {
        String configured = System.getProperty(DIRECTORY_PROPERTY, "").trim();
        return (configured.isEmpty() ? Path.of(System.getProperty("user.home"), ".swim", "error-reports")
                : Path.of(configured)).toAbsolutePath().normalize();
    }

    public static boolean isSavedReportPath(Path path) {
        if (path == null) {
            return false;
        }
        Path directory = reportDirectory();
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.startsWith(directory)) {
            return true;
        }
        try {
            return normalized.toRealPath().startsWith(directory.toRealPath());
        } catch (java.io.IOException ignored) {
            return false;
        }
    }

    synchronized void enablePersistence() {
        if (store == null) {
            store = new ErrorReportStore(reportDirectory());
            for (int i = 0; i < reports.size(); i++) {
                reports.set(i, store.save(reports.get(i)));
            }
        }
    }

    public void setEditorContext(String context) {
        editorContext = context;
    }

    public void capture(String operation, String thread, Throwable failure) {
        if (failure == null || isCancellation(failure)) {
            return;
        }
        var output = new BoundedWriter();
        failure.printStackTrace(new PrintWriter(output));
        Throwable root = failure;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }
        var frames = root.getStackTrace();
        add(root.toString(), operation, thread, output.toString(),
                safeText(operation) + "\n" + safeText(root.toString()) + "\n" + (frames.length == 0 ? "" : frames[0]));
    }

    public void captureText(String operation, String thread, String text) {
        String firstLine = text.lines().filter(line -> !line.isBlank()).findFirst().orElse("Unexpected error");
        add(firstLine, operation, thread, text, safeText(operation) + "\n" + safeText(text));
    }

    private void add(String summary, String operation, String thread, String details, String fingerprint) {
        boolean wake;
        synchronized (this) {
            String safeDetails = safeText(details);
            // Render failures can recur on every redraw. Updating one report without
            // scheduling another redraw prevents the reporter from feeding that loop.
            for (int i = reports.size() - 1; i >= 0; i--) {
                Report previous = reports.get(i);
                if (previous.fingerprint().equals(fingerprint)) {
                    var repeated = new Report(previous.id(), previous.time(), previous.summary(), previous.operation(),
                            previous.thread(), previous.context(), previous.details(), previous.occurrences() + 1, fingerprint, null, null);
                    reports.set(i, persist(repeated));
                    return;
                }
            }
            reports.add(persist(new Report(++nextId, Instant.now(), safeText(summary), safeText(operation), safeText(thread),
                    safeText(editorContext), safeDetails, 1, fingerprint, null, null)));
            if (reports.size() > MAX_REPORTS) {
                reports.removeFirst();
            }
            wake = !pending;
            pending = true;
        }
        if (wake) {
            EventThread.wakeForErrorReport();
        }
    }

    private Report persist(Report report) {
        return store == null ? report : store.save(report);
    }

    public synchronized List<Report> takePending() {
        if (!pending) {
            return List.of();
        }
        pending = false;
        return List.copyOf(reports);
    }

    public synchronized List<Report> history() {
        return List.copyOf(reports);
    }

    synchronized void clear() {
        reports.clear();
        pending = false;
        editorContext = "Editor context: starting up";
        store = null;
    }

    private static boolean isCancellation(Throwable failure) {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException || cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /** Keep terminal control sequences in exception messages from becoming UI instructions. */
    static String safeText(String value) {
        if (value == null) {
            return "unavailable";
        }
        String limited = value.length() > MAX_TEXT ? value.substring(0, MAX_TEXT) + "\n[truncated]" : value;
        var safe = new StringBuilder();
        limited.codePoints().forEach(c -> {
            if (c == '\n' || c == '\t' || !Character.isISOControl(c)) {
                safe.appendCodePoint(c);
            } else if (c != '\r') {
                safe.append(String.format("\\u%04x", c));
            }
        });
        return safe.toString();
    }

    private static final class BoundedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        @Override
        public void write(char[] chars, int offset, int length) {
            int accepted = Math.min(length, MAX_TEXT - text.length());
            text.append(chars, offset, accepted);
            truncated |= accepted < length;
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return text + (truncated ? "\n[truncated]" : "");
        }
    }
}
