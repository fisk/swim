package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.fisk.swim.testutil.SwimHomeFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorConfigIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinaryAppliesNormalModeRemapsFromConfig() throws Exception {
        Path file = tempDir.resolve("remap.txt");
        Files.writeString(file, "abc\n");
        var home = SwimHomeFixture.create(tempDir);
        Files.writeString(home.swimHome().resolve("config.json"), """
                {
                  "normalModeRemaps": [
                    {
                      "lhs": "Q",
                      "rhs": "x"
                    }
                  ]
                }
                """);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("abc", STARTUP_TIMEOUT);
            session.sendLiteral("Q");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("bc\n", Files.readString(file));
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryRestoresLastSessionWhenConfigured() throws Exception {
        Path first = tempDir.resolve("session-first.txt");
        Path secondFile = tempDir.resolve("session-second.txt");
        Files.writeString(first, "restore first\n");
        Files.writeString(secondFile, "restore second\n");
        var home = SwimHomeFixture.create(tempDir);
        Files.writeString(home.swimHome().resolve("config.json"), """
                {
                  "restoreLastSession": true
                }
                """);

        try (var firstSession = InstalledSwimDriver.startWithHome(home.home(), tempDir, first.getFileName().toString())) {
            firstSession.waitForText("restore first", STARTUP_TIMEOUT);
            firstSession.runCommand("vsplit");
            firstSession.runCommand("e " + secondFile.getFileName());
            firstSession.waitForText("restore second", UI_TIMEOUT);
            firstSession.runCommand("q");
            firstSession.waitForExit(Duration.ofSeconds(10));
        }

        try (var secondSession = InstalledSwimDriver.startWithHome(home.home(), tempDir)) {
            secondSession.waitForText("restore first", STARTUP_TIMEOUT);
            secondSession.waitForText("restore second", UI_TIMEOUT);
            secondSession.runCommand("q");
            secondSession.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(90)
    void installedLauncherBinaryReloadPreservesCurrentSessionWithoutConfigFlag() throws Exception {
        Path first = tempDir.resolve("reload-first.txt");
        Path secondFile = tempDir.resolve("reload-second.txt");
        Files.writeString(first, "reload first\n");
        Files.writeString(secondFile, "reload second\n");
        var home = SwimHomeFixture.create(tempDir);
        Files.writeString(home.swimHome().resolve("config.json"), "{}");

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, first.getFileName().toString())) {
            session.waitForText("reload first", STARTUP_TIMEOUT);
            session.runCommand("vsplit");
            session.runCommand("e " + secondFile.getFileName());
            session.waitForText("reload second", UI_TIMEOUT);

            session.runCommand("reload");
            session.waitForText("Reloaded SWIM core", Duration.ofSeconds(30));
            session.waitForText("reload first", UI_TIMEOUT);
            session.waitForText("reload second", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }
}
