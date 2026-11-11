package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fisk.swim.terminal.TerminalContextTestSupport;
import org.fisk.swim.ui.ErrorReportView;
import org.fisk.swim.ui.HeadlessWindowHarness;
import org.fisk.swim.ui.SplitView;
import org.fisk.swim.ui.View;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorReportWindowTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void clearReports() {
        ErrorReports.getInstance().clear();
    }

    @Test
    void errorOpensOnTheRightAndClosingItPreservesSourceAndFocus() throws Exception {
        TerminalContextTestSupport.install(120, 35);
        Path source = tempDir.resolve("Example.txt");
        Files.writeString(source, "source stays visible\n");
        try (var harness = HeadlessWindowHarness.create(source, 120, 35)) {
            var window = harness.getWindow();
            var buffer = window.getBufferContext().getBuffer();
            var sourceView = window.getBufferContext().getBufferView();
            window.update(true);
            ErrorReports.getInstance().capture("Reading Java symbols", "worker", new IllegalStateException("symbol failure"));
            window.update(true);

            var split = HeadlessWindowHarness.getField(window, "_workspaceView", SplitView.class);
            assertSame(sourceView, split.getFirstView());
            var report = assertInstanceOf(ErrorReportView.class, split.getSecondView());
            assertTrue(report.getBounds().getPoint().getX() > sourceView.getBounds().getPoint().getX());
            assertSame(sourceView, window.getActiveView());
            assertEquals("source stays visible\n", buffer.getString());
            assertTrue(ErrorReports.getInstance().history().getFirst().context().contains("Example.txt"));

            ErrorReports.getInstance().capture("Another operation", "worker", new IllegalArgumentException("other failure"));
            window.update(true);
            assertEquals(2, split.leafViews().size());
            assertSame(report, split.getSecondView());

            HeadlessWindowHarness.dispatch(report, HeadlessWindowHarness.key('q'));
            assertSame(sourceView, HeadlessWindowHarness.getField(window, "_workspaceView", View.class));
            assertSame(buffer, window.getBufferContext().getBuffer());
            assertTrue(window.showErrorReports());
        }
    }

    @Test
    void errorDetailsAreAbsentFromNemoObservationAndEditorControl() throws Exception {
        TerminalContextTestSupport.install(120, 35);
        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("private-error.txt"), 120, 35)) {
            var window = harness.getWindow();
            ErrorReports.getInstance().capture("Private integration", "worker", new IllegalStateException("private-report-token"));
            assertFalse(window.driveEditorInput(":errors<ENTER>", 30).accepted());
            assertFalse(HeadlessWindowHarness.getField(window, "_workspaceView") instanceof SplitView);
            assertTrue(window.showErrorReports());

            String snapshot = window.guestScreenSnapshot();
            assertTrue(snapshot.contains("blocked"));
            assertFalse(snapshot.contains("private-report-token"));
            var result = window.driveEditorInput("y", 5);
            assertFalse(result.accepted());
            assertFalse(result.toString().contains("private-report-token"));
        }
    }

    @Test
    void savedReportOpenedAsTextRemainsPrivateToTheHost() throws Exception {
        String previousDirectory = System.getProperty(ErrorReports.DIRECTORY_PROPERTY);
        Path directory = tempDir.resolve("reports");
        System.setProperty(ErrorReports.DIRECTORY_PROPERTY, directory.toString());
        TerminalContextTestSupport.install(120, 35);
        try {
            var reports = new ErrorReports(directory);
            reports.capture("Private integration", "worker", new RuntimeException("private-saved-report-token"));
            Path saved = reports.history().getFirst().savedPath();
            try (var harness = HeadlessWindowHarness.create(saved, 120, 35)) {
                var window = harness.getWindow();
                assertTrue(window.guestScreenSnapshot().contains("blocked"));
                assertFalse(window.guestScreenSnapshot().contains("private-saved-report-token"));
                assertFalse(window.driveEditorInput("y", 5).accepted());
            }
        } finally {
            if (previousDirectory == null) {
                System.clearProperty(ErrorReports.DIRECTORY_PROPERTY);
            } else {
                System.setProperty(ErrorReports.DIRECTORY_PROPERTY, previousDirectory);
            }
        }
    }
}
