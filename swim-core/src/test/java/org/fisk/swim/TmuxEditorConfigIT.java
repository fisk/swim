package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.fisk.swim.config.EditorConfigStore;
import org.fisk.swim.config.EditorPaths;
import org.fisk.swim.config.EditorSession;
import org.fisk.swim.config.SessionLayoutNode;
import org.fisk.swim.config.SessionWorkspace;
import org.fisk.swim.session.SwimServerSessions;
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
    @Timeout(45)
    void installedLauncherBinaryAppliesThemeColorsFromConfig() throws Exception {
        Path file = tempDir.resolve("theme.txt");
        Files.writeString(file, "theme test\n");
        var home = SwimHomeFixture.create(tempDir);
        Files.writeString(home.swimHome().resolve("config.json"), """
                {
                  "theme": {
                    "name": "tmux-theme-test",
                    "colors": {
                      "mode.normal": "#ff00ff",
                      "text.primary": "#00ff11",
                      "surface.background": "#010203"
                    }
                  }
                }
                """);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("theme test", STARTUP_TIMEOUT);
            session.waitForText("NORMAL", UI_TIMEOUT);
            session.waitForEscapedText(List.of("48;2;255;0;255", "48;5;201", "#ff00ff"), UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryKeepsCommandPromptVisibleWhenCompletionMenuIsOpen() throws Exception {
        Path file = tempDir.resolve("command-prompt.txt");
        Files.writeString(file, "command prompt\n");
        var home = SwimHomeFixture.create(tempDir);

        try (var session = InstalledSwimDriver.startWithHome(home.home(), tempDir, file.getFileName().toString())) {
            session.waitForText("command prompt", STARTUP_TIMEOUT);
            session.sendLiteral(":");
            session.sendLiteralKeyStrokes("help");
            session.waitForText(" command  :help", UI_TIMEOUT);
            session.sendEscape();
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryResizesDetachedSessionWhenReattachedFromLargerPane() throws Exception {
        Path file = tempDir.resolve("resize-attach.txt");
        Files.writeString(file, "attach resize\n");
        var home = SwimHomeFixture.create(tempDir);
        var environment = Map.of(SwimServerSessions.ENV_SESSION, "resize-" + Long.toUnsignedString(System.nanoTime(), 36));

        try (var small = InstalledSwimDriver.startWithHome(home.home(), tempDir, 80, 24, environment,
                file.getFileName().toString())) {
            small.waitForText("attach resize", STARTUP_TIMEOUT);
            small.runCommand("detach");
            small.waitForExit(Duration.ofSeconds(10));
        }

        try (var large = InstalledSwimDriver.startWithHome(home.home(), tempDir, 187, 51, environment)) {
            large.waitForText("attach resize", STARTUP_TIMEOUT);
            large.waitForText("0:resize-attach.txt", UI_TIMEOUT);
            org.junit.jupiter.api.Assertions.assertTrue(large.captureVisiblePane().contains("0:resize-attach.txt"));
            large.runCommand("q");
            large.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryOpensRequestedFileInsteadOfRestoringLastSession() throws Exception {
        Path first = tempDir.resolve("session-first.txt");
        Path secondFile = tempDir.resolve("session-second.txt");
        Path requested = tempDir.resolve("session-requested.txt");
        Files.writeString(first, "restore first\n");
        Files.writeString(secondFile, "restore second\n");
        Files.writeString(requested, "requested launch\n");
        var home = SwimHomeFixture.create(tempDir);
        Files.writeString(home.swimHome().resolve("config.json"), """
                {
                  "restoreLastSession": true
                }
                """);
        seedSplitSession(home, first, secondFile);

        try (var secondSession = InstalledSwimDriver.startWithHome(home.home(), tempDir, requested.getFileName().toString())) {
            secondSession.waitForText("requested launch", STARTUP_TIMEOUT);
            secondSession.runCommand("q");
            secondSession.waitForExit(Duration.ofSeconds(10));
        }

        try (var scratchSession = InstalledSwimDriver.startWithHome(home.home(), tempDir)) {
            scratchSession.waitForText("*scratch*", STARTUP_TIMEOUT);
            String pane = scratchSession.capturePane();
            assertFalse(pane.contains("restore first"), "Bare launch restored stale session:\n" + pane);
            assertFalse(pane.contains("restore second"), "Bare launch restored stale session:\n" + pane);
            scratchSession.runCommand("q");
            scratchSession.waitForExit(Duration.ofSeconds(10));
        }
    }

    private static void seedSplitSession(SwimHomeFixture home, Path first, Path second) {
        String firstPath = savedPath(first);
        String secondPath = savedPath(second);
        EditorConfigStore.saveSession(
                new EditorPaths(home.swimHome(),
                        home.swimHome().resolve("config.json"),
                        home.swimHome().resolve("session.json")),
                new EditorSession(
                        List.of(firstPath, secondPath),
                        secondPath,
                        List.of(new SessionWorkspace(
                                "BUFFER",
                                null,
                                secondPath,
                                new SessionLayoutNode(
                                        "VERTICAL",
                                        0.5,
                                        new SessionLayoutNode(null, 0.0, null, null, firstPath),
                                        new SessionLayoutNode(null, 0.0, null, null, secondPath),
                                        null))),
                        0));
    }

    private static String savedPath(Path path) {
        return path.toAbsolutePath().normalize().toString();
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
