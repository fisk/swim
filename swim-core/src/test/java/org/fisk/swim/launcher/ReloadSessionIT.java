package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class ReloadSessionIT {
    @TempDir
    Path tempDir;

    @Test
    @Timeout(90)
    void reloadRestoresVisibleNemoOverlayAndRemainsInteractive() throws Exception {
        Path file = tempDir.resolve("overlay.txt");
        Files.writeString(file, "overlay sentinel\n");
        Path home = InstalledSwimDriver.createHome(tempDir);
        try (var session = InstalledSwimDriver.startWithHome(home, tempDir, file.toString())) {
            session.waitForText("Loaded SWIM core", Duration.ofSeconds(20));
            session.sendLiteral("!");
            session.runCommand("rename Restore test overlay");
            session.waitForText("Restore test overlay", Duration.ofSeconds(5));
            // Use the editor's global command prompt while the chat is visible.
            session.sendKey("C-b");
            session.runCommand("reload");
            session.waitForText("Reloaded SWIM core", Duration.ofSeconds(30));
            assertTrue(session.captureVisiblePane().contains("Restore test overlay"));
            session.sendEscape();
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
            assertEquals("verlay sentinel\n", Files.readString(file));
        }
    }

    @Test
    @Timeout(90)
    void reloadRestoresNemoWorkspaceAndRemainsInteractive() throws Exception {
        Path file = tempDir.resolve("reload.txt");
        Files.writeString(file, "reload sentinel\n");
        Path home = InstalledSwimDriver.createHome(tempDir);
        try (var session = InstalledSwimDriver.startWithHome(home, tempDir, file.toString())) {
            session.waitForText("Loaded SWIM core", Duration.ofSeconds(20));
            session.runCommand("nemo");
            session.runCommand("rename Restore test chat");
            session.waitForText("Restore test chat", Duration.ofSeconds(5));
            session.sendEscape();
            session.runCommand("reload");
            session.waitForText("Reloaded SWIM core", Duration.ofSeconds(30));
            assertTrue(session.captureVisiblePane().contains("Restore test chat"),
                    "Restored pane:\n" + session.captureVisiblePane() + "\nSession:\n"
                            + Files.readString(home.resolve(".swim/session.json")) + "\nChats:\n"
                            + Files.readString(home.resolve(".swim/nemo/sessions.json")));
            session.sendEscape();
            session.runCommand("q");
            session.waitForText("reload sentinel", Duration.ofSeconds(5));
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
            assertEquals("eload sentinel\n", Files.readString(file));
        }
    }
}
