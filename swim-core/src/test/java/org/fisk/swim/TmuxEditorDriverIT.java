package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.fisk.swim.testutil.TmuxSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorDriverIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanInsertUndoRedoAndSaveInTmux() throws Exception {
        Path file = tempDir.resolve("undo-redo.txt");
        Files.writeString(file, "alpha\n");

        try (var session = startEditor(tempDir, file.getFileName().toString())) {
            session.waitForText("alpha", STARTUP_TIMEOUT);

            session.sendLiteral("i");
            session.sendLiteral("X");
            session.sendEscape();
            session.waitForText("Xalpha", UI_TIMEOUT);

            session.sendLiteral("u");
            session.waitForText("alpha", UI_TIMEOUT);

            session.sendKey("C-r");
            session.waitForText("Xalpha", UI_TIMEOUT);

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("Xalpha\n", Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanCreateNewFileViaColonEInTmux() throws Exception {
        Path seed = tempDir.resolve("seed.txt");
        Path created = tempDir.resolve("created.txt");
        Files.writeString(seed, "seed\n");

        try (var session = startEditor(tempDir, seed.getFileName().toString())) {
            session.waitForText("seed", STARTUP_TIMEOUT);

            session.runCommand("e created.txt");
            session.sendLiteral("i");
            session.sendLiteral("hello from tmux");
            session.sendEscape();
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("hello from tmux", Files.readString(created));
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryCanSplitFocusAndEditTwoFilesInTmux() throws Exception {
        Path first = tempDir.resolve("first.txt");
        Path second = tempDir.resolve("second.txt");
        Files.writeString(first, "one\n");
        Files.writeString(second, "two\n");

        try (var session = startEditor(tempDir, first.getFileName().toString())) {
            session.waitForText("one", STARTUP_TIMEOUT);

            session.runCommand("split");
            session.runCommand("e second.txt");
            session.sendLiteral("i");
            session.sendLiteral("B");
            session.sendEscape();
            session.runCommand("w");

            session.runCommand("focus up");
            session.sendLiteral("i");
            session.sendLiteral("A");
            session.sendEscape();
            session.runCommand("w");

            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("Aone\n", Files.readString(first));
        assertEquals("Btwo\n", Files.readString(second));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanSearchRepeatAndEditMatchedLineInTmux() throws Exception {
        Path file = tempDir.resolve("search.txt");
        Files.writeString(file, """
                alpha
                needle one
                middle
                needle two
                omega
                """);

        try (var session = startEditor(tempDir, file.getFileName().toString())) {
            session.waitForText("needle one", STARTUP_TIMEOUT);

            session.sendLiteral("/");
            session.sendLiteral("needle");
            session.sendEnter();
            session.sendLiteral("n");
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                alpha
                needle one
                middle
                eedle two
                omega
                """, Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanCompleteHelpCommandAndFilterResultsInTmux() throws Exception {
        Path file = tempDir.resolve("help.txt");
        Files.writeString(file, "help buffer\n");

        try (var session = startEditor(tempDir, file.getFileName().toString())) {
            session.waitForText("help buffer", STARTUP_TIMEOUT);

            session.sendLiteral(":");
            session.sendLiteral("he");
            session.sendKey("Tab");
            session.sendEnter();
            session.waitForText("SWIM tutorial", UI_TIMEOUT);
            session.waitForText("Getting started", UI_TIMEOUT);

            session.sendLiteral("grep");
            session.waitForText(":grep <text> searches project contents", UI_TIMEOUT);

            session.sendEscape();
            session.waitForText("help buffer", UI_TIMEOUT);
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }
    }

    @Test
    @Timeout(60)
    void installedLauncherBinaryProjectSearchIgnoresGeneratedDirectoriesAndOpensResultInTmux() throws Exception {
        Path project = tempDir.resolve("project");
        Path current = project.resolve("src/current.txt");
        Path result = project.resolve("src/result.txt");
        Path generated = project.resolve("target/generated.txt");
        Path hidden = project.resolve(".hidden/hidden.txt");
        Files.createDirectories(current.getParent());
        Files.createDirectories(generated.getParent());
        Files.createDirectories(hidden.getParent());
        Files.createDirectories(project.resolve(".swim"));
        Files.writeString(project.resolve("pom.xml"), "<project />\n");
        Files.writeString(current, "current buffer\n");
        Files.writeString(result, """
                alpha
                needle lives here
                omega
                """);
        Files.writeString(generated, "needle should be ignored\n");
        Files.writeString(hidden, "needle should also be ignored\n");

        try (var session = startEditor(project, "src/current.txt")) {
            session.waitForText("current buffer", STARTUP_TIMEOUT);

            session.runCommand("grep needle");
            session.waitForText("project search", UI_TIMEOUT);
            session.waitForText("result.txt:2:1", UI_TIMEOUT);
            String pane = session.capturePane();
            assertTrue(!pane.contains("generated.txt"), "Project search should ignore target/.\nPane:\n" + pane);
            assertTrue(!pane.contains("hidden.txt"), "Project search should ignore hidden directories.\nPane:\n" + pane);

            session.sendEnter();
            session.waitForText("needle lives here", UI_TIMEOUT);
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                alpha
                eedle lives here
                omega
                """, Files.readString(result));
        assertEquals("needle should be ignored\n", Files.readString(generated));
        assertEquals("needle should also be ignored\n", Files.readString(hidden));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanUseFancyJumpHintsToTargetSecondVisibleWordInTmux() throws Exception {
        Path file = tempDir.resolve("fancy-jump.txt");
        Files.writeString(file, """
                apple
                apricot
                avocado
                """);

        try (var session = startEditor(tempDir, file.getFileName().toString())) {
            session.waitForText("avocado", STARTUP_TIMEOUT);

            session.sendLiteral("g");
            session.sendLiteral("w");
            session.sendLiteral("a");
            session.sendLiteral("b");
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                apple
                pricot
                avocado
                """, Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinaryCanUseFancyCharacterJumpWithoutPressingEnterInTmux() throws Exception {
        Path file = tempDir.resolve("fancy-char-jump.txt");
        Files.writeString(file, "papaya\n");

        try (var session = startEditor(tempDir, file.getFileName().toString())) {
            session.waitForText("papaya", STARTUP_TIMEOUT);

            session.sendLiteral("g");
            session.sendLiteral("c");
            session.sendLiteral("a");
            session.sendLiteral("b");
            session.sendLiteral("x");
            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("papya\n", Files.readString(file));
    }

    private TmuxSession startEditor(Path workdir, String fileArgument) throws Exception {
        return InstalledSwimDriver.start(tempDir, workdir, fileArgument);
    }
}
