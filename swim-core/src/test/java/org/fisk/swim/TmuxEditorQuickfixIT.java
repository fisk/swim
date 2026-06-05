package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorQuickfixIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsQuickfixAndLocationListCommands() throws Exception {
        Path project = tempDir.resolve("quickfix-project");
        Path current = project.resolve("src/current.txt");
        Path target = project.resolve("src/target.txt");
        Files.createDirectories(current.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(current, """
                alpha needle
                beta
                gamma needle
                """);
        Files.writeString(target, """
                target
                project needle
                """);

        try (var session = InstalledSwimDriver.start(tempDir, project, "src/current.txt")) {
            session.waitForText("alpha needle", STARTUP_TIMEOUT);

            session.runCommand("grep needle");
            session.waitForText("project search", UI_TIMEOUT);
            session.waitForText("target.txt:2:9", UI_TIMEOUT);
            session.sendEnter();
            session.runCommand("cnext");
            session.waitForText("gamma needle", UI_TIMEOUT);

            session.runCommand("e src/current.txt");
            session.waitForText("gamma needle", UI_TIMEOUT);
            session.runCommand("lgrep needle");
            session.waitForText("Location", UI_TIMEOUT);
            session.sendEnter();
            session.runCommand("lnext");
            session.waitForText("gamma needle", UI_TIMEOUT);
            String pane = session.capturePane();
            assertTrue(pane.contains("gamma needle"));

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }
}
