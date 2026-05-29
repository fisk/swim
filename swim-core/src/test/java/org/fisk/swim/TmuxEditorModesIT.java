package org.fisk.swim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.fisk.swim.testutil.InstalledSwimDriver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class TmuxEditorModesIT {
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration UI_TIMEOUT = Duration.ofSeconds(10);

    @TempDir
    Path tempDir;

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsNavigationMotionsAcrossBuffer() throws Exception {
        Path file = tempDir.resolve("motions.txt");
        Files.writeString(file, """
                abc
                123
                xyz
                """);

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("xyz", STARTUP_TIMEOUT);

            session.sendLiteral("G");
            session.sendLiteral("k");
            session.sendLiteral("^");
            session.sendLiteral("x");

            session.sendLiteral("g");
            session.sendLiteral("g");
            session.sendLiteral("$");
            session.sendLiteral("h");
            session.sendLiteral("x");

            session.sendLiteral("^");
            session.sendLiteral("j");
            session.sendLiteral("k");
            session.sendLiteral("j");
            session.sendLiteral("l");
            session.sendLiteral("l");
            session.sendLiteral("x");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                ab
                12
                yz
                """, Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsFindForwardAndBackwardMotions() throws Exception {
        Path file = tempDir.resolve("find.txt");
        Files.writeString(file, "abc def ghi\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("abc def ghi", STARTUP_TIMEOUT);

            session.sendLiteral("f");
            session.sendLiteral("d");
            session.sendLiteral("x");
            session.sendLiteral("$");
            session.sendLiteral("h");
            session.sendLiteral("F");
            session.sendLiteral("b");
            session.sendLiteral("x");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("ac ef ghi\n", Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsLineYankPasteBeforeAndAfter() throws Exception {
        Path file = tempDir.resolve("yank.txt");
        Files.writeString(file, """
                one
                two
                """);

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("two", STARTUP_TIMEOUT);

            session.sendLiteral("j");
            session.sendLiteral("y");
            session.sendLiteral("y");
            session.sendLiteral("P");
            session.sendLiteral("g");
            session.sendLiteral("g");
            session.sendLiteral("y");
            session.sendLiteral("y");
            session.sendLiteral("p");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                one
                one
                two
                two
                """, Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsChangeAppendAndOpeningLines() throws Exception {
        Path file = tempDir.resolve("change.txt");
        Files.writeString(file, "alpha beta\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("alpha beta", STARTUP_TIMEOUT);

            session.sendLiteral("c");
            session.sendLiteral("w");
            session.sendLiteral("omega");
            session.sendEscape();
            session.sendLiteral("a");
            session.sendLiteral("?");
            session.sendEscape();
            session.sendLiteral("A");
            session.sendLiteral("!");
            session.sendEscape();
            session.sendLiteral("o");
            session.sendLiteral("next");
            session.sendEscape();
            session.sendLiteral("O");
            session.sendLiteral("mid");
            session.sendEscape();

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                omega? beta!
                mid
                next
                """, Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsVisualCharacterDeletion() throws Exception {
        Path file = tempDir.resolve("visual.txt");
        Files.writeString(file, "abcdef\n");

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("abcdef", STARTUP_TIMEOUT);

            session.sendLiteral("v");
            session.sendLiteral("l");
            session.sendLiteral("l");
            session.sendLiteral("d");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("def\n", Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsVisualLineDeletion() throws Exception {
        Path file = tempDir.resolve("visual-line.txt");
        Files.writeString(file, """
                one
                two
                three
                """);

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("three", STARTUP_TIMEOUT);

            session.sendLiteral("V");
            session.sendLiteral("j");
            session.sendLiteral("d");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("three\n", Files.readString(file));
    }

    @Test
    @Timeout(45)
    void installedLauncherBinarySupportsCurrentWordSearchBindings() throws Exception {
        Path file = tempDir.resolve("current-word-search.txt");
        Files.writeString(file, """
                alpha beta
                alpha gamma
                """);

        try (var session = InstalledSwimDriver.start(tempDir, tempDir, file.getFileName().toString())) {
            session.waitForText("alpha gamma", STARTUP_TIMEOUT);

            session.sendLiteral("*");
            session.sendLiteral("x");
            session.sendLiteral("#");
            session.sendLiteral("x");

            session.runCommand("w");
            session.runCommand("q");
            session.waitForExit(Duration.ofSeconds(10));
        }

        assertEquals("""
                apha beta
                lpha gamma
                """, Files.readString(file));
    }
}
