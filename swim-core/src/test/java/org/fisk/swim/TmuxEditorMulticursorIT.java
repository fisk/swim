package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorMulticursorIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsMulticursorCommandAndInput() throws Exception {
        Path file = tempDir.resolve("multicursor.txt");
        Files.writeString(file, """
                alpha
                beta alpha
                alpha gamma
                """);

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("alpha gamma", STARTUP_TIMEOUT);

            session.runCommand("multicursor alpha");
            session.sendLiteral("i");
            session.sendLiteral("X");
            session.sendEscape();

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                Xalpha
                beta Xalpha
                Xalpha gamma
                """, Files.readString(file));
    }
}
