package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorPanelsIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanOpenProjectFileListFilterAndOpenMatch() throws Exception {
        Path project = tempDir.resolve("files-project");
        Path current = project.resolve("src/current.txt");
        Path target = project.resolve("src/other.txt");
        Files.createDirectories(current.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(current, "current file\n");
        Files.writeString(target, "opened from file list\n");

        try (var session = InstalledSwimDriver.start(tempDir, project, "src/current.txt")) {
            session.waitForText("current file", STARTUP_TIMEOUT);

            session.sendLiteral("m");
            session.waitForText("Project Files", UI_TIMEOUT);
            session.sendLiteral("other");
            session.waitForText("other.txt", UI_TIMEOUT);
            session.sendEnter();
            session.waitForText("opened from file list", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanOpenProjectSearchPanelViaNormalModeBinding() throws Exception {
        Path project = tempDir.resolve("search-project");
        Path current = project.resolve("src/current.txt");
        Path target = project.resolve("src/target.txt");
        Files.createDirectories(current.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(current, "current file\n");
        Files.writeString(target, """
                alpha
                panel needle target
                omega
                """);

        try (var session = InstalledSwimDriver.start(tempDir, project, "src/current.txt")) {
            session.waitForText("current file", STARTUP_TIMEOUT);

            session.sendLiteral("M");
            session.waitForText("project search", UI_TIMEOUT);
            session.sendLiteral("needle");
            session.waitForText("target.txt:2:7", UI_TIMEOUT);
            session.sendEnter();
            session.waitForText("panel needle target", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanRunCommandsInsideShellPanel() throws Exception {
        Path file = tempDir.resolve("shell.txt");
        Files.writeString(file, "shell buffer\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("shell buffer", STARTUP_TIMEOUT);

            session.sendLiteral(">");
            session.waitForText("shell input active", UI_TIMEOUT);
            session.sendLiteral("printf 'shell works\\n'");
            session.sendEnter();
            session.waitForText("shell works", UI_TIMEOUT);
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryShellPanelHandlesAnsiColourAndCarriageReturnOutput() throws Exception {
        Path file = tempDir.resolve("shell-ansi.txt");
        Files.writeString(file, "shell ansi\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, java.util.Map.of("SHELL", "/bin/sh"),
                file.getFileName().toString())) {
            session.waitForText("shell ansi", STARTUP_TIMEOUT);

            session.sendLiteral(">");
            session.waitForText("shell input active", UI_TIMEOUT);
            session.sendLiteral("printf 'red\\nabc\\rXY\\n'");
            session.sendEnter();
            session.waitForText("red", UI_TIMEOUT);
            session.waitForText("XYc", UI_TIMEOUT);
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanOpenMailPanelWithDefaultLocalConfig() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-email-0.0.1-SNAPSHOT.jar");

        Path file = tempDir.resolve("mail.txt");
        Files.writeString(file, "mail buffer\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("mail buffer", STARTUP_TIMEOUT);

            session.sendLiteral("e");
            session.waitForText("Mail", UI_TIMEOUT);
            session.waitForText("No accounts configured", UI_TIMEOUT);
            session.sendLiteral("q");
            session.waitForText("mail buffer", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanNavigateTreeViewAndOpenAnotherFile() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-tree-view-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("tree-project");
        Path current = project.resolve("src/aaa-current.txt");
        Path target = project.resolve("src/zzz-target.txt");
        Files.createDirectories(current.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(current, "current tree file\n");
        Files.writeString(target, "opened by tree view\n");

        try (var session = InstalledSwimDriver.start(tempDir, project, "src/aaa-current.txt")) {
            session.waitForText("current tree file", STARTUP_TIMEOUT);

            session.sendLiteral("t");
            session.waitForText("Tree", UI_TIMEOUT);
            session.sendLiteral("j");
            session.sendEnter();
            session.waitForText("opened by tree view", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryTreeViewCanRefreshToShowNewFile() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-tree-view-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("tree-refresh-project");
        Path current = project.resolve("src/current.txt");
        Path later = project.resolve("src/later.txt");
        Files.createDirectories(current.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(current, "tree refresh\n");

        try (var session = InstalledSwimDriver.start(tempDir, project, "src/current.txt")) {
            session.waitForText("tree refresh", STARTUP_TIMEOUT);

            session.sendLiteral("t");
            session.waitForText("Tree", UI_TIMEOUT);

            Files.writeString(later, "appeared later\n");
            session.sendLiteral("r");
            session.waitForText("later.txt", UI_TIMEOUT);
            session.sendLiteral("j");
            session.sendEnter();
            session.waitForText("appeared later", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryTreeViewSupportsCollapseAndExpandAroundSelectedFile() throws Exception {
        InstalledSwimDriver.assumePluginAvailable("swim-tree-view-0.0.1-SNAPSHOT.jar");

        Path project = tempDir.resolve("tree-collapse-project");
        Path current = project.resolve("current.txt");
        Path nested = project.resolve("nested/inner.txt");
        Files.createDirectories(nested.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(current, "tree nested\n");
        Files.writeString(nested, "inner file\n");

        try (var session = InstalledSwimDriver.start(tempDir, project, "current.txt")) {
            session.waitForText("tree nested", STARTUP_TIMEOUT);

            session.sendLiteral("t");
            session.waitForText("Tree:", UI_TIMEOUT);
            session.sendLiteral("k");
            session.waitForText("nested", UI_TIMEOUT);

            session.sendLiteral("l");
            session.waitForText("inner.txt", UI_TIMEOUT);

            session.sendLiteral("h");
            Thread.sleep(300);
            String collapsed = session.capturePane();
            assertTrue(!collapsed.contains("inner.txt"), "Collapsed tree should hide nested child.\nPane:\n" + collapsed);
            assertTrue(collapsed.contains("nested"), "Collapsed tree should keep parent node visible.\nPane:\n" + collapsed);
            session.sendLiteral("q");
            session.waitForText("tree nested", UI_TIMEOUT);

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }
}
