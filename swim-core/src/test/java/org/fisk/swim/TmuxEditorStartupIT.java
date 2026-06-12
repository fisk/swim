package org.fisk.swim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorStartupIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinaryStartsWithPackagedStartupPlugins() throws Exception {
        Path file = tempDir.resolve("startup-smoke.txt");
        Files.writeString(file, "startup smoke\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("startup smoke", STARTUP_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }
}
