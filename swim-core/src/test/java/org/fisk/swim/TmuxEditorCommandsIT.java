package org.fisk.swim;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorCommandsIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsCommandAliasesAndFocusErrors() throws Exception {
        Path first = tempDir.resolve("cmd-first.txt");
        Path second = tempDir.resolve("cmd-second.txt");
        Files.writeString(first, "first\n");
        Files.writeString(second, "second\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, first.getFileName().toString())) {
            session.waitForText("first", STARTUP_TIMEOUT);

            session.runCommand("vs");
            session.runCommand("e cmd-second.txt");
            session.waitForText("second", UI_TIMEOUT);

            session.runCommand("focus h");
            session.waitForText("first", UI_TIMEOUT);

            session.runCommand("focus sideways");
            session.waitForText("Unknown focus target: sideways", UI_TIMEOUT);

            session.runCommand("focus next");
            session.waitForText("second", UI_TIMEOUT);

            session.runCommand("only");
            session.waitForText("second", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryShowsUnknownCommandNotice() throws Exception {
        Path file = tempDir.resolve("cmd-unknown.txt");
        Files.writeString(file, "unknown\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("unknown", STARTUP_TIMEOUT);
            session.runCommand("definitely-unknown");
            session.waitForText("Unknown command: definitely-unknown", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsCommandMenuSelectionWithArrowKeys() throws Exception {
        Path project = tempDir.resolve("cmd-menu-project");
        Path file = project.resolve("src/current.txt");
        Files.createDirectories(file.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(file, "menu buffer\n");

        try (var session = InstalledSwimDriver.start(tempDir, project, "src/current.txt")) {
            session.waitForText("menu buffer", STARTUP_TIMEOUT);

            session.sendLiteral(":");
            session.sendLiteral("g");
            session.waitForText("command matches", UI_TIMEOUT);
            session.waitForText(":git [status]", UI_TIMEOUT);
            session.waitForText(":grep <text>", UI_TIMEOUT);
            session.sendKey("Down");
            session.sendKey("Tab");
            session.sendEnter();
            session.waitForText("project search", UI_TIMEOUT);

            session.sendEscape();
            session.waitForText("menu buffer", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanOpenAndAutoRemoveShellWorkspace() throws Exception {
        Path file = tempDir.resolve("cmd-shell.txt");
        Files.writeString(file, "command shell\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("command shell", STARTUP_TIMEOUT);

            session.runCommand("shell");
            session.waitForText("shell input active", UI_TIMEOUT);
            session.waitForText("1:Shell", UI_TIMEOUT);

            session.sendLiteral("exit");
            session.sendEnter();
            session.waitForText("command shell", UI_TIMEOUT);
            String pane = session.capturePane();
            org.junit.jupiter.api.Assertions.assertTrue(!pane.contains("1:Shell"),
                    "Exited shell workspace should be removed from window history.\nPane:\n" + pane);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

}
