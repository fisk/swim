package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.fisk.swim.ErrorReports;
import org.fisk.swim.copy.Copy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ErrorReportViewTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void restoreClipboard() {
        ExternalResourceSupport.resetForTesting();
    }

    @Test
    void copyAndHistoryNavigationKeepPrivateTextOutOfEditorRegisters() throws Exception {
        var reports = new ErrorReports();
        reports.capture("First action", "ui", new IllegalStateException("first failure"));
        reports.capture("Second action", "ui", new IllegalStateException("second failure"));
        var clipboard = new AtomicReference<String>();
        ExternalResourceSupport.setTextCopierForTesting(text -> {
            clipboard.set(text);
            return true;
        });
        Copy.getInstance().setText("existing yank", false);
        try (var harness = HeadlessWindowHarness.create(tempDir.resolve("copy-report.txt"), 120, 30)) {
            var view = new ErrorReportView(reports.history());
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key('y'));
            assertTrue(clipboard.get().contains("second failure"));
            assertEquals("existing yank", Copy.getInstance().getText());

            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key('['));
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key('y'));
            assertTrue(clipboard.get().contains("first failure"));
            HeadlessWindowHarness.dispatch(view, HeadlessWindowHarness.key(']'));
            assertTrue(view.reportText().contains("second failure"));
        }
    }
}
