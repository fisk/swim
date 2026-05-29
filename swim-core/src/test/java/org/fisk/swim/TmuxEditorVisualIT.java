package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorVisualIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsVisualYankAndPaste() throws Exception {
        Path file = tempDir.resolve("visual-yank.txt");
        Files.writeString(file, "abcdef\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("abcdef", STARTUP_TIMEOUT);

            session.sendLiteral("v");
            session.sendLiteral("l");
            session.sendLiteral("l");
            session.sendLiteral("y");
            session.sendLiteral("p");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("abcabcdef\n", Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsVisualChangeIntoInsertMode() throws Exception {
        Path file = tempDir.resolve("visual-change.txt");
        Files.writeString(file, "abcdef\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("abcdef", STARTUP_TIMEOUT);

            session.sendLiteral("v");
            session.sendLiteral("l");
            session.sendLiteral("l");
            session.sendLiteral("c");
            session.sendLiteral("XYZ");
            session.sendEscape();

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("XYZdef\n", Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsVisualAnchorSwapBeforeDelete() throws Exception {
        Path file = tempDir.resolve("visual-swap.txt");
        Files.writeString(file, "abcdef\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("abcdef", STARTUP_TIMEOUT);

            session.sendLiteral("v");
            session.sendLiteral("l");
            session.sendLiteral("l");
            session.sendLiteral("o");
            session.sendLiteral("l");
            session.sendLiteral("d");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("adef\n", Files.readString(file));
    }
}
