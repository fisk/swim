package org.fisk.swim.testutil;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TmuxSessionIT {
    @TempDir
    Path tempDir;

    @Test
    void cleanupOnlyClosesOwnedSessionAndRetainsExitedPaneForDiagnostics() throws Exception {
        InstalledSwimDriver.assumeTmuxAvailable();
        try (var survivor = TmuxSession.start(tempDir, Map.of(), "/bin/sh", "-c",
                "echo survivor; read answer; echo survivor-finished")) {
            survivor.waitForText("survivor", Duration.ofSeconds(3));
            try (var exited = TmuxSession.start(tempDir, Map.of(), "/bin/sh", "-c", "echo exited")) {
                exited.waitForExit(Duration.ofSeconds(3));
                exited.waitForText("exited", Duration.ofSeconds(3));
            }
            assertTrue(survivor.captureVisiblePane().contains("survivor"));
            survivor.sendEnter();
            survivor.waitForText("survivor-finished", Duration.ofSeconds(3));
            survivor.waitForExit(Duration.ofSeconds(3));
        }
    }
}
