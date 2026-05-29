package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorPaneBindingsIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsCtrlWSplitAndFocusBindings() throws Exception {
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        Files.writeString(first, "one\n");
        Files.writeString(second, "two\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, first.getFileName().toString())) {
            session.waitForText("one", STARTUP_TIMEOUT);

            session.sendKey("C-w");
            session.sendLiteral("s");
            session.runCommand("e second.txt");
            session.sendLiteral("i");
            session.sendLiteral("B");
            session.sendEscape();
            session.runCommand("w");

            session.sendKey("C-w");
            session.sendLiteral("k");
            session.sendLiteral("i");
            session.sendLiteral("A");
            session.sendEscape();
            session.runCommand("w");

            session.sendKey("C-w");
            session.sendLiteral("j");
            session.waitForText("Btwo", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("Aone\n", Files.readString(first));
        assertEquals("Btwo\n", Files.readString(second));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsCtrlWCloseAndOnlyBindings() throws Exception {
        Path first = tempDir.resolve("close-only.txt");
        Path second = tempDir.resolve("close-target.txt");
        Files.writeString(first, "left\n");
        Files.writeString(second, "right\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, first.getFileName().toString())) {
            session.waitForText("left", STARTUP_TIMEOUT);

            session.sendKey("C-w");
            session.sendLiteral("v");
            session.runCommand("e close-target.txt");
            session.waitForText("right", UI_TIMEOUT);

            session.sendKey("C-w");
            session.sendLiteral("h");
            session.waitForText("left", UI_TIMEOUT);

            session.sendKey("C-w");
            session.sendLiteral("o");
            session.sendLiteral("i");
            session.sendLiteral("X");
            session.sendEscape();
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("Xleft\n", Files.readString(first));
        assertEquals("right\n", Files.readString(second));
    }
}
