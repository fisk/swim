package org.fisk.swim.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.fisk.swim.mail.MailClient;
import org.fisk.swim.mail.MailMessageDetail;
import org.fisk.swim.mail.MailSnapshot;
import org.fisk.swim.mail.MailThreadSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostGuestScreenControlTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        Window window = Window.getInstance();
        if (window != null) {
            window.dispose();
        }
    }

    @Test
    void guestSnapshotExcludesHostApprovalOverlayText() throws Exception {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();
            window.showHostApprovalOverlay(List.of(new HostApprovalOverlayView.Entry(
                    "approval-1",
                    "nemo-session",
                    "drive_editor",
                    "editor control",
                    "SECRET APPROVAL TEXT",
                    false)), ignored -> {
                    });

            String snapshot = window.guestScreenSnapshot();

            assertTrue(window.isShowingHostApprovalOverlay());
            assertTrue(snapshot.contains("alpha"));
            assertFalse(snapshot.contains("Host approval"));
            assertFalse(snapshot.contains("approval-1"));
            assertFalse(snapshot.contains("SECRET APPROVAL TEXT"));
        }
    }

    @Test
    void guestSnapshotBlocksMailWorkspace() throws Exception {
        Path file = tempDir.resolve("mail.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();
            assertTrue(window.showMailWorkspace(secretMailClient()));

            String snapshot = window.guestScreenSnapshot();

            assertTrue(snapshot.contains("mail is visible"));
            assertFalse(snapshot.contains("SECRET SUBJECT"));
            assertFalse(snapshot.contains("SECRET BODY"));
            assertFalse(snapshot.contains("sender@example.com"));
        }
    }

    @Test
    void driveEditorInputEditsActiveBuffer() throws Exception {
        Path file = tempDir.resolve("edit.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput("ihello <ESC>", 50);

            assertTrue(result.accepted(), result.message());
            assertTrue(window.getBufferContext().getBuffer().getString().startsWith("hello alpha"));
            assertTrue(result.afterSnapshot().contains("hello alpha"));
        }
    }

    @Test
    void driveEditorInputRunsSandboxedColonOpenAndSplitCommands() throws Exception {
        Path file = tempDir.resolve("first.txt");
        Path target = tempDir.resolve("src").resolve("Target.txt");
        Files.createDirectories(target.getParent());
        Files.writeString(file, "alpha\n");
        Files.writeString(target, "beta\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput(
                    ":vsplit<ENTER>:e src/Target.txt<ENTER>",
                    120,
                    tempDir,
                    true);

            assertTrue(result.accepted(), result.message());
            assertEquals(target.toAbsolutePath().normalize(), window.getBufferContext().getBuffer().getPath());
            assertTrue(result.afterSnapshot().contains("beta"));
        }
    }

    @Test
    void driveEditorInputSavesWorkspaceFileWithSandboxedColonWrite() throws Exception {
        Path file = tempDir.resolve("save.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput(
                    "ihello <ESC>:w<ENTER>",
                    50,
                    tempDir,
                    true);

            assertTrue(result.accepted(), result.message());
            assertEquals("hello alpha\n", Files.readString(file));
        }
    }

    @Test
    void driveEditorInputBlocksColonWriteOutsideWorkspace() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside.txt");
        Files.createDirectories(workspace);
        Files.writeString(outside, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(outside, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput(
                    "ihello <ESC>:w<ENTER>",
                    50,
                    workspace,
                    true);

            assertFalse(result.accepted());
            assertTrue(result.message().contains("save is only allowed inside the workspace"));
            assertEquals("alpha\n", Files.readString(outside));
        }
    }

    @Test
    void driveEditorInputBlocksUnsafeColonCommandsAtActionLayer() throws Exception {
        Path file = tempDir.resolve("blocked.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput(":shell<ENTER>", 50);

            assertFalse(result.accepted());
            assertTrue(result.message().contains("Editor control blocked :shell"));
            assertFalse(window.getCommandView().isActive());
        }
    }

    @Test
    void driveEditorInputBlocksMailKeyAtActionLayer() throws Exception {
        Path file = tempDir.resolve("mail-key.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput("e", 10);

            assertFalse(result.accepted());
            assertEquals(1, result.eventsProcessed());
            assertTrue(result.message().contains("mail workspace"));
            assertFalse(window.isShowingMailWorkspace());
        }
    }

    @Test
    void driveEditorInputBlocksMacrosAtActionLayer() throws Exception {
        Path file = tempDir.resolve("macro-key.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();

            Window.EditorDriveResult result = window.driveEditorInput("q a", 10);

            assertFalse(result.accepted());
            assertTrue(result.message().contains("macros are outside"));
            assertFalse(window.isRecordingMacro());
        }
    }

    @Test
    void driveEditorInputCannotControlHostOverlayButHostEscCanStopIt() throws Exception {
        Path file = tempDir.resolve("approval.txt");
        Files.writeString(file, "alpha\n");
        try (var harness = HeadlessWindowHarness.create(file, 80, 24)) {
            Window window = harness.getWindow();
            var decision = new AtomicReference<HostApprovalOverlayView.Decision>();
            window.showHostApprovalOverlay(List.of(new HostApprovalOverlayView.Entry(
                    "approval-1",
                    "nemo-session",
                    "drive_editor",
                    "editor control",
                    "send input",
                    false)), decision::set);

            Window.EditorDriveResult driveResult = window.driveEditorInput("<ENTER>", 10);
            assertFalse(driveResult.accepted());
            assertTrue(driveResult.message().contains("Host approval is waiting"));
            assertNull(decision.get());

            HeadlessWindowHarness.dispatch(window.getRootView(), HeadlessWindowHarness.escape());

            assertEquals("approval-1", decision.get().id());
            assertFalse(decision.get().approved());
        }
    }

    private MailClient secretMailClient() {
        return new MailClient() {
            @Override
            public MailSnapshot snapshot() {
                return new MailSnapshot(
                        List.of(),
                        List.of(new MailThreadSummary(1L, "work", "SECRET SUBJECT", "sender@example.com",
                                "SECRET SNIPPET", "2026-05-15T10:00:00Z", true, 1, List.of())),
                        "");
            }

            @Override
            public MailMessageDetail loadMessage(long threadId) {
                return new MailMessageDetail(1L, threadId, "SECRET SUBJECT", "sender@example.com",
                        "dest@example.com", "2026-05-15T10:00:00Z", "SECRET BODY", List.of());
            }

            @Override
            public void refresh() {
            }

            @Override
            public Path getDataPath() {
                return tempDir.resolve(".swim/email");
            }
        };
    }
}
